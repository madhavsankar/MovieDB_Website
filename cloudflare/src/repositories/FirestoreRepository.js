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

  static isSyntheticEpisodeList(eps) {
    if (!Array.isArray(eps) || eps.length === 0) return true;
    return eps.every((e) => !e || !e.title || /^Episode \d+$/i.test(String(e.title).trim()));
  }

  static compactEpisode(ep) {
    if (!ep || typeof ep !== "object") return ep;
    const out = {
      code: String(ep.code || "S01E01"),
      season: Number(ep.season) || 1,
      number: Number(ep.number) || 1,
    };
    if (ep.title) out.title = String(ep.title);
    if (ep.airDate) out.airDate = String(ep.airDate);
    if (ep.runtimeMins) out.runtimeMins = Number(ep.runtimeMins);
    return out;
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
    const statusRank = { completed: 4, in_progress: 3, watching: 3, stopped: 2, interested: 1 };
    for (const s of shows) {
      if (!s || typeof s !== "object") continue;
      const titleKey = FirestoreRepository.normalizeTitleKey(s.title);
      const idKey = String(s.id || "").trim();
      const key = titleKey ? `title:${titleKey}` : idKey || `idx:${byKey.size}`;
      const incomingEps = (Array.isArray(s.episodes) ? s.episodes : []).map((e) =>
        FirestoreRepository.compactEpisode(e)
      );
      if (!byKey.has(key)) {
        let watched = Array.from(
          new Set(Array.isArray(s.watchedEpisodes) ? s.watchedEpisodes : [])
        ).sort();
        if (s.userStatus === "completed" && incomingEps.length > 0 && watched.length < incomingEps.length) {
          watched = incomingEps.map((e) => e.code).sort();
        } else if (
          incomingEps.length > 0 &&
          !FirestoreRepository.isSyntheticEpisodeList(incomingEps)
        ) {
          const validCodes = new Set(incomingEps.map((e) => e.code));
          watched = watched.filter((c) => validCodes.has(c)).sort();
        }
        byKey.set(key, {
          ...s,
          watchedEpisodes: watched,
          episodes: incomingEps,
        });
      } else {
        const existing = byKey.get(key);
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
        let mergedWatched = Array.from(
          new Set([
            ...(Array.isArray(existing.watchedEpisodes) ? existing.watchedEpisodes : []),
            ...(Array.isArray(s.watchedEpisodes) ? s.watchedEpisodes : []),
          ])
        ).sort();
        if (bestStatus === "completed" && bestEps.length > 0) {
          mergedWatched = bestEps.map((e) => e.code).sort();
        } else if (bestEps.length > 0 && !FirestoreRepository.isSyntheticEpisodeList(bestEps)) {
          const validCodes = new Set(bestEps.map((e) => e.code));
          const filtered = mergedWatched.filter((c) => validCodes.has(c));
          if (filtered.length > 0) mergedWatched = filtered.sort();
        }
        byKey.set(key, {
          ...existing,
          ...s,
          id: existing.id || s.id,
          tvmazeId: existing.tvmazeId || s.tvmazeId || 0,
          tmdbId: existing.tmdbId || s.tmdbId || 0,
          posterUrl: existing.posterUrl || s.posterUrl || "",
          overview: existing.overview || s.overview || "",
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
      const titleKey = FirestoreRepository.normalizeTitleKey(m.title);
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
          posterUrl: existing.posterUrl || m.posterUrl || "",
          overview: existing.overview || m.overview || "",
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
      const friends = FirestoreRepository._cleanUsernameArray(parsed.friends, clean);
      const incomingFriendRequests = FirestoreRepository._cleanUsernameArray(
        parsed.incomingFriendRequests,
        clean
      ).filter((u) => !friends.includes(u));
      const outgoingFriendRequests = FirestoreRepository._cleanUsernameArray(
        parsed.outgoingFriendRequests,
        clean
      ).filter((u) => !friends.includes(u));

      const cleanedState = {
        userId: clean,
        email: parsed.email || FirestoreRepository.displayUserLabel(clean),
        shows,
        movies,
        actors,
        decidedIds,
        followingUsers,
        friends,
        incomingFriendRequests,
        outgoingFriendRequests,
        updatedAt: parsed.updatedAt || doc?.fields?.updatedAt?.stringValue || new Date().toISOString(),
      };
      if (
        rawShows.length !== shows.length ||
        rawMovies.length !== movies.length ||
        payloadStr.length > 750000
      ) {
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

    const shows = FirestoreRepository.deduplicateShows(Array.isArray(state.shows) ? state.shows : []);
    const movies = FirestoreRepository.deduplicateMovies(Array.isArray(state.movies) ? state.movies : []);
    const actors = Array.isArray(state.actors) ? state.actors : [];
    const decidedIds = Array.isArray(state.decidedIds) ? state.decidedIds : [];
    const followingUsers = Array.isArray(state.followingUsers) ? state.followingUsers : [];
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
      actors,
      decidedIds,
      followingUsers,
      friends,
      incomingFriendRequests,
      outgoingFriendRequests,
      updatedAt,
    };

    let serializedPayload = JSON.stringify(payloadObj);
    if (serializedPayload.length > 550000) {
      payloadObj.shows = shows.map((s) => {
        if (s.userStatus !== "completed") return s;
        return {
          ...s,
          watchedEpisodes: [],
          episodes: (Array.isArray(s.episodes) ? s.episodes : []).map((ep) => ({
            code: ep.code,
            season: ep.season,
            number: ep.number,
          })),
        };
      });
      serializedPayload = JSON.stringify(payloadObj);
    }
    if (serializedPayload.length > 750000) {
      payloadObj.shows = payloadObj.shows.map((s) => {
        const eps = Array.isArray(s.episodes) ? s.episodes : [];
        if (eps.length <= 100) return s;
        return {
          ...s,
          episodes: eps.map((ep) => ({
            code: ep.code,
            season: ep.season,
            number: ep.number,
          })),
        };
      });
      serializedPayload = JSON.stringify(payloadObj);
    }

    const firestoreBody = {
      fields: {
        userId: { stringValue: clean },
        email: { stringValue: payloadObj.email },
        payload: { stringValue: serializedPayload },
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

  async searchUsersByUsername(currentUserId, rawQuery = "") {
    const me = FirestoreRepository.sanitizeUserId(currentUserId);
    if (!this.projectId) return [];
    const myState = await this.getUserState(me);
    const q = String(rawQuery || "").trim().toLowerCase();
    const cleanQ = q ? FirestoreRepository.sanitizeUserId(q) : "";

    const usersById = new Map();
    const annotateRelation = (uid) => {
      if ((myState.friends || []).includes(uid)) return "friend";
      if ((myState.incomingFriendRequests || []).includes(uid)) return "incoming";
      if ((myState.outgoingFriendRequests || []).includes(uid)) return "outgoing";
      return "none";
    };

    try {
      const listUrl = `https://firestore.googleapis.com/v1/projects/${this.projectId}/databases/(default)/documents/tvtime_state?pageSize=100`;
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
          let showCount = Number(doc?.fields?.showCount?.integerValue || 0);
          let movieCount = Number(doc?.fields?.movieCount?.integerValue || 0);
          if (!showCount && !movieCount && doc?.fields?.payload?.stringValue) {
            try {
              const p = JSON.parse(doc.fields.payload.stringValue);
              showCount = Array.isArray(p.shows) ? p.shows.length : 0;
              movieCount = Array.isArray(p.movies) ? p.movies.length : 0;
            } catch (_) {}
          }
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
      usersById.set(cleanQ, {
        userId: cleanQ,
        email: cleanQ,
        showCount: 0,
        movieCount: 0,
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

    const myState = await this.getUserState(me);
    const targetState = await this.getUserState(target);

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

    await this.saveUserState(me, myState);
    await this.saveUserState(target, targetState);
    return myState;
  }

  async respondToFriendRequest(myUserId, requesterUsername, action) {
    const me = FirestoreRepository.sanitizeUserId(myUserId);
    const requester = FirestoreRepository.sanitizeUserId(requesterUsername);
    if (!me || me === "default" || !requester || requester === "default") {
      throw new Error("Invalid friend request.");
    }

    const myState = await this.getUserState(me);
    const reqState = await this.getUserState(requester);

    myState.incomingFriendRequests = (myState.incomingFriendRequests || []).filter((u) => u !== requester);
    myState.outgoingFriendRequests = (myState.outgoingFriendRequests || []).filter((u) => u !== requester);
    reqState.outgoingFriendRequests = (reqState.outgoingFriendRequests || []).filter((u) => u !== me);
    reqState.incomingFriendRequests = (reqState.incomingFriendRequests || []).filter((u) => u !== me);

    if (String(action || "").toLowerCase() === "accept") {
      myState.friends = FirestoreRepository._cleanUsernameArray([...(myState.friends || []), requester], me);
      reqState.friends = FirestoreRepository._cleanUsernameArray([...(reqState.friends || []), me], requester);
    }

    await this.saveUserState(me, myState);
    await this.saveUserState(requester, reqState);
    return myState;
  }

  async removeFriendOrRequest(myUserId, otherUsername) {
    const me = FirestoreRepository.sanitizeUserId(myUserId);
    const other = FirestoreRepository.sanitizeUserId(otherUsername);
    if (!me || me === "default" || !other || other === "default") {
      throw new Error("Invalid friend username.");
    }

    const myState = await this.getUserState(me);
    const otherState = await this.getUserState(other);

    myState.friends = (myState.friends || []).filter((u) => u !== other);
    myState.incomingFriendRequests = (myState.incomingFriendRequests || []).filter((u) => u !== other);
    myState.outgoingFriendRequests = (myState.outgoingFriendRequests || []).filter((u) => u !== other);

    otherState.friends = (otherState.friends || []).filter((u) => u !== me);
    otherState.incomingFriendRequests = (otherState.incomingFriendRequests || []).filter((u) => u !== me);
    otherState.outgoingFriendRequests = (otherState.outgoingFriendRequests || []).filter((u) => u !== me);

    await this.saveUserState(me, myState);
    await this.saveUserState(other, otherState);
    return myState;
  }

  async getFriendLibrary(myUserId, friendUsername) {
    const me = FirestoreRepository.sanitizeUserId(myUserId);
    const friend = FirestoreRepository.sanitizeUserId(friendUsername);
    if (!me || me === "default" || !friend || friend === "default") {
      return null;
    }
    const myState = await this.getUserState(me);
    const friendState = await this.getUserState(friend);
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
