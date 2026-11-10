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
  static async searchMulti(query, { fetchImpl = fetch, env = null } = {}) {
    const q = String(query || "").trim();
    if (!q) return { results: [] };

    const results = [];
    const seenShowTitles = new Set();
    const seenMovieTitles = new Set();
    try {
      const tmdbReq = LiveCatalogService._buildTmdbRequest(
        `/search/multi?query=${encodeURIComponent(q)}&include_adult=false&language=en-US&page=1`,
        env
      );
      const [tmdbRes, showsRes, imdbRes, moviesRes] = await Promise.allSettled([
        tmdbReq ? fetchImpl(tmdbReq.url, { headers: tmdbReq.headers }) : Promise.resolve(null),
        fetchImpl(`https://api.tvmaze.com/search/shows?q=${encodeURIComponent(q)}`),
        fetchImpl(`https://v3.sg.media-imdb.com/suggestion/x/${encodeURIComponent(q)}.json`),
        fetchImpl(`https://itunes.apple.com/search?term=${encodeURIComponent(q)}&country=US&entity=movie&limit=10`),
      ]);

      // Map TVMaze shows by normalized title so TMDB TV results can also carry tvmazeId for instant full-episode lookup
      const tvmazeByTitle = new Map();
      let showsData = [];
      if (showsRes.status === "fulfilled" && showsRes.value && showsRes.value.ok) {
        showsData = (await showsRes.value.json()) || [];
        for (const entry of showsData) {
          const s = entry?.show;
          if (s?.id && s?.name) {
            const k = String(s.name).trim().toLowerCase();
            if (!tvmazeByTitle.has(k)) tvmazeByTitle.set(k, s);
          }
        }
      }

      if (tmdbRes.status === "fulfilled" && tmdbRes.value && tmdbRes.value.ok) {
        const tmdbData = await tmdbRes.value.json();
        for (const item of (tmdbData?.results || []).slice(0, 18)) {
          if (!item || !item.id) continue;
          const mType = String(item.media_type || "").toLowerCase();
          if (mType === "tv") {
            const title = item.name || item.original_name || "Untitled Show";
            const normTitle = String(title).trim().toLowerCase();
            if (seenShowTitles.has(normTitle)) continue;
            seenShowTitles.add(normTitle);
            const firstAir = String(item.first_air_date || "");
            const year = firstAir ? parseInt(firstAir.slice(0, 4), 10) || 2024 : 2024;
            const matchedTvMaze = tvmazeByTitle.get(normTitle);
            const posterUrl = item.poster_path
              ? `https://image.tmdb.org/t/p/w500${item.poster_path}`
              : (matchedTvMaze?.image?.medium || "").replace(/^http:\/\//i, "https://");
            results.push({
              id: matchedTvMaze ? `tvmaze_${matchedTvMaze.id}` : `tmdb_tv_${item.id}`,
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
            const title = item.title || item.original_title || "Untitled Movie";
            const rel = String(item.release_date || "");
            const year = rel ? parseInt(rel.slice(0, 4), 10) || 2024 : 2024;
            const key = `${String(title).toLowerCase()}_${year}`;
            if (seenMovieTitles.has(key)) continue;
            seenMovieTitles.add(key);
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
      }

      for (const entry of showsData.slice(0, 12)) {
        const show = entry?.show;
        if (!show || !show.id) continue;
        const normTitle = String(show.name || "").trim().toLowerCase();
        if (seenShowTitles.has(normTitle)) continue;
        seenShowTitles.add(normTitle);
        const premiered = String(show.premiered || "");
        const year = premiered ? parseInt(premiered.slice(0, 4), 10) || 2024 : 2024;
        const network = show.network?.name || show.webChannel?.name || "Streaming";
        const posterUrl = show.image?.medium || show.image?.original || "";
        results.push({
          id: `tvmaze_${show.id}`,
          tvmazeId: show.id,
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

      if (imdbRes.status === "fulfilled" && imdbRes.value.ok) {
        const imdbData = await imdbRes.value.json();
        for (const item of imdbData?.d || []) {
          if (!item || !item.id || !item.l) continue;
          const qid = String(item.qid || "").toLowerCase();
          if (qid === "movie" || qid === "feature" || qid === "tvmovie") {
            const key = `${item.l.toLowerCase()}_${item.y || 2024}`;
            if (seenMovieTitles.has(key)) continue;
            seenMovieTitles.add(key);
            const castArr = item.s ? String(item.s).split(",").map((c) => c.trim()).filter(Boolean) : [];
            results.push({
              id: `movie_${item.id}`,
              tmdbId: parseInt(String(item.id).replace(/\D/g, ""), 10) || 0,
              mediaType: "movie",
              title: item.l,
              year: Number(item.y) || 2024,
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

      if (moviesRes.status === "fulfilled" && moviesRes.value.ok) {
        const moviesData = await moviesRes.value.json();
        for (const item of (moviesData?.results || []).slice(0, 10)) {
          if (!item || !item.trackId) continue;
          const rel = String(item.releaseDate || "");
          const year = rel ? parseInt(rel.slice(0, 4), 10) || 2024 : 2024;
          const key = `${String(item.trackName || "").toLowerCase()}_${year}`;
          if (seenMovieTitles.has(key)) continue;
          seenMovieTitles.add(key);
          const runtimeMins = item.trackTimeMillis ? Math.max(45, Math.round(item.trackTimeMillis / 60000)) : 120;
          const artwork = String(item.artworkUrl100 || "").replace("100x100bb.jpg", "600x900bb.jpg");
          results.push({
            id: `movie_${item.trackId}`,
            tmdbId: item.trackId,
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

    return { results };
  }

  /**
   * Discovers US TV Shows & Movies streaming on the specified US platform(s) via TMDB `/3/discover/tv` and `/3/discover/movie`
   * (`watch_region=US&with_watch_monetization_types=flatrate&with_watch_providers=...`).
   */
  static async discoverByUsProviders(platformsCsv, { fetchImpl = fetch, env = null } = {}) {
    const requestedKeys = String(platformsCsv || "")
      .split(",")
      .map((s) => s.trim())
      .filter(Boolean);
    if (requestedKeys.length === 0) {
      return LiveCatalogService.searchMulti("Game of Thrones", { fetchImpl, env });
    }

    const matchedPlatforms = LiveCatalogService.US_STREAMING_PLATFORMS.filter((p) =>
      requestedKeys.some((rk) => rk.toLowerCase() === p.key.toLowerCase())
    );
    const providerIdsPipe = matchedPlatforms.flatMap((p) => p.providerIds).join("|");
    const platformLabel = matchedPlatforms.map((p) => p.key).join(" / ") || requestedKeys.join(" / ");
    const results = [];

    if (providerIdsPipe) {
      const tvReq = LiveCatalogService._buildTmdbRequest(
        `/discover/tv?watch_region=US&with_watch_monetization_types=flatrate&with_watch_providers=${encodeURIComponent(providerIdsPipe)}&sort_by=popularity.desc&language=en-US&page=1`,
        env
      );
      const movReq = LiveCatalogService._buildTmdbRequest(
        `/discover/movie?watch_region=US&with_watch_monetization_types=flatrate&with_watch_providers=${encodeURIComponent(providerIdsPipe)}&sort_by=popularity.desc&language=en-US&page=1`,
        env
      );

      try {
        const [tvRes, movRes] = await Promise.allSettled([
          tvReq ? fetchImpl(tvReq.url, { headers: tvReq.headers }) : Promise.resolve(null),
          movReq ? fetchImpl(movReq.url, { headers: movReq.headers }) : Promise.resolve(null),
        ]);

        if (tvRes.status === "fulfilled" && tvRes.value && tvRes.value.ok) {
          const tvData = await tvRes.value.json();
          for (const item of (tvData?.results || []).slice(0, 12)) {
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
          for (const item of (movData?.results || []).slice(0, 12)) {
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

    return { results };
  }

  /**
   * Fetches personalized TV Show & Movie recommendations for the Tinder-style Swipe deck
   * using TMDB `/3/tv/{id}/recommendations`, `/3/movie/{id}/recommendations`, and trending fallbacks.
   */
  static async fetchRecommendations({ tvSeeds = [], movieSeeds = [], page = 1, fetchImpl = fetch, env = null } = {}) {
    const safePage = Math.max(1, Math.min(10, Number(page) || 1));
    const results = [];
    const seenKeys = new Set();

    const pushUnique = (item) => {
      if (!item || !item.title || !item.posterUrl) return;
      const normKey = `${item.mediaType}_${String(item.title).trim().toLowerCase()}`;
      if (seenKeys.has(item.id) || seenKeys.has(normKey)) return;
      seenKeys.add(item.id);
      seenKeys.add(normKey);
      results.push(item);
    };

    try {
      // Resolve missing tmdbId for seeds that only have a title
      const resolvedTvSeeds = [];
      for (const s of tvSeeds.slice(0, 5)) {
        let tmdbId = Number(s?.tmdbId) || 0;
        const title = String(s?.title || "").trim();
        if (!tmdbId && title) {
          const searchReq = LiveCatalogService._buildTmdbRequest(
            `/search/tv?query=${encodeURIComponent(title)}&language=en-US&page=1`,
            env
          );
          if (searchReq) {
            try {
              const r = await fetchImpl(searchReq.url, { headers: searchReq.headers });
              if (r.ok) {
                const d = await r.json();
                tmdbId = Number(d?.results?.[0]?.id) || 0;
              }
            } catch (_) {}
          }
        }
        if (tmdbId > 0) {
          resolvedTvSeeds.push({ tmdbId, title: title || "your watched shows" });
        }
      }

      const resolvedMovieSeeds = [];
      for (const m of movieSeeds.slice(0, 5)) {
        let tmdbId = Number(m?.tmdbId) || 0;
        const title = String(m?.title || "").trim();
        if (!tmdbId && title) {
          const searchReq = LiveCatalogService._buildTmdbRequest(
            `/search/movie?query=${encodeURIComponent(title)}&language=en-US&page=1`,
            env
          );
          if (searchReq) {
            try {
              const r = await fetchImpl(searchReq.url, { headers: searchReq.headers });
              if (r.ok) {
                const d = await r.json();
                tmdbId = Number(d?.results?.[0]?.id) || 0;
              }
            } catch (_) {}
          }
        }
        if (tmdbId > 0) {
          resolvedMovieSeeds.push({ tmdbId, title: title || "your watched movies" });
        }
      }

      const fetchJobs = [];
      for (const seed of resolvedTvSeeds) {
        const req = LiveCatalogService._buildTmdbRequest(
          `/tv/${seed.tmdbId}/recommendations?language=en-US&page=${safePage}`,
          env
        );
        if (req) {
          fetchJobs.push(
            fetchImpl(req.url, { headers: req.headers })
              .then((r) => (r.ok ? r.json() : null))
              .then((data) => ({ type: "tv", seedTitle: seed.title, items: data?.results || [] }))
              .catch(() => null)
          );
        }
      }

      for (const seed of resolvedMovieSeeds) {
        const req = LiveCatalogService._buildTmdbRequest(
          `/movie/${seed.tmdbId}/recommendations?language=en-US&page=${safePage}`,
          env
        );
        if (req) {
          fetchJobs.push(
            fetchImpl(req.url, { headers: req.headers })
              .then((r) => (r.ok ? r.json() : null))
              .then((data) => ({ type: "movie", seedTitle: seed.title, items: data?.results || [] }))
              .catch(() => null)
          );
        }
      }

      // Also include trending TV & Movies as discovery supplement
      const trendTvReq = LiveCatalogService._buildTmdbRequest(`/trending/tv/week?language=en-US&page=${safePage}`, env);
      const trendMovReq = LiveCatalogService._buildTmdbRequest(`/trending/movie/week?language=en-US&page=${safePage}`, env);
      if (trendTvReq) {
        fetchJobs.push(
          fetchImpl(trendTvReq.url, { headers: trendTvReq.headers })
            .then((r) => (r.ok ? r.json() : null))
            .then((data) => ({ type: "tv", seedTitle: "", items: data?.results || [] }))
            .catch(() => null)
        );
      }
      if (trendMovReq) {
        fetchJobs.push(
          fetchImpl(trendMovReq.url, { headers: trendMovReq.headers })
            .then((r) => (r.ok ? r.json() : null))
            .then((data) => ({ type: "movie", seedTitle: "", items: data?.results || [] }))
            .catch(() => null)
        );
      }

      const batches = (await Promise.all(fetchJobs)).filter(Boolean);
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
              becauseOf: batch.seedTitle ? `Because you watched ${batch.seedTitle}` : "Popular & Acclaimed Series",
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
              becauseOf: batch.seedTitle ? `Because you watched ${batch.seedTitle}` : "Popular & Acclaimed Movie",
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
  static async fetchFullShowFromTvMaze(tvmazeId, { fetchImpl = fetch, env = null } = {}) {
    const rawStr = String(tvmazeId || "").trim();
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
            const seasonNums = (show.seasons || [])
              .map((s) => Number(s.season_number))
              .filter((n) => n >= 1)
              .slice(-12);
            const seasonReqs = seasonNums.map((sNum) => {
              const sReq = LiveCatalogService._buildTmdbRequest(`/tv/${tmdbId}/season/${sNum}?language=en-US`, env);
              return sReq ? fetchImpl(sReq.url, { headers: sReq.headers }) : Promise.resolve(null);
            });
            const seasonResps = await Promise.allSettled(seasonReqs);
            const episodes = [];
            for (const sRes of seasonResps) {
              if (sRes.status === "fulfilled" && sRes.value && sRes.value.ok) {
                const sData = await sRes.value.json();
                const sNum = Number(sData.season_number) || 1;
                for (const ep of sData.episodes || []) {
                  const eNum = Number(ep.episode_number) || 1;
                  episodes.push({
                    code: formatEpisodeCode(sNum, eNum),
                    season: sNum,
                    number: eNum,
                    title: ep.name || `Episode ${eNum}`,
                    airDate: ep.air_date || "",
                    runtimeMins: Number(ep.runtime) || defaultRuntime,
                    imageUrl: ep.still_path ? `https://image.tmdb.org/t/p/w300${ep.still_path}` : "",
                    overview: stripHtml(ep.overview),
                  });
                }
              }
            }
            const nextAir = show.next_episode_to_air;
            if (nextAir && typeof nextAir === "object") {
              const nextS = Number(nextAir.season_number) || 0;
              const nextE = Number(nextAir.episode_number) || 0;
              const nextDate = String(nextAir.air_date || "").trim();
              if (nextS >= 1 && nextE >= 1 && nextDate) {
                const nextCode = formatEpisodeCode(nextS, nextE);
                if (!episodes.some((e) => e.code === nextCode)) {
                  episodes.push({
                    code: nextCode,
                    season: nextS,
                    number: nextE,
                    title: nextAir.name || `Episode ${nextE}`,
                    airDate: nextDate,
                    runtimeMins: Number(nextAir.runtime) || defaultRuntime,
                    imageUrl: nextAir.still_path ? `https://image.tmdb.org/t/p/w300${nextAir.still_path}` : "",
                    overview: stripHtml(nextAir.overview),
                  });
                }
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
    if (!cleanId) return null;

    try {
      const resp = await fetchImpl(`https://api.tvmaze.com/shows/${cleanId}?embed[]=episodes&embed[]=cast`);
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
      const episodes = rawEps.map((ep) => ({
        code: formatEpisodeCode(ep.season, ep.number),
        season: Number(ep.season) || 1,
        number: Number(ep.number) || 1,
        title: ep.name || `Episode ${ep.number || 1}`,
        airDate: ep.airdate || "",
        runtimeMins: Number(ep.runtime || show.averageRuntime || show.runtime) || 45,
        imageUrl: (ep.image?.medium || ep.image?.original || "").replace(/^http:\/\//i, "https://"),
        overview: stripHtml(ep.summary),
      }));

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
      const watchedSet = new Set(s.watchedEpisodes || []);
      totalEpisodesWatched += watchedSet.size;
      const defaultEpRuntime = Number(s.runtimeMins) || 45;
      if (Array.isArray(s.episodes) && s.episodes.length > 0) {
        for (const ep of s.episodes) {
          if (watchedSet.has(ep.code)) {
            showMinutesWatched += Number(ep.runtimeMins) || defaultEpRuntime;
          }
        }
      } else {
        showMinutesWatched += watchedSet.size * defaultEpRuntime;
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

