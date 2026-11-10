/**
 * Multi-User Cloud Firestore Repository for TV Time: Show & Movie Tracker (`tvtime_state/user_state_{userId}`).
 *
 * Shares the same free Firebase Cloud Firestore instance as PackTrack and PlacesTracker
 * under a dedicated `tvtime_state` collection with compact watched-episode maps (`< 100 KB` per user).
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

  static deduplicateShows(shows) {
    if (!Array.isArray(shows)) return [];
    const byKey = new Map();
    const statusRank = { completed: 4, in_progress: 3, watching: 3, stopped: 2, interested: 1 };
    for (const s of shows) {
      if (!s || typeof s !== "object") continue;
      const titleKey = String(s.title || "").trim().toLowerCase();
      const idKey = String(s.id || "").trim();
      const key = titleKey ? `title:${titleKey}` : idKey || `idx:${byKey.size}`;
      if (!byKey.has(key)) {
        byKey.set(key, {
          ...s,
          watchedEpisodes: Array.from(new Set(Array.isArray(s.watchedEpisodes) ? s.watchedEpisodes : [])).sort(),
          episodes: Array.isArray(s.episodes) ? s.episodes : [],
        });
      } else {
        const existing = byKey.get(key);
        const mergedWatched = Array.from(
          new Set([
            ...(Array.isArray(existing.watchedEpisodes) ? existing.watchedEpisodes : []),
            ...(Array.isArray(s.watchedEpisodes) ? s.watchedEpisodes : []),
          ])
        ).sort();
        const existingEps = Array.isArray(existing.episodes) ? existing.episodes : [];
        const newEps = Array.isArray(s.episodes) ? s.episodes : [];
        const bestEps = newEps.length > existingEps.length ? newEps : existingEps;
        const bestStatus =
          (statusRank[s.userStatus] || 0) > (statusRank[existing.userStatus] || 0)
            ? s.userStatus
            : existing.userStatus;
        byKey.set(key, {
          ...existing,
          ...s,
          id: existing.id || s.id,
          tvmazeId: existing.tvmazeId || s.tvmazeId || 0,
          tmdbId: existing.tmdbId || s.tmdbId || 0,
          network:
            existing.network && existing.network !== "Streaming"
              ? existing.network
              : s.network || existing.network,
          cast: Array.isArray(existing.cast) && existing.cast.length > 0 ? existing.cast : s.cast || [],
          watchedEpisodes: mergedWatched,
          episodes: bestEps,
          userStatus: bestStatus,
        });
      }
    }
    return Array.from(byKey.values());
  }

  static deduplicateMovies(movies) {
    if (!Array.isArray(movies)) return [];
    const byKey = new Map();
    for (const m of movies) {
      if (!m || typeof m !== "object") continue;
      const titleKey = String(m.title || "").trim().toLowerCase();
      const idKey = String(m.id || "").trim();
      const key = titleKey ? `title:${titleKey}:${m.year || ""}` : idKey || `idx:${byKey.size}`;
      if (!byKey.has(key)) {
        byKey.set(key, { ...m, watched: Boolean(m.watched) });
      } else {
        const existing = byKey.get(key);
        byKey.set(key, {
          ...existing,
          ...m,
          id: existing.id || m.id,
          tmdbId: existing.tmdbId || m.tmdbId || 0,
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
      shows: STARTER_SHOWS.map((s) => ({
        ...s,
        watchedEpisodes: [...(s.watchedEpisodes || [])],
        episodes: (s.episodes || []).map((ep) => ({ ...ep })),
      })),
      movies: STARTER_MOVIES.map((m) => ({ ...m })),
      actors: STARTER_ACTORS.map((a) => ({ ...a })),
      decidedIds: [],
      followingUsers: ["partner"],
      updatedAt: new Date().toISOString(),
    };
  }

  async getUserState(userId = "default") {
    const clean = FirestoreRepository.sanitizeUserId(userId);
    const url = this.docUrlForUser(clean);
    if (!url) {
      return this.defaultState(clean);
    }

    try {
      const resp = await fetch(url, { method: "GET" });
      if (!resp.ok) {
        return this.defaultState(clean);
      }
      const doc = await resp.json();
      const payloadStr = doc?.fields?.payload?.stringValue;
      if (!payloadStr) {
        return this.defaultState(clean);
      }
      const parsed = JSON.parse(payloadStr);
      const rawShows = Array.isArray(parsed.shows) ? parsed.shows : [];
      const rawMovies = Array.isArray(parsed.movies) ? parsed.movies : [];
      const shows = FirestoreRepository.deduplicateShows(rawShows);
      const movies = FirestoreRepository.deduplicateMovies(rawMovies);
      const actors = Array.isArray(parsed.actors) ? parsed.actors : STARTER_ACTORS.map((a) => ({ ...a }));
      const decidedIds = Array.isArray(parsed.decidedIds) ? parsed.decidedIds : [];
      const followingUsers = Array.isArray(parsed.followingUsers) ? parsed.followingUsers : [];

      const cleanedState = {
        userId: clean,
        email: parsed.email || FirestoreRepository.displayUserLabel(clean),
        shows,
        movies,
        actors,
        decidedIds,
        followingUsers,
        updatedAt: parsed.updatedAt || doc?.fields?.updatedAt?.stringValue || new Date().toISOString(),
      };
      if (rawShows.length !== shows.length || rawMovies.length !== movies.length) {
        await this.saveUserState(clean, cleanedState);
      }
      return cleanedState;
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

    const shows = FirestoreRepository.deduplicateShows(Array.isArray(state.shows) ? state.shows : []);
    const movies = FirestoreRepository.deduplicateMovies(Array.isArray(state.movies) ? state.movies : []);
    const actors = Array.isArray(state.actors) ? state.actors : [];
    const decidedIds = Array.isArray(state.decidedIds) ? state.decidedIds : [];
    const followingUsers = Array.isArray(state.followingUsers) ? state.followingUsers : [];
    const updatedAt = new Date().toISOString();

    const payloadObj = {
      userId: clean,
      email: state.email || FirestoreRepository.displayUserLabel(clean),
      shows,
      movies,
      actors,
      decidedIds,
      followingUsers,
      updatedAt,
    };

    const firestoreBody = {
      fields: {
        userId: { stringValue: clean },
        email: { stringValue: payloadObj.email },
        payload: { stringValue: JSON.stringify(payloadObj) },
        showCount: { integerValue: String(shows.length) },
        movieCount: { integerValue: String(movies.length) },
        actorCount: { integerValue: String(actors.length) },
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
}
