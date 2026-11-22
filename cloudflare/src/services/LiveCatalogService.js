/**
 * Live Catalog, Episode Schedule & Gemini AI Bulk-Logging Service for TV Time: Show & Movie Tracker.
 *
 * Uses:
 *  - TVMaze Public API (`https://api.tvmaze.com`) — 100% Free, No API Key Required for Shows, Episodes, Cast & Airing Schedules
 *  - Apple iTunes Search API (`https://itunes.apple.com/search`) — 100% Free, No API Key Required for Movies, Posters, Directors & Runtime
 *  - Optional TMDB API (`TMDB_API_KEY`) when configured
 *  - Google Gemini Dynamic Model Cascade (`/v1beta/models`) for natural-language watch logging & recommendations
 */

function stripHtml(raw) {
  return String(raw || "")
    .replace(/<[^>]*>/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

export function formatEpisodeCode(season, number) {
  const s = Math.max(1, parseInt(season, 10) || 1);
  const e = Math.max(1, parseInt(number, 10) || 1);
  return `S${String(s).padStart(2, "0")}E${String(e).padStart(2, "0")}`;
}

export class LiveCatalogService {
  static DEFAULT_MODEL_CASCADE = [
    "gemini-flash-latest",
    "gemini-3.8-flash",
    "gemini-3.6-flash",
    "gemini-3.5-flash-lite",
    "gemini-3.1-flash-lite",
    "gemini-flash-lite-latest",
  ];

  static _cachedCascade = null;
  static _cachedCascadeAt = 0;
  static _lastWorkingModel = null;

  static scoreGeminiModelId(rawId, metadata = null) {
    const id = String(rawId || "").replace(/^models\//, "").trim().toLowerCase();
    if (!id.startsWith("gemini-")) return -1;

    if (
      /image|banana|omni|transcribe|tts|audio|video|music|lyria|veo|imagen|embedding|aqa|learnlm|robotics|computer-use|deep-research|antigravity|translate|thinking|live|vision|customtools/i.test(
        id
      )
    ) {
      return -1;
    }

    if (metadata && typeof metadata === "object") {
      if (
        Array.isArray(metadata.supportedGenerationMethods) &&
        !metadata.supportedGenerationMethods.includes("generateContent")
      ) {
        return -1;
      }
      const desc = String(metadata.description || "").toLowerCase();
      if (/shut down|deprecated|text-to-speech|speech-to-text|image generation|video generation/i.test(desc)) {
        return -1;
      }
      if (typeof metadata.outputTokenLimit === "number" && metadata.outputTokenLimit > 0 && metadata.outputTokenLimit < 2048) {
        return -1;
      }
    }

    let tierScore = 0;
    if (id.includes("flash-lite") || id.includes("lite")) {
      tierScore = 20_000;
    } else if (id.includes("flash")) {
      tierScore = 30_000;
    } else if (id.includes("pro") || id.includes("argon") || id.includes("ultra")) {
      tierScore = 10_000;
    } else {
      return -1;
    }

    let versionScore = 0;
    const verMatch = id.match(/^gemini-(\d+)(?:\.(\d+))?-(?:flash-lite|flash|pro|argon|ultra)(?:$|-[a-z0-9-]+$)/);
    if (verMatch) {
      const major = parseInt(verMatch[1], 10) || 0;
      const minor = parseInt(verMatch[2] ?? "0", 10) || 0;
      if (major < 3) return -1;
      versionScore = major * 100 + minor * 10;
    } else if (id === "gemini-flash-latest" || id === "gemini-flash-lite-latest" || id === "gemini-pro-latest") {
      versionScore = 375;
    } else {
      return -1;
    }

    let stabilityScore = 0;
    if (id.includes("exp") || id.includes("experimental")) {
      stabilityScore = 1000;
    } else if (id.includes("preview")) {
      stabilityScore = 2000;
    } else if (id.endsWith("-latest")) {
      stabilityScore = 4500;
    } else if (/^gemini-\d+(?:\.\d+)?-(?:flash-lite|flash|pro|argon|ultra)$/.test(id)) {
      stabilityScore = 5000;
    } else {
      stabilityScore = 4000;
    }

    return tierScore + stabilityScore + versionScore;
  }

  static rankModelsFromList(modelsList = []) {
    const scored = [];
    for (const entry of modelsList || []) {
      const rawName = typeof entry === "string" ? entry : entry?.name || entry?.baseModelId || "";
      const id = String(rawName).replace(/^models\//, "").trim();
      if (!id) continue;
      const metadata = typeof entry === "object" ? entry : null;
      const s = LiveCatalogService.scoreGeminiModelId(id, metadata);
      if (s > 0) {
        scored.push({ id, score: s });
      }
    }
    scored.sort((a, b) => b.score - a.score);

    const cascade = [];
    const seen = new Set();
    const pushUnique = (modelId) => {
      const clean = String(modelId || "").replace(/^models\//, "").trim();
      if (clean && !seen.has(clean)) {
        seen.add(clean);
        cascade.push(clean);
      }
    };

    if (LiveCatalogService._lastWorkingModel) {
      pushUnique(LiveCatalogService._lastWorkingModel);
    }
    for (const item of scored) {
      pushUnique(item.id);
    }
    for (const fallbackId of LiveCatalogService.DEFAULT_MODEL_CASCADE) {
      pushUnique(fallbackId);
    }
    return cascade;
  }

  static async resolveModelCascade(geminiApiKey, fetchImpl = fetch) {
    const now = Date.now();
    if (
      LiveCatalogService._cachedCascade &&
      LiveCatalogService._cachedCascade.length > 0 &&
      now - LiveCatalogService._cachedCascadeAt < 3600_000
    ) {
      return LiveCatalogService.rankModelsFromList(LiveCatalogService._cachedCascade);
    }

    try {
      const listUrl = `https://generativelanguage.googleapis.com/v1beta/models?key=${encodeURIComponent(String(geminiApiKey).trim())}`;
      const resp = await fetchImpl(listUrl, { method: "GET" });
      if (resp.ok) {
        const data = await resp.json();
        if (Array.isArray(data?.models) && data.models.length > 0) {
          const ranked = LiveCatalogService.rankModelsFromList(data.models);
          LiveCatalogService._cachedCascade = ranked;
          LiveCatalogService._cachedCascadeAt = now;
          return ranked;
        }
      }
    } catch (_) {}
    return LiveCatalogService.rankModelsFromList([]);
  }

  static async callGeminiWithModelCascade(requestBody, geminiApiKey, { fetchImpl = fetch, cascadeOverride = null } = {}) {
    const cleanKey = String(geminiApiKey || "").trim();
    if (!cleanKey) return null;

    const cascade =
      Array.isArray(cascadeOverride) && cascadeOverride.length > 0
        ? cascadeOverride
        : await LiveCatalogService.resolveModelCascade(cleanKey, fetchImpl);

    for (const modelId of cascade) {
      try {
        const url = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(modelId)}:generateContent?key=${encodeURIComponent(cleanKey)}`;
        const resp = await fetchImpl(url, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(requestBody),
        });
        if (!resp.ok) continue;
        const data = await resp.json();
        const text = data?.candidates?.[0]?.content?.parts?.[0]?.text || "";
        if (text.trim()) {
          LiveCatalogService._lastWorkingModel = modelId;
          return { data, modelUsed: modelId };
        }
      } catch (_) {}
    }
    return null;
  }

  static US_STREAMING_PLATFORMS = [
    { key: "Netflix", providerIds: [8, 1796], matchRe: /netflix/i },
    { key: "Prime Video", providerIds: [9, 119, 2100], matchRe: /prime video|amazon/i },
    { key: "Hulu", providerIds: [15], matchRe: /hulu|\bfx\b/i },
    { key: "Max", providerIds: [1899, 384], matchRe: /\bmax\b|\bhbo\b/i },
    { key: "Disney+", providerIds: [337], matchRe: /disney/i },
    { key: "Apple TV+", providerIds: [350, 2], matchRe: /apple tv/i },
    { key: "Paramount+", providerIds: [531, 1853, 2303], matchRe: /paramount|\bcbs\b|showtime/i },
    { key: "Peacock", providerIds: [386, 387], matchRe: /peacock|\bnbc\b/i },
  ];

  static normalizeUsProvidersFromTmdb(usWatchObj, fallbackText = "") {
    const names = [];
    const seen = new Set();
    const pushClean = (rawName) => {
      const s = String(rawName || "").trim();
      if (!s) return;
      for (const p of LiveCatalogService.US_STREAMING_PLATFORMS) {
        if (p.matchRe.test(s)) {
          if (!seen.has(p.key)) {
            seen.add(p.key);
            names.push(p.key);
          }
          return;
        }
      }
      if (!seen.has(s)) {
        seen.add(s);
        names.push(s);
      }
    };

    if (usWatchObj && typeof usWatchObj === "object") {
      const buckets = [
        ...(usWatchObj.flatrate || []),
        ...(usWatchObj.free || []),
        ...(usWatchObj.ads || []),
      ];
      for (const b of buckets) {
        if (b?.provider_name) pushClean(b.provider_name);
      }
      if (names.length === 0) {
        const rentBuy = [...(usWatchObj.rent || []), ...(usWatchObj.buy || [])];
        for (const b of rentBuy.slice(0, 4)) {
          if (b?.provider_name) pushClean(b.provider_name);
        }
      }
    }
    if (fallbackText) {
      for (const part of String(fallbackText).split(/[,/]/)) {
        pushClean(part);
      }
    }
    return names.join(" / ") || fallbackText || "Streaming";
  }

  static _buildTmdbRequest(path, env = null) {
    const readToken = String(env?.TMDB_READ_TOKEN || "").trim();
    const apiKey = String(env?.TMDB_API_KEY || "").trim();
    if (!readToken && !apiKey) return null;
    const sep = path.includes("?") ? "&" : "?";
    const url = apiKey && !readToken
      ? `https://api.themoviedb.org/3${path}${sep}api_key=${encodeURIComponent(apiKey)}`
      : `https://api.themoviedb.org/3${path}`;
    const headers = { accept: "application/json" };
    if (readToken) {
      headers["Authorization"] = `Bearer ${readToken}`;
    }
    return { url, headers };
  }

  /**
   * Searches live TV Shows and Movies across:
   *  - TMDB v3 (`/3/search/multi`) when `TMDB_READ_TOKEN` or `TMDB_API_KEY` is configured
   *  - TVMaze (`/search/shows?q=...`) for TV Shows
   *  - IMDb Suggestion API + Apple iTunes Search API for Movies
   */
  static async searchMulti(query, { page = 1, fetchImpl = fetch, env = null } = {}) {
    const q = String(query || "").trim();
    const safePage = Math.max(1, Math.min(25, Math.floor(Number(page) || 1)));
    if (!q) return { results: [], page: safePage, hasMore: false };

    const results = [];
    const seenShowTmdbIds = new Set();
    const seenShowTitles = new Set();
    const seenShowBaseTitleYears = new Map();
    const seenMovieTmdbIds = new Set();
    const seenMovieTitles = new Set();
    const seenMovieBaseTitleYears = new Map();
    const normalizeSearchTitle = (s) =>
      String(s || "")
        .toLowerCase()
        .replace(/&/g, " and ")
        .replace(/[^a-z0-9]+/g, " ")
        .trim();
    const normalizeBaseTitle = (s) =>
      normalizeSearchTitle(
        String(s || "")
          .replace(/\([^)]*\)/g, " ")
          .replace(/\[[^\]]*\]/g, " ")
      );
    const seenMovieSkeletonYears = new Map();
    const translitSkeleton = (raw) => {
      const compact = normalizeBaseTitle(raw).replace(/[^a-z0-9]+/g, "");
      if (!compact) return "";
      const folded = compact
        .replace(/th/g, "t")
        .replace(/dh/g, "d")
        .replace(/kh/g, "k")
        .replace(/gh/g, "g")
        .replace(/bh/g, "b")
        .replace(/ph/g, "f")
        .replace(/sh/g, "s")
        .replace(/w/g, "v");
      return (folded[0] + folded.slice(1).replace(/[aeiouy]/g, "")).replace(/(.)\1+/g, "$1");
    };
    const hasMovieNearYear = (baseTitle, yr) => {
      if (!baseTitle) return false;
      const yrs = seenMovieBaseTitleYears.get(baseTitle);
      if (yrs && yrs.some((y) => Math.abs(y - yr) <= 1)) return true;
      const skel = translitSkeleton(baseTitle);
      if (skel.length >= 8) {
        const sYrs = seenMovieSkeletonYears.get(skel);
        if (sYrs && sYrs.some((y) => Math.abs(y - yr) <= 1)) return true;
      }
      return false;
    };
    const recordMovieTitleYear = (baseTitle, yr) => {
      if (!baseTitle) return;
      if (!seenMovieBaseTitleYears.has(baseTitle)) seenMovieBaseTitleYears.set(baseTitle, []);
      seenMovieBaseTitleYears.get(baseTitle).push(yr);
      const skel = translitSkeleton(baseTitle);
      if (skel.length >= 8) {
        if (!seenMovieSkeletonYears.has(skel)) seenMovieSkeletonYears.set(skel, []);
        seenMovieSkeletonYears.get(skel).push(yr);
      }
    };
    const hasShowNearYear = (baseTitle, yr) => {
      if (!baseTitle) return false;
      const yrs = seenShowBaseTitleYears.get(baseTitle);
      if (!yrs) return false;
      return yrs.some((y) => Math.abs(y - yr) <= 1);
    };
    const recordShowTitleYear = (baseTitle, yr) => {
      if (!baseTitle) return;
      if (!seenShowBaseTitleYears.has(baseTitle)) seenShowBaseTitleYears.set(baseTitle, []);
      seenShowBaseTitleYears.get(baseTitle).push(yr);
    };
    let tmdbMovieCount = 0;
    const normQuery = normalizeSearchTitle(q);
    const ampVariant = /\band\b/i.test(q) ? q.replace(/\band\b/gi, "&") : "";
    let tmdbHasMore = false;
    let ampHasMore = false;
    let actorHasMore = false;
    let itunesHasMore = false;

    try {
      const tmdbReq = LiveCatalogService._buildTmdbRequest(
        `/search/multi?query=${encodeURIComponent(q)}&include_adult=false&language=en-US&page=${safePage}`,
        env
      );
      const tmdbAmpTvReq = ampVariant
        ? LiveCatalogService._buildTmdbRequest(
            `/search/tv?query=${encodeURIComponent(ampVariant)}&include_adult=false&language=en-US&page=${safePage}`,
            env
          )
        : null;
      const tmdbPersonReq =
        safePage > 1
          ? LiveCatalogService._buildTmdbRequest(
              `/search/person?query=${encodeURIComponent(q)}&include_adult=false&language=en-US&page=1`,
              env
            )
          : null;
      const itunesOffset = (safePage - 1) * 25;
      const [tmdbRes, tmdbAmpTvRes, tmdbPersonRes, showsRes, imdbRes, moviesRes] =
        await Promise.allSettled([
          tmdbReq ? fetchImpl(tmdbReq.url, { headers: tmdbReq.headers }) : Promise.resolve(null),
          tmdbAmpTvReq
            ? fetchImpl(tmdbAmpTvReq.url, { headers: tmdbAmpTvReq.headers })
            : Promise.resolve(null),
          tmdbPersonReq
            ? fetchImpl(tmdbPersonReq.url, { headers: tmdbPersonReq.headers })
            : Promise.resolve(null),
          safePage === 1
            ? fetchImpl(`https://api.tvmaze.com/search/shows?q=${encodeURIComponent(q)}`)
            : Promise.resolve(null),
          safePage === 1
            ? fetchImpl(`https://v3.sg.media-imdb.com/suggestion/x/${encodeURIComponent(q)}.json`)
            : Promise.resolve(null),
          fetchImpl(
            `https://itunes.apple.com/search?term=${encodeURIComponent(q)}&country=US&entity=movie&limit=25&offset=${itunesOffset}`
          ),
        ]);

      // Map TVMaze shows by normalized title + year so TMDB TV results only attach matching-year tvmazeId
      const tvmazeByTitle = new Map();
      const seenShowTvmazeIds = new Set();
      let showsData = [];
      if (showsRes.status === "fulfilled" && showsRes.value && showsRes.value.ok) {
        const rawShows = await showsRes.value.json();
        showsData = Array.isArray(rawShows) ? rawShows : [];
        for (const entry of showsData) {
          const s = entry?.show;
          if (s?.id && s?.name) {
            const k = normalizeSearchTitle(s.name);
            if (k) {
              if (!tvmazeByTitle.has(k)) tvmazeByTitle.set(k, []);
              tvmazeByTitle.get(k).push(s);
            }
          }
        }
      }

      const combinedTmdbItems = [];
      let tmdbRawResults = [];
      if (tmdbAmpTvRes.status === "fulfilled" && tmdbAmpTvRes.value && tmdbAmpTvRes.value.ok) {
        const ampData = await tmdbAmpTvRes.value.json();
        const ampTotalPages = Number(ampData?.total_pages) || 1;
        if (safePage < ampTotalPages) ampHasMore = true;
        for (const tvItem of ampData?.results || []) {
          if (tvItem && tvItem.id) {
            combinedTmdbItems.push({ ...tvItem, media_type: "tv" });
          }
        }
      }
      if (tmdbRes.status === "fulfilled" && tmdbRes.value && tmdbRes.value.ok) {
        const tmdbData = await tmdbRes.value.json();
        const tmdbTotalPages = Number(tmdbData?.total_pages) || 1;
        if (safePage < tmdbTotalPages) tmdbHasMore = true;
        tmdbRawResults = Array.isArray(tmdbData?.results) ? tmdbData.results : [];
        for (const item of tmdbRawResults) {
          if (item && item.id) combinedTmdbItems.push(item);
        }
      }

      // Expand matched actors (person) into their movies (paginated in slices of 30 per page)
      const personCandidates = [...tmdbRawResults];
      if (tmdbPersonRes.status === "fulfilled" && tmdbPersonRes.value && tmdbPersonRes.value.ok) {
        const pData = await tmdbPersonRes.value.json();
        for (const p of (pData?.results || []).slice(0, 4)) {
          if (p && p.id) personCandidates.push({ ...p, media_type: "person" });
        }
      }
      const matchedPersons = [];
      const seenPersonIds = new Set();
      for (let idx = 0; idx < Math.min(10, personCandidates.length); idx++) {
        const p = personCandidates[idx];
        if (!p || !p.id || String(p.media_type || "").toLowerCase() !== "person") continue;
        const pNorm = normalizeSearchTitle(p.name || p.original_name || "");
        if (!pNorm || seenPersonIds.has(p.id)) continue;
        if (
          (safePage === 1 && idx < 3) ||
          pNorm === normQuery ||
          (normQuery.length >= 4 && (pNorm.startsWith(normQuery) || normQuery.startsWith(pNorm)))
        ) {
          seenPersonIds.add(p.id);
          matchedPersons.push(p);
        }
      }
      const actorIsPrimary =
        matchedPersons.length > 0 &&
        (String(tmdbRawResults[0]?.media_type || "").toLowerCase() === "person" ||
          normalizeSearchTitle(matchedPersons[0].name || "") === normQuery);

      const ACTOR_PAGE_SIZE = 30;
      const actorCreditItems = [];
      if (matchedPersons.length > 0) {
        const creditReqs = matchedPersons.slice(0, 2).map((p) => {
          const cReq = LiveCatalogService._buildTmdbRequest(
            `/person/${p.id}/combined_credits?language=en-US`,
            env
          );
          return cReq
            ? fetchImpl(cReq.url, { headers: cReq.headers })
                .then((r) => (r.ok ? r.json() : null))
                .then((data) => ({ personName: p.name || "Actor", cast: data?.cast || [] }))
                .catch(() => null)
            : Promise.resolve(null);
        });
        const creditBatches = (await Promise.all(creditReqs)).filter(Boolean);
        for (const batch of creditBatches) {
          const cleanCast = (batch.cast || []).filter((c) => {
            if (!c || !c.id) return false;
            const rawType = String(c.media_type || (c.first_air_date && !c.title ? "tv" : "movie")).toLowerCase();
            if (rawType !== "movie" && rawType !== "tv") return false;
            const hasTitle = rawType === "tv" ? Boolean(c.name || c.original_name) : Boolean(c.title || c.original_title);
            if (!hasTitle) return false;

            const gIds = Array.isArray(c.genre_ids) ? c.genre_ids.map(Number) : [];
            const charLower = String(c.character || "").trim().toLowerCase();
            const epCount = Number(c.episode_count) || 0;
            const votes = Number(c.vote_count) || 0;
            const isSelfRole =
              charLower === "self" ||
              charLower.startsWith("self ") ||
              charLower.startsWith("self-") ||
              charLower.includes("himself") ||
              charLower.includes("herself") ||
              charLower.includes("archive") ||
              charLower.includes("guest");

            if (rawType === "tv") {
              // Exclude late-night talk shows (10767) & news (10763) guest spots unless the person is the multi-episode host
              if ((gIds.includes(10767) || gIds.includes(10763)) && epCount < 15) return false;
              // Exclude documentary (99) / reality (10764) guest appearances
              if ((gIds.includes(99) || gIds.includes(10764)) && (isSelfRole || !charLower) && !(epCount >= 10 && votes >= 200)) {
                return false;
              }
              // Exclude explicit Self/Archive cameos (award shows, specials)
              if (isSelfRole && epCount <= 3) return false;
              return true;
            }

            const isSelfDoc = gIds.includes(99) && isSelfRole && votes < 200;
            return !isSelfDoc;
          });
          const scoreCredit = (c) => {
            const rawType = String(c.media_type || (c.first_air_date && !c.title ? "tv" : "movie")).toLowerCase();
            const isTv = rawType === "tv";
            const votes = Number(c.vote_count) || 0;
            const pop = Number(c.popularity) || 0;
            const epCount = Number(c.episode_count) || 0;
            const starringBonus = isTv && epCount >= 4 ? 25 : 0;
            const voteBoost = Math.min(300, votes / (isTv ? 6 : 20));
            const base =
              votes >= (isTv ? 10 : 25)
                ? pop + voteBoost + starringBonus
                : pop * 0.2;
            return isTv && epCount === 1 && votes < 5000 ? base * 0.35 : base;
          };
          cleanCast.sort((a, b) => scoreCredit(b) - scoreCredit(a) || (Number(b.id) || 0) - (Number(a.id) || 0));
          const sliceStart = (safePage - 1) * ACTOR_PAGE_SIZE;
          const sliceEnd = sliceStart + ACTOR_PAGE_SIZE;
          if (cleanCast.length > sliceEnd) actorHasMore = true;
          for (const c of cleanCast.slice(sliceStart, sliceEnd)) {
            actorCreditItems.push({ credit: c, personName: batch.personName });
          }
        }
      }

      const pushActorCredits = () => {
        for (const entry of actorCreditItems) {
          const c = entry.credit;
          const rawType = String(c.media_type || (c.first_air_date && !c.title ? "tv" : "movie")).toLowerCase();
          if (rawType === "tv") {
            const tvId = Number(c.id) || 0;
            if (tvId > 0 && seenShowTmdbIds.has(tvId)) continue;
            const title = c.name || c.original_name || "Untitled Show";
            const normTitle = normalizeSearchTitle(title);
            const firstAir = String(c.first_air_date || "");
            const year = firstAir ? parseInt(firstAir.slice(0, 4), 10) || 2024 : 2024;
            const showKey = `${normTitle}_${year}`;
            if (!normTitle || seenShowTitles.has(showKey)) continue;
            if (tvId > 0) seenShowTmdbIds.add(tvId);
            seenShowTitles.add(showKey);
            recordShowTitleYear(normalizeBaseTitle(title), year);
            const candidates = tvmazeByTitle.get(normTitle) || [];
            const matchedTvMaze =
              candidates.find((cand) => {
                const mYear = parseInt(String(cand?.premiered || "").slice(0, 4), 10) || 0;
                return !seenShowTvmazeIds.has(cand.id) && mYear > 0 && year > 0 && Math.abs(mYear - year) <= 1;
              }) || null;
            if (matchedTvMaze?.id) seenShowTvmazeIds.add(matchedTvMaze.id);
            const posterUrl = c.poster_path
              ? `https://image.tmdb.org/t/p/w500${c.poster_path}`
              : (matchedTvMaze?.image?.medium || "").replace(/^http:\/\//i, "https://");
            const cleanOverview = stripHtml(c.overview || matchedTvMaze?.summary || "");
            results.push({
              id: `tmdb_tv_${c.id}`,
              tvmazeId: matchedTvMaze ? matchedTvMaze.id : 0,
              tmdbId: c.id,
              mediaType: "show",
              title,
              year,
              network: matchedTvMaze?.network?.name || matchedTvMaze?.webChannel?.name || "Streaming",
              status: matchedTvMaze?.status || "Running",
              runtimeMins: Number(matchedTvMaze?.averageRuntime || matchedTvMaze?.runtime) || 45,
              genres: Array.isArray(matchedTvMaze?.genres) ? matchedTvMaze.genres : ["TV Series"],
              cast: [entry.personName],
              rating: c.vote_average ? Math.round(Number(c.vote_average) * 10) / 10 : 8.0,
              posterUrl,
              overview: cleanOverview ? `Starring ${entry.personName} · ${cleanOverview}` : `Starring ${entry.personName}`,
            });
          } else {
            const mId = Number(c.id) || 0;
            if (mId > 0 && seenMovieTmdbIds.has(mId)) continue;
            const title = c.title || c.original_title || "Untitled Movie";
            const rel = String(c.release_date || "");
            const year = rel ? parseInt(rel.slice(0, 4), 10) || 2024 : 2024;
            const key = `${normalizeSearchTitle(title)}_${year}`;
            if (seenMovieTitles.has(key)) continue;
            if (mId > 0) seenMovieTmdbIds.add(mId);
            seenMovieTitles.add(key);
            recordMovieTitleYear(normalizeBaseTitle(title), year);
            tmdbMovieCount += 1;
            const posterUrl = c.poster_path ? `https://image.tmdb.org/t/p/w500${c.poster_path}` : "";
            const cleanOverview = stripHtml(c.overview || "");
            results.push({
              id: `movie_${c.id}`,
              tmdbId: c.id,
              mediaType: "movie",
              title,
              year,
              director: `Starring ${entry.personName}`,
              cast: [entry.personName],
              runtimeMins: 125,
              genres: ["Movie"],
              streamingService: "Theaters / Streaming",
              rating: c.vote_average ? Math.round(Number(c.vote_average) * 10) / 10 : 7.8,
              posterUrl,
              overview: cleanOverview ? `Starring ${entry.personName} · ${cleanOverview}` : `Starring ${entry.personName}`,
            });
          }
        }
      };

      if (actorIsPrimary) {
        pushActorCredits();
      }

      combinedTmdbItems.sort((a, b) => {
        const aTitle = normalizeSearchTitle(a.name || a.title || a.original_name || a.original_title || "");
        const bTitle = normalizeSearchTitle(b.name || b.title || b.original_name || b.original_title || "");
        const aStarts = normQuery && (aTitle === normQuery || aTitle.startsWith(normQuery + " ")) ? 1 : 0;
        const bStarts = normQuery && (bTitle === normQuery || bTitle.startsWith(normQuery + " ")) ? 1 : 0;
        const aPop = (Number(a.popularity) || 0) + Math.min(200, (Number(a.vote_count) || 0) / 10) + aStarts * 80;
        const bPop = (Number(b.popularity) || 0) + Math.min(200, (Number(b.vote_count) || 0) / 10) + bStarts * 80;
        return bPop - aPop;
      });

      for (const item of combinedTmdbItems) {
        if (!item || !item.id) continue;
        const mType = String(item.media_type || "").toLowerCase();
        if (actorIsPrimary && (Number(item.vote_count) || 0) < 25) continue;
        if (mType === "tv") {
          const tvId = Number(item.id) || 0;
          if (tvId > 0 && seenShowTmdbIds.has(tvId)) continue;
          const title = item.name || item.original_name || "Untitled Show";
          const normTitle = normalizeSearchTitle(title);
          const firstAir = String(item.first_air_date || "");
          const year = firstAir ? parseInt(firstAir.slice(0, 4), 10) || 2024 : 2024;
          const showKey = `${normTitle}_${year}`;
          if (!normTitle || seenShowTitles.has(showKey)) continue;
          if (tvId > 0) seenShowTmdbIds.add(tvId);
          seenShowTitles.add(showKey);
          recordShowTitleYear(normalizeBaseTitle(title), year);
          const candidates = tvmazeByTitle.get(normTitle) || [];
          const matchedTvMaze = candidates.find((cand) => {
            const mYear = parseInt(String(cand?.premiered || "").slice(0, 4), 10) || 0;
            return !seenShowTvmazeIds.has(cand.id) && mYear > 0 && year > 0 && Math.abs(mYear - year) <= 1;
          }) || null;
          if (matchedTvMaze?.id) seenShowTvmazeIds.add(matchedTvMaze.id);
          const posterUrl = item.poster_path
            ? `https://image.tmdb.org/t/p/w500${item.poster_path}`
            : (matchedTvMaze?.image?.medium || "").replace(/^http:\/\//i, "https://");
          results.push({
            id: `tmdb_tv_${item.id}`,
            tvmazeId: matchedTvMaze ? matchedTvMaze.id : 0,
            tmdbId: item.id,
            mediaType: "show",
            title,
            year,
            network: matchedTvMaze?.network?.name || matchedTvMaze?.webChannel?.name || "Streaming",
            status: matchedTvMaze?.status || "Running",
            runtimeMins: Number(matchedTvMaze?.averageRuntime || matchedTvMaze?.runtime) || 45,
            genres: Array.isArray(matchedTvMaze?.genres) ? matchedTvMaze.genres : ["TV Series"],
            rating: item.vote_average ? Math.round(Number(item.vote_average) * 10) / 10 : 8.0,
            posterUrl,
            overview: stripHtml(item.overview || matchedTvMaze?.summary || ""),
          });
        } else if (mType === "movie") {
          const mId = Number(item.id) || 0;
          if (mId > 0 && seenMovieTmdbIds.has(mId)) continue;
          const title = item.title || item.original_title || "Untitled Movie";
          const rel = String(item.release_date || "");
          const year = rel ? parseInt(rel.slice(0, 4), 10) || 2024 : 2024;
          const key = `${normalizeSearchTitle(title)}_${year}`;
          if (seenMovieTitles.has(key)) continue;
          if (mId > 0) seenMovieTmdbIds.add(mId);
          seenMovieTitles.add(key);
          recordMovieTitleYear(normalizeBaseTitle(title), year);
          tmdbMovieCount += 1;
          const posterUrl = item.poster_path ? `https://image.tmdb.org/t/p/w500${item.poster_path}` : "";
          results.push({
            id: `movie_${item.id}`,
            tmdbId: item.id,
            mediaType: "movie",
            title,
            year,
            director: "",
            runtimeMins: 125,
            genres: ["Movie"],
            streamingService: "Theaters / Streaming",
            rating: item.vote_average ? Math.round(Number(item.vote_average) * 10) / 10 : 7.8,
            posterUrl,
            overview: stripHtml(item.overview || ""),
          });
        }
      }

      if (!actorIsPrimary && actorCreditItems.length > 0) {
        pushActorCredits();
      }

      if (!actorIsPrimary || actorCreditItems.length === 0) {
        for (const entry of showsData) {
          const show = entry?.show;
          if (!show || !show.id || seenShowTvmazeIds.has(show.id)) continue;
          const normTitle = normalizeSearchTitle(show.name || "");
          const premiered = String(show.premiered || "");
          const year = premiered ? parseInt(premiered.slice(0, 4), 10) || 2024 : 2024;
          const showKey = `${normTitle}_${year}`;
          const baseShowTitle = normalizeBaseTitle(show.name || "");
          if (!normTitle || seenShowTitles.has(showKey) || hasShowNearYear(baseShowTitle, year)) continue;
          seenShowTvmazeIds.add(show.id);
          seenShowTitles.add(showKey);
          recordShowTitleYear(baseShowTitle, year);
          const network = show.network?.name || show.webChannel?.name || "Streaming";
          const posterUrl = show.image?.medium || show.image?.original || "";
          results.push({
            id: `tvmaze_${show.id}`,
            tvmazeId: show.id,
            tmdbId: 0,
            mediaType: "show",
            title: show.name || "Untitled Show",
            year,
            network,
            status: show.status || "Running",
            runtimeMins: Number(show.averageRuntime || show.runtime) || 45,
            genres: Array.isArray(show.genres) ? show.genres : [],
            rating: Number(show.rating?.average) || 7.8,
            posterUrl: posterUrl.replace(/^http:\/\//i, "https://"),
            overview: stripHtml(show.summary),
          });
        }
      }

      const tvShowCountBeforeImdb = results.filter((r) => r.mediaType === "show").length;
      if ((tmdbMovieCount < 5 || tvShowCountBeforeImdb < 3) && imdbRes.status === "fulfilled" && imdbRes.value && imdbRes.value.ok) {
        const imdbData = await imdbRes.value.json();
        const imdbItems = Array.isArray(imdbData?.d) ? imdbData.d : [];
        let tvShowCountNow = tvShowCountBeforeImdb;

        // When TMDB + TVMaze returned 0 TV matches (e.g. transliterated spelling or missing regional show),
        // first try resolving IMDb's top tvSeries hit via TMDB `/find/{ttId}?external_source=imdb_id`!
        if (tvShowCountNow === 0) {
          const topImdbShows = imdbItems
            .filter((it) => {
              const qid = String(it?.qid || "").toLowerCase();
              return /^tt\d+$/i.test(String(it?.id || "")) && (qid === "tvseries" || qid === "tvminiseries");
            })
            .slice(0, 2);
          for (const imShow of topImdbShows) {
            const findReq = LiveCatalogService._buildTmdbRequest(
              `/find/${encodeURIComponent(imShow.id)}?external_source=imdb_id&language=en-US`,
              env
            );
            if (!findReq) continue;
            try {
              const fr = await fetchImpl(findReq.url, { headers: findReq.headers });
              if (fr.ok) {
                const fd = await fr.json();
                for (const tv of fd?.tv_results || []) {
                  const tvId = Number(tv?.id) || 0;
                  if (tvId <= 0 || seenShowTmdbIds.has(tvId)) continue;
                  const title = tv.name || tv.original_name || imShow.l || "Untitled Show";
                  const firstAir = String(tv.first_air_date || "");
                  const year = firstAir ? parseInt(firstAir.slice(0, 4), 10) || Number(imShow.y) || 2024 : Number(imShow.y) || 2024;
                  const baseTitle = normalizeBaseTitle(title);
                  const showKey = `${normalizeSearchTitle(title)}_${year}`;
                  if (seenShowTitles.has(showKey) || hasShowNearYear(baseTitle, year)) continue;
                  seenShowTmdbIds.add(tvId);
                  seenShowTitles.add(showKey);
                  recordShowTitleYear(baseTitle, year);
                  tvShowCountNow += 1;
                  results.push({
                    id: `tmdb_tv_${tvId}`,
                    tvmazeId: 0,
                    tmdbId: tvId,
                    mediaType: "show",
                    title,
                    year,
                    network: "Streaming",
                    status: "Running",
                    runtimeMins: 45,
                    genres: ["TV Series"],
                    rating: tv.vote_average ? Math.round(Number(tv.vote_average) * 10) / 10 : 8.0,
                    posterUrl: tv.poster_path ? `https://image.tmdb.org/t/p/w500${tv.poster_path}` : imShow.i?.imageUrl || "",
                    overview: stripHtml(tv.overview || ""),
                  });
                }
              }
            } catch (_) {}
          }
        }

        // When TMDB missed a transliterated/misspelled movie query (e.g. "ala vaikuntapuramloo"),
        // resolve IMDb's top tt* hit to its canonical TMDB movie ID via `/find/{ttId}?external_source=imdb_id`!
        if (tmdbMovieCount === 0) {
          const topImdbMovies = imdbItems
            .filter((it) => {
              const qid = String(it?.qid || "").toLowerCase();
              return /^tt\d+$/i.test(String(it?.id || "")) && (qid === "movie" || qid === "feature" || qid === "tvmovie");
            })
            .slice(0, 2);
          for (const imHit of topImdbMovies) {
            const findReq = LiveCatalogService._buildTmdbRequest(
              `/find/${encodeURIComponent(imHit.id)}?external_source=imdb_id&language=en-US`,
              env
            );
            if (!findReq) continue;
            try {
              const fr = await fetchImpl(findReq.url, { headers: findReq.headers });
              if (fr.ok) {
                const fd = await fr.json();
                for (const m of fd?.movie_results || []) {
                  const mId = Number(m?.id) || 0;
                  if (mId <= 0 || seenMovieTmdbIds.has(mId)) continue;
                  const title = m.title || m.original_title || imHit.l || "Untitled Movie";
                  const rel = String(m.release_date || "");
                  const year = rel ? parseInt(rel.slice(0, 4), 10) || Number(imHit.y) || 2024 : Number(imHit.y) || 2024;
                  const baseTitle = normalizeBaseTitle(title);
                  const key = `${normalizeSearchTitle(title)}_${year}`;
                  if (seenMovieTitles.has(key) || hasMovieNearYear(baseTitle, year)) continue;
                  seenMovieTmdbIds.add(mId);
                  seenMovieTitles.add(key);
                  recordMovieTitleYear(baseTitle, year);
                  tmdbMovieCount += 1;
                  results.push({
                    id: `movie_${mId}`,
                    tmdbId: mId,
                    mediaType: "movie",
                    title,
                    year,
                    director: "",
                    runtimeMins: 125,
                    genres: ["Movie"],
                    streamingService: "Theaters / Streaming",
                    rating: m.vote_average ? Math.round(Number(m.vote_average) * 10) / 10 : 7.8,
                    posterUrl: m.poster_path ? `https://image.tmdb.org/t/p/w500${m.poster_path}` : imHit.i?.imageUrl || "",
                    overview: stripHtml(m.overview || ""),
                  });
                }
              }
            } catch (_) {}
          }
        }

        const candidateImdbShows = [];
        if (tvShowCountNow < 3) {
          for (const item of imdbItems) {
            if (!item || !item.id || !item.l) continue;
            const qid = String(item.qid || "").toLowerCase();
            if (qid === "tvseries" || qid === "tvminiseries") {
              const year = Number(item.y) || 2024;
              const showKey = `${normalizeSearchTitle(item.l)}_${year}`;
              const baseShowTitle = normalizeBaseTitle(item.l);
              if (seenShowTitles.has(showKey) || hasShowNearYear(baseShowTitle, year)) continue;
              if (candidateImdbShows.length < 3) {
                candidateImdbShows.push({ item, year });
              }
            }
          }
        }
        const wikiByImdbId = new Map();
        if (candidateImdbShows.length > 0) {
          const wikiChecks = await Promise.all(
            candidateImdbShows.map(({ item, year }) =>
              LiveCatalogService.lookupWikipediaShowMeta({
                title: item.l,
                year,
                imdbId: item.id,
                fetchImpl,
              }).catch(() => ({ totalEpisodes: 0, isIndian: false }))
            )
          );
          for (let ci = 0; ci < candidateImdbShows.length; ci++) {
            wikiByImdbId.set(candidateImdbShows[ci].item.id, wikiChecks[ci] || {});
          }
        }

        for (const item of imdbItems) {
          if (!item || !item.id || !item.l) continue;
          const qid = String(item.qid || "").toLowerCase();
          if (tvShowCountNow < 3 && (qid === "tvseries" || qid === "tvminiseries")) {
            const wikiMeta = wikiByImdbId.get(item.id);
            if (!wikiMeta || !wikiMeta.isIndian || !(Number(wikiMeta.totalEpisodes) >= 5)) continue;
            const year = Number(item.y) || 2024;
            const showKey = `${normalizeSearchTitle(item.l)}_${year}`;
            const baseShowTitle = normalizeBaseTitle(item.l);
            if (seenShowTitles.has(showKey) || hasShowNearYear(baseShowTitle, year)) continue;
            seenShowTitles.add(showKey);
            recordShowTitleYear(baseShowTitle, year);
            tvShowCountNow += 1;
            const castArr = item.s ? String(item.s).split(",").map((c) => c.trim()).filter(Boolean) : [];
            results.push({
              id: `imdb_tv_${item.id}`,
              tvmazeId: 0,
              tmdbId: 0,
              mediaType: "show",
              title: item.l,
              year,
              network: wikiMeta.network || "Television",
              status: wikiMeta.status || "Ended",
              runtimeMins: wikiMeta.runtimeMins || 22,
              totalEpisodes: wikiMeta.totalEpisodes,
              countOnly: true,
              genres: ["TV Series"],
              cast: castArr,
              rating: 8.0,
              posterUrl: item.i?.imageUrl || wikiMeta.posterUrl || "",
              overview: wikiMeta.overview || (item.s ? `TV Series · Starring ${item.s}` : "TV Series"),
            });
          } else if (tmdbMovieCount < 5 && (qid === "movie" || qid === "feature" || qid === "tvmovie")) {
            const year = Number(item.y) || 2024;
            const key = `${normalizeSearchTitle(item.l)}_${year}`;
            const baseTitle = normalizeBaseTitle(item.l);
            if (seenMovieTitles.has(key) || hasMovieNearYear(baseTitle, year)) continue;
            seenMovieTitles.add(key);
            recordMovieTitleYear(baseTitle, year);
            const castArr = item.s ? String(item.s).split(",").map((c) => c.trim()).filter(Boolean) : [];
            results.push({
              id: `movie_${item.id}`,
              tmdbId: 0,
              mediaType: "movie",
              title: item.l,
              year,
              director: castArr[0] || "",
              cast: castArr,
              runtimeMins: 125,
              genres: ["Movie"],
              streamingService: "Theaters / Streaming",
              rating: 8.0,
              posterUrl: item.i?.imageUrl || "",
              overview: item.s ? `Starring ${item.s}` : "Feature Film",
            });
          }
        }
      }

      if (tmdbMovieCount < 5 && moviesRes.status === "fulfilled" && moviesRes.value && moviesRes.value.ok) {
        const moviesData = await moviesRes.value.json();
        const itunesItems = Array.isArray(moviesData?.results) ? moviesData.results : [];
        if (itunesItems.length >= 25) itunesHasMore = true;
        for (const item of itunesItems) {
          if (!item || !item.trackId) continue;
          const rel = String(item.releaseDate || "");
          const year = rel ? parseInt(rel.slice(0, 4), 10) || 2024 : 2024;
          const key = `${normalizeSearchTitle(item.trackName || "")}_${year}`;
          const baseTitle = normalizeBaseTitle(item.trackName || "");
          if (seenMovieTitles.has(key) || hasMovieNearYear(baseTitle, year)) continue;
          seenMovieTitles.add(key);
          recordMovieTitleYear(baseTitle, year);
          const runtimeMins = item.trackTimeMillis ? Math.max(45, Math.round(item.trackTimeMillis / 60000)) : 120;
          const artwork = String(item.artworkUrl100 || "").replace("100x100bb.jpg", "600x900bb.jpg");
          results.push({
            id: `movie_itunes_${item.trackId}`,
            tmdbId: 0,
            mediaType: "movie",
            title: item.trackName || "Untitled Movie",
            year,
            director: item.artistName || "",
            runtimeMins,
            genres: item.primaryGenreName ? [item.primaryGenreName] : ["Movie"],
            streamingService: "Digital / Streaming",
            rating: 7.7,
            posterUrl: artwork,
            overview: stripHtml(item.longDescription || item.shortDescription || ""),
          });
        }
      }
    } catch (_) {}

    const hasMore =
      results.length > 0 &&
      safePage < 25 &&
      (tmdbHasMore || ampHasMore || actorHasMore || itunesHasMore);
    return { results, page: safePage, hasMore: Boolean(hasMore) };
  }

  /**
   * Discovers US TV Shows & Movies streaming on the specified US platform(s) via TMDB `/3/discover/tv` and `/3/discover/movie`
   * (`watch_region=US&with_watch_monetization_types=flatrate&with_watch_providers=...`).
   */
  static async discoverByUsProviders(platformsCsv, { page = 1, fetchImpl = fetch, env = null } = {}) {
    const safePage = Math.max(1, Math.min(25, Math.floor(Number(page) || 1)));
    const requestedKeys = String(platformsCsv || "")
      .split(",")
      .map((s) => s.trim())
      .filter(Boolean);
    if (requestedKeys.length === 0) {
      return LiveCatalogService.searchMulti("Game of Thrones", { page: safePage, fetchImpl, env });
    }

    const matchedPlatforms = LiveCatalogService.US_STREAMING_PLATFORMS.filter((p) =>
      requestedKeys.some((rk) => rk.toLowerCase() === p.key.toLowerCase())
    );
    const providerIdsPipe = matchedPlatforms.flatMap((p) => p.providerIds).join("|");
    const platformLabel = matchedPlatforms.map((p) => p.key).join(" / ") || requestedKeys.join(" / ");
    const results = [];
    let tvHasMore = false;
    let movHasMore = false;

    if (providerIdsPipe) {
      const tvReq = LiveCatalogService._buildTmdbRequest(
        `/discover/tv?watch_region=US&with_watch_monetization_types=flatrate&with_watch_providers=${encodeURIComponent(providerIdsPipe)}&sort_by=popularity.desc&language=en-US&page=${safePage}`,
        env
      );
      const movReq = LiveCatalogService._buildTmdbRequest(
        `/discover/movie?watch_region=US&with_watch_monetization_types=flatrate&with_watch_providers=${encodeURIComponent(providerIdsPipe)}&sort_by=popularity.desc&language=en-US&page=${safePage}`,
        env
      );

      try {
        const [tvRes, movRes] = await Promise.allSettled([
          tvReq ? fetchImpl(tvReq.url, { headers: tvReq.headers }) : Promise.resolve(null),
          movReq ? fetchImpl(movReq.url, { headers: movReq.headers }) : Promise.resolve(null),
        ]);

        if (tvRes.status === "fulfilled" && tvRes.value && tvRes.value.ok) {
          const tvData = await tvRes.value.json();
          if (safePage < (Number(tvData?.total_pages) || 1)) tvHasMore = true;
          for (const item of tvData?.results || []) {
            if (!item?.id) continue;
            const firstAir = String(item.first_air_date || "");
            const year = firstAir ? parseInt(firstAir.slice(0, 4), 10) || 2024 : 2024;
            results.push({
              id: `tmdb_tv_${item.id}`,
              tvmazeId: 0,
              tmdbId: item.id,
              mediaType: "show",
              title: item.name || item.original_name || "Untitled Show",
              year,
              network: platformLabel,
              status: "Running",
              runtimeMins: 45,
              genres: ["TV Series"],
              rating: item.vote_average ? Math.round(Number(item.vote_average) * 10) / 10 : 8.0,
              posterUrl: item.poster_path ? `https://image.tmdb.org/t/p/w500${item.poster_path}` : "",
              overview: stripHtml(item.overview || ""),
            });
          }
        }

        if (movRes.status === "fulfilled" && movRes.value && movRes.value.ok) {
          const movData = await movRes.value.json();
          if (safePage < (Number(movData?.total_pages) || 1)) movHasMore = true;
          for (const item of movData?.results || []) {
            if (!item?.id) continue;
            const rel = String(item.release_date || "");
            const year = rel ? parseInt(rel.slice(0, 4), 10) || 2024 : 2024;
            results.push({
              id: `movie_${item.id}`,
              tmdbId: item.id,
              mediaType: "movie",
              title: item.title || item.original_title || "Untitled Movie",
              year,
              director: platformLabel,
              runtimeMins: 120,
              genres: ["Movie"],
              streamingService: platformLabel,
              rating: item.vote_average ? Math.round(Number(item.vote_average) * 10) / 10 : 7.8,
              posterUrl: item.poster_path ? `https://image.tmdb.org/t/p/w500${item.poster_path}` : "",
              overview: stripHtml(item.overview || ""),
            });
          }
        }
      } catch (_) {}
    }

    const hasMore = results.length > 0 && safePage < 25 && (tvHasMore || movHasMore);
    return { results, page: safePage, hasMore: Boolean(hasMore) };
  }

  /**
   * Discovers Movies (by Language + Exact Release Year) or TV Series (by Year Range + Genre)
   * for the Categories browser tab.
   */
  static async discoverByCategory({
    mediaType = "movie",
    language = "",
    year = 0,
    yearFrom = 0,
    yearTo = 0,
    genre = "",
    sort = "votes",
    page = 1,
    fetchImpl = fetch,
    env = null,
  } = {}) {
    const safePage = Math.max(1, Math.min(50, Number(page) || 1));
    const isShow = String(mediaType || "").toLowerCase() === "show" || String(mediaType || "").toLowerCase() === "tv";
    const cleanLang = String(language || "").trim().toLowerCase();
    const cleanGenre = String(genre || "").trim();
    const sortKey = String(sort || "votes").toLowerCase();

    const sortByParam =
      sortKey === "popularity"
        ? "popularity.desc"
        : sortKey === "rating"
          ? "vote_average.desc"
          : sortKey === "newest"
            ? isShow
              ? "first_air_date.desc"
              : "primary_release_date.desc"
            : "vote_count.desc";

    const queryParts = [
      `sort_by=${encodeURIComponent(sortByParam)}`,
      "include_adult=false",
      "language=en-US",
      `page=${safePage}`,
    ];
    if (sortKey === "rating") {
      queryParts.push("vote_count.gte=5");
    }

    if (isShow) {
      let yStart = Number(yearFrom) || 0;
      let yEnd = Number(yearTo) || 0;
      if (yStart > 1900 && yEnd > 1900 && yStart > yEnd) {
        const tmp = yStart;
        yStart = yEnd;
        yEnd = tmp;
      }
      if (yStart > 1900) queryParts.push(`first_air_date.gte=${yStart}-01-01`);
      if (yEnd > 1900) queryParts.push(`first_air_date.lte=${yEnd}-12-31`);
      if (cleanGenre && cleanGenre !== "all") {
        queryParts.push(`with_genres=${encodeURIComponent(cleanGenre)}`);
      }
      if (cleanLang && cleanLang !== "all") {
        queryParts.push(`with_original_language=${encodeURIComponent(cleanLang)}`);
      }
    } else {
      const yr = Number(year) || 0;
      if (yr > 1900) {
        queryParts.push(`primary_release_year=${yr}`);
      }
      if (cleanLang && cleanLang !== "all") {
        queryParts.push(`with_original_language=${encodeURIComponent(cleanLang)}`);
      }
      if (cleanGenre && cleanGenre !== "all") {
        queryParts.push(`with_genres=${encodeURIComponent(cleanGenre)}`);
      }
    }

    const endpoint = isShow ? "/discover/tv?" : "/discover/movie?";
    const req = LiveCatalogService._buildTmdbRequest(endpoint + queryParts.join("&"), env);
    const results = [];
    let totalPages = 1;

    if (req) {
      try {
        const resp = await fetchImpl(req.url, { headers: req.headers });
        if (resp && resp.ok) {
          const data = await resp.json();
          totalPages = Math.max(1, Number(data?.total_pages) || 1);
          const seenIds = new Set();
          for (const item of data?.results || []) {
            const id = Number(item?.id) || 0;
            if (id <= 0 || seenIds.has(id)) continue;
            seenIds.add(id);
            const posterUrl = item.poster_path ? `https://image.tmdb.org/t/p/w500${item.poster_path}` : "";
            const overview = stripHtml(item.overview || "");
            if (isShow) {
              const title = item.name || item.original_name || "";
              if (!title) continue;
              const firstAir = String(item.first_air_date || "");
              const itemYear = firstAir ? parseInt(firstAir.slice(0, 4), 10) || (Number(yearFrom) || 2024) : Number(yearFrom) || 2024;
              results.push({
                id: `tmdb_tv_${id}`,
                tvmazeId: 0,
                tmdbId: id,
                mediaType: "show",
                title,
                year: itemYear,
                network: "Streaming",
                status: "Running",
                runtimeMins: 45,
                genres: ["TV Series"],
                rating: item.vote_average ? Math.round(Number(item.vote_average) * 10) / 10 : 7.8,
                posterUrl,
                overview,
              });
            } else {
              const title = item.title || item.original_title || "";
              if (!title) continue;
              const rel = String(item.release_date || "");
              const itemYear = rel ? parseInt(rel.slice(0, 4), 10) || (Number(year) || 2024) : Number(year) || 2024;
              results.push({
                id: `movie_${id}`,
                tmdbId: id,
                mediaType: "movie",
                title,
                year: itemYear,
                director: "",
                runtimeMins: 125,
                genres: ["Movie"],
                streamingService: "Theaters / Streaming",
                rating: item.vote_average ? Math.round(Number(item.vote_average) * 10) / 10 : 7.5,
                posterUrl,
                overview,
              });
            }
          }
        }
      } catch (_) {}
    }

    const hasMore = results.length > 0 && safePage < Math.min(50, totalPages);
    return { results, page: safePage, hasMore: Boolean(hasMore) };
  }

  /**
   * Fetches personalized TV Show & Movie recommendations for the Tinder-style Swipe deck
   * using TMDB `/3/tv/{id}/recommendations`, `/3/movie/{id}/recommendations`, and trending fallbacks.
   */
  static async fetchRecommendations({ tvSeeds = [], movieSeeds = [], page = 1, fetchImpl = fetch, env = null } = {}) {
    const safePage = Math.max(1, Math.min(10, Number(page) || 1));
    const results = [];
    const seenKeys = new Set();
    const normalizeRecBaseTitle = (s) => {
      let clean = String(s || "")
        .toLowerCase()
        .replace(/\([^)]*\)/g, " ")
        .replace(/\[[^\]]*\]/g, " ")
        .replace(/['\u2019]/g, "")
        .replace(/&/g, " and ")
        .replace(/sorcerers stone/g, "philosophers stone")
        .replace(/[^a-z0-9]+/g, " ")
        .trim();
      if (clean.startsWith("the ") && clean.length > 5) clean = clean.slice(4).trim();
      else if (clean.startsWith("a ") && clean.length > 3) clean = clean.slice(2).trim();
      if (clean.endsWith(" the movie") && clean.length > 11) {
        clean = clean.slice(0, -10).trim();
      }
      return clean;
    };

    const registerExcludedSeed = (mediaType, tmdbId, title) => {
      const numId = Number(tmdbId) || 0;
      if (numId > 0) {
        seenKeys.add(`${mediaType}_tmdb_${numId}`);
        seenKeys.add(mediaType === "movie" ? `movie_${numId}` : `tmdb_tv_${numId}`);
      }
      const baseTitle = normalizeRecBaseTitle(title);
      if (baseTitle && baseTitle.length >= 4) {
        seenKeys.add(`${mediaType}_${baseTitle}`);
      }
    };

    for (const s of tvSeeds) registerExcludedSeed("show", s?.tmdbId, s?.title);
    for (const m of movieSeeds) registerExcludedSeed("movie", m?.tmdbId, m?.title);

    const pushUnique = (item) => {
      if (!item || !item.title || !item.posterUrl) return;
      const baseTitle = normalizeRecBaseTitle(item.title);
      const normKey = `${item.mediaType}_${baseTitle}`;
      const tmdbKey = item.tmdbId > 0 ? `${item.mediaType}_tmdb_${item.tmdbId}` : "";
      if (seenKeys.has(item.id) || (baseTitle && seenKeys.has(normKey)) || (tmdbKey && seenKeys.has(tmdbKey))) return;
      seenKeys.add(item.id);
      if (baseTitle) seenKeys.add(normKey);
      if (tmdbKey) seenKeys.add(tmdbKey);
      results.push(item);
    };

    const INDIAN_LANGS = new Set(["hi", "ml", "ta", "te", "kn", "bn", "mr", "pa", "gu", "ur"]);
    const LANG_LABELS = {
      hi: "Hindi",
      ml: "Malayalam",
      ta: "Tamil",
      te: "Telugu",
      kn: "Kannada",
      bn: "Bengali",
      mr: "Marathi",
      pa: "Punjabi",
      gu: "Gujarati",
      ur: "Urdu",
    };
    const GENRE_LABELS = {
      28: "Action",
      12: "Adventure",
      16: "Animation",
      35: "Comedy",
      80: "Crime",
      99: "Documentary",
      18: "Drama",
      10751: "Family",
      14: "Fantasy",
      36: "History",
      27: "Horror",
      9648: "Mystery",
      10749: "Romance",
      878: "Sci-Fi",
      53: "Thriller",
      10759: "Action & Adventure",
      10765: "Sci-Fi & Fantasy",
    };

    const isRelevantSeedCandidate = (item, seedGenreIds, seedLang) => {
      if (!item || !item.id || !item.poster_path) return false;
      const voteCount = Number(item.vote_count) || 0;
      const voteAvg = Number(item.vote_average) || 0;
      if (voteCount < 15 || voteAvg < 5.5) return false;

      const itemGenres = Array.isArray(item.genre_ids)
        ? item.genre_ids.map((g) => Number(g)).filter((n) => n > 0)
        : [];
      const sharedGenres =
        seedGenreIds.size > 0
          ? itemGenres.filter((g) => seedGenreIds.has(g)).length
          : 1;
      if (seedGenreIds.size > 0 && sharedGenres < 1) return false;

      const itemLang = String(item.original_language || "").trim().toLowerCase();
      if (seedLang) {
        if (INDIAN_LANGS.has(seedLang)) {
          if (!INDIAN_LANGS.has(itemLang)) {
            if (!(sharedGenres >= 2 && voteCount >= 1500 && voteAvg >= 7.4)) return false;
          }
        } else if (itemLang && itemLang !== seedLang) {
          if (!(sharedGenres >= 2 && voteCount >= 300 && voteAvg >= 6.8)) return false;
        }
      }
      return true;
    };

    const pickMatchingTmdbHit = (candidates, targetTitle, targetYear, dateField) => {
      const list = Array.isArray(candidates) ? candidates : [];
      const wantNorm = normalizeRecBaseTitle(targetTitle);
      if (!wantNorm) return null;
      const titleMatches = list.filter((hit) => {
        const primary = normalizeRecBaseTitle(hit?.title || hit?.name || "");
        const orig = normalizeRecBaseTitle(hit?.original_title || hit?.original_name || "");
        return primary === wantNorm || (orig && orig === wantNorm);
      });
      if (titleMatches.length === 0) return null;
      if (targetYear > 1900) {
        const yearMatch = titleMatches.find((hit) => {
          const yr = parseInt(String(hit?.[dateField] || "").slice(0, 4), 10) || 0;
          return yr > 1900 && Math.abs(yr - targetYear) <= 1;
        });
        if (yearMatch) return yearMatch;
        const looseYearMatch = titleMatches.find((hit) => {
          const yr = parseInt(String(hit?.[dateField] || "").slice(0, 4), 10) || 0;
          return yr > 1900 && Math.abs(yr - targetYear) <= 2;
        });
        if (looseYearMatch && dateField === "first_air_date") return looseYearMatch;
        return null;
      }
      return titleMatches[0];
    };

    try {
      const indianLangCounts = new Map();
      let totalSeedLangCount = 0;
      const recordSeedLang = (lang) => {
        const clean = String(lang || "").trim().toLowerCase();
        if (!clean) return;
        totalSeedLangCount += 1;
        if (INDIAN_LANGS.has(clean)) {
          indianLangCounts.set(clean, (indianLangCounts.get(clean) || 0) + 1);
        }
      };

      const resolveTvJobs = tvSeeds.slice(0, 5).map(async (s) => {
        let tmdbId = Number(s?.tmdbId) || 0;
        const seedYear = Number(s?.year) || 0;
        const title = String(s?.title || "").trim();
        let lang = String(s?.lang || "").trim().toLowerCase();
        if (tmdbId <= 0 && title) {
          const yrParam = seedYear > 1900 ? `&first_air_date_year=${seedYear}` : "";
          const searchReq = LiveCatalogService._buildTmdbRequest(
            `/search/tv?query=${encodeURIComponent(title)}${yrParam}&language=en-US&page=1`,
            env
          );
          if (searchReq) {
            try {
              const r = await fetchImpl(searchReq.url, { headers: searchReq.headers });
              if (r.ok) {
                const d = await r.json();
                let hit = pickMatchingTmdbHit(d?.results, title, seedYear, "first_air_date");
                if (!hit && yrParam) {
                  const fallbackReq = LiveCatalogService._buildTmdbRequest(
                    `/search/tv?query=${encodeURIComponent(title)}&language=en-US&page=1`,
                    env
                  );
                  if (fallbackReq) {
                    const fr = await fetchImpl(fallbackReq.url, { headers: fallbackReq.headers });
                    if (fr.ok) {
                      const fd = await fr.json();
                      hit = pickMatchingTmdbHit(fd?.results, title, seedYear, "first_air_date");
                    }
                  }
                }
                tmdbId = Number(hit?.id) || 0;
                if (hit?.original_language) lang = String(hit.original_language).toLowerCase();
              }
            } catch (_) {}
          }
        }
        if (tmdbId > 0) {
          registerExcludedSeed("show", tmdbId, title);
          return { tmdbId, title: title || "your watched shows", lang };
        }
        return null;
      });

      const resolveMovieJobs = movieSeeds.slice(0, 6).map(async (m) => {
        let tmdbId = Number(m?.tmdbId) || 0;
        const seedYear = Number(m?.year) || 0;
        const title = String(m?.title || "").trim();
        let lang = String(m?.lang || "").trim().toLowerCase();
        if (tmdbId <= 0 && title) {
          const yrParam = seedYear > 1900 ? `&year=${seedYear}` : "";
          const searchReq = LiveCatalogService._buildTmdbRequest(
            `/search/movie?query=${encodeURIComponent(title)}${yrParam}&language=en-US&page=1`,
            env
          );
          if (searchReq) {
            try {
              const r = await fetchImpl(searchReq.url, { headers: searchReq.headers });
              if (r.ok) {
                const d = await r.json();
                let hit = pickMatchingTmdbHit(d?.results, title, seedYear, "release_date");
                if (!hit && yrParam) {
                  const fallbackReq = LiveCatalogService._buildTmdbRequest(
                    `/search/movie?query=${encodeURIComponent(title)}&language=en-US&page=1`,
                    env
                  );
                  if (fallbackReq) {
                    const fr = await fetchImpl(fallbackReq.url, { headers: fallbackReq.headers });
                    if (fr.ok) {
                      const fd = await fr.json();
                      hit = pickMatchingTmdbHit(fd?.results, title, seedYear, "release_date");
                    }
                  }
                }
                tmdbId = Number(hit?.id) || 0;
                if (hit?.original_language) lang = String(hit.original_language).toLowerCase();
              }
            } catch (_) {}
          }
        }
        if (tmdbId > 0) {
          registerExcludedSeed("movie", tmdbId, title);
          return { tmdbId, title: title || "your watched movies", lang };
        }
        return null;
      });

      const [resolvedTvRaw, resolvedMovieRaw] = await Promise.all([
        Promise.all(resolveTvJobs),
        Promise.all(resolveMovieJobs),
      ]);
      const resolvedTvSeeds = resolvedTvRaw.filter(Boolean);
      const resolvedMovieSeeds = resolvedMovieRaw.filter(Boolean);

      const seedFetchJobs = [];
      for (const seed of resolvedTvSeeds) {
        // Use collaborative `recommendations` ONLY (never TMDB's noisy keyword `/similar`)
        const path = `/tv/${seed.tmdbId}?append_to_response=recommendations&language=en-US`;
        const req = LiveCatalogService._buildTmdbRequest(path, env);
        if (req) {
          seedFetchJobs.push(
            fetchImpl(req.url, { headers: req.headers })
              .then((r) => (r.ok ? r.json() : null))
              .then(async (data) => {
                if (!data) return [];
                const lang = String(data?.original_language || seed.lang || "").toLowerCase();
                recordSeedLang(lang);
                const seedGenreIds = new Set(
                  (Array.isArray(data?.genres) ? data.genres : [])
                    .map((g) => Number(g?.id))
                    .filter((n) => n > 0)
                );
                let recs = Array.isArray(data?.recommendations?.results) ? data.recommendations.results : [];
                if (safePage > 1) {
                  const pReq = LiveCatalogService._buildTmdbRequest(
                    `/tv/${seed.tmdbId}/recommendations?language=en-US&page=${safePage}`,
                    env
                  );
                  if (pReq) {
                    try {
                      const pr = await fetchImpl(pReq.url, { headers: pReq.headers });
                      if (pr.ok) {
                        const pd = await pr.json();
                        if (Array.isArray(pd?.results) && pd.results.length > 0) recs = pd.results;
                      }
                    } catch (_) {}
                  }
                }
                const validRecs = recs.filter((it) => isRelevantSeedCandidate(it, seedGenreIds, lang));
                const outBatches = [];
                if (validRecs.length > 0) {
                  outBatches.push({ type: "tv", seedTitle: seed.title, items: validRecs });
                }
                if (validRecs.length < 3 && lang && seedGenreIds.size > 0) {
                  const primaryGenre = Array.from(seedGenreIds)[0];
                  const genreLabel = GENRE_LABELS[primaryGenre] || "";
                  const langLabel = LANG_LABELS[lang] || "";
                  const fbReq = LiveCatalogService._buildTmdbRequest(
                    `/discover/tv?with_original_language=${encodeURIComponent(lang)}&with_genres=${primaryGenre}&sort_by=popularity.desc&vote_count.gte=20&vote_average.gte=6.2&include_adult=false&language=en-US&page=${safePage}`,
                    env
                  );
                  if (fbReq) {
                    try {
                      const fbr = await fetchImpl(fbReq.url, { headers: fbReq.headers });
                      if (fbr.ok) {
                        const fbd = await fbr.json();
                        const fbReason = [langLabel, genreLabel, "Series"].filter(Boolean).join(" ");
                        outBatches.push({
                          type: "tv",
                          seedTitle: "",
                          customReason: fbReason ? `Popular ${fbReason}` : "Popular & Acclaimed Series",
                          items: (fbd?.results || []).slice(0, 8),
                        });
                      }
                    } catch (_) {}
                  }
                }
                return outBatches;
              })
              .catch(() => [])
          );
        }
      }

      for (const seed of resolvedMovieSeeds) {
        const path = `/movie/${seed.tmdbId}?append_to_response=recommendations&language=en-US`;
        const req = LiveCatalogService._buildTmdbRequest(path, env);
        if (req) {
          seedFetchJobs.push(
            fetchImpl(req.url, { headers: req.headers })
              .then((r) => (r.ok ? r.json() : null))
              .then(async (data) => {
                if (!data) return [];
                const lang = String(data?.original_language || seed.lang || "").toLowerCase();
                recordSeedLang(lang);
                const seedGenreIds = new Set(
                  (Array.isArray(data?.genres) ? data.genres : [])
                    .map((g) => Number(g?.id))
                    .filter((n) => n > 0)
                );
                let recs = Array.isArray(data?.recommendations?.results) ? data.recommendations.results : [];
                if (safePage > 1) {
                  const pReq = LiveCatalogService._buildTmdbRequest(
                    `/movie/${seed.tmdbId}/recommendations?language=en-US&page=${safePage}`,
                    env
                  );
                  if (pReq) {
                    try {
                      const pr = await fetchImpl(pReq.url, { headers: pReq.headers });
                      if (pr.ok) {
                        const pd = await pr.json();
                        if (Array.isArray(pd?.results) && pd.results.length > 0) recs = pd.results;
                      }
                    } catch (_) {}
                  }
                }
                const validRecs = recs.filter((it) => isRelevantSeedCandidate(it, seedGenreIds, lang));
                const outBatches = [];
                if (validRecs.length > 0) {
                  outBatches.push({ type: "movie", seedTitle: seed.title, items: validRecs });
                }
                if (validRecs.length < 3 && lang && seedGenreIds.size > 0) {
                  const primaryGenre = Array.from(seedGenreIds)[0];
                  const genreLabel = GENRE_LABELS[primaryGenre] || "";
                  const langLabel = LANG_LABELS[lang] || "";
                  const fbReq = LiveCatalogService._buildTmdbRequest(
                    `/discover/movie?with_original_language=${encodeURIComponent(lang)}&with_genres=${primaryGenre}&sort_by=popularity.desc&vote_count.gte=30&vote_average.gte=6.2&include_adult=false&language=en-US&page=${safePage}`,
                    env
                  );
                  if (fbReq) {
                    try {
                      const fbr = await fetchImpl(fbReq.url, { headers: fbReq.headers });
                      if (fbr.ok) {
                        const fbd = await fbr.json();
                        const fbReason = [langLabel, genreLabel, "Cinema"].filter(Boolean).join(" ");
                        outBatches.push({
                          type: "movie",
                          seedTitle: "",
                          customReason: fbReason ? `Popular ${fbReason}` : "Popular & Acclaimed Movie",
                          items: (fbd?.results || []).slice(0, 8),
                        });
                      }
                    } catch (_) {}
                  }
                }
                return outBatches;
              })
              .catch(() => [])
          );
        }
      }

      const seedBatches = (await Promise.all(seedFetchJobs)).flat().filter(Boolean);

      const indianTotal = Array.from(indianLangCounts.values()).reduce((a, b) => a + b, 0);
      const indianRatio = totalSeedLangCount > 0 ? indianTotal / totalSeedLangCount : 0;
      const discoveryJobs = [];

      if (indianTotal > 0) {
        const rankedLangs = Array.from(indianLangCounts.entries())
          .sort((a, b) => b[1] - a[1])
          .map(([k]) => k);
        if (!rankedLangs.includes("hi")) rankedLangs.unshift("hi");
        const targetLangs = rankedLangs.slice(0, 3);

        for (const lang of targetLangs) {
          const langName = LANG_LABELS[lang] || "Indian";
          const discMovPop = LiveCatalogService._buildTmdbRequest(
            `/discover/movie?with_original_language=${encodeURIComponent(lang)}&sort_by=popularity.desc&vote_count.gte=15&include_adult=false&language=en-US&page=${safePage}`,
            env
          );
          if (discMovPop) {
            discoveryJobs.push(
              fetchImpl(discMovPop.url, { headers: discMovPop.headers })
                .then((r) => (r.ok ? r.json() : null))
                .then((d) => ({
                  type: "movie",
                  seedTitle: "",
                  customReason: `Popular in ${langName} Cinema`,
                  items: d?.results || [],
                }))
                .catch(() => null)
            );
          }
          if (lang === "hi" || indianRatio >= 0.4) {
            const discMovTop = LiveCatalogService._buildTmdbRequest(
              `/discover/movie?with_original_language=${encodeURIComponent(lang)}&sort_by=vote_average.desc&vote_count.gte=60&include_adult=false&language=en-US&page=${safePage}`,
              env
            );
            if (discMovTop) {
              discoveryJobs.push(
                fetchImpl(discMovTop.url, { headers: discMovTop.headers })
                  .then((r) => (r.ok ? r.json() : null))
                  .then((d) => ({
                    type: "movie",
                    seedTitle: "",
                    customReason: `Top-Rated ${langName} Movie`,
                    items: d?.results || [],
                  }))
                  .catch(() => null)
              );
            }
          }
        }

        const hiTvReq = LiveCatalogService._buildTmdbRequest(
          `/discover/tv?with_original_language=hi&sort_by=popularity.desc&vote_count.gte=10&include_adult=false&language=en-US&page=${safePage}`,
          env
        );
        if (hiTvReq) {
          discoveryJobs.push(
            fetchImpl(hiTvReq.url, { headers: hiTvReq.headers })
              .then((r) => (r.ok ? r.json() : null))
              .then((d) => ({
                type: "tv",
                seedTitle: "",
                customReason: "Popular Hindi & Indian Series",
                items: d?.results || [],
              }))
              .catch(() => null)
          );
        }
      }

      // Include global trending as light supplement (limited when user's seeds are predominantly Indian)
      const trendTvReq = LiveCatalogService._buildTmdbRequest(`/trending/tv/week?language=en-US&page=${safePage}`, env);
      const trendMovReq = LiveCatalogService._buildTmdbRequest(`/trending/movie/week?language=en-US&page=${safePage}`, env);
      if (trendTvReq) {
        discoveryJobs.push(
          fetchImpl(trendTvReq.url, { headers: trendTvReq.headers })
            .then((r) => (r.ok ? r.json() : null))
            .then((data) => ({
              type: "tv",
              seedTitle: "",
              items: (data?.results || []).slice(0, indianRatio >= 0.45 ? 8 : 20),
            }))
            .catch(() => null)
        );
      }
      if (trendMovReq) {
        discoveryJobs.push(
          fetchImpl(trendMovReq.url, { headers: trendMovReq.headers })
            .then((r) => (r.ok ? r.json() : null))
            .then((data) => ({
              type: "movie",
              seedTitle: "",
              items: (data?.results || []).slice(0, indianRatio >= 0.45 ? 6 : 20),
            }))
            .catch(() => null)
        );
      }

      const discoveryBatches = (await Promise.all(discoveryJobs)).filter(Boolean);
      const batches = [...seedBatches, ...discoveryBatches];
      const maxLen = batches.reduce((acc, b) => Math.max(acc, (b.items || []).length), 0);

      for (let i = 0; i < maxLen; i++) {
        for (const batch of batches) {
          const item = batch.items[i];
          if (!item || !item.id || !item.poster_path) continue;
          if (batch.type === "tv") {
            const title = item.name || item.original_name || "";
            if (!title) continue;
            const firstAir = String(item.first_air_date || "");
            const year = firstAir ? parseInt(firstAir.slice(0, 4), 10) || 2024 : 2024;
            pushUnique({
              id: `tmdb_tv_${item.id}`,
              tvmazeId: 0,
              tmdbId: item.id,
              mediaType: "show",
              title,
              year,
              network: "Streaming",
              status: "Running",
              runtimeMins: 45,
              genres: ["TV Series"],
              rating: item.vote_average ? Math.round(Number(item.vote_average) * 10) / 10 : 8.0,
              posterUrl: `https://image.tmdb.org/t/p/w500${item.poster_path}`,
              overview: stripHtml(item.overview || ""),
              becauseOf: batch.seedTitle
                ? `Because you watched ${batch.seedTitle}`
                : batch.customReason || "Popular & Acclaimed Series",
            });
          } else {
            const title = item.title || item.original_title || "";
            if (!title) continue;
            const rel = String(item.release_date || "");
            const year = rel ? parseInt(rel.slice(0, 4), 10) || 2024 : 2024;
            pushUnique({
              id: `movie_${item.id}`,
              tmdbId: item.id,
              mediaType: "movie",
              title,
              year,
              director: "",
              runtimeMins: 120,
              genres: ["Movie"],
              streamingService: "Streaming",
              rating: item.vote_average ? Math.round(Number(item.vote_average) * 10) / 10 : 7.8,
              posterUrl: `https://image.tmdb.org/t/p/w500${item.poster_path}`,
              overview: stripHtml(item.overview || ""),
              becauseOf: batch.seedTitle
                ? `Because you watched ${batch.seedTitle}`
                : batch.customReason || "Popular & Acclaimed Movie",
            });
          }
        }
      }
    } catch (_) {}

    return { results };
  }

  static isIndianSeriesMetadata(text) {
    const s = String(text || "");
    if (!s) return false;
    return /\b(india|indian|malayalam|tamil|telugu|hindi|kannada|bengali|marathi|gujarati|punjabi|odia|assamese|urdu)\b/i.test(
      s
    );
  }

  static hasIndianWikiCategories(categories) {
    if (!Array.isArray(categories)) return false;
    return categories.some((c) => LiveCatalogService.isIndianSeriesMetadata(c?.title || ""));
  }

  static parseWikiDateMs(raw) {
    const clean = String(raw || "")
      .replace(/\[\[|\]\]/g, " ")
      .replace(/\{\{[^}]*\}\}/g, " ")
      .replace(/<[^>]*>/g, " ")
      .trim();
    const months = {
      january: 0, february: 1, march: 2, april: 3, may: 4, june: 5,
      july: 6, august: 7, september: 8, october: 9, november: 10, december: 11,
    };
    const m1 = /\b([0-9]{1,2})\s+([A-Za-z]+)\s+([12][0-9]{3})\b/.exec(clean);
    if (m1 && months[m1[2].toLowerCase()] !== undefined) {
      return Date.UTC(parseInt(m1[3], 10), months[m1[2].toLowerCase()], parseInt(m1[1], 10));
    }
    const m2 = /\b([A-Za-z]+)\s+([0-9]{1,2}),?\s+([12][0-9]{3})\b/.exec(clean);
    if (m2 && months[m2[1].toLowerCase()] !== undefined) {
      return Date.UTC(parseInt(m2[3], 10), months[m2[1].toLowerCase()], parseInt(m2[2], 10));
    }
    return 0;
  }

  static parseSeasonTableEpisodes(wt, queryTitle = "", pageTitle = "") {
    if (!wt) return 0;
    const cleanQueryWords = String(queryTitle || "")
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, " ")
      .trim()
      .split(/\s+/)
      .filter((w) => w.length >= 3);
    const pageWords = new Set(
      String(pageTitle || "")
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, " ")
        .trim()
        .split(/\s+/)
    );
    const sponsorTokens = cleanQueryWords.filter((w) => !pageWords.has(w) && w !== "series" && w !== "the");

    const tables = String(wt).match(/\{\|[\s\S]*?\|\}/g) || [];
    const nowMs = Date.now();
    for (const tb of tables) {
      const cleanTb = tb.replace(/<ref[\s\S]*?(?:<\/ref>|\/>)/gi, "");
      const rows = cleanTb.split(/\n\|-/);
      const header = rows[0] || "";
      if (!/season/i.test(header)) continue;
      const hasEpisodesCol = /\bepisodes?\b/i.test(header);
      const hasAiredCols = /aired|first\s+aired|original\s+release/i.test(header);
      if (!hasEpisodesCol && !hasAiredCols) continue;

      let totalAll = 0;
      let totalSponsor = 0;
      let headerEpIdx = -1;
      if (hasEpisodesCol) {
        const hCells = header
          .split(/!!|\n!/)
          .map((c) => c.replace(/^[\s!|]+/, "").trim())
          .filter(Boolean);
        headerEpIdx = hCells.findIndex((c) => /\bepisodes?\b/i.test(c));
      }

      for (let ri = 1; ri < rows.length; ri++) {
        const row = rows[ri];
        const cells = row
          .split(/\|\||\n\|/)
          .map((c) => c.trim())
          .filter(Boolean);
        if (cells.length < 2) continue;
        let rowEps = 0;
        if (headerEpIdx >= 0 && cells[headerEpIdx]) {
          const mEp = /^([0-9]{1,4})$/.exec(cells[headerEpIdx].replace(/,/g, "").trim());
          if (mEp) rowEps = parseInt(mEp[1], 10);
        }
        if (rowEps === 0 && hasAiredCols) {
          const dateValues = [];
          for (const cell of cells) {
            const dMs = LiveCatalogService.parseWikiDateMs(cell);
            if (dMs > 0) dateValues.push(dMs);
          }
          if (dateValues.length >= 2 && dateValues[1] > dateValues[0]) {
            const startMs = dateValues[0];
            const endMs = Math.min(dateValues[1], nowMs);
            if (endMs > startMs) {
              const rowLower = row.toLowerCase();
              const perWeek =
                /mon[^a-z0-9]{1,5}sat/.test(rowLower)
                  ? 6
                  : /mon[^a-z0-9]{1,5}fri/.test(rowLower)
                    ? 5
                    : /\bdaily\b/.test(rowLower)
                      ? 7
                      : 2;
              rowEps = Math.max(1, Math.round(((endMs - startMs) / (7 * 86400000)) * perWeek));
            }
          }
        }
        if (rowEps > 0) {
          totalAll += rowEps;
          if (sponsorTokens.length > 0 && sponsorTokens.every((tk) => row.toLowerCase().includes(tk))) {
            totalSponsor += rowEps;
          }
        }
      }
      if (totalSponsor >= 5) return totalSponsor;
      if (totalAll >= 5) return totalAll;
    }
    return 0;
  }

  /**
   * Resolves episode count, runtime, network, overview, and poster via Wikipedia Infoboxes,
   * Wikipedia Season Tables, Broadcaster Program Tables (e.g. Chandanamazha -> 1,173 eps on Asianet), and Wikidata P1113.
   */
  static async lookupWikipediaShowMeta({ title = "", year = 0, imdbId = "", fetchImpl = fetch } = {}) {
    const cleanTitle = String(title || "").trim();
    const cleanImdb = /^tt\d+$/i.test(String(imdbId || "").trim()) ? String(imdbId).trim().toLowerCase() : "";
    const out = { totalEpisodes: 0, runtimeMins: 0, year: 0, network: "", overview: "", posterUrl: "", status: "", isIndian: false };
    if (!cleanTitle && !cleanImdb) return out;
    const wikiHeaders = { "User-Agent": "MovieDBTracker/1.0 (https://moviedb-website.madhavsankar.workers.dev)" };

    const parseInfoboxWikitext = (wt) => {
      if (!wt) return;
      if (LiveCatalogService.isIndianSeriesMetadata(wt)) out.isIndian = true;
      const mEps = wt.match(/num_episodes\s*=\s*(?:\{\{[^}|]*\|)?\s*([0-9][0-9,]*)/i);
      if (mEps) {
        const n = parseInt(mEps[1].replace(/,/g, ""), 10);
        if (n >= 2 && n <= 25000) out.totalEpisodes = n;
      }
      const mRt = wt.match(/runtime\s*=\s*[^0-9\n]{0,25}([0-9]{2,3})(?:\s*[-\u2013\u2014]\s*([0-9]{2,3}))?/i);
      if (mRt) {
        const r1 = parseInt(mRt[1], 10);
        const r2 = mRt[2] ? parseInt(mRt[2], 10) : 0;
        if (r1 >= 10 && r1 <= 180) {
          out.runtimeMins = r2 >= r1 && r2 <= 240 ? Math.round((r1 + r2) / 2) : r1;
        }
      }
      const mYr = wt.match(/first_aired\s*=\s*[^\n]*?\b(19[5-9][0-9]|20[0-3][0-9])\b/i);
      if (mYr && !out.year) {
        out.year = parseInt(mYr[1], 10);
      }
      const mCountry = wt.match(/\|\s*country\s*=\s*([^\n|]+)/i);
      if (mCountry && LiveCatalogService.isIndianSeriesMetadata(mCountry[1])) {
        out.isIndian = true;
      }
      const mNetBlock = wt.match(/\|\s*network\s*=\s*([\s\S]{1,260}?)(?=\n\s*\||\n\}\})/i);
      if (mNetBlock && !out.network) {
        const block = mNetBlock[1];
        const linkMatch = /\[\[([^\]|]+)(?:\|([^\]]+))?\]\]/.exec(block);
        const rawNetStr = linkMatch
          ? String(linkMatch[2] || linkMatch[1] || "").trim()
          : String(block.replace(/\{\{[^|}]*\|?/g, "").replace(/\}\}/g, "").split(/\n|\*/)[0] || "").trim();
        const cleanNetStr = rawNetStr.replace(/\s*\(TV channel\)\s*/i, "").trim();
        if (cleanNetStr && cleanNetStr.length <= 45) {
          out.network = cleanNetStr;
        }
      }
      if (/last_aired\s*=\s*[^\n]*present/i.test(wt)) {
        out.status = "Running";
      } else if (/last_aired\s*=\s*[^\n]*[0-9]{4}/i.test(wt)) {
        out.status = "Ended";
      }
    };

    // 1. Direct Wikipedia Infobox lookup
    let matchedInfoboxPageId = 0;
    let matchedInfoboxTitle = "";
    if (cleanTitle) {
      const candidates = [cleanTitle, `${cleanTitle} (Indian TV series)`, `${cleanTitle} (TV series)`];
      if (Number(year) > 1950) candidates.push(`${cleanTitle} (${year} TV series)`);
      if (cleanTitle.includes(":")) {
        const afterColon = cleanTitle.split(":").slice(1).join(":").trim();
        if (afterColon.length >= 4) {
          candidates.push(afterColon, `${afterColon} (Indian TV series)`, `${afterColon} (TV series)`);
        }
      }
      try {
        const directUrl = `https://en.wikipedia.org/w/api.php?action=query&titles=${encodeURIComponent(candidates.join("|"))}&prop=revisions|extracts|pageimages|categories&cllimit=30&exintro=1&explaintext=1&rvprop=content&rvsection=0&piprop=thumbnail&pithumbsize=500&format=json&redirects=1&origin=*`;
        const resp = await fetchImpl(directUrl, { headers: wikiHeaders });
        if (resp && resp.ok) {
          const data = await resp.json();
          const pages = Object.values(data?.query?.pages || {}).filter((p) => p && Number(p.pageid) > 0);
          pages.sort((a, b) => {
            const rank = (t) => (/indian tv series/i.test(t) ? 2 : /tv series/i.test(t) ? 1 : 0);
            return rank(b.title || "") - rank(a.title || "");
          });
          for (const page of pages) {
            const rev = page.revisions?.[0];
            const wt = rev?.slots?.main?.["*"] || rev?.["*"] || "";
            if (!/infobox\s+television/i.test(wt) && !/num_episodes/i.test(wt)) continue;
            parseInfoboxWikitext(wt);
            if (LiveCatalogService.hasIndianWikiCategories(page.categories)) out.isIndian = true;
            if (page.extract) {
              if (LiveCatalogService.isIndianSeriesMetadata(page.extract)) out.isIndian = true;
              if (!out.overview) out.overview = String(page.extract).trim().slice(0, 500);
            }
            if (page.title && LiveCatalogService.isIndianSeriesMetadata(page.title)) out.isIndian = true;
            if (page.thumbnail?.source && !out.posterUrl) {
              out.posterUrl = String(page.thumbnail.source);
            }
            matchedInfoboxPageId = Number(page.pageid) || 0;
            matchedInfoboxTitle = String(page.title || "");
            if (out.totalEpisodes > 0) break;
          }
        }
      } catch (_) {}
    }

    // 1b. If an Indian Infobox television page lacked `num_episodes`, parse its full Wikipedia Season Table generically
    if (out.totalEpisodes === 0 && out.isIndian && matchedInfoboxPageId > 0) {
      try {
        const fullUrl = `https://en.wikipedia.org/w/api.php?action=query&pageids=${matchedInfoboxPageId}&prop=revisions&rvprop=content&format=json&origin=*`;
        const fResp = await fetchImpl(fullUrl, { headers: wikiHeaders });
        if (fResp && fResp.ok) {
          const fData = await fResp.json();
          const fPage = fData?.query?.pages?.[String(matchedInfoboxPageId)];
          const fRev = fPage?.revisions?.[0];
          const fullWt = fRev?.slots?.main?.["*"] || fRev?.["*"] || "";
          const tableEps = LiveCatalogService.parseSeasonTableEpisodes(fullWt, cleanTitle, matchedInfoboxTitle);
          if (tableEps >= 15) {
            out.totalEpisodes = tableEps;
            if (!out.status) out.status = "Ended";
          }
        }
      } catch (_) {}
    }

    // 2. Wikipedia Broadcaster Program List / Search Fallback
    if (out.totalEpisodes === 0 && cleanTitle) {
      try {
        const sQuery = `"${cleanTitle}" episodes`;
        const searchUrl = `https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=${encodeURIComponent(sQuery)}&srlimit=6&format=json&origin=*`;
        const sResp = await fetchImpl(searchUrl, { headers: wikiHeaders });
        if (sResp && sResp.ok) {
          const sData = await sResp.json();
          const hits = (sData?.query?.search || [])
            .map((h) => h?.title)
            .filter(Boolean)
            .sort((a, b) => (/^list of /i.test(b) ? 1 : 0) - (/^list of /i.test(a) ? 1 : 0));
          if (hits.length > 0) {
            const pUrl = `https://en.wikipedia.org/w/api.php?action=query&titles=${encodeURIComponent(hits.slice(0, 4).join("|"))}&prop=revisions|categories&cllimit=30&rvprop=content&format=json&redirects=1&origin=*`;
            const pResp = await fetchImpl(pUrl, { headers: wikiHeaders });
            if (pResp && pResp.ok) {
              const pData = await pResp.json();
              const escTitle = cleanTitle.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
              const titleRe = new RegExp(`(?:'+'|\\[\\[(?:[^\\]|]*\\|)?)${escTitle}(?:'+'|\\]\\])`, "i");
              for (const page of Object.values(pData?.query?.pages || {})) {
                const rev = page?.revisions?.[0];
                const wt = rev?.slots?.main?.["*"] || rev?.["*"] || "";
                if (!wt) continue;
                const pageTitleStr = String(page?.title || "");
                const pageHasIndianCat = LiveCatalogService.hasIndianWikiCategories(page?.categories);
                parseInfoboxWikitext(wt);
                if (out.totalEpisodes > 0) {
                  if (pageHasIndianCat || LiveCatalogService.isIndianSeriesMetadata(pageTitleStr)) out.isIndian = true;
                  break;
                }
                const tables = wt.match(/\{\|[\s\S]*?\|\}/g) || [];
                for (const tb of tables) {
                  const cleanTb = tb.replace(/<ref[\s\S]*?(?:<\/ref>|\/>)/gi, "");
                  const rows = cleanTb.split(/\n\|-/);
                  const header = rows[0] || "";
                  if (!/\bepisodes?\b/i.test(header)) continue;
                  const hCells = header
                    .split(/!!|\n!/)
                    .map((c) => c.replace(/^[\s!|]+/, "").trim())
                    .filter(Boolean);
                  const epColIdx = hCells.findIndex((c) => /\bepisodes?\b/i.test(c));
                  for (let ri = 1; ri < rows.length; ri++) {
                    const row = rows[ri];
                    if (!titleRe.test(row)) continue;
                    const cells = row
                      .split(/\|\||\n\|/)
                      .map((c) => c.trim())
                      .filter(Boolean);
                    let picked = 0;
                    if (epColIdx >= 0 && cells[epColIdx]) {
                      const mExact = /^([0-9]{2,5})$/.exec(cells[epColIdx].replace(/,/g, "").trim());
                      if (mExact) picked = parseInt(mExact[1], 10);
                    }
                    if (!picked) {
                      for (let ci = cells.length - 1; ci >= 0; ci--) {
                        const mNum = /^([0-9]{2,5})$/.exec(cells[ci].replace(/,/g, ""));
                        if (mNum) {
                          const candVal = parseInt(mNum[1], 10);
                          if (candVal >= 1950 && candVal <= 2035) continue;
                          picked = candVal;
                          break;
                        }
                      }
                    }
                    if (picked >= 15 && picked <= 25000 && picked !== Number(year)) {
                      out.totalEpisodes = picked;
                      out.status = out.status || "Ended";
                      const netMatch = /broadcast by\s+(.+)$/i.exec(pageTitleStr);
                      if (netMatch && !out.network) out.network = netMatch[1].trim();
                      if (
                        pageHasIndianCat ||
                        LiveCatalogService.isIndianSeriesMetadata(pageTitleStr) ||
                        LiveCatalogService.isIndianSeriesMetadata(row) ||
                        LiveCatalogService.isIndianSeriesMetadata(wt.slice(0, 1500))
                      ) {
                        out.isIndian = true;
                      }
                      break;
                    }
                  }
                  if (out.totalEpisodes > 0) break;
                }
                if (out.totalEpisodes > 0) break;
              }
            }
          }
        }
      } catch (_) {}
    }

    // 3. Wikidata SPARQL Fallback by IMDb ID (P345 -> P1113 number of episodes, P495 country of origin)
    if (out.totalEpisodes === 0 && cleanImdb) {
      try {
        const sparql = `SELECT ?episodes ?duration ?country WHERE { ?item wdt:P345 "${cleanImdb}" . OPTIONAL { ?item wdt:P1113 ?episodes . } OPTIONAL { ?item wdt:P2047 ?duration . } OPTIONAL { ?item wdt:P495 ?country . } } LIMIT 1`;
        const wdUrl = `https://query.wikidata.org/sparql?format=json&query=${encodeURIComponent(sparql)}`;
        const wdResp = await fetchImpl(wdUrl, { headers: wikiHeaders });
        if (wdResp && wdResp.ok) {
          const wdData = await wdResp.json();
          const b = wdData?.results?.bindings?.[0];
          const epVal = parseInt(b?.episodes?.value || "0", 10);
          if (epVal >= 2 && epVal <= 25000) out.totalEpisodes = epVal;
          const durVal = parseInt(b?.duration?.value || "0", 10);
          if (durVal >= 10 && durVal <= 180 && !out.runtimeMins) out.runtimeMins = durVal;
          if (String(b?.country?.value || "").endsWith("/Q668")) out.isIndian = true;
        }
      } catch (_) {}
    }

    return out;
  }

  /**
   * Fetches full TV show details + complete episode list + cast from TMDB (`/3/tv/:id`), TVMaze, or IMDb+Wikipedia count-only fallback.
   */
  static async fetchFullShowFromTvMaze(tvmazeId, { title = "", fetchImpl = fetch, env = null } = {}) {
    let rawStr = String(tvmazeId || "").trim();
    const cleanTitle = String(title || "").trim();
    const isImdbTvId = /^imdb_tv_tt\d+$/i.test(rawStr);
    const extractedImdbId = isImdbTvId ? rawStr.replace(/^imdb_tv_/i, "") : "";
    if (rawStr.startsWith("movie_")) {
      const isNonTmdbMovie = rawStr.startsWith("movie_tt") || rawStr.startsWith("movie_itunes_");
      let tmdbId = isNonTmdbMovie ? 0 : Number(rawStr.replace(/^movie_/, "").replace(/\D/g, ""));
      if (!tmdbId && cleanTitle) {
        const sMovReq = LiveCatalogService._buildTmdbRequest(
          `/search/movie?query=${encodeURIComponent(cleanTitle)}&include_adult=false&language=en-US&page=1`,
          env
        );
        if (sMovReq) {
          try {
            const sr = await fetchImpl(sMovReq.url, { headers: sMovReq.headers });
            if (sr.ok) {
              const sd = await sr.json();
              tmdbId = Number(sd?.results?.[0]?.id) || 0;
            }
          } catch (_) {}
        }
      }
      const tmdbReq = LiveCatalogService._buildTmdbRequest(
        `/movie/${tmdbId}?append_to_response=credits,watch/providers&language=en-US`,
        env
      );
      if (tmdbId && tmdbReq) {
        try {
          const resp = await fetchImpl(tmdbReq.url, { headers: tmdbReq.headers });
          if (resp.ok) {
            const mov = await resp.json();
            const rel = String(mov.release_date || "");
            const year = rel ? parseInt(rel.slice(0, 4), 10) || 2024 : 2024;
            const usProviders = mov["watch/providers"]?.results?.US;
            const streamingService = LiveCatalogService.normalizeUsProvidersFromTmdb(usProviders, "");
            const posterUrl = mov.poster_path ? `https://image.tmdb.org/t/p/w500${mov.poster_path}` : "";
            const castList = (mov.credits?.cast || [])
              .slice(0, 8)
              .map((c) => c?.name)
              .filter(Boolean);
            const directorEntry = (mov.credits?.crew || []).find((c) => c?.job === "Director");
            return {
              id: `movie_${mov.id}`,
              tmdbId: mov.id,
              mediaType: "movie",
              title: mov.title || mov.original_title || "Untitled Movie",
              year,
              director: directorEntry?.name || "",
              streamingService,
              runtimeMins: Number(mov.runtime) || 120,
              genres: (mov.genres || []).map((g) => g?.name).filter(Boolean),
              cast: castList,
              rating: mov.vote_average ? Math.round(Number(mov.vote_average) * 10) / 10 : 7.8,
              posterUrl,
              overview: stripHtml(mov.overview),
            };
          }
        } catch (_) {}
      }
      return null;
    }

    // For IMDb TV series IDs (e.g. imdb_tv_tt10339970 Chandanamazha), resolve via TMDB /find/{ttId} first;
    // never blindly match an unrelated same-name TMDB stub from a different decade!
    if (isImdbTvId && extractedImdbId) {
      const findTvReq = LiveCatalogService._buildTmdbRequest(
        `/find/${encodeURIComponent(extractedImdbId)}?external_source=imdb_id&language=en-US`,
        env
      );
      if (findTvReq) {
        try {
          const fr = await fetchImpl(findTvReq.url, { headers: findTvReq.headers });
          if (fr.ok) {
            const fd = await fr.json();
            const matchedTvId = Number(fd?.tv_results?.[0]?.id || 0);
            if (matchedTvId > 0) {
              rawStr = `tmdb_tv_${matchedTvId}`;
            }
          }
        } catch (_) {}
      }
      if (rawStr.startsWith("imdb_tv_")) {
        const wikiMeta = await LiveCatalogService.lookupWikipediaShowMeta({
          title: cleanTitle,
          imdbId: extractedImdbId,
          fetchImpl,
        });
        // Also pull poster & year & cast from IMDb suggestion index if needed
        let imdbPoster = "";
        let imdbYear = 2024;
        let imdbCast = [];
        try {
          const suggKey = (cleanTitle || extractedImdbId)[0].toLowerCase().replace(/[^a-z0-9]/, "x");
          const suggUrl = `https://v3.sg.media-imdb.com/suggestion/${suggKey}/${encodeURIComponent(cleanTitle || extractedImdbId)}.json`;
          const sResp = await fetchImpl(suggUrl);
          if (sResp && sResp.ok) {
            const sJson = await sResp.json();
            const hit = (sJson?.d || []).find((d) => String(d?.id || "").toLowerCase() === extractedImdbId.toLowerCase());
            if (hit) {
              imdbPoster = hit.i?.imageUrl || "";
              imdbYear = Number(hit.y) || 2024;
              if (hit.s) {
                imdbCast = String(hit.s).split(",").map((c) => c.trim()).filter(Boolean);
              }
            }
          }
        } catch (_) {}
        const resolvedTotal = Math.max(wikiMeta.totalEpisodes || 0, 1);
        return {
          id: rawStr,
          tvmazeId: 0,
          tmdbId: 0,
          title: cleanTitle || "Untitled Show",
          year: imdbYear,
          network: wikiMeta.network || "Television",
          status: wikiMeta.status || "Ended",
          runtimeMins: wikiMeta.runtimeMins || 22,
          genres: ["TV Series"],
          cast: imdbCast,
          rating: 8.0,
          posterUrl: imdbPoster || wikiMeta.posterUrl || "",
          overview: wikiMeta.overview || (imdbCast.length > 0 ? `TV Series · Starring ${imdbCast.join(", ")}` : "TV Series"),
          totalEpisodes: resolvedTotal,
          countOnly: true,
          episodes: [],
        };
      }
    }

    // Resolve non-TMDB / non-TVMaze synthetic IDs (e.g. csv_show_*) via title search on TMDB first
    if (!rawStr.startsWith("tmdb_tv_") && !isImdbTvId && !Number(rawStr.replace(/^tvmaze_/, "")) && cleanTitle) {
      const searchTvReq = LiveCatalogService._buildTmdbRequest(
        `/search/tv?query=${encodeURIComponent(cleanTitle)}&include_adult=false&language=en-US&page=1`,
        env
      );
      if (searchTvReq) {
        try {
          const sResp = await fetchImpl(searchTvReq.url, { headers: searchTvReq.headers });
          if (sResp.ok) {
            const sData = await sResp.json();
            const firstId = Number(sData?.results?.[0]?.id || 0);
            if (firstId > 0) {
              rawStr = `tmdb_tv_${firstId}`;
            }
          }
        } catch (_) {}
      }
    }

    if (rawStr.startsWith("tmdb_tv_")) {
      const tmdbId = Number(rawStr.replace(/^tmdb_tv_/, ""));
      const tmdbReq = LiveCatalogService._buildTmdbRequest(
        `/tv/${tmdbId}?append_to_response=credits,watch/providers&language=en-US`,
        env
      );
      if (tmdbId && tmdbReq) {
        try {
          const resp = await fetchImpl(tmdbReq.url, { headers: tmdbReq.headers });
          if (resp.ok) {
            const show = await resp.json();
            const firstAir = String(show.first_air_date || "");
            const year = firstAir ? parseInt(firstAir.slice(0, 4), 10) || 2024 : 2024;
            const rawNet = show.networks?.[0]?.name || "";
            const usProviders = show["watch/providers"]?.results?.US;
            const network = LiveCatalogService.normalizeUsProvidersFromTmdb(usProviders, rawNet);
            const posterUrl = show.poster_path ? `https://image.tmdb.org/t/p/w500${show.poster_path}` : "";
            const castList = (show.credits?.cast || [])
              .slice(0, 8)
              .map((c) => c?.name)
              .filter(Boolean);
            const defaultRuntime = Number(show.episode_run_time?.[0]) || 45;
            const seasonNums = Array.from(
              new Set(
                (show.seasons || [])
                  .map((s) => Number(s.season_number))
                  .filter((n) => Number.isFinite(n) && n >= 1)
              )
            ).sort((a, b) => a - b);

            const seasonChunks = [];
            for (let i = 0; i < seasonNums.length; i += 20) {
              seasonChunks.push(seasonNums.slice(i, i + 20));
            }
            const chunkReqs = seasonChunks.map((chunk) => {
              const appendSeasons = chunk.map((n) => `season/${n}`).join(",");
              const bReq = LiveCatalogService._buildTmdbRequest(
                `/tv/${tmdbId}?append_to_response=${appendSeasons}&language=en-US`,
                env
              );
              return bReq ? fetchImpl(bReq.url, { headers: bReq.headers }) : Promise.resolve(null);
            });
            const chunkResps = await Promise.allSettled(chunkReqs);
            const episodes = [];
            const seenCodes = new Set();
            const pushEpisode = (sNum, ep) => {
              const eNum = Number(ep?.episode_number) || 1;
              const code = formatEpisodeCode(sNum, eNum);
              if (seenCodes.has(code)) return;
              seenCodes.add(code);
              episodes.push({
                code,
                season: sNum,
                number: eNum,
                title: ep.name || `Episode ${eNum}`,
                airDate: ep.air_date || "",
                runtimeMins: Number(ep.runtime) || defaultRuntime,
                imageUrl: ep.still_path ? `https://image.tmdb.org/t/p/w300${ep.still_path}` : "",
                overview: stripHtml(ep.overview),
              });
            };

            const missingSeasons = [];
            for (let cIdx = 0; cIdx < seasonChunks.length; cIdx++) {
              const chunk = seasonChunks[cIdx];
              const cRes = chunkResps[cIdx];
              let batchData = null;
              if (cRes && cRes.status === "fulfilled" && cRes.value && cRes.value.ok) {
                try {
                  batchData = await cRes.value.json();
                } catch (_) {}
              }
              for (const sNum of chunk) {
                const sData = batchData ? batchData[`season/${sNum}`] : null;
                if (sData && Array.isArray(sData.episodes)) {
                  for (const ep of sData.episodes) {
                    pushEpisode(sNum, ep);
                  }
                } else {
                  missingSeasons.push(sNum);
                }
              }
            }

            if (missingSeasons.length > 0 && missingSeasons.length <= 20) {
              const fallbackReqs = missingSeasons.map((sNum) => {
                const sReq = LiveCatalogService._buildTmdbRequest(`/tv/${tmdbId}/season/${sNum}?language=en-US`, env);
                return sReq ? fetchImpl(sReq.url, { headers: sReq.headers }) : Promise.resolve(null);
              });
              const fallbackResps = await Promise.allSettled(fallbackReqs);
              for (let idx = 0; idx < missingSeasons.length; idx++) {
                const fRes = fallbackResps[idx];
                if (fRes && fRes.status === "fulfilled" && fRes.value && fRes.value.ok) {
                  try {
                    const sData = await fRes.value.json();
                    const sNum = Number(sData.season_number) || missingSeasons[idx];
                    for (const ep of sData.episodes || []) {
                      pushEpisode(sNum, ep);
                    }
                  } catch (_) {}
                }
              }
            }

            const nextAir = show.next_episode_to_air;
            if (nextAir && typeof nextAir === "object") {
              const nextS = Number(nextAir.season_number) || 0;
              const nextE = Number(nextAir.episode_number) || 0;
              const nextDate = String(nextAir.air_date || "").trim();
              if (nextS >= 1 && nextE >= 1 && nextDate) {
                pushEpisode(nextS, nextAir);
              }
            }
            episodes.sort((a, b) => (a.season - b.season) || (a.number - b.number));

            // Count-only mode applies ONLY to Indian series that do NOT have detailed multi-season episode guides on TMDB
            // (never multi-season OTT shows like The Family Man or Mirzapur).
            const indianLangSet = new Set(["hi", "ml", "ta", "te", "kn", "bn", "mr", "pa", "gu", "ur", "or", "as"]);
            const originCountries = Array.isArray(show.origin_country) ? show.origin_country.map((c) => String(c || "").toUpperCase()) : [];
            const origLang = String(show.original_language || "").trim().toLowerCase();
            const populatedSeasonCount = new Set(episodes.map((e) => Number(e.season) || 1)).size;
            const hasDetailedSeasons = populatedSeasonCount >= 2;
            const tmdbIsIndian = originCountries.includes("IN") || indianLangSet.has(origLang);
            const tmdbExplicitlyNonIndian =
              episodes.length > 0 &&
              !tmdbIsIndian &&
              ((originCountries.length > 0 && !originCountries.includes("IN") && !originCountries.includes("IO")) ||
                (origLang.length > 0 && !indianLangSet.has(origLang)));
            const lacksDetailedGuide =
              !hasDetailedSeasons && (episodes.length <= 1 || (tmdbIsIndian && episodes.length <= 35));
            if (lacksDetailedGuide && !tmdbExplicitlyNonIndian) {
              const wikiMeta = await LiveCatalogService.lookupWikipediaShowMeta({
                title: show.name || cleanTitle,
                year: firstAir ? year : 0,
                fetchImpl,
              });
              const wikiConfirmedTotal = Number(wikiMeta.totalEpisodes) || 0;
              const meetsSerialThreshold =
                episodes.length <= 1
                  ? wikiConfirmedTotal >= 15
                  : wikiConfirmedTotal >= 50 && wikiConfirmedTotal >= episodes.length * 4;
              if ((tmdbIsIndian || wikiMeta.isIndian) && meetsSerialThreshold) {
                const hasExplicitTmdbRt = Array.isArray(show.episode_run_time) && Number(show.episode_run_time[0]) > 0;
                return {
                  id: `tmdb_tv_${show.id}`,
                  tvmazeId: 0,
                  tmdbId: show.id,
                  title: show.name || "Untitled Show",
                  year: firstAir ? year : wikiMeta.year || year,
                  network: wikiMeta.network && network === "Streaming" ? wikiMeta.network : network,
                  status: wikiMeta.status || show.status || "Running",
                  runtimeMins: hasExplicitTmdbRt ? defaultRuntime : wikiMeta.runtimeMins || 22,
                  genres: (show.genres || []).map((g) => g?.name).filter(Boolean),
                  cast: castList,
                  rating: show.vote_average ? Math.round(Number(show.vote_average) * 10) / 10 : 8.0,
                  posterUrl: posterUrl || wikiMeta.posterUrl || "",
                  overview: stripHtml(show.overview) || wikiMeta.overview || "",
                  totalEpisodes: wikiConfirmedTotal,
                  countOnly: true,
                  episodes: [],
                };
              }
            }

            return {
              id: `tmdb_tv_${show.id}`,
              tvmazeId: 0,
              tmdbId: show.id,
              title: show.name || "Untitled Show",
              year,
              network,
              status: show.status || "Running",
              runtimeMins: defaultRuntime,
              genres: (show.genres || []).map((g) => g?.name).filter(Boolean),
              cast: castList,
              rating: show.vote_average ? Math.round(Number(show.vote_average) * 10) / 10 : 8.0,
              posterUrl,
              overview: stripHtml(show.overview),
              episodes,
            };
          }
        } catch (_) {}
      }
    }

    const cleanId = Number(rawStr.replace(/^tvmaze_/, ""));
    if (!cleanId && !cleanTitle) return null;

    try {
      const tvmazeUrl = cleanId
        ? `https://api.tvmaze.com/shows/${cleanId}?embed[]=episodes&embed[]=cast`
        : `https://api.tvmaze.com/singlesearch/shows?q=${encodeURIComponent(cleanTitle)}&embed[]=episodes&embed[]=cast`;
      const resp = await fetchImpl(tvmazeUrl);
      if (!resp.ok) return null;
      const show = await resp.json();
      const premiered = String(show.premiered || "");
      const year = premiered ? parseInt(premiered.slice(0, 4), 10) || 2024 : 2024;
      const rawNetwork = show.webChannel?.name || show.network?.name || "Streaming";
      const network = LiveCatalogService.normalizeUsProvidersFromTmdb(null, rawNetwork);
      const posterUrl = (show.image?.medium || show.image?.original || "").replace(/^http:\/\//i, "https://");
      const castList = (show._embedded?.cast || [])
        .slice(0, 8)
        .map((c) => c?.person?.name)
        .filter(Boolean);
      const rawEps = show._embedded?.episodes || [];
      const episodes = rawEps
        .map((ep) => ({
          code: formatEpisodeCode(ep.season, ep.number),
          season: Number(ep.season) || 1,
          number: Number(ep.number) || 1,
          title: ep.name || `Episode ${ep.number || 1}`,
          airDate: ep.airdate || "",
          runtimeMins: Number(ep.runtime || show.averageRuntime || show.runtime) || 45,
          imageUrl: (ep.image?.medium || ep.image?.original || "").replace(/^http:\/\//i, "https://"),
          overview: stripHtml(ep.summary),
        }))
        .sort((a, b) => (a.season - b.season) || (a.number - b.number));

      const isIndianTvMaze =
        String(show.network?.country?.code || show.webChannel?.country?.code || "").toUpperCase() === "IN" ||
        LiveCatalogService.isIndianSeriesMetadata(show.language || "") ||
        LiveCatalogService.isIndianSeriesMetadata(rawNetwork);
      const tvMazeSeasons = new Set(episodes.map((e) => Number(e.season) || 1)).size;
      if (isIndianTvMaze && tvMazeSeasons <= 1 && episodes.length <= 200) {
        const wikiMeta = await LiveCatalogService.lookupWikipediaShowMeta({
          title: show.name || cleanTitle,
          year,
          fetchImpl,
        });
        const wikiTot = Number(wikiMeta.totalEpisodes) || 0;
        if (wikiMeta.isIndian && wikiTot >= 50 && wikiTot >= episodes.length * 2) {
          return {
            id: `tvmaze_${show.id}`,
            tvmazeId: show.id,
            title: show.name || "Untitled Show",
            year,
            network: wikiMeta.network && network === "Streaming" ? wikiMeta.network : network,
            status: wikiMeta.status || show.status || "Ended",
            runtimeMins: Number(show.averageRuntime || show.runtime) || wikiMeta.runtimeMins || 22,
            genres: Array.isArray(show.genres) ? show.genres : [],
            cast: castList,
            rating: Number(show.rating?.average) || 7.9,
            posterUrl: posterUrl || wikiMeta.posterUrl || "",
            overview: stripHtml(show.summary) || wikiMeta.overview || "",
            totalEpisodes: wikiTot,
            countOnly: true,
            episodes: [],
          };
        }
      }

      return {
        id: `tvmaze_${show.id}`,
        tvmazeId: show.id,
        title: show.name || "Untitled Show",
        year,
        network,
        status: show.status || "Running",
        runtimeMins: Number(show.averageRuntime || show.runtime) || 45,
        genres: Array.isArray(show.genres) ? show.genres : [],
        cast: castList,
        rating: Number(show.rating?.average) || 7.9,
        posterUrl,
        overview: stripHtml(show.summary),
        episodes,
      };
    } catch (_) {
      return null;
    }
  }

  /**
   * Computes watch hours and library counts for a user state (without rewatch multipliers).
   */
  static computeStats(state) {
    const shows = Array.isArray(state?.shows) ? state.shows : [];
    const movies = Array.isArray(state?.movies) ? state.movies : [];

    let totalEpisodesWatched = 0;
    let showMinutesWatched = 0;
    const todayIso = new Date().toISOString().slice(0, 10);
    const isFutureEp = (ep) => {
      const d = String(ep?.airDate || "").trim().slice(0, 10);
      return /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(d) && d > todayIso;
    };

    for (const s of shows) {
      const eps = Array.isArray(s.episodes) ? s.episodes : [];
      const rawStatus = String(s.userStatus || "").toLowerCase();
      const defaultEpRuntime = Math.max(5, Number(s.runtimeMins) || 45);
      const watchedSet = new Set(
        (s.watchedEpisodes || [])
          .map((c) => String(c || "").trim().toUpperCase())
          .filter(Boolean)
      );
      const compMap = s.completedSeasons && typeof s.completedSeasons === "object" ? s.completedSeasons : {};
      const completedSeasonNums = new Set();
      let compactedCount = 0;
      for (const [sk, cnt] of Object.entries(compMap)) {
        const sNum = Number(sk) || 0;
        const cNum = Math.max(0, Number(cnt) || 0);
        if (sNum > 0 && cNum > 0) {
          completedSeasonNums.add(sNum);
          compactedCount += cNum;
        }
      }

      if (rawStatus === "completed" && watchedSet.size === 0 && completedSeasonNums.size === 0) {
        const count = Math.max(Number(s.totalEpisodes) || 0, 1);
        totalEpisodesWatched += count;
        showMinutesWatched += count * defaultEpRuntime;
      } else if (rawStatus === "completed") {
        const airedEps = eps.filter((ep) => !isFutureEp(ep));
        const storedTotal = Number(s.totalEpisodes) || 0;
        if (airedEps.length > 0 && storedTotal <= eps.length) {
          totalEpisodesWatched += compactedCount;
          showMinutesWatched += compactedCount * defaultEpRuntime;
          for (const ep of airedEps) {
            const sNum = Number(ep.season) || 1;
            if (completedSeasonNums.has(sNum)) continue;
            totalEpisodesWatched += 1;
            showMinutesWatched += Number(ep.runtimeMins) || defaultEpRuntime;
          }
        } else {
          const count = Math.max(storedTotal, compactedCount + watchedSet.size, 1);
          totalEpisodesWatched += count;
          showMinutesWatched += count * defaultEpRuntime;
        }
      } else {
        // Covers both `in_progress` and `stopped` shows up to exact completed seasons + watched episodes
        totalEpisodesWatched += compactedCount;
        showMinutesWatched += compactedCount * defaultEpRuntime;
        const presentCodes = new Set();
        for (const ep of eps) {
          const code = String(ep.code || "").trim().toUpperCase();
          const sNum = Number(ep.season) || 1;
          if (code) presentCodes.add(code);
          if (completedSeasonNums.has(sNum)) continue;
          if (code && watchedSet.has(code)) {
            totalEpisodesWatched += 1;
            showMinutesWatched += Number(ep.runtimeMins) || defaultEpRuntime;
          }
        }
        for (const code of watchedSet) {
          if (presentCodes.has(code)) continue;
          const m = /^S0*([0-9]+)E[0-9]+$/i.exec(code);
          const sNum = m ? Number(m[1]) || 0 : 0;
          if (sNum > 0 && completedSeasonNums.has(sNum)) continue;
          totalEpisodesWatched += 1;
          showMinutesWatched += defaultEpRuntime;
        }
      }
    }

    let totalMoviesWatched = 0;
    let movieMinutesWatched = 0;
    for (const m of movies) {
      if (m.watched) {
        totalMoviesWatched += 1;
        movieMinutesWatched += Number(m.runtimeMins) || 120;
      }
    }

    const totalMinutes = showMinutesWatched + movieMinutesWatched;
    return {
      totalMinutes,
      showMinutesWatched,
      movieMinutesWatched,
      totalHours: Math.round((totalMinutes / 60) * 10) / 10,
      showHours: Math.round((showMinutesWatched / 60) * 10) / 10,
      movieHours: Math.round((movieMinutesWatched / 60) * 10) / 10,
      totalEpisodesWatched,
      totalMoviesWatched,
      totalSeriesCount: shows.length,
      totalMoviesCount: movies.length,
      totalDays: Math.floor(totalMinutes / (60 * 24)),
      totalHoursRemainder: Math.floor((totalMinutes % (60 * 24)) / 60),
      showDays: Math.floor(showMinutesWatched / (60 * 24)),
      showHoursRemainder: Math.floor((showMinutesWatched % (60 * 24)) / 60),
      movieDays: Math.floor(movieMinutesWatched / (60 * 24)),
      movieHoursRemainder: Math.floor((movieMinutesWatched % (60 * 24)) / 60),
    };
  }
}

