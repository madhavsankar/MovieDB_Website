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
    const hasMovieNearYear = (baseTitle, yr) => {
      if (!baseTitle) return false;
      const yrs = seenMovieBaseTitleYears.get(baseTitle);
      if (!yrs) return false;
      return yrs.some((y) => Math.abs(y - yr) <= 1);
    };
    const recordMovieTitleYear = (baseTitle, yr) => {
      if (!baseTitle) return;
      if (!seenMovieBaseTitleYears.has(baseTitle)) seenMovieBaseTitleYears.set(baseTitle, []);
      seenMovieBaseTitleYears.get(baseTitle).push(yr);
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

      // Map TVMaze shows by normalized title so TMDB TV results can also carry tvmazeId for instant full-episode lookup
      const tvmazeByTitle = new Map();
      let showsData = [];
      if (showsRes.status === "fulfilled" && showsRes.value && showsRes.value.ok) {
        showsData = (await showsRes.value.json()) || [];
        for (const entry of showsData) {
          const s = entry?.show;
          if (s?.id && s?.name) {
            const k = normalizeSearchTitle(s.name);
            if (k && !tvmazeByTitle.has(k)) tvmazeByTitle.set(k, s);
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
      const actorMovieItems = [];
      if (matchedPersons.length > 0) {
        const creditReqs = matchedPersons.slice(0, 2).map((p) => {
          const cReq = LiveCatalogService._buildTmdbRequest(
            `/person/${p.id}/movie_credits?language=en-US`,
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
          const cleanCast = (batch.cast || []).filter((m) => {
            if (!m || !m.id || !(m.title || m.original_title)) return false;
            const gIds = Array.isArray(m.genre_ids) ? m.genre_ids : [];
            const charLower = String(m.character || "").toLowerCase();
            const isSelfDoc =
              gIds.includes(99) &&
              (charLower.includes("self") ||
                charLower.includes("himself") ||
                charLower.includes("herself") ||
                charLower.includes("archive")) &&
              (Number(m.vote_count) || 0) < 200;
            return !isSelfDoc;
          });
          cleanCast.sort((a, b) => {
            const aVotes = Number(a.vote_count) || 0;
            const bVotes = Number(b.vote_count) || 0;
            const aScore =
              aVotes >= 30
                ? (Number(a.popularity) || 0) + Math.min(300, aVotes / 20)
                : (Number(a.popularity) || 0) * 0.2;
            const bScore =
              bVotes >= 30
                ? (Number(b.popularity) || 0) + Math.min(300, bVotes / 20)
                : (Number(b.popularity) || 0) * 0.2;
            return bScore - aScore || (Number(b.id) || 0) - (Number(a.id) || 0);
          });
          const sliceStart = (safePage - 1) * ACTOR_PAGE_SIZE;
          const sliceEnd = sliceStart + ACTOR_PAGE_SIZE;
          if (cleanCast.length > sliceEnd) actorHasMore = true;
          for (const m of cleanCast.slice(sliceStart, sliceEnd)) {
            actorMovieItems.push({ movie: m, personName: batch.personName });
          }
        }
      }

      const pushActorMovies = () => {
        for (const entry of actorMovieItems) {
          const m = entry.movie;
          const mId = Number(m.id) || 0;
          if (mId > 0 && seenMovieTmdbIds.has(mId)) continue;
          const title = m.title || m.original_title || "Untitled Movie";
          const rel = String(m.release_date || "");
          const year = rel ? parseInt(rel.slice(0, 4), 10) || 2024 : 2024;
          const key = `${normalizeSearchTitle(title)}_${year}`;
          if (seenMovieTitles.has(key)) continue;
          if (mId > 0) seenMovieTmdbIds.add(mId);
          seenMovieTitles.add(key);
          recordMovieTitleYear(normalizeBaseTitle(title), year);
          tmdbMovieCount += 1;
          const posterUrl = m.poster_path ? `https://image.tmdb.org/t/p/w500${m.poster_path}` : "";
          const cleanOverview = stripHtml(m.overview || "");
          results.push({
            id: `movie_${m.id}`,
            tmdbId: m.id,
            mediaType: "movie",
            title,
            year,
            director: `Starring ${entry.personName}`,
            cast: [entry.personName],
            runtimeMins: 125,
            genres: ["Movie"],
            streamingService: "Theaters / Streaming",
            rating: m.vote_average ? Math.round(Number(m.vote_average) * 10) / 10 : 7.8,
            posterUrl,
            overview: cleanOverview ? `Starring ${entry.personName} · ${cleanOverview}` : `Starring ${entry.personName}`,
          });
        }
      };

      if (actorIsPrimary) {
        pushActorMovies();
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
          const matchedTvMaze = tvmazeByTitle.get(normTitle);
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

      if (!actorIsPrimary && actorMovieItems.length > 0) {
        pushActorMovies();
      }

      if (!actorIsPrimary || actorMovieItems.length === 0) {
        for (const entry of showsData) {
          const show = entry?.show;
          if (!show || !show.id) continue;
          const normTitle = normalizeSearchTitle(show.name || "");
          const premiered = String(show.premiered || "");
          const year = premiered ? parseInt(premiered.slice(0, 4), 10) || 2024 : 2024;
          const showKey = `${normTitle}_${year}`;
          const baseShowTitle = normalizeBaseTitle(show.name || "");
          if (!normTitle || seenShowTitles.has(showKey) || hasShowNearYear(baseShowTitle, year)) continue;
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

      if (tmdbMovieCount < 5 && imdbRes.status === "fulfilled" && imdbRes.value && imdbRes.value.ok) {
        const imdbData = await imdbRes.value.json();
        for (const item of imdbData?.d || []) {
          if (!item || !item.id || !item.l) continue;
          const qid = String(item.qid || "").toLowerCase();
          if (qid === "movie" || qid === "feature" || qid === "tvmovie") {
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
   * Fetches personalized TV Show & Movie recommendations for the Tinder-style Swipe deck
   * using TMDB `/3/tv/{id}/recommendations`, `/3/movie/{id}/recommendations`, and trending fallbacks.
   */
  static async fetchRecommendations({ tvSeeds = [], movieSeeds = [], page = 1, fetchImpl = fetch, env = null } = {}) {
    const safePage = Math.max(1, Math.min(10, Number(page) || 1));
    const results = [];
    const seenKeys = new Set();
    const normalizeRecBaseTitle = (s) =>
      String(s || "")
        .toLowerCase()
        .replace(/\([^)]*\)/g, " ")
        .replace(/\[[^\]]*\]/g, " ")
        .replace(/&/g, " and ")
        .replace(/[^a-z0-9]+/g, " ")
        .trim();

    const pushUnique = (item) => {
      if (!item || !item.title || !item.posterUrl) return;
      const baseTitle = normalizeRecBaseTitle(item.title);
      const normKey = `${item.mediaType}_${baseTitle}`;
      const tmdbKey = item.tmdbId > 0 ? `${item.mediaType}_tmdb_${item.tmdbId}` : "";
      if (seenKeys.has(item.id) || seenKeys.has(normKey) || (tmdbKey && seenKeys.has(tmdbKey))) return;
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
        const title = String(s?.title || "").trim();
        let lang = String(s?.lang || "").trim().toLowerCase();
        if (tmdbId <= 0 && title) {
          const searchReq = LiveCatalogService._buildTmdbRequest(
            `/search/tv?query=${encodeURIComponent(title)}&language=en-US&page=1`,
            env
          );
          if (searchReq) {
            try {
              const r = await fetchImpl(searchReq.url, { headers: searchReq.headers });
              if (r.ok) {
                const d = await r.json();
                const hit = d?.results?.[0];
                tmdbId = Number(hit?.id) || 0;
                if (hit?.original_language) lang = String(hit.original_language).toLowerCase();
              }
            } catch (_) {}
          }
        }
        return tmdbId > 0 ? { tmdbId, title: title || "your watched shows", lang } : null;
      });

      const resolveMovieJobs = movieSeeds.slice(0, 6).map(async (m) => {
        let tmdbId = Number(m?.tmdbId) || 0;
        const title = String(m?.title || "").trim();
        let lang = String(m?.lang || "").trim().toLowerCase();
        if (tmdbId <= 0 && title) {
          const searchReq = LiveCatalogService._buildTmdbRequest(
            `/search/movie?query=${encodeURIComponent(title)}&language=en-US&page=1`,
            env
          );
          if (searchReq) {
            try {
              const r = await fetchImpl(searchReq.url, { headers: searchReq.headers });
              if (r.ok) {
                const d = await r.json();
                const hit = d?.results?.[0];
                tmdbId = Number(hit?.id) || 0;
                if (hit?.original_language) lang = String(hit.original_language).toLowerCase();
              }
            } catch (_) {}
          }
        }
        return tmdbId > 0 ? { tmdbId, title: title || "your watched movies", lang } : null;
      });

      const [resolvedTvRaw, resolvedMovieRaw] = await Promise.all([
        Promise.all(resolveTvJobs),
        Promise.all(resolveMovieJobs),
      ]);
      const resolvedTvSeeds = resolvedTvRaw.filter(Boolean);
      const resolvedMovieSeeds = resolvedMovieRaw.filter(Boolean);

      const seedFetchJobs = [];
      for (const seed of resolvedTvSeeds) {
        const path =
          safePage === 1
            ? `/tv/${seed.tmdbId}?append_to_response=recommendations,similar&language=en-US`
            : `/tv/${seed.tmdbId}/recommendations?language=en-US&page=${safePage}`;
        const req = LiveCatalogService._buildTmdbRequest(path, env);
        if (req) {
          seedFetchJobs.push(
            fetchImpl(req.url, { headers: req.headers })
              .then((r) => (r.ok ? r.json() : null))
              .then((data) => {
                const lang = String(data?.original_language || seed.lang || "").toLowerCase();
                recordSeedLang(lang);
                const recs = Array.isArray(data?.recommendations?.results)
                  ? data.recommendations.results
                  : Array.isArray(data?.results)
                    ? data.results
                    : [];
                const sim = Array.isArray(data?.similar?.results) ? data.similar.results : [];
                return { type: "tv", seedTitle: seed.title, items: [...recs, ...sim] };
              })
              .catch(() => null)
          );
        }
      }

      for (const seed of resolvedMovieSeeds) {
        const path =
          safePage === 1
            ? `/movie/${seed.tmdbId}?append_to_response=recommendations,similar&language=en-US`
            : `/movie/${seed.tmdbId}/recommendations?language=en-US&page=${safePage}`;
        const req = LiveCatalogService._buildTmdbRequest(path, env);
        if (req) {
          seedFetchJobs.push(
            fetchImpl(req.url, { headers: req.headers })
              .then((r) => (r.ok ? r.json() : null))
              .then((data) => {
                const lang = String(data?.original_language || seed.lang || "").toLowerCase();
                recordSeedLang(lang);
                const recs = Array.isArray(data?.recommendations?.results)
                  ? data.recommendations.results
                  : Array.isArray(data?.results)
                    ? data.results
                    : [];
                const sim = Array.isArray(data?.similar?.results) ? data.similar.results : [];
                return { type: "movie", seedTitle: seed.title, items: [...recs, ...sim] };
              })
              .catch(() => null)
          );
        }
      }

      const seedBatches = (await Promise.all(seedFetchJobs)).filter(Boolean);

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

  /**
   * Fetches full TV show details + complete episode list + cast from TMDB (`/3/tv/:id`) or TVMaze (`/shows/:id?embed[]=episodes&embed[]=cast`).
   */
  static async fetchFullShowFromTvMaze(tvmazeId, { title = "", fetchImpl = fetch, env = null } = {}) {
    let rawStr = String(tvmazeId || "").trim();
    const cleanTitle = String(title || "").trim();
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

    // Resolve non-TMDB / non-TVMaze synthetic IDs (e.g. csv_show_*) via title search on TMDB first
    if (!rawStr.startsWith("tmdb_tv_") && !Number(rawStr.replace(/^tvmaze_/, "")) && cleanTitle) {
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
    for (const s of shows) {
      const eps = Array.isArray(s.episodes) ? s.episodes : [];
      const rawStatus = String(s.userStatus || "").toLowerCase();
      const defaultEpRuntime = Number(s.runtimeMins) || 45;
      const watchedSet = new Set(
        (s.watchedEpisodes || [])
          .map((c) => String(c || "").trim().toUpperCase())
          .filter(Boolean)
      );
      const presentSeasons = new Set(eps.map((ep) => String(Number(ep.season) || 1)));
      const compMap = s.completedSeasons && typeof s.completedSeasons === "object" ? s.completedSeasons : {};
      let compactedCount = 0;
      for (const [sk, cnt] of Object.entries(compMap)) {
        const sNum = Number(sk) || 0;
        if (sNum > 0 && !presentSeasons.has(String(sNum))) {
          compactedCount += Math.max(0, Number(cnt) || 0);
        }
      }
      if (rawStatus === "completed") {
        const count = Math.max(
          Number(s.totalEpisodes) || 0,
          eps.length + compactedCount,
          compactedCount + watchedSet.size,
          1
        );
        totalEpisodesWatched += count;
        showMinutesWatched += count * defaultEpRuntime;
      } else {
        totalEpisodesWatched += compactedCount;
        showMinutesWatched += compactedCount * defaultEpRuntime;
        if (eps.length > 0) {
          for (const ep of eps) {
            const sk = String(Number(ep.season) || 1);
            if (watchedSet.has(String(ep.code || "").trim().toUpperCase()) || compMap[sk]) {
              totalEpisodesWatched += 1;
              showMinutesWatched += Number(ep.runtimeMins) || defaultEpRuntime;
            }
          }
          // Count any partial watched codes from non-present, non-compacted seasons
          for (const code of watchedSet) {
            const m = /^S0*([0-9]+)E[0-9]+$/i.exec(code);
            const sStr = m ? String(Number(m[1]) || 1) : "";
            if (!sStr || (!presentSeasons.has(sStr) && !compMap[sStr])) {
              totalEpisodesWatched += 1;
              showMinutesWatched += defaultEpRuntime;
            }
          }
        } else {
          totalEpisodesWatched += watchedSet.size;
          showMinutesWatched += watchedSet.size * defaultEpRuntime;
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

