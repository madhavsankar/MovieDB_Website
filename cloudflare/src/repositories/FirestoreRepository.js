/**
 * Multi-User Cloud Firestore Repository for TV Time: Show & Movie Tracker (`tvtime_state/user_state_{userId}`).
 *
 * Shares the same free Firebase Cloud Firestore instance as PackTrack and PlacesTracker
 * under a dedicated `tvtime_state` collection with compact watched-episode maps (`< 100 KB` per user).
 * Anonymous `"default"` profile never reads or writes to Cloud Firestore.
 */

export const STARTER_SHOWS = [
  {
    id: "tvmaze_49059",
    tvmazeId: 49059,
    tmdbId: 95396,
    title: "Severance",
    year: 2022,
    network: "Apple TV+",
    status: "Running",
    runtimeMins: 50,
    genres: ["Drama", "Sci-Fi", "Mystery"],
    cast: ["Adam Scott", "Britt Lower", "John Turturro", "Patricia Arquette"],
    rating: 8.7,
    posterUrl: "https://static.tvmaze.com/uploads/images/medium_portrait/548/1371268.jpg",
    overview: "Mark leads a team of office workers whose memories have been surgically divided between their work and personal lives.",
    userStatus: "watching",
    userRating: 5,
    reaction: "😍",
    isFavorite: true,
    rewatchCount: 0,
    addedAt: "2026-01-15T00:00:00Z",
    lastWatchedAt: "2026-10-20T00:00:00Z",
    watchedEpisodes: [
      "S01E01", "S01E02", "S01E03", "S01E04", "S01E05",
      "S01E06", "S01E07", "S01E08", "S01E09",
      "S02E01", "S02E02", "S02E03"
    ],
    episodes: [
      { code: "S01E01", season: 1, number: 1, title: "Good News About Hell", airDate: "2022-02-18", runtimeMins: 57, imageUrl: "https://static.tvmaze.com/uploads/images/medium_landscape/392/981270.jpg", overview: "Mark is promoted to lead a team whose memories have been surgically divided." },
      { code: "S01E02", season: 1, number: 2, title: "Half Loop", airDate: "2022-02-18", runtimeMins: 47, imageUrl: "https://static.tvmaze.com/uploads/images/medium_landscape/392/981271.jpg", overview: "The team trains new hire Helly on macrodata refinement." },
      { code: "S01E03", season: 1, number: 3, title: "In Perpetuity", airDate: "2022-02-25", runtimeMins: 52, imageUrl: "https://static.tvmaze.com/uploads/images/medium_landscape/393/983749.jpg", overview: "Mark takes the team on a field trip to the Perpetuity Wing." },
      { code: "S01E04", season: 1, number: 4, title: "The You You Are", airDate: "2022-03-04", runtimeMins: 46, imageUrl: "https://static.tvmaze.com/uploads/images/medium_landscape/395/988062.jpg", overview: "Irving finds an intriguing book at work; Helly aggressively pursues a meeting with her Outie." },
      { code: "S01E05", season: 1, number: 5, title: "The Grim Barbarity of Optics and Design", airDate: "2022-03-11", runtimeMins: 43, imageUrl: "https://static.tvmaze.com/uploads/images/medium_landscape/396/992028.jpg", overview: "Irving and Dylan confront Burt; Mark and Helly discover a new department." },
      { code: "S01E06", season: 1, number: 6, title: "Hide and Seek", airDate: "2022-03-18", runtimeMins: 47, imageUrl: "https://static.tvmaze.com/uploads/images/medium_landscape/398/995965.jpg", overview: "The team angers Cobel by forming an alliance." },
      { code: "S01E07", season: 1, number: 7, title: "Defiant Jazz", airDate: "2022-03-25", runtimeMins: 49, imageUrl: "https://static.tvmaze.com/uploads/images/medium_landscape/400/1000249.jpg", overview: "Mark and the team face new security measures from Cobel." },
      { code: "S01E08", season: 1, number: 8, title: "What's for Dinner?", airDate: "2022-04-01", runtimeMins: 48, imageUrl: "https://static.tvmaze.com/uploads/images/medium_landscape/401/1004590.jpg", overview: "The team prepares a daring plan to awaken their Innie selves on the outside." },
      { code: "S01E09", season: 1, number: 9, title: "The We We Are", airDate: "2022-04-08", runtimeMins: 40, imageUrl: "https://static.tvmaze.com/uploads/images/medium_landscape/403/1008565.jpg", overview: "The team discovers shocking truths in the outside world." },
      { code: "S02E01", season: 2, number: 1, title: "Hello, Ms. Cobel", airDate: "2025-01-17", runtimeMins: 49, imageUrl: "https://static.tvmaze.com/uploads/images/medium_landscape/552/1380501.jpg", overview: "Mark returns to Lumon to find a transformed Macrodata Refinement floor." },
      { code: "S02E02", season: 2, number: 2, title: "Goodbye, Mrs. Selvig", airDate: "2025-01-24", runtimeMins: 47, imageUrl: "https://static.tvmaze.com/uploads/images/medium_landscape/553/1384712.jpg", overview: "Outie Mark pieces together the aftermath of the Overtime Contingency." },
      { code: "S02E03", season: 2, number: 3, title: "Who Is Alive?", airDate: "2025-01-31", runtimeMins: 53, imageUrl: "https://static.tvmaze.com/uploads/images/medium_landscape/555/1388101.jpg", overview: "Mark and Helly search for the goat department and clues to Gemma." },
      { code: "S02E04", season: 2, number: 4, title: "Woe's Hollow", airDate: "2025-02-07", runtimeMins: 51, imageUrl: "https://static.tvmaze.com/uploads/images/medium_landscape/556/1391882.jpg", overview: "The team embarks on an outdoor retreat across frozen terrain." },
      { code: "S02E05", season: 2, number: 5, title: "Trojan's Horse", airDate: "2025-02-14", runtimeMins: 48, imageUrl: "", overview: "Tensions rise inside MDR after the ORTBO revelation." },
      { code: "S02E06", season: 2, number: 6, title: "Attila", airDate: "2025-02-21", runtimeMins: 50, imageUrl: "", overview: "Irving's Outie shares dinner with Burt and Fields." }
    ]
  },
  {
    id: "tvmaze_55554",
    tvmazeId: 55554,
    tmdbId: 136315,
    title: "The Bear",
    year: 2022,
    network: "FX / Hulu",
    status: "Running",
    runtimeMins: 34,
    genres: ["Drama", "Comedy"],
    cast: ["Jeremy Allen White", "Ayo Edebiri", "Ebon Moss-Bachrach"],
    rating: 8.6,
    posterUrl: "https://static.tvmaze.com/uploads/images/medium_portrait/523/1309665.jpg",
    overview: "A young chef from the fine dining world comes home to Chicago to run his family sandwich shop.",
    userStatus: "watching",
    userRating: 5,
    reaction: "🔥",
    isFavorite: true,
    rewatchCount: 0,
    addedAt: "2026-02-10T00:00:00Z",
    lastWatchedAt: "2026-10-18T00:00:00Z",
    watchedEpisodes: ["S01E01", "S01E02", "S01E03", "S01E04"],
    episodes: [
      { code: "S01E01", season: 1, number: 1, title: "System", airDate: "2022-06-23", runtimeMins: 29, imageUrl: "", overview: "Carmy attempts to retrain the stubborn kitchen staff at The Original Beef." },
      { code: "S01E02", season: 1, number: 2, title: "Hands", airDate: "2022-06-23", runtimeMins: 30, imageUrl: "", overview: "A surprise health inspection reveals flaws in the restaurant's foundation." },
      { code: "S01E03", season: 1, number: 3, title: "Brigade", airDate: "2022-06-23", runtimeMins: 25, imageUrl: "", overview: "Carmy attends an Al-Anon meeting and puts Sydney in charge of a French brigade kitchen." },
      { code: "S01E04", season: 1, number: 4, title: "Dogs", airDate: "2022-06-23", runtimeMins: 29, imageUrl: "", overview: "Carmy and Richie cater a children's birthday party." },
      { code: "S01E05", season: 1, number: 5, title: "Sheridan", airDate: "2022-06-23", runtimeMins: 30, imageUrl: "", overview: "Things break down in the kitchen just as lunch service starts." },
      { code: "S01E06", season: 1, number: 6, title: "Ceres", airDate: "2022-06-23", runtimeMins: 35, imageUrl: "", overview: "Richie notices the neighborhood changing while Sugar helps with unpaid taxes." },
      { code: "S01E07", season: 1, number: 7, title: "Review", airDate: "2022-06-23", runtimeMins: 20, imageUrl: "", overview: "A single-take morning rush pushes the kitchen to the brink." },
      { code: "S01E08", season: 1, number: 8, title: "Braciole", airDate: "2022-06-23", runtimeMins: 47, imageUrl: "", overview: "Season 1 finale: Carmy confronts his grief and makes a life-changing discovery." }
    ]
  },
  {
    id: "tvmaze_58957",
    tvmazeId: 58957,
    tmdbId: 125988,
    title: "Silo",
    year: 2023,
    network: "Apple TV+",
    status: "Running",
    runtimeMins: 52,
    genres: ["Drama", "Sci-Fi", "Thriller"],
    cast: ["Rebecca Ferguson", "Common", "Tim Robbins", "Harriet Walter"],
    rating: 8.1,
    posterUrl: "https://static.tvmaze.com/uploads/images/medium_portrait/539/1349746.jpg",
    overview: "In a ruined and toxic future, thousands live in a giant silo deep underground. After its sheriff breaks a cardinal rule and residents die mysteriously, engineer Juliette starts to uncover shocking secrets.",
    userStatus: "watching",
    userRating: 4,
    reaction: "🤯",
    isFavorite: false,
    rewatchCount: 0,
    addedAt: "2026-03-01T00:00:00Z",
    lastWatchedAt: "2026-10-05T00:00:00Z",
    watchedEpisodes: [],
    episodes: [
      { code: "S01E01", season: 1, number: 1, title: "Freedom Day", airDate: "2023-05-05", runtimeMins: 59, imageUrl: "", overview: "Sheriff Becker's plans for the future are thrown off course after his wife meets a hacker with information about the silo." },
      { code: "S01E02", season: 1, number: 2, title: "Holston's Pick", airDate: "2023-05-05", runtimeMins: 48, imageUrl: "", overview: "Juliette, an engineer, pieces together what might have led to a coworker's mysterious death." },
      { code: "S01E03", season: 1, number: 3, title: "Machines", airDate: "2023-05-12", runtimeMins: 62, imageUrl: "", overview: "In her hunt for a new sheriff, Mayor Jahns clashes with Bernard." },
      { code: "S01E04", season: 1, number: 4, title: "Truth", airDate: "2023-05-19", runtimeMins: 46, imageUrl: "", overview: "Juliette tries winning the trust of Deputy Marnes as they work to uncover the truth about recent tragedies." },
      { code: "S01E05", season: 1, number: 5, title: "The Janitor's Boy", airDate: "2026-11-12", runtimeMins: 51, imageUrl: "", overview: "Sims appoints Billings as Juliette's chief deputy in Order to keep tabs on her." }
    ]
  }
];

export const STARTER_MOVIES = [
  {
    id: "movie_693134",
    tmdbId: 693134,
    title: "Dune: Part Two",
    year: 2024,
    runtimeMins: 166,
    genres: ["Sci-Fi", "Adventure"],
    cast: ["Timothée Chalamet", "Zendaya", "Rebecca Ferguson", "Javier Bardem"],
    director: "Denis Villeneuve",
    streamingService: "Max",
    rating: 8.5,
    posterUrl: "https://is1-ssl.mzstatic.com/image/thumb/Video221/v4/71/15/3d/71153d6a-0c85-8f5b-6e87-91d7d7d96f9d/pr_source.lsr/600x900bb.jpg",
    overview: "Paul Atreides unites with Chani and the Fremen while on a warpath of revenge against the conspirators who destroyed his family.",
    watched: true,
    watchedAt: "2026-09-12",
    userRating: 5,
    reaction: "😍",
    rewatchCount: 1,
    isFavorite: true,
    notes: "Watched in IMAX"
  },
  {
    id: "movie_872585",
    tmdbId: 872585,
    title: "Oppenheimer",
    year: 2023,
    runtimeMins: 180,
    genres: ["Biography", "Drama", "History"],
    cast: ["Cillian Murphy", "Emily Blunt", "Matt Damon", "Robert Downey Jr."],
    director: "Christopher Nolan",
    streamingService: "Peacock / Prime Video",
    rating: 8.9,
    posterUrl: "https://is1-ssl.mzstatic.com/image/thumb/Video116/v4/68/98/48/68984863-8884-30e5-0038-1b91e066e775/pr_source.lsr/600x900bb.jpg",
    overview: "The story of J. Robert Oppenheimer's role in the development of the atomic bomb during World War II.",
    watched: true,
    watchedAt: "2026-08-04",
    userRating: 5,
    reaction: "🤯",
    rewatchCount: 0,
    isFavorite: true,
    notes: ""
  },
  {
    id: "movie_1022789",
    tmdbId: 1022789,
    title: "Conclave",
    year: 2024,
    runtimeMins: 120,
    genres: ["Drama", "Thriller"],
    cast: ["Ralph Fiennes", "Stanley Tucci", "John Lithgow", "Isabella Rossellini"],
    director: "Edward Berger",
    streamingService: "Peacock",
    rating: 7.6,
    posterUrl: "",
    overview: "After the unexpected death of the Pope, Cardinal Lawrence is tasked with managing the covert and ancient ritual of electing a new one.",
    watched: false,
    watchedAt: "",
    userRating: 0,
    reaction: "",
    rewatchCount: 0,
    isFavorite: false,
    notes: "Watchlist for weekend movie night"
  }
];

export const STARTER_ACTORS = [
  {
    id: "actor_rebecca_ferguson",
    name: "Rebecca Ferguson",
    role: "Acting",
    imageUrl: "https://static.tvmaze.com/uploads/images/medium_portrait/454/1136976.jpg",
    imdbUrl: "https://www.imdb.com/find/?q=Rebecca+Ferguson",
    knownFor: "Silo, Dune: Part Two"
  },
  {
    id: "actor_adam_scott",
    name: "Adam Scott",
    role: "Acting",
    imageUrl: "https://static.tvmaze.com/uploads/images/medium_portrait/392/981251.jpg",
    imdbUrl: "https://www.imdb.com/find/?q=Adam+Scott",
    knownFor: "Severance, Parks and Recreation"
  },
  {
    id: "actor_rami_malek",
    name: "Rami Malek",
    role: "Acting",
    imageUrl: "https://static.tvmaze.com/uploads/images/medium_portrait/61/154952.jpg",
    imdbUrl: "https://www.imdb.com/find/?q=Rami+Malek",
    knownFor: "Mr. Robot, Oppenheimer"
  },
  {
    id: "actor_bob_odenkirk",
    name: "Bob Odenkirk",
    role: "Acting",
    imageUrl: "https://static.tvmaze.com/uploads/images/medium_portrait/0/1079.jpg",
    imdbUrl: "https://www.imdb.com/find/?q=Bob+Odenkirk",
    knownFor: "Better Call Saul, The Bear"
  }
];

export class FirestoreRepository {
  constructor(env) {
    this.projectId = (env?.FIREBASE_PROJECT_ID || "").trim();
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
      const followingUsers = Array.isArray(parsed.followingUsers) ? parsed.followingUsers : [];

      return {
        userId: clean,
        email: parsed.email || FirestoreRepository.displayUserLabel(clean),
        shows,
        movies,
        actors,
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
    const followingUsers = Array.isArray(state.followingUsers) ? state.followingUsers : [];
    const updatedAt = new Date().toISOString();

    const payloadObj = {
      userId: clean,
      email: state.email || FirestoreRepository.displayUserLabel(clean),
      shows,
      movies,
      actors,
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
