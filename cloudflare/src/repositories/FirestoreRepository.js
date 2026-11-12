/**
 * Multi-User Cloud Firestore Repository for TV Time: Show & Movie Tracker (`tvtime_state/user_state_{userId}`).
 *
 * Shares the same free Firebase Cloud Firestore instance as PackTrack and PlacesTracker
 * under a dedicated `tvtime_state` collection with ultra-compact storage (`< 60 KB` per user).
 * Anonymous `"default"` profile never reads or writes to Cloud Firestore.
 */

export const STARTER_SHOWS = [];

export const STARTER_MOVIES = [];

export const STARTER_ACTORS = [];

export class FirestoreRepository {
  constructor(env) {
    this.projectId = String(env?.FIREBASE_PROJECT_ID || "").trim();
  }

  static sanitizeUserId(raw) {
    const trimmed = String(raw || "").trim().toLowerCase();
    if (!trimmed) return "default";
    const withoutDomain = trimmed.split("@")[0].replace(/_gmail\.com$/, "");
    const cleaned = withoutDomain.replace(/[^a-z0-9_.-]/g, "_").slice(0, 64);
    return cleaned || "default";
  }

  static displayUserLabel(userId) {
    return FirestoreRepository.sanitizeUserId(userId);
  }

  static isSyntheticEpisodeList(eps) {
    if (!Array.isArray(eps) || eps.length === 0) return true;
    if (eps.length === 1) {
      const e0 = eps[0];
      const t0 = String(e0?.title || "").trim();
      return !e0?.airDate && (!t0 || /^(Pilot|Episode 1)$/i.test(t0));
    }
    return eps.every(
      (e) =>
        (Number(e?.season) || 1) === 1 &&
        !e?.airDate &&
        /^Episode \d+$/i.test(String(e?.title || "").trim())
    );
  }

  static parseSeasonFromCode(code) {
    const m = /^S0*([0-9]+)E[0-9]+$/i.exec(String(code || "").trim());
    return m ? Number(m[1]) || 1 : 0;
  }

  static compactEpisode(ep) {
    if (!ep || typeof ep !== "object") return null;
    const out = {
      code: String(ep.code || "S01E01").trim().toUpperCase(),
      season: Number(ep.season) || 1,
      number: Number(ep.number) || 1,
    };
    if (ep.title) out.title = String(ep.title);
    if (ep.airDate) out.airDate = String(ep.airDate);
    if (ep.runtimeMins) out.runtimeMins = Number(ep.runtimeMins);
    return out;
  }

  static cleanMovieTmdbId(rawId, rawTmdbId) {
    const idStr = String(rawId || "").trim();
    const num = Number(rawTmdbId) || 0;
    if (num <= 0) return 0;
    if (idStr.startsWith("movie_tt")) {
      const ttDigits = Number(idStr.replace(/\D/g, "")) || 0;
      if (num === ttDigits) return 0;
    }
    return num;
  }

  static compactMovieForStorage(m) {
    if (!m || typeof m !== "object") return null;
    const id = String(m.id || "").trim();
    const tmdbId = FirestoreRepository.cleanMovieTmdbId(id, m.tmdbId);
    const out = {
      id: id || (tmdbId > 0 ? `movie_${tmdbId}` : "movie_0"),
      tmdbId,
      title: String(m.title || "Untitled Movie"),
      year: Number(m.year) || 2024,
      runtimeMins: Number(m.runtimeMins) || 120,
      rating: Math.round((Number(m.rating) || 8.0) * 10) / 10,
      posterUrl: String(m.posterUrl || ""),
      watched: Boolean(m.watched),
    };
    const stream = String(m.streamingService || "").trim();
    if (stream && stream !== "Streaming") {
      out.streamingService = stream;
    }
    const dir = String(m.director || "").trim();
    if (dir && dir !== stream && dir !== "Streaming") {
      out.director = dir;
    }
    if (Array.isArray(m.cast) && m.cast.length > 0) {
      const topCast = m.cast
        .map((c) => String(c || "").trim())
        .filter(Boolean)
        .slice(0, 3);
      if (topCast.length > 0) out.cast = topCast;
    }
    if (out.watched && m.watchedAt) {
      out.watchedAt = String(m.watchedAt);
    }
    return out;
  }

  /**
   * Compacts a TV show for Cloud Firestore & wire transfer:
   * - Strips unused legacy fields (`userRating`, `reaction`, `rewatchCount`, `isFavorite`, `overview`, `genres`, `cast`).
   * - Completed shows: stores `totalEpisodes` + `userStatus: "completed"` with `episodes: []` and `watchedEpisodes: []` (~0.35 KB).
   * - In-progress / Stopped / Watchlist shows: compacts finished seasons into `completedSeasons: { [season]: count }`,
   *   retains partial watched codes in `watchedEpisodes`, and keeps only the 1 active next-up season (+ 1 upcoming future episode)
   *   in `episodes` (~1.5-2.2 KB even for a 50-season x 25-episode = 1,250-episode series).
   */
  static compactShowForStorage(s) {
    if (!s || typeof s !== "object") return null;
    const eps = (Array.isArray(s.episodes) ? s.episodes : [])
      .map((e) => FirestoreRepository.compactEpisode(e))
      .filter(Boolean)
      .sort((a, b) => a.season - b.season || a.number - b.number);

    const watchedSet = new Set(
      (Array.isArray(s.watchedEpisodes) ? s.watchedEpisodes : [])
        .map((c) => String(c || "").trim().toUpperCase())
        .filter(Boolean)
    );

    const rawComp =
      s.completedSeasons && typeof s.completedSeasons === "object" ? s.completedSeasons : {};
    const presentSeasons = new Set(eps.map((e) => String(e.season)));
    const completedSeasons = {};
    let missingCompEps = 0;

    for (const [sk, cnt] of Object.entries(rawComp)) {
      const sNum = Number(sk) || 0;
      const cNum = Math.max(0, Number(cnt) || 0);
      if (sNum <= 0 || cNum <= 0) continue;
      if (presentSeasons.has(String(sNum))) {
        for (const ep of eps) {
          if (ep.season === sNum) watchedSet.add(ep.code);
        }
      } else {
        completedSeasons[String(sNum)] = cNum;
        missingCompEps += cNum;
      }
    }

    const watchedOutsideComp = Array.from(watchedSet)
      .filter((code) => {
        const sNum = FirestoreRepository.parseSeasonFromCode(code);
        return !sNum || !completedSeasons[String(sNum)];
      })
      .sort();

    const nonPresentWatchedCount = watchedOutsideComp.filter((code) => {
      const sNum = FirestoreRepository.parseSeasonFromCode(code);
      return !sNum || !presentSeasons.has(String(sNum));
    }).length;

    const computedTotal = Math.max(
      Number(s.totalEpisodes) || 0,
      eps.length + missingCompEps,
      missingCompEps + watchedOutsideComp.length
    );

    let statusLower = String(s.userStatus || "interested").toLowerCase();
    if (statusLower === "watching") statusLower = "in_progress";

    const base = {
      id: String(s.id || ""),
      tvmazeId: Number(s.tvmazeId) || 0,
      tmdbId: Number(s.tmdbId) || 0,
      title: String(s.title || "Untitled Show"),
      year: Number(s.year) || 2024,
      network: String(s.network || "Streaming"),
      status: String(s.status || "Running"),
      runtimeMins: Number(s.runtimeMins) || 45,
      rating: Math.round((Number(s.rating) || 8.0) * 10) / 10,
      posterUrl: String(s.posterUrl || ""),
      userStatus: statusLower,
    };
    if (s.lastWatchedAt) {
      base.lastWatchedAt = String(s.lastWatchedAt);
    }

    if (statusLower === "completed") {
      return {
        ...base,
        userStatus: "completed",
        totalEpisodes: Math.max(1, computedTotal),
        watchedEpisodes: [],
        episodes: [],
      };
    }

    if (eps.length > 0 && !FirestoreRepository.isSyntheticEpisodeList(eps)) {
      const bySeason = new Map();
      for (const ep of eps) {
        if (!bySeason.has(ep.season)) bySeason.set(ep.season, []);
        bySeason.get(ep.season).push(ep);
      }

      if (statusLower !== "interested") {
        for (const [sNum, sEps] of bySeason.entries()) {
          if (sEps.length > 0 && sEps.every((ep) => watchedSet.has(ep.code))) {
            completedSeasons[String(sNum)] = sEps.length;
          }
        }
      }

      // Active-Season Window: keep ONLY the first incomplete season (+ earliest upcoming future episode)
      let activeSeasonNum = null;
      for (const sNum of bySeason.keys()) {
        if (!completedSeasons[String(sNum)]) {
          activeSeasonNum = sNum;
          break;
        }
      }

      const todayIso = new Date().toISOString().slice(0, 10);
      let nextUpcomingEp = null;
      for (const ep of eps) {
        if (ep.airDate && /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(ep.airDate) && ep.airDate >= todayIso) {
          if (!nextUpcomingEp || ep.airDate < nextUpcomingEp.airDate) {
            nextUpcomingEp = ep;
          }
        }
      }

      const keptEps = [];
      if (activeSeasonNum !== null) {
        for (const ep of bySeason.get(activeSeasonNum) || []) {
          keptEps.push(ep);
        }
      }
      if (
        nextUpcomingEp &&
        nextUpcomingEp.season !== activeSeasonNum &&
        !keptEps.some((e) => e.code === nextUpcomingEp.code)
      ) {
        keptEps.push(nextUpcomingEp);
      }

      const validPresentCodes = new Set(eps.map((e) => e.code));
      const keptWatched = Array.from(watchedSet)
        .filter((code) => {
          const sNum = FirestoreRepository.parseSeasonFromCode(code);
          if (sNum && completedSeasons[String(sNum)]) return false;
          if (sNum && presentSeasons.has(String(sNum))) {
            return validPresentCodes.has(code);
          }
          return true;
        })
        .sort();

      const compSeasonSum = Object.values(completedSeasons).reduce(
        (acc, n) => acc + (Number(n) || 0),
        0
      );
      const finalTotal = Math.max(computedTotal, eps.length + missingCompEps + nonPresentWatchedCount);

      if (
        activeSeasonNum === null &&
        compSeasonSum >= finalTotal &&
        finalTotal > 0 &&
        statusLower !== "stopped" &&
        statusLower !== "interested"
      ) {
        return {
          ...base,
          userStatus: "completed",
          totalEpisodes: finalTotal,
          watchedEpisodes: [],
          episodes: [],
        };
      }

      const out = {
        ...base,
        totalEpisodes: Math.max(1, finalTotal),
        watchedEpisodes: keptWatched,
        episodes: keptEps,
      };
      if (Object.keys(completedSeasons).length > 0) {
        out.completedSeasons = completedSeasons;
      }
      return out;
    }

    const out = {
      ...base,
      totalEpisodes: Math.max(computedTotal, eps.length),
      watchedEpisodes: watchedOutsideComp,
      episodes: eps,
    };
    if (Object.keys(completedSeasons).length > 0) {
      out.completedSeasons = completedSeasons;
    }
    return out;
  }

  static compactDecidedIds(decidedIds, legacyActors) {
    const canon = (raw) => {
      const s = String(raw || "").trim();
      if (!s || s.startsWith("title_")) return "";
      if (s.startsWith("tmdb_movie_")) return "movie_" + s.slice(11);
      if (s.startsWith("tmdb_show_")) return "tmdb_tv_" + s.slice(10);
      return s;
    };
    const rawList = [];
    if (Array.isArray(decidedIds)) {
      for (const id of decidedIds) {
        const c = canon(id);
        if (c) rawList.push(c);
      }
    }
    if (Array.isArray(legacyActors)) {
      for (const a of legacyActors) {
        if (a && a.role === "decided_rec" && a.id) {
          const c = canon(a.id);
          if (c) rawList.push(c);
        }
      }
    }
    return Array.from(new Set(rawList)).slice(-2500);
  }

  static normalizeTitleKey(raw) {
    return String(raw || "")
      .toLowerCase()
      .split("'")
      .join("")
      .split("\u2019")
      .join("")
      .replace(/&/g, " and ")
      .replace(/[^a-z0-9]+/g, " ")
      .trim();
  }

  static deduplicateShows(shows) {
    if (!Array.isArray(shows)) return [];
    const byKey = new Map();
    const titleToKeys = new Map();
    const statusRank = { completed: 4, in_progress: 3, watching: 3, stopped: 2, interested: 1 };
    const cleanCompMap = (raw) => {
      const out = {};
      if (raw && typeof raw === "object") {
        for (const [k, v] of Object.entries(raw)) {
          const sNum = Number(k) || 0;
          const cNum = Math.max(0, Number(v) || 0);
          if (sNum > 0 && cNum > 0) out[String(sNum)] = cNum;
        }
      }
      return out;
    };

    for (const s of shows) {
      if (!s || typeof s !== "object") continue;
      const titleKey = FirestoreRepository.normalizeTitleKey(s.title);
      const tmdbId = Number(s.tmdbId) || 0;
      const tvmazeId = Number(s.tvmazeId) || 0;
      const idKey = String(s.id || "").trim();

      let matchedKey = null;
      if (tmdbId > 0 && byKey.has(`tmdb:${tmdbId}`)) {
        matchedKey = `tmdb:${tmdbId}`;
      } else if (tvmazeId > 0 && byKey.has(`tvmaze:${tvmazeId}`)) {
        matchedKey = `tvmaze:${tvmazeId}`;
      } else if (titleKey && titleToKeys.has(titleKey)) {
        const sYear = Number(s.year) || 0;
        for (const candKey of titleToKeys.get(titleKey)) {
          const cand = byKey.get(candKey);
          if (!cand) continue;
          const cTmdb = Number(cand.tmdbId) || 0;
          const cMaze = Number(cand.tvmazeId) || 0;
          const cYear = Number(cand.year) || 0;
          if (tmdbId > 0 && cTmdb > 0 && tmdbId !== cTmdb) continue;
          if (tvmazeId > 0 && cMaze > 0 && tvmazeId !== cMaze) continue;
          if (sYear > 1900 && cYear > 1900 && Math.abs(sYear - cYear) > 1) continue;
          matchedKey = candKey;
          break;
        }
      }

      const incomingEps = (Array.isArray(s.episodes) ? s.episodes : [])
        .map((e) => FirestoreRepository.compactEpisode(e))
        .filter(Boolean);
      const incomingComp = cleanCompMap(s.completedSeasons);

      if (!matchedKey) {
        const primaryKey =
          tmdbId > 0
            ? `tmdb:${tmdbId}`
            : tvmazeId > 0
              ? `tvmaze:${tvmazeId}`
              : titleKey
                ? `title:${titleKey}:${s.year || ""}`
                : idKey || `idx:${byKey.size}`;

        let watched = Array.from(
          new Set(
            (Array.isArray(s.watchedEpisodes) ? s.watchedEpisodes : [])
              .map((c) => String(c || "").trim().toUpperCase())
              .filter(Boolean)
          )
        ).sort();
        for (const ep of incomingEps) {
          const sk = String(Number(ep.season) || 1);
          if (incomingComp[sk]) {
            watched.push(ep.code);
          }
        }
        watched = Array.from(new Set(watched)).sort();
        const presentS = new Set(incomingEps.map((e) => String(Number(e.season) || 1)));
        for (const sk of presentS) delete incomingComp[sk];
        if (s.userStatus === "completed" && incomingEps.length > 0 && watched.length < incomingEps.length) {
          watched = incomingEps.map((e) => e.code).sort();
        }
        const compSum = Object.values(incomingComp).reduce((a, b) => a + b, 0);
        const totalEpisodes = Math.max(
          Number(s.totalEpisodes) || 0,
          incomingEps.length + compSum,
          compSum + watched.length
        );
        const entry = {
          ...s,
          tmdbId,
          tvmazeId,
          totalEpisodes,
          completedSeasons: incomingComp,
          watchedEpisodes: watched,
          episodes: incomingEps,
        };
        byKey.set(primaryKey, entry);
        if (titleKey) {
          if (!titleToKeys.has(titleKey)) titleToKeys.set(titleKey, []);
          titleToKeys.get(titleKey).push(primaryKey);
        }
      } else {
        const existing = byKey.get(matchedKey);
        const existingEps = Array.isArray(existing.episodes) ? existing.episodes : [];
        const existingSynth = FirestoreRepository.isSyntheticEpisodeList(existingEps);
        const newSynth = FirestoreRepository.isSyntheticEpisodeList(incomingEps);
        let bestEps = existingEps;
        if (existingSynth && !newSynth && incomingEps.length > 0) {
          bestEps = incomingEps;
        } else if (!existingSynth && newSynth && existingEps.length > 0) {
          bestEps = existingEps;
        } else if (incomingEps.length > existingEps.length) {
          bestEps = incomingEps;
        }
        const bestStatus =
          (statusRank[s.userStatus] || 0) > (statusRank[existing.userStatus] || 0)
            ? s.userStatus
            : existing.userStatus;
        const mergedComp = {
          ...cleanCompMap(existing.completedSeasons),
          ...incomingComp,
        };
        let mergedWatched = [
          ...(Array.isArray(existing.watchedEpisodes) ? existing.watchedEpisodes : []),
          ...(Array.isArray(s.watchedEpisodes) ? s.watchedEpisodes : []),
        ]
          .map((c) => String(c || "").trim().toUpperCase())
          .filter(Boolean);
        for (const ep of bestEps) {
          const sk = String(Number(ep.season) || 1);
          if (mergedComp[sk]) {
            mergedWatched.push(ep.code);
          }
        }
        mergedWatched = Array.from(new Set(mergedWatched)).sort();
        const presentS = new Set(bestEps.map((e) => String(Number(e.season) || 1)));
        for (const sk of presentS) delete mergedComp[sk];
        if (bestStatus === "completed" && bestEps.length > 0) {
          mergedWatched = bestEps.map((e) => e.code).sort();
        }
        const compSum = Object.values(mergedComp).reduce((a, b) => a + b, 0);
        const bestTotalEpisodes = Math.max(
          Number(existing.totalEpisodes) || 0,
          Number(s.totalEpisodes) || 0,
          bestEps.length + compSum,
          compSum + mergedWatched.length
        );
        const mergedEntry = {
          ...existing,
          ...s,
          id: existing.id || s.id,
          tvmazeId: existing.tvmazeId || tvmazeId || 0,
          tmdbId: existing.tmdbId || tmdbId || 0,
          posterUrl: existing.posterUrl || s.posterUrl || "",
          network:
            existing.network && existing.network !== "Streaming"
              ? existing.network
              : s.network || existing.network,
          totalEpisodes: bestTotalEpisodes,
          completedSeasons: mergedComp,
          watchedEpisodes: mergedWatched,
          episodes: bestEps,
          userStatus: bestStatus,
        };
        byKey.set(matchedKey, mergedEntry);
      }
    }
    return Array.from(byKey.values());
  }

  static deduplicateMovies(movies) {
    if (!Array.isArray(movies)) return [];
    const byKey = new Map();
    const titleYearToKeys = new Map();
    for (const m of movies) {
      if (!m || typeof m !== "object") continue;
      const titleKey = FirestoreRepository.normalizeTitleKey(m.title);
      const idKey = String(m.id || "").trim();
      const tmdbId = FirestoreRepository.cleanMovieTmdbId(idKey, m.tmdbId);
      const yearStr = String(Number(m.year) || "");
      const tyKey = titleKey ? `${titleKey}:${yearStr}` : "";

      let matchedKey = null;
      if (tmdbId > 0 && byKey.has(`tmdb:${tmdbId}`)) {
        matchedKey = `tmdb:${tmdbId}`;
      } else if (tyKey && titleYearToKeys.has(tyKey)) {
        for (const candKey of titleYearToKeys.get(tyKey)) {
          const cand = byKey.get(candKey);
          if (!cand) continue;
          const cTmdb = Number(cand.tmdbId) || 0;
          if (tmdbId > 0 && cTmdb > 0 && tmdbId !== cTmdb) continue;
          matchedKey = candKey;
          break;
        }
      }

      if (!matchedKey) {
        const primaryKey =
          tmdbId > 0
            ? `tmdb:${tmdbId}`
            : tyKey
              ? `title:${tyKey}`
              : idKey || `idx:${byKey.size}`;
        byKey.set(primaryKey, { ...m, tmdbId, watched: Boolean(m.watched) });
        if (tyKey) {
          if (!titleYearToKeys.has(tyKey)) titleYearToKeys.set(tyKey, []);
          titleYearToKeys.get(tyKey).push(primaryKey);
        }
      } else {
        const existing = byKey.get(matchedKey);
        byKey.set(matchedKey, {
          ...existing,
          ...m,
          id: existing.id || m.id,
          tmdbId: existing.tmdbId || tmdbId || 0,
          posterUrl: existing.posterUrl || m.posterUrl || "",
          watched: Boolean(existing.watched || m.watched),
          watchedAt: existing.watchedAt || m.watchedAt || "",
          streamingService:
            existing.streamingService && existing.streamingService !== "Streaming"
              ? existing.streamingService
              : m.streamingService || existing.streamingService,
          cast: Array.isArray(existing.cast) && existing.cast.length > 0 ? existing.cast : m.cast || [],
          director: existing.director || m.director || "",
        });
      }
    }
    return Array.from(byKey.values());
  }

  docUrlForUser(userId) {
    const clean = FirestoreRepository.sanitizeUserId(userId);
    if (!this.projectId || clean === "default") return null;
    return `https://firestore.googleapis.com/v1/projects/${this.projectId}/databases/(default)/documents/tvtime_state/user_state_${clean}`;
  }

  defaultState(userId = "default") {
    const clean = FirestoreRepository.sanitizeUserId(userId);
    return {
      userId: clean,
      email: FirestoreRepository.displayUserLabel(clean),
      shows: [],
      movies: [],
      actors: [],
      decidedIds: [],
      friends: [],
      incomingFriendRequests: [],
      outgoingFriendRequests: [],
      updatedAt: new Date().toISOString(),
    };
  }

  static _cleanUsernameArray(arr, excludeUser = "") {
    if (!Array.isArray(arr)) return [];
    const seen = new Set();
    const out = [];
    for (const item of arr) {
      const c = FirestoreRepository.sanitizeUserId(item);
      if (c && c !== "default" && c !== excludeUser && !seen.has(c)) {
        seen.add(c);
        out.push(c);
      }
    }
    return out;
  }

  /**
   * Strictly reads a user's Firestore document without auto-saving or swallowing network/parse errors as empty state.
   * Returns `{ exists: false, state }` ONLY on HTTP 404 or missing projectId/default user.
   * Throws on HTTP 5xx, network failure, or corrupt JSON so callers never mistake an error for 0 shows/movies.
   */
  async _readUserDocStrict(userId = "default") {
    const clean = FirestoreRepository.sanitizeUserId(userId);
    const url = this.docUrlForUser(clean);
    if (!url) {
      return { exists: false, payloadStr: "", state: this.defaultState(clean) };
    }
    const resp = await fetch(url, { method: "GET" });
    if (resp.status === 404) {
      return { exists: false, payloadStr: "", state: this.defaultState(clean) };
    }
    if (!resp.ok) {
      throw new Error(`Firestore read failed with HTTP ${resp.status}`);
    }
    const doc = await resp.json();
    const payloadStr = doc?.fields?.payload?.stringValue || "";
    if (!payloadStr) {
      return { exists: false, payloadStr: "", state: this.defaultState(clean) };
    }
    const parsed = JSON.parse(payloadStr);
    const rawShows = Array.isArray(parsed.shows) ? parsed.shows : [];
    const rawMovies = Array.isArray(parsed.movies) ? parsed.movies : [];
    const shows = FirestoreRepository.deduplicateShows(rawShows)
      .map((s) => FirestoreRepository.compactShowForStorage(s))
      .filter(Boolean);
    const movies = FirestoreRepository.deduplicateMovies(rawMovies)
      .map((m) => FirestoreRepository.compactMovieForStorage(m))
      .filter(Boolean);
    const decidedIds = FirestoreRepository.compactDecidedIds(parsed.decidedIds, parsed.actors);
    const friends = FirestoreRepository._cleanUsernameArray(parsed.friends, clean);
    const incomingFriendRequests = FirestoreRepository._cleanUsernameArray(
      parsed.incomingFriendRequests,
      clean
    ).filter((u) => !friends.includes(u));
    const outgoingFriendRequests = FirestoreRepository._cleanUsernameArray(
      parsed.outgoingFriendRequests,
      clean
    ).filter((u) => !friends.includes(u));

    const hasLegacyBloat =
      Array.isArray(parsed.actors) ||
      Array.isArray(parsed.followingUsers) ||
      rawShows.some(
        (s) =>
          s &&
          ("userRating" in s ||
            "overview" in s ||
            (s.userStatus === "completed" && Array.isArray(s.episodes) && s.episodes.length > 0))
      ) ||
      rawMovies.some((m) => m && ("userRating" in m || "overview" in m));

    const cleanedState = {
      userId: clean,
      email: parsed.email || FirestoreRepository.displayUserLabel(clean),
      shows,
      movies,
      actors: [],
      decidedIds,
      friends,
      incomingFriendRequests,
      outgoingFriendRequests,
      updatedAt:
        parsed.updatedAt || doc?.fields?.updatedAt?.stringValue || new Date().toISOString(),
    };
    return {
      exists: true,
      payloadStr,
      rawShowCount: rawShows.length,
      rawMovieCount: rawMovies.length,
      hasLegacyBloat,
      state: cleanedState,
    };
  }

  async getUserState(userId = "default") {
    const clean = FirestoreRepository.sanitizeUserId(userId);
    try {
      const res = await this._readUserDocStrict(clean);
      if (
        res.exists &&
        (res.rawShowCount !== res.state.shows.length ||
          res.rawMovieCount !== res.state.movies.length ||
          res.hasLegacyBloat ||
          res.payloadStr.length > 180000)
      ) {
        await this.saveUserState(clean, res.state);
      }
      return res.state;
    } catch (_) {
      return this.defaultState(clean);
    }
  }

  async saveUserState(userId, state) {
    const clean = FirestoreRepository.sanitizeUserId(userId);
    const url = this.docUrlForUser(clean);
    if (!url) {
      return false;
    }

    let existingSocial = null;
    if (
      !Array.isArray(state.friends) ||
      !Array.isArray(state.incomingFriendRequests) ||
      !Array.isArray(state.outgoingFriendRequests)
    ) {
      try {
        const curResp = await fetch(url, { method: "GET" });
        if (curResp.ok) {
          const curDoc = await curResp.json();
          const curStr = curDoc?.fields?.payload?.stringValue;
          if (curStr) existingSocial = JSON.parse(curStr);
        }
      } catch (_) {}
    }

    const shows = FirestoreRepository.deduplicateShows(
      Array.isArray(state.shows) ? state.shows : []
    )
      .map((s) => FirestoreRepository.compactShowForStorage(s))
      .filter(Boolean);
    const movies = FirestoreRepository.deduplicateMovies(
      Array.isArray(state.movies) ? state.movies : []
    )
      .map((m) => FirestoreRepository.compactMovieForStorage(m))
      .filter(Boolean);
    const decidedIds = FirestoreRepository.compactDecidedIds(state.decidedIds, state.actors);
    const friends = FirestoreRepository._cleanUsernameArray(
      Array.isArray(state.friends) ? state.friends : existingSocial?.friends,
      clean
    );
    const incomingFriendRequests = FirestoreRepository._cleanUsernameArray(
      Array.isArray(state.incomingFriendRequests)
        ? state.incomingFriendRequests
        : existingSocial?.incomingFriendRequests,
      clean
    ).filter((u) => !friends.includes(u));
    const outgoingFriendRequests = FirestoreRepository._cleanUsernameArray(
      Array.isArray(state.outgoingFriendRequests)
        ? state.outgoingFriendRequests
        : existingSocial?.outgoingFriendRequests,
      clean
    ).filter((u) => !friends.includes(u));
    const updatedAt = new Date().toISOString();

    const payloadObj = {
      userId: clean,
      email: state.email || FirestoreRepository.displayUserLabel(clean),
      shows,
      movies,
      decidedIds,
      friends,
      incomingFriendRequests,
      outgoingFriendRequests,
      updatedAt,
    };

    const serializedPayload = JSON.stringify(payloadObj);

    const firestoreBody = {
      fields: {
        userId: { stringValue: clean },
        email: { stringValue: payloadObj.email },
        payload: { stringValue: serializedPayload },
        showCount: { integerValue: String(shows.length) },
        movieCount: { integerValue: String(movies.length) },
        updatedAt: { stringValue: updatedAt },
      },
    };

    try {
      const resp = await fetch(url, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(firestoreBody),
      });
      return resp.ok;
    } catch (_) {
      return false;
    }
  }

  async searchUsersByUsername(currentUserId, rawQuery = "") {
    const me = FirestoreRepository.sanitizeUserId(currentUserId);
    if (!this.projectId) return [];
    let myFriends = [];
    let myIncoming = [];
    let myOutgoing = [];
    try {
      const myDoc = await this._readUserDocStrict(me);
      myFriends = myDoc.state.friends || [];
      myIncoming = myDoc.state.incomingFriendRequests || [];
      myOutgoing = myDoc.state.outgoingFriendRequests || [];
    } catch (_) {}

    const q = String(rawQuery || "").trim().toLowerCase();
    const cleanQ = q ? FirestoreRepository.sanitizeUserId(q) : "";

    const usersById = new Map();
    const annotateRelation = (uid) => {
      if (myFriends.includes(uid)) return "friend";
      if (myIncoming.includes(uid)) return "incoming";
      if (myOutgoing.includes(uid)) return "outgoing";
      return "none";
    };

    try {
      const maskParams = [
        "pageSize=100",
        "mask.fieldPaths=userId",
        "mask.fieldPaths=email",
        "mask.fieldPaths=showCount",
        "mask.fieldPaths=movieCount",
      ].join("&");
      const listUrl = `https://firestore.googleapis.com/v1/projects/${this.projectId}/databases/(default)/documents/tvtime_state?${maskParams}`;
      const resp = await fetch(listUrl, { method: "GET" });
      if (resp.ok) {
        const data = await resp.json();
        const docs = Array.isArray(data?.documents) ? data.documents : [];
        for (const doc of docs) {
          const docName = String(doc?.name || "");
          const docId = docName.split("/").pop() || "";
          if (!docId.startsWith("user_state_")) continue;
          const uid = FirestoreRepository.sanitizeUserId(
            doc?.fields?.userId?.stringValue || docId.slice("user_state_".length)
          );
          if (!uid || uid === "default" || uid === me) continue;
          const email = String(doc?.fields?.email?.stringValue || uid);
          const showCount = Number(doc?.fields?.showCount?.integerValue || 0);
          const movieCount = Number(doc?.fields?.movieCount?.integerValue || 0);
          if (!q || uid.includes(q) || email.toLowerCase().includes(q) || (cleanQ && uid.includes(cleanQ))) {
            usersById.set(uid, {
              userId: uid,
              email,
              showCount,
              movieCount,
              relation: annotateRelation(uid),
            });
          }
        }
      }
    } catch (_) {}

    if (cleanQ && cleanQ !== "default" && cleanQ !== me && !usersById.has(cleanQ)) {
      let showCount = 0;
      let movieCount = 0;
      let email = cleanQ;
      try {
        const directDoc = await this._readUserDocStrict(cleanQ);
        if (directDoc.exists) {
          showCount = directDoc.state.shows.length;
          movieCount = directDoc.state.movies.length;
          email = directDoc.state.email || cleanQ;
        }
      } catch (_) {}
      usersById.set(cleanQ, {
        userId: cleanQ,
        email,
        showCount,
        movieCount,
        relation: annotateRelation(cleanQ),
      });
    }

    return Array.from(usersById.values());
  }

  async sendFriendRequest(fromUserId, targetUsername) {
    const me = FirestoreRepository.sanitizeUserId(fromUserId);
    const target = FirestoreRepository.sanitizeUserId(targetUsername);
    if (!me || me === "default") {
      throw new Error("Please sign in before sending friend requests.");
    }
    if (!target || target === "default" || target === me) {
      throw new Error("Please enter a valid friend username.");
    }

    const [myDoc, targetDoc] = await Promise.all([
      this._readUserDocStrict(me),
      this._readUserDocStrict(target),
    ]);
    const myState = myDoc.state;
    const targetState = targetDoc.state;

    if ((myState.friends || []).includes(target)) {
      return myState;
    }

    if ((myState.incomingFriendRequests || []).includes(target)) {
      myState.incomingFriendRequests = (myState.incomingFriendRequests || []).filter((u) => u !== target);
      myState.outgoingFriendRequests = (myState.outgoingFriendRequests || []).filter((u) => u !== target);
      myState.friends = FirestoreRepository._cleanUsernameArray([...(myState.friends || []), target], me);

      targetState.outgoingFriendRequests = (targetState.outgoingFriendRequests || []).filter((u) => u !== me);
      targetState.incomingFriendRequests = (targetState.incomingFriendRequests || []).filter((u) => u !== me);
      targetState.friends = FirestoreRepository._cleanUsernameArray([...(targetState.friends || []), me], target);
    } else {
      myState.outgoingFriendRequests = FirestoreRepository._cleanUsernameArray(
        [...(myState.outgoingFriendRequests || []), target],
        me
      );
      targetState.incomingFriendRequests = FirestoreRepository._cleanUsernameArray(
        [...(targetState.incomingFriendRequests || []), me],
        target
      );
    }

    await Promise.all([
      this.saveUserState(me, myState),
      this.saveUserState(target, targetState),
    ]);
    return myState;
  }

  async respondToFriendRequest(myUserId, requesterUsername, action) {
    const me = FirestoreRepository.sanitizeUserId(myUserId);
    const requester = FirestoreRepository.sanitizeUserId(requesterUsername);
    if (!me || me === "default" || !requester || requester === "default") {
      throw new Error("Invalid friend request.");
    }

    const [myDoc, reqDoc] = await Promise.all([
      this._readUserDocStrict(me),
      this._readUserDocStrict(requester),
    ]);
    const myState = myDoc.state;
    const reqState = reqDoc.state;

    myState.incomingFriendRequests = (myState.incomingFriendRequests || []).filter((u) => u !== requester);
    myState.outgoingFriendRequests = (myState.outgoingFriendRequests || []).filter((u) => u !== requester);
    reqState.outgoingFriendRequests = (reqState.outgoingFriendRequests || []).filter((u) => u !== me);
    reqState.incomingFriendRequests = (reqState.incomingFriendRequests || []).filter((u) => u !== me);

    if (String(action || "").toLowerCase() === "accept") {
      myState.friends = FirestoreRepository._cleanUsernameArray([...(myState.friends || []), requester], me);
      reqState.friends = FirestoreRepository._cleanUsernameArray([...(reqState.friends || []), me], requester);
    }

    await Promise.all([
      this.saveUserState(me, myState),
      this.saveUserState(requester, reqState),
    ]);
    return myState;
  }

  async removeFriendOrRequest(myUserId, otherUsername) {
    const me = FirestoreRepository.sanitizeUserId(myUserId);
    const other = FirestoreRepository.sanitizeUserId(otherUsername);
    if (!me || me === "default" || !other || other === "default") {
      throw new Error("Invalid friend username.");
    }

    const [myDoc, otherDoc] = await Promise.all([
      this._readUserDocStrict(me),
      this._readUserDocStrict(other),
    ]);
    const myState = myDoc.state;
    const otherState = otherDoc.state;

    myState.friends = (myState.friends || []).filter((u) => u !== other);
    myState.incomingFriendRequests = (myState.incomingFriendRequests || []).filter((u) => u !== other);
    myState.outgoingFriendRequests = (myState.outgoingFriendRequests || []).filter((u) => u !== other);

    otherState.friends = (otherState.friends || []).filter((u) => u !== me);
    otherState.incomingFriendRequests = (otherState.incomingFriendRequests || []).filter((u) => u !== me);
    otherState.outgoingFriendRequests = (otherState.outgoingFriendRequests || []).filter((u) => u !== me);

    await Promise.all([
      this.saveUserState(me, myState),
      this.saveUserState(other, otherState),
    ]);
    return myState;
  }

  async getFriendLibrary(myUserId, friendUsername) {
    const me = FirestoreRepository.sanitizeUserId(myUserId);
    const friend = FirestoreRepository.sanitizeUserId(friendUsername);
    if (!me || me === "default" || !friend || friend === "default") {
      return null;
    }
    const [myDoc, friendDoc] = await Promise.all([
      this._readUserDocStrict(me),
      this._readUserDocStrict(friend),
    ]);
    const myState = myDoc.state;
    const friendState = friendDoc.state;
    const isMutual =
      (myState.friends || []).includes(friend) || (friendState.friends || []).includes(me);
    if (!isMutual) {
      return null;
    }
    return {
      userId: friendState.userId,
      email: friendState.email || friendState.userId,
      shows: friendState.shows || [],
      movies: friendState.movies || [],
    };
  }
}
