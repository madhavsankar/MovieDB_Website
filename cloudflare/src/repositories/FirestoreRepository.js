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
      const shows = Array.isArray(parsed.shows) ? parsed.shows : [];
      const movies = Array.isArray(parsed.movies) ? parsed.movies : [];
      const actors = Array.isArray(parsed.actors) ? parsed.actors : STARTER_ACTORS.map((a) => ({ ...a }));
      const decidedIds = Array.isArray(parsed.decidedIds) ? parsed.decidedIds : [];
      const followingUsers = Array.isArray(parsed.followingUsers) ? parsed.followingUsers : [];

      return {
        userId: clean,
        email: parsed.email || FirestoreRepository.displayUserLabel(clean),
        shows,
        movies,
        actors,
        decidedIds,
        followingUsers,
        updatedAt: parsed.updatedAt || doc?.fields?.updatedAt?.stringValue || new Date().toISOString(),
      };
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

    const shows = Array.isArray(state.shows) ? state.shows : [];
    const movies = Array.isArray(state.movies) ? state.movies : [];
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
