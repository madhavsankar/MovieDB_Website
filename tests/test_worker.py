import json
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


class TestCloudflareWorkerEngine(unittest.TestCase):
    def _run_js(self, harness: str) -> dict:
        node_bin = shutil.which("node")
        jsc_bin = "/System/Library/Frameworks/JavaScriptCore.framework/Versions/Current/Helpers/jsc"
        preamble = """
        if (typeof globalThis.btoa === 'undefined') {
          globalThis.btoa = (s) => Buffer.from(String(s), 'binary').toString('base64');
        }
        if (typeof globalThis.atob === 'undefined') {
          globalThis.atob = (s) => Buffer.from(String(s), 'base64').toString('binary');
        }
        """
        with tempfile.NamedTemporaryFile(mode="w", suffix=".cjs", delete=False, encoding="utf-8") as tf:
            tf.write(preamble + "\n" + harness)
            tmp_path = tf.name
        try:
            cmd = [node_bin, tmp_path] if node_bin else [jsc_bin, tmp_path]
            proc = subprocess.run(cmd, cwd=ROOT_DIR, capture_output=True, text=True, check=True)
            return json.loads(proc.stdout.strip().splitlines()[-1])
        finally:
            if os.path.exists(tmp_path):
                os.remove(tmp_path)

    def test_worker_js_catalog_and_stats_engine(self):
        repo_path = os.path.join(ROOT_DIR, "cloudflare", "src", "repositories", "FirestoreRepository.js")
        svc_path = os.path.join(ROOT_DIR, "cloudflare", "src", "services", "LiveCatalogService.js")

        with open(repo_path, "r", encoding="utf-8") as f:
            repo_src = (
                f.read()
                .replace("export const STARTER_SHOWS", "const STARTER_SHOWS")
                .replace("export const STARTER_MOVIES", "const STARTER_MOVIES")
                .replace("export const STARTER_ACTORS", "const STARTER_ACTORS")
                .replace("export class FirestoreRepository", "class FirestoreRepository")
            )
        with open(svc_path, "r", encoding="utf-8") as f:
            svc_src = (
                f.read()
                .replace("export function formatEpisodeCode", "function formatEpisodeCode")
                .replace("export class LiveCatalogService", "class LiveCatalogService")
            )

        harness = (
            repo_src
            + "\n"
            + svc_src
            + """
            const code = formatEpisodeCode(2, 4);
            const repo = new FirestoreRepository({ FIREBASE_PROJECT_ID: 'demo-firebase-project' });
            const defState = repo.defaultState('demo_user');
            const populatedState = {
              ...defState,
              shows: [
                {
                  id: 'tvmaze_44933',
                  title: 'Severance',
                  runtimeMins: 50,
                  watchedEpisodes: ['S01E01', 'S01E02'],
                  episodes: [
                    { code: 'S01E01', season: 1, number: 1, runtimeMins: 57 },
                    { code: 'S01E02', season: 1, number: 2, runtimeMins: 47 }
                  ]
                }
              ],
              movies: [
                { id: 'movie_693134', title: 'Dune: Part Two', runtimeMins: 166, watched: true }
              ]
            };
            const stats = LiveCatalogService.computeStats(populatedState);
            const docUrl = repo.docUrlForUser('demo_user');
            const normalizedUs = LiveCatalogService.normalizeUsProvidersFromTmdb({ flatrate: [{ provider_id: 8, provider_name: 'Netflix' }, { provider_id: 9, provider_name: 'Amazon Prime Video' }] }, '');
            const out = JSON.stringify({ code, stats, docUrl, defaultShowsCount: defState.shows.length, defaultMoviesCount: defState.movies.length, normalizedUs, platformsCount: LiveCatalogService.US_STREAMING_PLATFORMS.length });
            if (typeof console !== 'undefined' && console.log) console.log(out);
            else if (typeof print !== 'undefined') print(out);
            """
        )

        out = self._run_js(harness)
        self.assertEqual(out["code"], "S02E04")
        self.assertIn("tvtime_state/user_state_demo_user", out["docUrl"])
        self.assertEqual(out["defaultShowsCount"], 0)
        self.assertEqual(out["defaultMoviesCount"], 0)
        self.assertEqual(out["normalizedUs"], "Netflix / Prime Video")
        self.assertEqual(out["platformsCount"], 8)
        self.assertGreater(out["stats"]["totalEpisodesWatched"], 0)
        self.assertGreater(out["stats"]["totalMoviesWatched"], 0)
        self.assertGreater(out["stats"]["totalHours"], 0)
        self.assertEqual(out["stats"]["totalSeriesCount"], 1)
        self.assertEqual(out["stats"]["totalMoviesCount"], 1)

    def test_web_app_view_client_js_syntax(self):
        view_path = os.path.join(ROOT_DIR, "cloudflare", "src", "ui", "WebAppView.js")
        with open(view_path, "r", encoding="utf-8") as f:
            raw_file = f.read()

        # Invariant 1: Zero backslashes in WebAppView.js (uses String.raw template literal)
        self.assertNotIn("\\", raw_file, "WebAppView.js uses String.raw and must not contain backslashes")

        view_src = raw_file.replace("export const WEB_APP_HTML", "const WEB_APP_HTML")
        self.assertNotIn("Search TV shows, movies, or actors", view_src)
        self.assertIn("Search TV shows or movies...", view_src)
        self.assertNotIn("Your Reaction Badge", view_src)
        self.assertIn("Series watch hours", view_src)
        self.assertIn("Movie watch hours", view_src)
        self.assertNotIn("carousel-series-watchlist", view_src)
        self.assertNotIn("carousel-movie-watchlist", view_src)
        self.assertIn("carousel-recent-series", view_src)
        self.assertIn("carousel-recent-movies", view_src)
        self.assertIn("hamburger-drawer", view_src)
        self.assertIn("openHamburgerMenu", view_src)
        self.assertIn("tab-swipe", view_src)
        self.assertIn("decideSwipeCard", view_src)
        self.assertIn("swipe-deck-container", view_src)
        self.assertIn("home-status-select", view_src)
        self.assertIn("search-sort-select", view_src)
        self.assertIn("swipe-filter-select", view_src)
        self.assertNotIn("All US", view_src)
        self.assertNotIn("btn-3d-toggle", view_src)
        self.assertNotIn("All Movies", view_src)
        self.assertIn("stat-series-count", view_src)
        self.assertIn("stat-episodes-count", view_src)
        self.assertIn("initBackNavigation", view_src)
        self.assertIn("initPullToRefresh", view_src)

        harness = (
            view_src
            + """
            const sStart = WEB_APP_HTML.lastIndexOf("<script>") + 8;
            const sEnd = WEB_APP_HTML.lastIndexOf("</script>");
            const clientJs = WEB_APP_HTML.slice(sStart, sEnd);
            new Function(clientJs);
            const out = JSON.stringify({ ok: true, len: WEB_APP_HTML.length });
            if (typeof console !== 'undefined' && console.log) console.log(out);
            else if (typeof print !== 'undefined') print(out);
            """
        )
        out = self._run_js(harness)
        self.assertTrue(out["ok"])
        self.assertGreater(out["len"], 50000)

    def test_web_app_behavioral_evals(self):
        """Executes TvTimeClient behavioral evals: status transitions, watchlist toggle-removal, back button history, and streaming filter labels."""
        view_path = os.path.join(ROOT_DIR, "cloudflare", "src", "ui", "WebAppView.js")
        with open(view_path, "r", encoding="utf-8") as f:
            view_src = f.read().replace("export const WEB_APP_HTML", "const WEB_APP_HTML")

        harness = (
            view_src
            + """
            const sStart = WEB_APP_HTML.lastIndexOf("<script>") + 8;
            const sEnd = WEB_APP_HTML.lastIndexOf("</script>");
            const clientJs = WEB_APP_HTML.slice(sStart, sEnd).replace("const app = new TvTimeClient();", "globalThis.TvTimeClientClass = TvTimeClient;");

            const elements = {};
            const getEl = (id) => {
              if (!elements[id]) {
                const classes = new Set(["hidden"]);
                elements[id] = {
                  id,
                  innerHTML: "",
                  textContent: "",
                  value: "",
                  className: "",
                  style: {},
                  classList: {
                    add: (...c) => c.forEach((x) => classes.add(x)),
                    remove: (...c) => c.forEach((x) => classes.delete(x)),
                    contains: (x) => classes.has(x),
                    toggle: (x, force) => {
                      if (force === undefined) {
                        if (classes.has(x)) classes.delete(x);
                        else classes.add(x);
                      } else if (force) classes.add(x);
                      else classes.delete(x);
                    }
                  },
                  addEventListener: () => {}
                };
              }
              return elements[id];
            };

            let popStateHandler = null;
            Object.defineProperty(globalThis, "window", {
              value: {
                location: { search: "" },
                history: {
                  replaceState: () => {},
                  pushState: () => {}
                },
                addEventListener: (evt, fn) => {
                  if (evt === "popstate") popStateHandler = fn;
                }
              },
              writable: true,
              configurable: true
            });
            Object.defineProperty(globalThis, "document", {
              value: {
                cookie: "",
                getElementById: getEl,
                querySelectorAll: () => [],
                addEventListener: () => {},
                scrollingElement: { scrollTop: 0 },
                documentElement: { scrollTop: 0 }
              },
              writable: true,
              configurable: true
            });
            Object.defineProperty(globalThis, "localStorage", {
              value: {
                getItem: () => null,
                setItem: () => {},
                removeItem: () => {}
              },
              writable: true,
              configurable: true
            });
            Object.defineProperty(globalThis, "fetch", {
              value: async () => ({ ok: true, json: async () => ({ results: [] }) }),
              writable: true,
              configurable: true
            });

            (new Function(clientJs))();
            const proto = globalThis.TvTimeClientClass.prototype;

            const dummy = Object.create(proto);
            dummy.userId = "testuser";
            dummy.userEmail = "testuser@gmail.com";
            dummy.activeTab = "home";
            dummy.tabHistory = [];
            dummy.homeSegment = "shows";
            dummy.mediaFilter = "in_progress";
            dummy.selectedUsPlatforms = new Set();
            dummy.usPlatforms = ["Netflix", "Prime Video", "Hulu", "Max", "Disney+", "Apple TV+", "Paramount+", "Peacock"];
            dummy.searchQuery = "";
            dummy.searchResults = [];
            dummy.recPool = [];
            dummy.swipeMediaFilter = "all";
            dummy.openSeasons = new Set();
            dummy.state = {
              shows: [
                {
                  id: "s1",
                  title: "Severance",
                  userStatus: "interested",
                  watchedEpisodes: [],
                  episodes: [
                    { code: "S01E01", season: 1, number: 1, title: "Ep 1" },
                    { code: "S01E02", season: 1, number: 2, title: "Ep 2" }
                  ]
                }
              ],
              movies: [
                { id: "m1", title: "Dune", watched: false }
              ],
              actors: [],
              decidedIds: []
            };

            // 1. Effective status transitions
            const statusInitial = dummy.getEffectiveShowStatus(dummy.state.shows[0]);
            const headerWatchlistHtml = dummy.renderShowStatusHeaderHtml(dummy.state.shows[0]);
            const watchlistCallsRemove = headerWatchlistHtml.includes("removeItemFromLibrary");

            dummy.state.shows[0].watchedEpisodes = ["S01E01"];
            const statusInProgress = dummy.getEffectiveShowStatus(dummy.state.shows[0]);

            dummy.state.shows[0].watchedEpisodes = ["S01E01", "S01E02"];
            const statusCompleted = dummy.getEffectiveShowStatus(dummy.state.shows[0]);

            // 2. Back navigation & popstate unwinding
            dummy.initBackNavigation();
            dummy.switchTab("stats");
            dummy.switchTab("settings");
            const tabBeforeBack = dummy.activeTab;
            dummy.goBack();
            const tabAfterFirstBack = dummy.activeTab;
            dummy.goBack();
            const tabAfterSecondBack = dummy.activeTab;

            // Open drawer and trigger popstate -> closes drawer without changing tab
            dummy.openHamburgerMenu();
            const drawerOpenBeforePop = !getEl("hamburger-drawer").classList.contains("hidden");
            if (popStateHandler) popStateHandler();
            const drawerOpenAfterPop = !getEl("hamburger-drawer").classList.contains("hidden");

            // 3. Streaming platform label formatting
            dummy.renderPlatformBars();
            const labelEmpty = getEl("home-us-platform-bar").innerHTML.includes(">Streaming<");
            dummy.selectedUsPlatforms.add("Netflix");
            dummy.renderPlatformBars();
            const labelSingle = getEl("home-us-platform-bar").innerHTML.includes(">Netflix<");
            dummy.selectedUsPlatforms.add("Max");
            dummy.renderPlatformBars();
            const labelMulti = getEl("home-us-platform-bar").innerHTML.includes(">Streaming (2)<");

            // 4. Watchlist movie removal when already in Watchlist
            dummy.setMovieStatus("m1", false);
            const moviesRemainingAfterWatchlistToggle = dummy.state.movies.length;

            // 5. Upcoming episodes strictly exclude past airDates and pick soonest future episode
            const pastShow = {
              id: "s_past",
              title: "Past Show",
              episodes: [{ code: "S01E01", season: 1, number: 1, title: "Old", airDate: "2021-05-01" }]
            };
            const futureShow = {
              id: "s_future",
              title: "Future Show",
              episodes: [
                { code: "S01E01", season: 1, number: 1, title: "Past Ep", airDate: "2024-01-01" },
                { code: "S01E03", season: 1, number: 3, title: "Later Ep", airDate: "2026-11-25" },
                { code: "S01E02", season: 1, number: 2, title: "Soon Ep", airDate: "2026-11-12" }
              ]
            };
            const pastUpcoming = dummy.findNextUpcomingEpisode(pastShow, "2026-11-09");
            const futureUpcoming = dummy.findNextUpcomingEpisode(futureShow, "2026-11-09");

            // 6. Search quick-actions (+ Watchlist and ✓ Completed)
            dummy.searchResults = [
              { id: "m_search_1", mediaType: "movie", title: "Search Movie", year: 2025, rating: 8.1 }
            ];
            dummy.quickMarkSearchCompleted("m_search_1");

            // 7. 10-Year Session Token persistence & validation
            const tenYearExp = Math.floor(Date.now() / 1000) + 315360000;
            const fakeTenYearPayload = btoa(JSON.stringify({ email: "user@example.com", exp: tenYearExp }));
            const fakeTenYearToken = "tvtime_session." + fakeTenYearPayload + ".fakesig";
            dummy.persistAuthSession(fakeTenYearToken, "user@example.com", "user");
            const tenYearValid = dummy.isGoogleTokenValid();

            const out = JSON.stringify({
              statusInitial,
              watchlistCallsRemove,
              statusInProgress,
              statusCompleted,
              tabBeforeBack,
              tabAfterFirstBack,
              tabAfterSecondBack,
              drawerOpenBeforePop,
              drawerOpenAfterPop,
              labelEmpty,
              labelSingle,
              labelMulti,
              moviesRemainingAfterWatchlistToggle,
              pastUpcomingIsNull: pastUpcoming === null,
              futureUpcomingCode: futureUpcoming ? futureUpcoming.ep.code : "",
              futureUpcomingBucket: futureUpcoming ? futureUpcoming.bucket : "",
              tenYearValid
            });
            if (typeof console !== 'undefined' && console.log) console.log(out);
            else if (typeof print !== 'undefined') print(out);
            """
        )
        out = self._run_js(harness)
        self.assertEqual(out["statusInitial"], "interested")
        self.assertTrue(out["watchlistCallsRemove"])
        self.assertEqual(out["statusInProgress"], "in_progress")
        self.assertEqual(out["statusCompleted"], "completed")
        self.assertEqual(out["tabBeforeBack"], "settings")
        self.assertEqual(out["tabAfterFirstBack"], "stats")
        self.assertEqual(out["tabAfterSecondBack"], "home")
        self.assertTrue(out["drawerOpenBeforePop"])
        self.assertFalse(out["drawerOpenAfterPop"])
        self.assertTrue(out["labelEmpty"])
        self.assertTrue(out["labelSingle"])
        self.assertTrue(out["labelMulti"])
        self.assertEqual(out["moviesRemainingAfterWatchlistToggle"], 0)
        self.assertTrue(out["pastUpcomingIsNull"])
        self.assertEqual(out["futureUpcomingCode"], "S01E02")
        self.assertEqual(out["futureUpcomingBucket"], "In 3 days")
        self.assertTrue(out["tenYearValid"])

    def test_android_architecture_and_best_practices_evals(self):
        """Verifies Android best practices across MainActivity.kt, TvTimeMainScreen.kt, and TvTimeDialogs.kt."""
        main_act_path = os.path.join(
            ROOT_DIR, "android", "app", "src", "main", "java", "com", "tvtime", "tracker", "MainActivity.kt"
        )
        main_screen_path = os.path.join(
            ROOT_DIR, "android", "app", "src", "main", "java", "com", "tvtime", "tracker", "ui", "TvTimeMainScreen.kt"
        )
        dialogs_path = os.path.join(
            ROOT_DIR, "android", "app", "src", "main", "java", "com", "tvtime", "tracker", "ui", "TvTimeDialogs.kt"
        )

        with open(main_act_path, "r", encoding="utf-8") as f:
            main_act = f.read()
        with open(main_screen_path, "r", encoding="utf-8") as f:
            main_screen = f.read()
        with open(dialogs_path, "r", encoding="utf-8") as f:
            dialogs = f.read()

        # 1. Explicit Dark SystemBarStyle in enableEdgeToEdge & Share Target intent support
        self.assertIn("SystemBarStyle.dark", main_act)
        self.assertIn("Intent.ACTION_SEND", main_act)
        self.assertIn("onNewIntent", main_act)

        # 2. BackHandler, tabBackStack, rememberSaveable, statusBarsPadding, pullToRefresh, ImeAction.Search
        self.assertIn("BackHandler(enabled = canInterceptBack)", main_screen)
        self.assertIn("rememberSaveable", main_screen)
        self.assertIn(".statusBarsPadding()", main_screen)
        self.assertIn("pullToRefreshNestedScroll", main_screen)
        self.assertIn("ImeAction.Search", main_screen)
        self.assertIn("Streaming ▾", main_screen)

        # 3. Scrollable detail sheets & Watchlist toggle-removal in TvTimeDialogs.kt
        self.assertIn("verticalScroll(rememberScrollState())", dialogs)
        self.assertIn("dao.deleteShow(", dialogs)
        self.assertIn("dao.deleteMovie(", dialogs)

        # 4. Friends library scroll-position preservation & Android crash/lag guards
        self.assertIn("key = { row -> row.stableKey }", main_screen)
        self.assertNotIn("preparedLibrary = preparedLibrary.copy(isComputing = true)", main_screen)
        with open(os.path.join(ROOT_DIR, "android", "app", "src", "main", "java", "com", "tvtime", "tracker", "ui", "TvTimeComponents.kt"), "r", encoding="utf-8") as f:
            components_kt = f.read()
        with open(os.path.join(ROOT_DIR, "android", "app", "src", "main", "java", "com", "tvtime", "tracker", "data", "FirestoreSyncService.kt"), "r", encoding="utf-8") as f:
            sync_kt = f.read()
        self.assertIn("Bitmap.Config.RGB_565", components_kt)
        self.assertIn("inSampleSize", components_kt)
        self.assertIn("dao.replaceShowsForUser(", sync_kt)
        self.assertIn("dao.replaceMoviesForUser(", sync_kt)

        # 4. Friends library must be virtualized via LazyColumn itemsIndexed + off-UI-thread Dispatchers.Default indexing (no ANR!)
        self.assertNotIn("filteredFriendMovies.forEach", main_screen)
        self.assertNotIn("filteredFriendShows.forEach", main_screen)
        self.assertIn("PreparedFriendLibraryState", main_screen)
        self.assertIn("withContext(Dispatchers.Default)", main_screen)
        models_path = os.path.join(
            ROOT_DIR, "android", "app", "src", "main", "java", "com", "tvtime", "tracker", "domain", "TvTimeModels.kt"
        )
        with open(models_path, "r", encoding="utf-8") as f:
            models_src = f.read()
        self.assertIn("PAREN_GROUP_REGEX", models_src)
        self.assertIn("NON_ALNUM_REGEX", models_src)

    def test_local_python_server_helpers(self):
        import server

        st = server.default_user_state("demo_user")
        self.assertEqual(st["userId"], "demo_user")
        self.assertEqual(len(st["shows"]), 0)
        self.assertEqual(len(st["movies"]), 0)
        st["shows"] = [
            {
                "id": "tvmaze_44933",
                "tvmazeId": 44933,
                "title": "Severance",
                "runtimeMins": 50,
                "watchedEpisodes": ["S01E01"],
                "episodes": [{"code": "S01E01", "runtimeMins": 57}],
            }
        ]
        st["movies"] = [{"id": "movie_1", "title": "Dune", "runtimeMins": 166, "watched": True}]
        stats = server.compute_stats(st)
        html = server.load_web_app_html()
        self.assertTrue(html.startswith("<!DOCTYPE html>"))
        self.assertNotIn("export const WEB_APP_HTML", html)
        self.assertGreater(stats["totalMins"], 200)

    def test_all_35_seasons_batched_fetch_and_backfill(self):
        """Verifies 35+ season shows return Seasons 1..35 via batched TMDB append_to_response and backfill previously truncated library shows."""
        svc_path = os.path.join(ROOT_DIR, "cloudflare", "src", "services", "LiveCatalogService.js")
        view_path = os.path.join(ROOT_DIR, "cloudflare", "src", "ui", "WebAppView.js")
        with open(svc_path, "r", encoding="utf-8") as f:
            svc_src = (
                f.read()
                .replace("export function formatEpisodeCode", "function formatEpisodeCode")
                .replace("export class LiveCatalogService", "class LiveCatalogService")
            )
        with open(view_path, "r", encoding="utf-8") as f:
            view_src = f.read().replace("export const WEB_APP_HTML", "const WEB_APP_HTML")

        harness = (
            svc_src
            + "\n"
            + view_src
            + """
            (async () => {
              let fetchCallCount = 0;
              const mockFetch = async (url) => {
                fetchCallCount++;
                const rawUrl = String(url || "");
                const m = rawUrl.match(/append_to_response=([^&]+)/);
                const append = m ? decodeURIComponent(m[1]) : "";
                if (append.includes("credits")) {
                  return {
                    ok: true,
                    json: async () => ({
                      id: 2734,
                      name: "Law & Order: Special Victims Unit",
                      first_air_date: "1999-09-20",
                      seasons: Array.from({ length: 36 }, (_, idx) => ({ season_number: idx })),
                      episode_run_time: [43],
                      credits: { cast: [{ name: "Mariska Hargitay" }] }
                    })
                  };
                }
                if (append.startsWith("season/")) {
                  const obj = {};
                  for (const part of append.split(",")) {
                    const sNum = Number(part.split("/")[1]);
                    obj[part] = {
                      season_number: sNum,
                      episodes: [{ episode_number: 1, name: "Premiere S" + sNum, air_date: "2000-01-01", runtime: 43 }]
                    };
                  }
                  return { ok: true, json: async () => obj };
                }
                return { ok: false };
              };

              const fullShow = await LiveCatalogService.fetchFullShowFromTvMaze("tmdb_tv_2734", {
                fetchImpl: mockFetch,
                env: { TMDB_API_KEY: "test_key" }
              });
              const seasonsReturned = Array.from(new Set(fullShow.episodes.map((e) => e.season)));

              // Verify WebAppView openItemById backfills a show previously cached with only Seasons 17..35
              const sStart = WEB_APP_HTML.lastIndexOf("<script>") + 8;
              const sEnd = WEB_APP_HTML.lastIndexOf("</script>");
              const clientJs = WEB_APP_HTML.slice(sStart, sEnd).replace("const app = new TvTimeClient();", "globalThis.TvTimeClientClass = TvTimeClient;");
              const els = {};
              const makeEl = (id) => {
                if (!els[id]) {
                  els[id] = {
                    id,
                    innerHTML: "",
                    className: "",
                    classList: { add: () => {}, remove: () => {}, contains: () => false }
                  };
                }
                return els[id];
              };
              Object.defineProperty(globalThis, "window", {
                value: { location: { search: "" }, history: { replaceState: () => {}, pushState: () => {} }, addEventListener: () => {} },
                writable: true,
                configurable: true
              });
              Object.defineProperty(globalThis, "document", {
                value: { cookie: "", getElementById: makeEl, querySelectorAll: () => [], addEventListener: () => {} },
                writable: true,
                configurable: true
              });
              Object.defineProperty(globalThis, "localStorage", {
                value: { getItem: () => null, setItem: () => {}, removeItem: () => {} },
                writable: true,
                configurable: true
              });
              Object.defineProperty(globalThis, "setTimeout", {
                value: (fn) => { fn(); return 1; },
                writable: true,
                configurable: true
              });
              Object.defineProperty(globalThis, "fetch", {
                value: async () => ({ ok: true, json: async () => fullShow }),
                writable: true,
                configurable: true
              });

              (new Function(clientJs))();
              const dummy = Object.create(globalThis.TvTimeClientClass.prototype);
              dummy.openSeasons = new Set();
              dummy.tabHistory = [];
              dummy.persistState = async () => {};
              dummy.state = {
                shows: [
                  {
                    id: "tmdb_tv_2734",
                    tmdbId: 2734,
                    title: "Law & Order: Special Victims Unit",
                    userStatus: "in_progress",
                    watchedEpisodes: ["S17E01"],
                    episodes: Array.from({ length: 19 }, (_, idx) => ({
                      code: "S" + String(17 + idx).padStart(2, "0") + "E01",
                      season: 17 + idx,
                      number: 1,
                      title: "Ep 1"
                    }))
                  }
                ],
                movies: []
              };

              await dummy.openItemById("tmdb_tv_2734");
              await Promise.resolve();
              await Promise.resolve();
              await Promise.resolve();
              const backfilledSeasons = Array.from(new Set(dummy.state.shows[0].episodes.map((e) => e.season)));
              const hasSeason1Html = makeEl("detail-body").innerHTML.includes("Season 1<");
              const hasSeason35Html = makeEl("detail-body").innerHTML.includes("Season 35<");

              const out = JSON.stringify({
                fetchCallCount,
                firstSeason: seasonsReturned[0],
                lastSeason: seasonsReturned[seasonsReturned.length - 1],
                seasonCount: seasonsReturned.length,
                backfilledFirst: backfilledSeasons[0],
                backfilledCount: backfilledSeasons.length,
                hasSeason1Html,
                hasSeason35Html
              });
              if (typeof console !== "undefined" && console.log) console.log(out);
              else if (typeof print !== "undefined") print(out);
            })();
            """
        )
        out = self._run_js(harness)
        self.assertEqual(out["fetchCallCount"], 3)
        self.assertEqual(out["firstSeason"], 1)
        self.assertEqual(out["lastSeason"], 35)
        self.assertEqual(out["seasonCount"], 35)
        self.assertEqual(out["backfilledFirst"], 1)
        self.assertEqual(out["backfilledCount"], 35)
        self.assertTrue(out["hasSeason1Html"])
        self.assertTrue(out["hasSeason35Html"])

    def test_actor_search_returns_actor_movies_without_actor_items(self):
        """Verifies searching an actor name returns movies of that actor and zero actor items."""
        svc_path = os.path.join(ROOT_DIR, "cloudflare", "src", "services", "LiveCatalogService.js")
        with open(svc_path, "r", encoding="utf-8") as f:
            svc_src = (
                f.read()
                .replace("export function formatEpisodeCode", "function formatEpisodeCode")
                .replace("export class LiveCatalogService", "class LiveCatalogService")
            )

        harness = (
            svc_src
            + """
            (async () => {
              const mockFetch = async (url) => {
                const rawUrl = String(url || "");
                if (rawUrl.includes("/search/multi")) {
                  return {
                    ok: true,
                    json: async () => ({
                      results: [
                        { id: 6193, media_type: "person", name: "Leonardo DiCaprio", popularity: 95.0 }
                      ]
                    })
                  };
                }
                if (rawUrl.includes("/person/6193/combined_credits")) {
                  return {
                    ok: true,
                    json: async () => ({
                      cast: [
                        { id: 27205, media_type: "movie", title: "Inception", release_date: "2010-07-16", popularity: 120, vote_count: 35000, vote_average: 8.4, genre_ids: [28, 878], character: "Dom Cobb", poster_path: "/inc.jpg", overview: "Dream heist." },
                        { id: 100088, media_type: "tv", name: "The Last of Us", first_air_date: "2023-01-15", popularity: 115, vote_count: 5200, vote_average: 8.6, genre_ids: [18], episode_count: 16, character: "Joel Miller", poster_path: "/tlou.jpg", overview: "Post-apocalyptic journey." },
                        { id: 597, media_type: "movie", title: "Titanic", release_date: "1997-11-18", popularity: 110, vote_count: 24000, vote_average: 7.9, genre_ids: [18, 10749], character: "Jack Dawson", poster_path: "/tit.jpg", overview: "Ocean liner." },
                        { id: 2224, media_type: "tv", name: "The Tonight Show", first_air_date: "2014-02-17", popularity: 95, vote_count: 800, vote_average: 6.2, genre_ids: [10767], episode_count: 2, character: "Self - Guest", poster_path: "/tonight.jpg", overview: "Late night talk." },
                        { id: 999999, media_type: "movie", title: "Archive Self Short", release_date: "2020-01-01", popularity: 5, vote_count: 12, vote_average: 6.0, genre_ids: [99], character: "Self (archive footage)", overview: "Doc." }
                      ]
                    })
                  };
                }
                return { ok: true, json: async () => [] };
              };

              const res = await LiveCatalogService.searchMulti("Leonardo DiCaprio", {
                fetchImpl: mockFetch,
                env: { TMDB_API_KEY: "test_key" }
              });
              const out = JSON.stringify({
                count: res.results.length,
                hasActorItems: res.results.some((r) => r.mediaType === "actor"),
                mediaTypes: res.results.map((r) => r.mediaType),
                titles: res.results.map((r) => r.title),
                firstMovieDirector: (res.results.find((r) => r.mediaType === "movie") || {}).director || "",
                tvShowOverview: (res.results.find((r) => r.mediaType === "show") || {}).overview || "",
                firstCast: res.results[0] ? res.results[0].cast : []
              });
              if (typeof console !== "undefined" && console.log) console.log(out);
              else if (typeof print !== "undefined") print(out);
            })();
            """
        )
        out = self._run_js(harness)
        self.assertFalse(out["hasActorItems"])
        self.assertEqual(out["titles"], ["The Last of Us", "Inception", "Titanic"])
        self.assertEqual(out["mediaTypes"], ["show", "movie", "movie"])
        self.assertEqual(out["firstMovieDirector"], "Starring Leonardo DiCaprio")
        self.assertIn("Starring Leonardo DiCaprio", out["tvShowOverview"])
        self.assertEqual(out["firstCast"], ["Leonardo DiCaprio"])

    def test_search_quick_complete_persists_immediately_and_survives_research_and_compaction(self):
        """Verifies marking completed in search saves immediately, survives re-search/cloning, and compacts cleanly in Firestore."""
        view_path = os.path.join(ROOT_DIR, "cloudflare", "src", "ui", "WebAppView.js")
        repo_path = os.path.join(ROOT_DIR, "cloudflare", "src", "repositories", "FirestoreRepository.js")
        with open(repo_path, "r", encoding="utf-8") as f:
            repo_src = (
                f.read()
                .replace("export const STARTER_SHOWS", "const STARTER_SHOWS")
                .replace("export const STARTER_MOVIES", "const STARTER_MOVIES")
                .replace("export const STARTER_ACTORS", "const STARTER_ACTORS")
                .replace("export class FirestoreRepository", "class FirestoreRepository")
            )
        with open(view_path, "r", encoding="utf-8") as f:
            view_src = f.read().replace("export const WEB_APP_HTML", "const WEB_APP_HTML")

        harness = (
            repo_src
            + "\n"
            + view_src
            + """
            (async () => {
              const sStart = WEB_APP_HTML.lastIndexOf("<script>") + 8;
              const sEnd = WEB_APP_HTML.lastIndexOf("</script>");
              const clientJs = WEB_APP_HTML.slice(sStart, sEnd).replace("const app = new TvTimeClient();", "globalThis.TvTimeClientClass = TvTimeClient;");
              const els = {};
              const makeEl = (id) => {
                if (!els[id]) {
                  els[id] = {
                    id,
                    innerHTML: "",
                    textContent: "",
                    value: "",
                    className: "",
                    style: {},
                    classList: { add: () => {}, remove: () => {}, toggle: () => {}, contains: () => true }
                  };
                }
                return els[id];
              };
              Object.defineProperty(globalThis, "window", {
                value: { location: { search: "" }, history: { replaceState: () => {}, pushState: () => {} }, addEventListener: () => {} },
                writable: true,
                configurable: true
              });
              Object.defineProperty(globalThis, "document", {
                value: { cookie: "", getElementById: makeEl, querySelectorAll: () => [], addEventListener: () => {} },
                writable: true,
                configurable: true
              });
              const storage = {};
              Object.defineProperty(globalThis, "localStorage", {
                value: {
                  getItem: (k) => storage[k] || null,
                  setItem: (k, v) => { storage[k] = String(v); },
                  removeItem: (k) => { delete storage[k]; }
                },
                writable: true,
                configurable: true
              });
              Object.defineProperty(globalThis, "setTimeout", {
                value: (fn) => { fn(); return 1; },
                writable: true,
                configurable: true
              });
              Object.defineProperty(globalThis, "clearTimeout", {
                value: () => {},
                writable: true,
                configurable: true
              });

              let savedPayloads = [];
              let resolveShowDetails = null;
              const detailsGate = new Promise((r) => { resolveShowDetails = r; });
              const himymFullEpisodes = Array.from({ length: 208 }, (_, idx) => {
                const sNum = Math.floor(idx / 24) + 1;
                const eNum = (idx % 24) + 1;
                return {
                  code: "S" + String(sNum).padStart(2, "0") + "E" + String(eNum).padStart(2, "0"),
                  season: sNum,
                  number: eNum,
                  title: "Episode " + (idx + 1),
                  airDate: "2006-01-01",
                  runtimeMins: 22
                };
              });

              Object.defineProperty(globalThis, "fetch", {
                value: async (url, opts) => {
                  const u = String(url || "");
                  if (u === "/api/state" && opts && opts.method === "POST") {
                    savedPayloads.push(JSON.parse(opts.body));
                    return { ok: true, json: async () => ({ ok: true }) };
                  }
                  if (u.includes("/api/show-details")) {
                    await detailsGate;
                    return {
                      ok: true,
                      json: async () => ({
                        id: "tmdb_tv_1100",
                        tmdbId: 1100,
                        title: "How I Met Your Mother",
                        episodes: himymFullEpisodes
                      })
                    };
                  }
                  return { ok: false };
                },
                writable: true,
                configurable: true
              });

              (new Function(clientJs))();
              const dummy = Object.create(globalThis.TvTimeClientClass.prototype);
              dummy.userId = "tester";
              dummy.userEmail = "tester@example.com";
              dummy.googleIdToken = "tok";
              dummy.activeTab = "search";
              dummy.homeSegment = "shows";
              dummy.mediaFilter = "in_progress";
              dummy.selectedUsPlatforms = new Set();
              dummy.usPlatforms = ["Netflix", "Prime Video", "Hulu", "Max", "Disney+", "Apple TV+", "Paramount+", "Peacock"];
              dummy.swipeMediaFilter = "all";
              dummy.searchSort = "top";
              dummy.searchQuery = "How I Met Your Mother";
              dummy.searchItemCache = new Map();
              dummy._searchReqSeq = 0;
              dummy._persistPromise = null;
              dummy._persistNeedsRerun = false;
              dummy._lastLocalMutationAt = 0;
              dummy._sessionRemovedKeys = new Set();
              dummy.openSeasons = new Set();
              dummy.tabHistory = [];
              dummy.recPool = [];
              dummy.state = { userId: "tester", email: "tester@example.com", shows: [], movies: [], actors: [], decidedIds: [], friends: [], incomingFriendRequests: [], outgoingFriendRequests: [] };

              const initialResults = [
                { id: "tmdb_tv_1100", tmdbId: 1100, mediaType: "show", title: "How I Met Your Mother", year: 2005, rating: 8.2 }
              ];
              dummy.cacheSearchItems(initialResults);
              dummy.searchResults = initialResults;
              dummy.renderSearch();

              // Start quickMarkSearchCompleted - details fetch is still pending!
              const completePromise = dummy.quickMarkSearchCompleted("tmdb_tv_1100");
              await Promise.resolve();
              await Promise.resolve();
              await Promise.resolve();

              const savedBeforeDetailsCount = savedPayloads.length;
              const savedBeforeDetailsStatus = savedBeforeDetailsCount > 0 ? savedPayloads[0].shows[0].userStatus : "";

              // User immediately searches again (even if result ID differs slightly or punctuation varies) while details are still loading
              dummy.deduplicateState();
              dummy.searchQuery = "how i met your mother";
              dummy.searchResults = [
                { id: "tvmaze_171", tvmazeId: 171, tmdbId: 1100, mediaType: "show", title: "How I Met Your Mother", year: 2005, rating: 8.2 }
              ];
              dummy.cacheSearchItems(dummy.searchResults);
              dummy.renderSearch();
              const tickedDuringResearchHtml = makeEl("search-results-list").innerHTML.includes("Completed (Tap to mark unwatched)");

              // Now let show-details resolve with 208 episodes and ensure liveShow (not detached!) gets all 208 episodes
              resolveShowDetails();
              await completePromise;

              const finalSavedShow = savedPayloads[savedPayloads.length - 1].shows[0];
              dummy.renderSearch();
              const tickedAfterDetailsHtml = makeEl("search-results-list").innerHTML.includes("Completed (Tap to mark unwatched)");

              // Test FirestoreRepository >550KB compaction and full 208-episode watched reconstruction
              let storedFirestoreDoc = null;
              Object.defineProperty(globalThis, "fetch", {
                value: async (fUrl, fOpts) => {
                  if (fOpts && fOpts.method === "PATCH") {
                    storedFirestoreDoc = JSON.parse(fOpts.body);
                    return { ok: true, json: async () => storedFirestoreDoc };
                  }
                  if (storedFirestoreDoc) {
                    return { ok: true, json: async () => storedFirestoreDoc };
                  }
                  return { ok: false };
                },
                writable: true,
                configurable: true
              });
              const repo = new FirestoreRepository({ FIREBASE_PROJECT_ID: "demo-test-proj" });
              const largeFillerShows = Array.from({ length: 120 }, (_, i) => ({
                id: "filler_" + i,
                title: "Filler Series " + i,
                userStatus: "completed",
                watchedEpisodes: himymFullEpisodes.slice(0, 40).map((e) => e.code),
                episodes: himymFullEpisodes.slice(0, 40),
                overview: "A".repeat(400)
              }));
              await repo.saveUserState("tester", {
                userId: "tester",
                shows: [finalSavedShow, ...largeFillerShows],
                movies: []
              });
              const compactedPayloadStr = storedFirestoreDoc.fields.payload.stringValue;
              const compactedParsed = JSON.parse(compactedPayloadStr);
              const compactedHimym = compactedParsed.shows.find((s) => s.title === "How I Met Your Mother");
              const reloaded = await repo.getUserState("tester");
              const reloadedHimym = reloaded.shows.find((s) => s.title === "How I Met Your Mother");

              const out = JSON.stringify({
                savedBeforeDetailsCount,
                savedBeforeDetailsStatus,
                tickedDuringResearchHtml,
                tickedAfterDetailsHtml,
                finalSaveCount: savedPayloads.length,
                finalEpCount: finalSavedShow.episodes.length,
                finalTotalEpisodes: finalSavedShow.totalEpisodes,
                finalWatchedCount: finalSavedShow.watchedEpisodes.length,
                compactedByteLen: compactedPayloadStr.length,
                compactedEpLen: compactedHimym.episodes.length,
                compactedTotalEpisodes: compactedHimym.totalEpisodes,
                compactedWatchedLen: compactedHimym.watchedEpisodes.length,
                reloadedTotalEpisodes: reloadedHimym.totalEpisodes,
                reloadedWatchedLen: reloadedHimym.watchedEpisodes.length,
                reloadedStatus: reloadedHimym.userStatus
              });
              if (typeof console !== "undefined" && console.log) console.log(out);
              else if (typeof print !== "undefined") print(out);
            })();
            """
        )
        out = self._run_js(harness)
        self.assertGreaterEqual(out["savedBeforeDetailsCount"], 1)
        self.assertEqual(out["savedBeforeDetailsStatus"], "completed")
        self.assertTrue(out["tickedDuringResearchHtml"])
        self.assertTrue(out["tickedAfterDetailsHtml"])
        self.assertGreaterEqual(out["finalSaveCount"], 2)
        self.assertEqual(out["finalEpCount"], 0)
        self.assertEqual(out["finalTotalEpisodes"], 208)
        self.assertEqual(out["finalWatchedCount"], 0)
        self.assertLess(out["compactedByteLen"], 35000)
        self.assertEqual(out["compactedEpLen"], 0)
        self.assertEqual(out["compactedTotalEpisodes"], 208)
        self.assertEqual(out["compactedWatchedLen"], 0)
        self.assertEqual(out["reloadedTotalEpisodes"], 208)
        self.assertEqual(out["reloadedWatchedLen"], 0)
        self.assertEqual(out["reloadedStatus"], "completed")

    def test_ongoing_series_caught_up_completed_and_returns_to_in_progress_when_next_episode_airs(self):
        """Verifies ongoing series goes to completed while caught up, stays in Upcoming, and flips back to in_progress when next episode airs."""
        repo_path = os.path.join(ROOT_DIR, "cloudflare", "src", "repositories", "FirestoreRepository.js")
        view_path = os.path.join(ROOT_DIR, "cloudflare", "src", "ui", "WebAppView.js")
        with open(repo_path, "r", encoding="utf-8") as f:
            repo_src = (
                f.read()
                .replace("export const STARTER_SHOWS", "const STARTER_SHOWS")
                .replace("export const STARTER_MOVIES", "const STARTER_MOVIES")
                .replace("export const STARTER_ACTORS", "const STARTER_ACTORS")
                .replace("export class FirestoreRepository", "class FirestoreRepository")
            )
        with open(view_path, "r", encoding="utf-8") as f:
            view_src = f.read().replace("export const WEB_APP_HTML", "const WEB_APP_HTML")

        harness = (
            repo_src
            + "\n"
            + view_src
            + """
            (async () => {
              const sStart = WEB_APP_HTML.lastIndexOf("<script>") + 8;
              const sEnd = WEB_APP_HTML.lastIndexOf("</script>");
              const clientJs = WEB_APP_HTML.slice(sStart, sEnd).replace("const app = new TvTimeClient();", "globalThis.TvTimeClientClass = TvTimeClient;");
              (new Function(clientJs))();
              const app = Object.create(globalThis.TvTimeClientClass.prototype);
              const ongoingShow = {
                id: "tmdb_tv_95396",
                tmdbId: 95396,
                title: "Severance",
                year: 2022,
                status: "Returning Series",
                userStatus: "in_progress",
                watchedEpisodes: ["S01E01", "S01E02", "S01E03"],
                episodes: [
                  { code: "S01E01", season: 1, number: 1, title: "Ep 1", airDate: "2025-01-01" },
                  { code: "S01E02", season: 1, number: 2, title: "Ep 2", airDate: "2025-01-08" },
                  { code: "S01E03", season: 1, number: 3, title: "Ep 3", airDate: "2025-01-15" },
                  { code: "S01E04", season: 1, number: 4, title: "Future Ep", airDate: "2099-05-01" }
                ]
              };
              const statusBeforeAir = app.getEffectiveShowStatus(ongoingShow, "2026-11-21");
              const progBeforeAir = app.computeShowProgress(ongoingShow, "2026-11-21");
              const compacted = FirestoreRepository.compactShowForStorage(ongoingShow);
              const upcomingBeforeAir = app.findNextUpcomingEpisode(compacted, "2026-11-21");
              const statusCompactedBeforeAir = app.getEffectiveShowStatus(compacted, "2026-11-21");
              const statusCompactedOnAirDay = app.getEffectiveShowStatus(compacted, "2099-05-01");
              const progOnAirDay = app.computeShowProgress(compacted, "2099-05-01");

              const out = JSON.stringify({
                statusBeforeAir,
                watchedBeforeAir: progBeforeAir.watchedCount,
                totalBeforeAir: progBeforeAir.total,
                compactedStatus: compacted.userStatus,
                compactedTotal: compacted.totalEpisodes,
                compactedEpsLen: compacted.episodes.length,
                upcomingCode: upcomingBeforeAir ? upcomingBeforeAir.ep.code : null,
                statusCompactedBeforeAir,
                statusCompactedOnAirDay,
                nextEpOnAirDay: progOnAirDay.nextEp ? progOnAirDay.nextEp.code : null,
                leftOnAirDay: progOnAirDay.leftCount
              });
              if (typeof console !== "undefined" && console.log) console.log(out);
              else if (typeof print !== "undefined") print(out);
            })();
            """
        )
        out = self._run_js(harness)
        self.assertEqual(out["statusBeforeAir"], "completed")
        self.assertEqual(out["watchedBeforeAir"], 3)
        self.assertEqual(out["totalBeforeAir"], 3)
        self.assertEqual(out["compactedStatus"], "completed")
        self.assertEqual(out["compactedTotal"], 3)
        self.assertEqual(out["compactedEpsLen"], 1)
        self.assertEqual(out["upcomingCode"], "S01E04")
        self.assertEqual(out["statusCompactedBeforeAir"], "completed")
        self.assertEqual(out["statusCompactedOnAirDay"], "in_progress")
        self.assertEqual(out["nextEpOnAirDay"], "S01E04")
        self.assertEqual(out["leftOnAirDay"], 1)

    def test_50_season_window_and_pass2_ongoing_compaction_and_dad_isolation(self):
        """Verifies 50-season windowed progress/uncheck, Pass-2 completed+future compaction stability, and Dad strict matching."""
        repo_path = os.path.join(ROOT_DIR, "cloudflare", "src", "repositories", "FirestoreRepository.js")
        view_path = os.path.join(ROOT_DIR, "cloudflare", "src", "ui", "WebAppView.js")
        with open(repo_path, "r", encoding="utf-8") as f:
            repo_src = (
                f.read()
                .replace("export const STARTER_SHOWS", "const STARTER_SHOWS")
                .replace("export const STARTER_MOVIES", "const STARTER_MOVIES")
                .replace("export const STARTER_ACTORS", "const STARTER_ACTORS")
                .replace("export class FirestoreRepository", "class FirestoreRepository")
            )
        with open(view_path, "r", encoding="utf-8") as f:
            view_src = f.read().replace("export const WEB_APP_HTML", "const WEB_APP_HTML")

        harness = (
            repo_src
            + "\n"
            + view_src
            + """
            (async () => {
              const sStart = WEB_APP_HTML.lastIndexOf("<script>") + 8;
              const sEnd = WEB_APP_HTML.lastIndexOf("</script>");
              const clientJs = WEB_APP_HTML.slice(sStart, sEnd).replace("const app = new TvTimeClient();", "globalThis.TvTimeClientClass = TvTimeClient;");
              (new Function(clientJs))();
              const app = Object.create(globalThis.TvTimeClientClass.prototype);

              // 1. 50-season x 25-ep (1,250 eps) show watched through S12E07 (282 watched)
              const full50 = [];
              const watched50 = [];
              for (let s = 1; s <= 50; s++) {
                for (let e = 1; e <= 25; e++) {
                  const code = "S" + String(s).padStart(2, "0") + "E" + String(e).padStart(2, "0");
                  full50.push({ code, season: s, number: e, title: "Episode " + e, airDate: "2010-01-01", runtimeMins: 42 });
                  if (s < 12 || (s === 12 && e <= 7)) watched50.push(code);
                }
              }
              const comp50 = FirestoreRepository.compactShowForStorage({
                id: "tmdb_tv_2734",
                tmdbId: 2734,
                title: "50 Season Drama",
                year: 1999,
                status: "Returning Series",
                userStatus: "in_progress",
                watchedEpisodes: watched50,
                episodes: full50
              });
              const prog50 = app.computeShowProgress(comp50, "2026-11-21");

              // Uncheck S05E03 from full50 and re-compact -> must remain 281/1250 in_progress
              const watchedAfterUncheck = watched50.filter((c) => c !== "S05E03");
              const recomp50 = app.compactShowForStorage({
                ...comp50,
                episodes: full50,
                completedSeasons: {},
                watchedEpisodes: watchedAfterUncheck,
                userStatus: "in_progress"
              });
              const progAfterUncheck = app.computeShowProgress(recomp50, "2026-11-21");
              const effAfterUncheck = app.getEffectiveShowStatus(recomp50, "2026-11-21");

              // 2. Pass-2 compaction & deduplication of completed ongoing show with 1 future episode
              const ongoingRaw = {
                id: "tmdb_tv_95557",
                tmdbId: 95557,
                title: "Invincible",
                year: 2021,
                status: "Returning Series",
                userStatus: "completed",
                watchedEpisodes: Array.from({ length: 24 }, (_, i) => "S01E" + String(i + 1).padStart(2, "0")),
                episodes: [
                  ...Array.from({ length: 24 }, (_, i) => ({
                    code: "S01E" + String(i + 1).padStart(2, "0"),
                    season: 1,
                    number: i + 1,
                    title: "Aired " + (i + 1),
                    airDate: "2024-03-01"
                  })),
                  { code: "S04E01", season: 4, number: 1, title: "Season 4 Premiere", airDate: "2099-06-01" }
                ]
              };
              const pass1Server = FirestoreRepository.compactShowForStorage(ongoingRaw);
              const deduped = FirestoreRepository.deduplicateShows([pass1Server])[0];
              const pass2Server = FirestoreRepository.compactShowForStorage(deduped);
              const pass1Client = app.compactShowForStorage(ongoingRaw);
              const pass2Client = app.compactShowForStorage(pass1Client);

              // 3. Dad strict matching isolation
              const dad1989 = { id: "movie_21291", tmdbId: 21291, title: "Dad", year: 1989 };
              const dad2005 = { id: "movie_99999", tmdbId: 99999, title: "Dad", year: 2005 };
              const dadFallbackDiffYear = { id: "movie_tt1111111", tmdbId: 0, title: "Dad", year: 1990 };

              const out = JSON.stringify({
                comp50Bytes: JSON.stringify(comp50).length,
                prog50Watched: prog50.watchedCount,
                prog50Total: prog50.total,
                afterUncheckWatched: progAfterUncheck.watchedCount,
                afterUncheckTotal: progAfterUncheck.total,
                effAfterUncheck,
                pass1Total: pass1Server.totalEpisodes,
                pass2Total: pass2Server.totalEpisodes,
                pass2FutureLen: pass2Server.episodes.length,
                pass2ClientTotal: pass2Client.totalEpisodes,
                pass2ClientFutureLen: pass2Client.episodes.length,
                dadDiffTmdbMatches: app.doesMovieMatchItem(dad1989, dad2005),
                dadDiffYearNoTmdbMatches: app.doesMovieMatchItem({ id: "csv_dad", tmdbId: 0, title: "Dad", year: 1989 }, dadFallbackDiffYear),
                dadExactMatches: app.doesMovieMatchItem(dad1989, { id: "tmdb_movie_21291", tmdbId: 21291, title: "Dad", year: 1989 })
              });
              if (typeof console !== "undefined" && console.log) console.log(out);
              else if (typeof print !== "undefined") print(out);
            })();
            """
        )
        out = self._run_js(harness)
        self.assertLess(out["comp50Bytes"], 3200)
        self.assertEqual(out["prog50Watched"], 282)
        self.assertEqual(out["prog50Total"], 1250)
        self.assertEqual(out["afterUncheckWatched"], 281)
        self.assertEqual(out["afterUncheckTotal"], 1250)
        self.assertEqual(out["effAfterUncheck"], "in_progress")
        self.assertEqual(out["pass1Total"], 24)
        self.assertEqual(out["pass2Total"], 24)
        self.assertEqual(out["pass2FutureLen"], 1)
        self.assertEqual(out["pass2ClientTotal"], 24)
        self.assertEqual(out["pass2ClientFutureLen"], 1)
        self.assertFalse(out["dadDiffTmdbMatches"])
        self.assertFalse(out["dadDiffYearNoTmdbMatches"])
        self.assertTrue(out["dadExactMatches"])

    def test_recommendations_relevance_and_completed_exclusion(self):
        """Verifies Swipe recommendations never use noisy /similar, reject unrelated cross-language/0-genre items, preserve colons/years, and exclude completed CSV/TVMaze items."""
        import re as _re

        def load_cf_module(rel_path):
            with open(os.path.join(ROOT_DIR, "cloudflare", "src", *rel_path), "r", encoding="utf-8") as f:
                src = f.read()
            src = _re.sub(r"^import\s+.*?;\s*$", "", src, flags=_re.MULTILINE)
            src = src.replace("export class ", "class ").replace("export function ", "function ").replace("export const ", "const ")
            return src

        svc_src = load_cf_module(("services", "LiveCatalogService.js"))
        router_src = load_cf_module(("controllers", "HttpRouter.js"))
        view_src = load_cf_module(("ui", "WebAppView.js"))

        harness = (
            """
            class FirestoreRepository { constructor() {} }
            class McpRouter { constructor() {} }
            if (typeof Response === "undefined") {
              globalThis.Response = class Response {
                constructor(body, init) { this.body = body; this.status = (init && init.status) || 200; }
              };
            }
            if (typeof URL === "undefined") {
              globalThis.URL = class URL {
                constructor(raw) {
                  const qIdx = raw.indexOf("?");
                  const pathPart = qIdx === -1 ? raw : raw.slice(0, qIdx);
                  const qsPart = qIdx === -1 ? "" : raw.slice(qIdx + 1);
                  const schemeEnd = pathPart.indexOf("://");
                  const slashIdx = pathPart.indexOf("/", schemeEnd + 3);
                  this.pathname = slashIdx === -1 ? "/" : pathPart.slice(slashIdx);
                  const map = {};
                  for (const pair of qsPart.split("&")) {
                    if (!pair) continue;
                    const eq = pair.indexOf("=");
                    const k = decodeURIComponent(eq === -1 ? pair : pair.slice(0, eq));
                    const v = decodeURIComponent(eq === -1 ? "" : pair.slice(eq + 1));
                    map[k] = v;
                  }
                  this.searchParams = { get: (k) => (k in map ? map[k] : null) };
                }
              };
            }
            """
            + "\n"
            + svc_src
            + "\n"
            + view_src
            + "\n"
            + router_src
            + """
            (async () => {
              const sStart = WEB_APP_HTML.lastIndexOf("<script>") + 8;
              const sEnd = WEB_APP_HTML.lastIndexOf("</script>");
              const clientJs = WEB_APP_HTML.slice(sStart, sEnd).replace("const app = new TvTimeClient();", "globalThis.TvTimeClientClass = TvTimeClient;");
              (new Function(clientJs))();

              const requestedUrls = [];
              const mockFetch = async (url) => {
                requestedUrls.push(String(url));
                const u = String(url);
                if (u.includes("/search/movie") && u.includes("Youth")) {
                  return {
                    ok: true,
                    json: async () => ({
                      results: [
                        { id: 293660, title: "Youth", release_date: "2015-05-20", original_language: "it" },
                        { id: 1388999, title: "Youth", release_date: "2026-02-14", original_language: "ta" }
                      ]
                    })
                  };
                }
                if (u.includes("/movie/284289?")) {
                  return {
                    ok: true,
                    json: async () => ({
                      id: 284289,
                      original_language: "ta",
                      genres: [{ id: 28, name: "Action" }, { id: 53, name: "Thriller" }],
                      recommendations: {
                        results: [
                          { id: 290555, title: "Thuppakki", release_date: "2012-11-13", original_language: "ta", genre_ids: [28, 53], vote_count: 240, vote_average: 7.5, poster_path: "/thuppakki.jpg" },
                          { id: 26690, title: "Mr. Majestyk", release_date: "1974-07-17", original_language: "en", genre_ids: [80], vote_count: 95, vote_average: 6.5, poster_path: "/majestyk.jpg" },
                          { id: 777111, title: "Random Romance", release_date: "2021-01-01", original_language: "ta", genre_ids: [10749], vote_count: 80, vote_average: 6.8, poster_path: "/rom.jpg" }
                        ]
                      },
                      similar: {
                        results: [
                          { id: 484444, title: "Marry Me at Christmas", release_date: "2017-11-04", original_language: "en", genre_ids: [10749], vote_count: 55, vote_average: 6.4, poster_path: "/xmas.jpg" }
                        ]
                      }
                    })
                  };
                }
                if (u.includes("/movie/1388999?")) {
                  return {
                    ok: true,
                    json: async () => ({
                      id: 1388999,
                      original_language: "ta",
                      genres: [{ id: 35, name: "Comedy" }],
                      recommendations: { results: [] }
                    })
                  };
                }
                if (u.includes("/movie/617126?")) {
                  return {
                    ok: true,
                    json: async () => ({
                      id: 617126,
                      original_language: "en",
                      genres: [{ id: 28, name: "Action" }, { id: 878, name: "Sci-Fi" }],
                      recommendations: {
                        results: [
                          { id: 986056, title: "Thunderbolts*", release_date: "2025-05-02", original_language: "en", genre_ids: [28, 878], vote_count: 900, vote_average: 7.4, poster_path: "/tb.jpg" }
                        ]
                      }
                    })
                  };
                }
                return { ok: true, json: async () => [] };
              };

              const recData = await LiveCatalogService.fetchRecommendations({
                tvSeeds: [],
                movieSeeds: [
                  { tmdbId: 284289, year: 2014, title: "Kaththi" },
                  { tmdbId: 0, year: 2026, title: "Youth" },
                  { tmdbId: 617126, year: 2025, title: "The Fantastic 4: First Steps" }
                ],
                page: 1,
                fetchImpl: mockFetch,
                env: { TMDB_API_KEY: "dummy_key" }
              });

              let parsedRouterSeeds = null;
              const origFetchRecs = LiveCatalogService.fetchRecommendations;
              LiveCatalogService.fetchRecommendations = async (opts) => {
                parsedRouterSeeds = opts.movieSeeds;
                return { results: [] };
              };
              const router = new HttpRouter({ TMDB_API_KEY: "dummy_key" });
              await router.handle({
                method: "GET",
                url: "https://example.com/api/recommendations?movieSeeds=" +
                  encodeURIComponent("617126:2025:The Fantastic 4: First Steps|0:2026:Youth")
              });
              LiveCatalogService.fetchRecommendations = origFetchRecs;

              const app = Object.create(globalThis.TvTimeClientClass.prototype);
              app.state = {
                decidedIds: [],
                movies: [
                  { id: "csv_movie_lotr_1", tmdbId: 0, title: "Lord of the Rings: The Fellowship of the Ring", year: 2001, watched: true },
                  { id: "csv_movie_hp_1", tmdbId: 0, title: "Harry Potter and the Sorcerer's Stone", year: 2001, watched: true },
                  { id: "csv_movie_f1", tmdbId: 0, title: "F1 The Movie", year: 2025, watched: true },
                  { id: "movie_21291", tmdbId: 21291, title: "Dad", year: 1989, watched: true }
                ],
                shows: [
                  { id: "tvmaze_4712", tmdbId: 0, tvmazeId: 4712, title: "Death Note", year: 2015, userStatus: "completed", totalEpisodes: 37, episodes: [], watchedEpisodes: [] }
                ]
              };

              const out = JSON.stringify({
                usedSimilarParam: requestedUrls.some((u) => u.includes("similar")),
                resolvedIndianYouth2026: requestedUrls.some((u) => u.includes("/movie/1388999?")),
                resolvedWrongItalianYouth: requestedUrls.some((u) => u.includes("/movie/293660?")),
                recTitles: recData.results.map((r) => r.title),
                recReasons: recData.results.map((r) => r.becauseOf),
                parsedColonTitle: parsedRouterSeeds ? parsedRouterSeeds[0].title : "",
                parsedYear: parsedRouterSeeds ? parsedRouterSeeds[0].year : 0,
                lotrExcluded: app.isItemInDbOrDecided({ id: "movie_120", tmdbId: 120, mediaType: "movie", title: "The Lord of the Rings: The Fellowship of the Ring", year: 2001 }),
                hpExcluded: app.isItemInDbOrDecided({ id: "movie_671", tmdbId: 671, mediaType: "movie", title: "Harry Potter and the Philosopher's Stone", year: 2001 }),
                f1Excluded: app.isItemInDbOrDecided({ id: "movie_911430", tmdbId: 911430, mediaType: "movie", title: "F1", year: 2025 }),
                deathNoteExcluded: app.isItemInDbOrDecided({ id: "tmdb_tv_13916", tmdbId: 13916, mediaType: "show", title: "Death Note", year: 2006 }),
                otherDadNotExcluded: !app.isItemInDbOrDecided({ id: "movie_88888", tmdbId: 88888, mediaType: "movie", title: "Dad", year: 2024 })
              });
              if (typeof console !== "undefined" && console.log) console.log(out);
              else if (typeof print !== "undefined") print(out);
            })();
            """
        )
        out = self._run_js(harness)
        self.assertFalse(out["usedSimilarParam"])
        self.assertTrue(out["resolvedIndianYouth2026"])
        self.assertFalse(out["resolvedWrongItalianYouth"])
        self.assertIn("Thuppakki", out["recTitles"])
        self.assertNotIn("Mr. Majestyk", out["recTitles"])
        self.assertNotIn("Random Romance", out["recTitles"])
        self.assertNotIn("Marry Me at Christmas", out["recTitles"])
        self.assertIn("Because you watched The Fantastic 4: First Steps", out["recReasons"])
        self.assertEqual(out["parsedColonTitle"], "The Fantastic 4: First Steps")
        self.assertEqual(out["parsedYear"], 2025)
        self.assertTrue(out["lotrExcluded"])
        self.assertTrue(out["hpExcluded"])
        self.assertTrue(out["f1Excluded"])
        self.assertTrue(out["deathNoteExcluded"])
        self.assertTrue(out["otherDadNotExcluded"])

    def test_watch_hours_include_in_progress_and_stopped_seasons_and_episodes(self):
        """Verifies Total & Series Watch Hours include completed, in-progress, and stopped shows up to exact completed seasons + watched episodes (including 30-ep windowed seasons and future-ep ongoing shows)."""
        import re as _re
        import server as local_server

        def load_cf_module(rel_path):
            with open(os.path.join(ROOT_DIR, "cloudflare", "src", *rel_path), "r", encoding="utf-8") as f:
                src = f.read()
            src = _re.sub(r"^import\s+.*?;\s*$", "", src, flags=_re.MULTILINE)
            src = src.replace("export class ", "class ").replace("export function ", "function ").replace("export const ", "const ")
            return src

        svc_src = load_cf_module(("services", "LiveCatalogService.js"))
        view_src = load_cf_module(("ui", "WebAppView.js"))

        sample_state = {
            "shows": [
                # 1. Completed compacted show (20 eps * 50m = 1000m) + 1 future episode that must NOT add extra minutes
                {
                    "id": "tmdb_tv_1",
                    "title": "Completed Ongoing",
                    "runtimeMins": 50,
                    "userStatus": "completed",
                    "totalEpisodes": 20,
                    "watchedEpisodes": [],
                    "episodes": [{"code": "S03E01", "season": 3, "number": 1, "airDate": "2099-05-01", "runtimeMins": 50}],
                },
                # 2. In-progress multi-season show: Seasons 1-2 completed (20 eps * 45m = 900m) + 3 watched eps in S3 (one 60m finale = 45+45+60 = 150m) => 23 eps, 1050m
                {
                    "id": "tmdb_tv_2",
                    "title": "In Progress Multi",
                    "runtimeMins": 45,
                    "userStatus": "in_progress",
                    "totalEpisodes": 30,
                    "completedSeasons": {"1": 10, "2": 10},
                    "watchedEpisodes": ["S03E01", "S03E02", "S03E03"],
                    "episodes": [
                        {"code": "S03E01", "season": 3, "number": 1, "airDate": "2024-01-01", "runtimeMins": 45},
                        {"code": "S03E02", "season": 3, "number": 2, "airDate": "2024-01-08", "runtimeMins": 45},
                        {"code": "S03E03", "season": 3, "number": 3, "airDate": "2024-01-15", "runtimeMins": 60},
                        {"code": "S03E04", "season": 3, "number": 4, "airDate": "2024-01-22", "runtimeMins": 45},
                    ],
                },
                # 3. Stopped show: Season 1 completed (8 eps * 40m = 320m) + 2 watched eps in S2 (80m), with only a future S4 ep in episodes => 10 eps, 400m
                {
                    "id": "tmdb_tv_3",
                    "title": "Stopped Show",
                    "runtimeMins": 40,
                    "userStatus": "stopped",
                    "totalEpisodes": 24,
                    "completedSeasons": {"1": 8},
                    "watchedEpisodes": ["S02E01", "S02E02"],
                    "episodes": [{"code": "S04E01", "season": 4, "number": 1, "airDate": "2099-09-01", "runtimeMins": 40}],
                },
                # 4. Giant single-season windowed in-progress show: 50 watched in S1, window only holds S01E50..S01E52 (50 * 24m = 1200m)
                {
                    "id": "tmdb_tv_4",
                    "title": "Long Anime",
                    "runtimeMins": 24,
                    "userStatus": "in_progress",
                    "totalEpisodes": 100,
                    "watchedEpisodes": [f"S01E{i:02d}" for i in range(1, 51)],
                    "episodes": [
                        {"code": "S01E50", "season": 1, "number": 50, "airDate": "2024-01-01", "runtimeMins": 24},
                        {"code": "S01E51", "season": 1, "number": 51, "airDate": "2024-01-08", "runtimeMins": 24},
                    ],
                },
                # 5. Interested watchlist show (0 watched => 0m)
                {
                    "id": "tmdb_tv_5",
                    "title": "Watchlist Show",
                    "runtimeMins": 55,
                    "userStatus": "interested",
                    "totalEpisodes": 10,
                    "watchedEpisodes": [],
                    "episodes": [],
                },
            ],
            "movies": [
                {"id": "movie_1", "title": "Watched Movie", "runtimeMins": 130, "watched": True},
                {"id": "movie_2", "title": "Watchlist Movie", "runtimeMins": 140, "watched": False},
            ],
        }

        py_stats = local_server.compute_stats(sample_state)
        # Expected: 20 + 23 + 10 + 50 = 103 eps; 1000 + 1050 + 400 + 1200 = 3650 show mins; + 130 movie mins = 3780 total mins
        self.assertEqual(py_stats["totalEpisodesWatched"], 103)
        self.assertEqual(py_stats["episodeMins"], 3650)
        self.assertEqual(py_stats["movieMins"], 130)
        self.assertEqual(py_stats["totalMins"], 3780)

        harness = (
            svc_src
            + "\n"
            + view_src
            + "\nconst SAMPLE_STATE = "
            + json.dumps(sample_state)
            + ";\n"
            + """
            const sStart = WEB_APP_HTML.lastIndexOf("<script>") + 8;
            const sEnd = WEB_APP_HTML.lastIndexOf("</script>");
            const clientJs = WEB_APP_HTML.slice(sStart, sEnd).replace("const app = new TvTimeClient();", "globalThis.TvTimeClientClass = TvTimeClient;");
            (new Function(clientJs))();
            const app = Object.create(globalThis.TvTimeClientClass.prototype);

            const workerStats = LiveCatalogService.computeStats(SAMPLE_STATE);
            let webEps = 0;
            let webShowMins = 0;
            for (const s of SAMPLE_STATE.shows) {
              const wt = app.computeShowWatchTime(s);
              webEps += wt.watchedCount;
              webShowMins += wt.minutes;
            }
            const out = JSON.stringify({ workerStats, webEps, webShowMins });
            if (typeof console !== "undefined" && console.log) console.log(out);
            else if (typeof print !== "undefined") print(out);
            """
        )
        out = self._run_js(harness)
        self.assertEqual(out["workerStats"]["totalEpisodesWatched"], 103)
        self.assertEqual(out["workerStats"]["showMinutesWatched"], 3650)
        self.assertEqual(out["workerStats"]["movieMinutesWatched"], 130)
        self.assertEqual(out["workerStats"]["totalMinutes"], 3780)
        self.assertEqual(out["webEps"], 103)
        self.assertEqual(out["webShowMins"], 3650)

    def test_friend_library_keeps_only_unwatched_movies_and_shows(self):
        """Verifies viewing a friend's library/watchlist keeps only movies & shows the current user hasn't watched (including CSV/TVMaze title variants)."""
        import re as _re

        with open(os.path.join(ROOT_DIR, "cloudflare", "src", "ui", "WebAppView.js"), "r", encoding="utf-8") as f:
            view_src = _re.sub(r"^import\s+.*?;\s*$", "", f.read(), flags=_re.MULTILINE).replace("export const ", "const ")

        harness = (
            """
            const domMap = {};
            const mkEl = () => ({
              innerHTML: "",
              textContent: "",
              className: "",
              classList: { add() {}, remove() {} }
            });
            for (const id of [
              "friend-library-panel",
              "friend-library-title",
              "friend-library-subtitle",
              "friend-lib-seg-shows",
              "friend-lib-seg-movies",
              "friend-lib-filter-bar",
              "friend-library-items"
            ]) {
              domMap[id] = mkEl();
            }
            globalThis.document = {
              getElementById: (id) => domMap[id] || null,
              querySelectorAll: () => [],
              addEventListener: () => {}
            };
            """
            + "\n"
            + view_src
            + """
            const sStart = WEB_APP_HTML.lastIndexOf("<script>") + 8;
            const sEnd = WEB_APP_HTML.lastIndexOf("</script>");
            const clientJs = WEB_APP_HTML.slice(sStart, sEnd).replace("const app = new TvTimeClient();", "globalThis.TvTimeClientClass = TvTimeClient;");
            (new Function(clientJs))();
            const app = Object.create(globalThis.TvTimeClientClass.prototype);

            app.selectedFriendId = "anu.deoghare28";
            app.isLoadingFriendLibrary = false;
            app.friendLibraryError = "";
            app.state = {
              movies: [
                { id: "csv_movie_lotr_1", tmdbId: 0, title: "Lord of the Rings: The Fellowship of the Ring", year: 2001, watched: true },
                { id: "movie_555", tmdbId: 555, title: "Shared Watchlist Movie", year: 2024, watched: false }
              ],
              shows: [
                { id: "tvmaze_4712", tmdbId: 0, tvmazeId: 4712, title: "Death Note", year: 2015, userStatus: "completed", totalEpisodes: 37, episodes: [], watchedEpisodes: [] },
                { id: "tmdb_tv_999", tmdbId: 999, title: "Already Watching Show", year: 2023, userStatus: "in_progress", totalEpisodes: 10, watchedEpisodes: ["S01E01"], episodes: [] },
                 { id: "tmdb_tv_777", tmdbId: 777, title: "My Watchlist Show", year: 2025, userStatus: "interested", totalEpisodes: 8, watchedEpisodes: [], episodes: [] },
                 { id: "tmdb_tv_444", tmdbId: 444, title: "My Stopped Show", year: 2022, userStatus: "stopped", totalEpisodes: 20, watchedEpisodes: ["S01E01"], episodes: [] }
               ]
             };
             app.selectedFriendLibrary = {
               movies: [
                 { id: "movie_120", tmdbId: 120, title: "The Lord of the Rings: The Fellowship of the Ring", year: 2001, watched: true },
                 { id: "movie_555", tmdbId: 555, title: "Shared Watchlist Movie", year: 2024, watched: false },
                 { id: "movie_888", tmdbId: 888, title: "Brand New Friend Movie", year: 2025, watched: false }
               ],
               shows: [
                 { id: "tmdb_tv_13916", tmdbId: 13916, title: "Death Note", year: 2006, userStatus: "completed", totalEpisodes: 37, episodes: [], watchedEpisodes: [] },
                 { id: "tmdb_tv_999", tmdbId: 999, title: "Already Watching Show", year: 2023, userStatus: "interested", totalEpisodes: 10, episodes: [], watchedEpisodes: [] },
                 { id: "tmdb_tv_777", tmdbId: 777, title: "My Watchlist Show", year: 2025, userStatus: "completed", totalEpisodes: 8, episodes: [], watchedEpisodes: [] },
                 { id: "tmdb_tv_444", tmdbId: 444, title: "My Stopped Show", year: 2022, userStatus: "completed", totalEpisodes: 20, episodes: [], watchedEpisodes: [] },
                 { id: "tmdb_tv_333", tmdbId: 333, title: "Unwatched Friend Show", year: 2024, userStatus: "interested", totalEpisodes: 12, episodes: [], watchedEpisodes: [] },
                 { id: "tmdb_tv_222", tmdbId: 222, title: "Friend In Progress Show", year: 2025, userStatus: "in_progress", totalEpisodes: 20, completedSeasons: { "1": 10 }, watchedEpisodes: ["S02E01", "S02E02"], episodes: [{ code: "S02E01", season: 2, number: 1, airDate: "2025-01-01" }, { code: "S02E02", season: 2, number: 2, airDate: "2025-01-08" }, { code: "S02E03", season: 2, number: 3, airDate: "2025-01-15" }] },
                 { id: "tmdb_tv_111", tmdbId: 111, title: "Friend Stopped Show", year: 2021, userStatus: "stopped", totalEpisodes: 30, completedSeasons: { "1": 8 }, watchedEpisodes: ["S02E01"], episodes: [{ code: "S02E01", season: 2, number: 1, airDate: "2021-05-01" }] }
               ]
             };

             (async () => {
               app.persistState = async () => {};
               app.friendLibrarySegment = "shows";
               app.friendLibraryFilter = "all";
               app.renderFriendLibrary();
               const showsHtml = domMap["friend-library-items"].innerHTML;
               const filterHtml = domMap["friend-lib-filter-bar"].innerHTML;
               const subtitle = domMap["friend-library-subtitle"].textContent;

               await app.addFromFriendLibrary("show", "tmdb_tv_222", "copy");
               await app.addFromFriendLibrary("show", "tmdb_tv_111", "copy");
               const copiedInProg = app.state.shows.find((s) => s.id === "tmdb_tv_222");
               const copiedStopped = app.state.shows.find((s) => s.id === "tmdb_tv_111");

               app.friendLibrarySegment = "movies";
               app.friendLibraryFilter = "all";
               app.renderFriendLibrary();
               const moviesHtml = domMap["friend-library-items"].innerHTML;

               const out = JSON.stringify({
                 subtitle,
                 showsHtml,
                 filterHtml,
                 moviesHtml,
                 copiedInProg: copiedInProg ? { status: copiedInProg.userStatus, completedSeasons: copiedInProg.completedSeasons, watchedEpisodes: copiedInProg.watchedEpisodes } : null,
                 copiedStopped: copiedStopped ? { status: copiedStopped.userStatus, completedSeasons: copiedStopped.completedSeasons, watchedEpisodes: copiedStopped.watchedEpisodes } : null
               });
               if (typeof console !== "undefined" && console.log) console.log(out);
               else if (typeof print !== "undefined") print(out);
             })();
            """
        )
        out = self._run_js(harness)
        self.assertEqual(out["subtitle"], "3 unwatched series · 1 unwatched movies")
        self.assertNotIn("Death Note", out["showsHtml"])
        self.assertNotIn("Already Watching Show", out["showsHtml"])
        self.assertNotIn("My Watchlist Show", out["showsHtml"])
        self.assertNotIn("My Stopped Show", out["showsHtml"])
        self.assertIn("Unwatched Friend Show", out["showsHtml"])
        self.assertIn("Friend In Progress Show", out["showsHtml"])
        self.assertIn("Friend Stopped Show", out["showsHtml"])
        self.assertIn("Stopped (1)", out["filterHtml"])
        self.assertIn("📋 Copy", out["showsHtml"])
        self.assertNotIn("✓ Completed</button>", out["showsHtml"])
        self.assertEqual(out["copiedInProg"], {"status": "in_progress", "completedSeasons": {"1": 10}, "watchedEpisodes": ["S02E01", "S02E02"]})
        self.assertEqual(out["copiedStopped"], {"status": "stopped", "completedSeasons": {"1": 8}, "watchedEpisodes": ["S02E01"]})
        self.assertNotIn("Fellowship of the Ring", out["moviesHtml"])
        self.assertNotIn("Shared Watchlist Movie", out["moviesHtml"])
        self.assertIn("Brand New Friend Movie", out["moviesHtml"])

    def test_tba_blank_airdate_episodes_after_future_premiere_stay_unaired(self):
        """Verifies TBA blank-airDate episodes following a future premiere (e.g. Only Murders S06E01 future + S06E02..S06E10 blank) are treated as unaired and keep caught-up shows completed."""
        import re as _re

        with open(os.path.join(ROOT_DIR, "cloudflare", "src", "repositories", "FirestoreRepository.js"), "r", encoding="utf-8") as f:
            repo_src = (
                f.read()
                .replace("export const STARTER_SHOWS", "const STARTER_SHOWS")
                .replace("export const STARTER_MOVIES", "const STARTER_MOVIES")
                .replace("export const STARTER_ACTORS", "const STARTER_ACTORS")
                .replace("export class FirestoreRepository", "class FirestoreRepository")
            )
        with open(os.path.join(ROOT_DIR, "cloudflare", "src", "ui", "WebAppView.js"), "r", encoding="utf-8") as f:
            view_src = _re.sub(r"^import\s+.*?;\s*$", "", f.read(), flags=_re.MULTILINE).replace("export const ", "const ")

        harness = (
            repo_src
            + "\n"
            + view_src
            + """
            const sStart = WEB_APP_HTML.lastIndexOf("<script>") + 8;
            const sEnd = WEB_APP_HTML.lastIndexOf("</script>");
            const clientJs = WEB_APP_HTML.slice(sStart, sEnd).replace("const app = new TvTimeClient();", "globalThis.TvTimeClientClass = TvTimeClient;");
            (new Function(clientJs))();
            const app = Object.create(globalThis.TvTimeClientClass.prototype);

            const fullEps = [];
            for (let s = 1; s <= 5; s++) {
              for (let e = 1; e <= 10; e++) {
                fullEps.push({
                  code: "S0" + s + "E" + String(e).padStart(2, "0"),
                  season: s,
                  number: e,
                  title: "Aired Ep " + e,
                  airDate: "2024-08-01"
                });
              }
            }
            fullEps.push({ code: "S06E01", season: 6, number: 1, title: "Episode 1", airDate: "2099-12-08" });
            for (let e = 2; e <= 10; e++) {
              fullEps.push({ code: "S06E" + String(e).padStart(2, "0"), season: 6, number: e, title: "Episode " + e, airDate: "" });
            }

            const airedClient = app.getAiredEpisodes(fullEps, "2026-11-21");
            const compacted = FirestoreRepository.compactShowForStorage({
              id: "tmdb_tv_107113",
              tmdbId: 107113,
              title: "Only Murders in the Building",
              status: "Returning Series",
              userStatus: "completed",
              totalEpisodes: 50,
              episodes: fullEps,
              watchedEpisodes: airedClient.map((e) => e.code)
            });
            const prog = app.computeShowProgress(compacted, "2026-11-21");
            const eff = app.getEffectiveShowStatus(compacted, "2026-11-21");

            const out = JSON.stringify({
              airedLen: airedClient.length,
              compactedStatus: compacted.userStatus,
              compactedTotal: compacted.totalEpisodes,
              compactedEps: compacted.episodes.map((e) => e.code),
              progWatched: prog.watchedCount,
              progLeft: prog.leftCount,
              eff
            });
            if (typeof console !== "undefined" && console.log) console.log(out);
            else if (typeof print !== "undefined") print(out);
            """
        )
        out = self._run_js(harness)
        self.assertEqual(out["airedLen"], 50)
        self.assertEqual(out["compactedStatus"], "completed")
        self.assertEqual(out["compactedTotal"], 50)
        self.assertEqual(out["compactedEps"], ["S06E01"])
        self.assertEqual(out["progWatched"], 50)
        self.assertEqual(out["progLeft"], 0)
        self.assertEqual(out["eff"], "completed")

    def test_transliteration_search_and_csv_library_deduplication(self):
        """Verifies misspelled/transliterated Search ('ala vaikuntapuramloo') resolves to canonical TMDB movie_628241 without 'Ala Vykuntapurram Loo' dupes, and deduplicates CSV/edition library pairs."""
        import re as _re

        def load_cf_module(rel_path):
            with open(os.path.join(ROOT_DIR, "cloudflare", "src", *rel_path), "r", encoding="utf-8") as f:
                src = f.read()
            src = _re.sub(r"^import\s+.*?;\s*$", "", src, flags=_re.MULTILINE)
            return src.replace("export class ", "class ").replace("export function ", "function ").replace("export const ", "const ")

        repo_src = load_cf_module(("repositories", "FirestoreRepository.js"))
        svc_src = load_cf_module(("services", "LiveCatalogService.js"))
        view_src = load_cf_module(("ui", "WebAppView.js"))

        harness = (
            repo_src
            + "\n"
            + svc_src
            + "\n"
            + view_src
            + """
            (async () => {
              const sStart = WEB_APP_HTML.lastIndexOf("<script>") + 8;
              const sEnd = WEB_APP_HTML.lastIndexOf("</script>");
              const clientJs = WEB_APP_HTML.slice(sStart, sEnd).replace("const app = new TvTimeClient();", "globalThis.TvTimeClientClass = TvTimeClient;");
              (new Function(clientJs))();
              const app = Object.create(globalThis.TvTimeClientClass.prototype);

              const mockFetch = async (url) => {
                const u = String(url);
                if (u.includes("/search/multi")) return { ok: true, json: async () => ({ results: [] }) };
                if (u.includes("v3.sg.media-imdb.com")) {
                  return {
                    ok: true,
                    json: async () => ({
                      d: [
                        { id: "tt9537292", l: "Ala Vaikunthapurramuloo", qid: "movie", y: 2020, s: "Allu Arjun" },
                        { id: "tt11607018", l: "Ala Vykuntapurram Loo", qid: "movie", y: 2020, s: "Allu Arjun" }
                      ]
                    })
                  };
                }
                if (u.includes("/find/tt9537292")) {
                  return {
                    ok: true,
                    json: async () => ({
                      movie_results: [
                        { id: 628241, title: "Ala Vaikunthapurramuloo", release_date: "2020-01-12", vote_average: 7.3, poster_path: "/ala.jpg" }
                      ]
                    })
                  };
                }
                return { ok: true, json: async () => [] };
              };

              const searchOut = await LiveCatalogService.searchMulti("ala vaikuntapuramloo", {
                page: 1,
                fetchImpl: mockFetch,
                env: { TMDB_API_KEY: "dummy" }
              });

              const dedupMovies = FirestoreRepository.deduplicateMovies([
                { id: "csv_movie_lotr", tmdbId: 0, title: "Lord of the Rings: The Two Towers", year: 2002, watched: true },
                { id: "movie_121", tmdbId: 121, title: "The Lord of the Rings: The Two Towers", year: 2002, watched: true },
                { id: "csv_movie_hp", tmdbId: 241527, title: "Harry Potter and the Sorcerer's Stone", year: 2001, watched: true },
                { id: "movie_671", tmdbId: 671, title: "Harry Potter and the Philosopher's Stone", year: 2001, watched: true },
                { id: "csv_movie_f1", tmdbId: 0, title: "F1 The Movie", year: 2025, watched: true },
                { id: "movie_911430", tmdbId: 911430, title: "F1", year: 2025, watched: true },
                { id: "movie_8587", tmdbId: 8587, title: "The Lion King", year: 1994, watched: true },
                { id: "movie_420818", tmdbId: 420818, title: "The Lion King", year: 2019, watched: true }
              ]);
              const dedupShows = FirestoreRepository.deduplicateShows([
                { id: "tmdb_tv_74682", tmdbId: 74682, title: "I Am Not a Robot", year: 2017, userStatus: "completed", totalEpisodes: 32, episodes: [], watchedEpisodes: [] },
                { id: "tvmaze_32272", tmdbId: 0, tvmazeId: 32272, title: "I'm Not a Robot", year: 2017, userStatus: "completed", totalEpisodes: 32, episodes: [], watchedEpisodes: [] }
              ]);

              app.state = {
                movies: [
                  { id: "csv_movie_scene_tamil", tmdbId: 0, title: "Scene (Tamil)", year: 2026, watched: true },
                  { id: "movie_1402181", tmdbId: 1402181, title: "Scene", year: 2026, watched: true }
                ],
                shows: []
              };
              app.deduplicateState();

              const out = JSON.stringify({
                searchIds: searchOut.results.map((r) => r.id),
                searchTitles: searchOut.results.map((r) => r.title),
                dedupMovieIds: dedupMovies.map((m) => m.id),
                dedupShowCount: dedupShows.length,
                clientSceneIds: app.state.movies.map((m) => m.id)
              });
              if (typeof console !== "undefined" && console.log) console.log(out);
              else if (typeof print !== "undefined") print(out);
            })();
            """
        )
        out = self._run_js(harness)
        self.assertEqual(out["searchIds"], ["movie_628241"])
        self.assertEqual(out["searchTitles"], ["Ala Vaikunthapurramuloo"])
        self.assertEqual(out["dedupMovieIds"], ["movie_121", "movie_671", "movie_911430", "movie_8587", "movie_420818"])
        self.assertEqual(out["dedupShowCount"], 1)
        self.assertEqual(out["clientSceneIds"], ["movie_1402181"])

    def test_categories_discover_movies_year_lang_and_shows_year_range_genre_untracked_only(self):
        """Verifies Categories tab discovers Movies by Year+Language (e.g. 2004 Tamil/Malayalam/Marathi) and Shows by Year Range+Genre, while hiding both Completed and Watchlist library items."""
        import re as _re

        def load_cf_module(rel_path):
            with open(os.path.join(ROOT_DIR, "cloudflare", "src", *rel_path), "r", encoding="utf-8") as f:
                src = f.read()
            src = _re.sub(r"^import\s+.*?;\s*$", "", src, flags=_re.MULTILINE)
            return src.replace("export class ", "class ").replace("export function ", "function ").replace("export const ", "const ")

        svc_src = load_cf_module(("services", "LiveCatalogService.js"))
        view_src = load_cf_module(("ui", "WebAppView.js"))

        harness = (
            svc_src
            + "\n"
            + view_src
            + """
            (async () => {
              const sStart = WEB_APP_HTML.lastIndexOf("<script>") + 8;
              const sEnd = WEB_APP_HTML.lastIndexOf("</script>");
              const clientJs = WEB_APP_HTML.slice(sStart, sEnd).replace("const app = new TvTimeClient();", "globalThis.TvTimeClientClass = TvTimeClient;");
              (new Function(clientJs))();
              const app = Object.create(globalThis.TvTimeClientClass.prototype);

              const requestedUrls = [];
              const mockFetch = async (url) => {
                const u = String(url);
                requestedUrls.push(u);
                if (u.includes("/discover/movie?")) {
                  return {
                    ok: true,
                    json: async () => ({
                      total_pages: 4,
                      results: [
                        { id: 50001, title: "Ghilli", release_date: "2004-04-17", vote_average: 7.8, poster_path: "/ghilli.jpg" },
                        { id: 50002, title: "Kaadhal", release_date: "2004-12-17", vote_average: 7.7, poster_path: "/kaadhal.jpg" },
                        { id: 50003, title: "Autograph", release_date: "2004-02-14", vote_average: 7.6, poster_path: "/auto.jpg" }
                      ]
                    })
                  };
                }
                if (u.includes("/discover/tv?")) {
                  return {
                    ok: true,
                    json: async () => ({
                      total_pages: 2,
                      results: [
                        { id: 1396, name: "Breaking Bad", first_air_date: "2008-01-20", vote_average: 8.9, poster_path: "/bb.jpg" },
                        { id: 1405, name: "Dexter", first_air_date: "2006-10-01", vote_average: 8.2, poster_path: "/dex.jpg" }
                      ]
                    })
                  };
                }
                return { ok: true, json: async () => ({ results: [] }) };
              };

              const movCat = await LiveCatalogService.discoverByCategory({
                mediaType: "movie",
                language: "ta",
                genre: "28",
                year: 2004,
                page: 1,
                fetchImpl: mockFetch,
                env: { TMDB_API_KEY: "dummy" }
              });

              const showCat = await LiveCatalogService.discoverByCategory({
                mediaType: "show",
                language: "ml",
                yearFrom: 2005,
                yearTo: 2010,
                genre: "80",
                sort: "votes",
                page: 1,
                fetchImpl: mockFetch,
                env: { TMDB_API_KEY: "dummy" }
              });

              app.categorySegment = "movies";
              app.categoryMovieLang = "ta";
              app.categoryMovieGenre = "28";
              app.categoryMovieYear = 2004;
              app.categorySort = "votes";
              const builtMovieCatUrl = app.buildCategoriesApiUrl(1);

              app.categorySegment = "shows";
              app.categoryShowLang = "hi";
              app.categoryShowGenre = "18";
              app.categoryShowYearFrom = 2018;
              app.categoryShowYearTo = 2025;
              app.categorySort = "votes";
              const builtShowCatUrl = app.buildCategoriesApiUrl(1);

              // Verify Web Categories filters out BOTH watched (completed) AND watchlist (interested) items
              app.state = {
                movies: [
                  { id: "movie_50001", tmdbId: 50001, title: "Ghilli", year: 2004, watched: true },
                  { id: "movie_50002", tmdbId: 50002, title: "Kaadhal", year: 2004, watched: false }
                ],
                shows: [
                  { id: "tmdb_tv_1396", tmdbId: 1396, title: "Breaking Bad", year: 2008, userStatus: "interested", totalEpisodes: 62, episodes: [], watchedEpisodes: [] }
                ]
              };
              const untrackedMov = movCat.results.filter((it) => !app.isItemAlreadyInLibrary(it));
              const untrackedShows = showCat.results.filter((it) => !app.isItemAlreadyInLibrary(it));

              const out = JSON.stringify({
                movUrl: requestedUrls[0],
                showUrl: requestedUrls[1],
                builtMovieCatUrl,
                builtShowCatUrl,
                hasMovieGenreDropdown: WEB_APP_HTML.includes('id="cat-movie-genre"'),
                hasShowLangDropdown: WEB_APP_HTML.includes('id="cat-show-lang"'),
                movHasMore: movCat.hasMore,
                untrackedMovTitles: untrackedMov.map((m) => m.title),
                untrackedShowTitles: untrackedShows.map((s) => s.title),
                hasDrawerCategoriesBtn: WEB_APP_HTML.includes("switchTab('categories')")
              });
              if (typeof console !== "undefined" && console.log) console.log(out);
              else if (typeof print !== "undefined") print(out);
            })();
            """
        )
        out = self._run_js(harness)
        self.assertIn("primary_release_year=2004", out["movUrl"])
        self.assertIn("with_original_language=ta", out["movUrl"])
        self.assertIn("with_genres=28", out["movUrl"])
        self.assertIn("sort_by=vote_count.desc", out["movUrl"])
        self.assertIn("genre=28", out["builtMovieCatUrl"])
        self.assertTrue(out["hasMovieGenreDropdown"])
        self.assertIn("first_air_date.gte=2005-01-01", out["showUrl"])
        self.assertIn("first_air_date.lte=2010-12-31", out["showUrl"])
        self.assertIn("with_genres=80", out["showUrl"])
        self.assertIn("with_original_language=ml", out["showUrl"])
        self.assertIn("language=hi", out["builtShowCatUrl"])
        self.assertTrue(out["hasShowLangDropdown"])
        self.assertTrue(out["movHasMore"])
        self.assertEqual(out["untrackedMovTitles"], ["Autograph"])
        self.assertEqual(out["untrackedShowTitles"], ["Dexter"])
        self.assertTrue(out["hasDrawerCategoriesBtn"])

    def test_stick_future_episodes_unmarkable_and_never_shown_watched_when_caught_up(self):
        """Verifies marking Nov 17 S02E04 in Stick never marks future S02E05..S02E10 as watched, and future episodes are locked ('Upcoming') and unmarkable."""
        import re as _re

        def load_cf_module(rel_path):
            with open(os.path.join(ROOT_DIR, "cloudflare", "src", *rel_path), "r", encoding="utf-8") as f:
                src = f.read()
            src = _re.sub(r"^import\s+.*?;\s*$", "", src, flags=_re.MULTILINE)
            return src.replace("export class ", "class ").replace("export function ", "function ").replace("export const ", "const ")

        view_src = load_cf_module(("ui", "WebAppView.js"))
        harness = (
            view_src
            + """
            (async () => {
              const els = {};
              globalThis.document = {
                getElementById: (id) => {
                  if (!els[id]) {
                    els[id] = { innerHTML: "", textContent: "", className: "", classList: { add: () => {}, remove: () => {}, toggle: () => {} } };
                  }
                  return els[id];
                }
              };
              const sStart = WEB_APP_HTML.lastIndexOf("<script>") + 8;
              const sEnd = WEB_APP_HTML.lastIndexOf("</script>");
              const clientJs = WEB_APP_HTML.slice(sStart, sEnd).replace("const app = new TvTimeClient();", "globalThis.TvTimeClientClass = TvTimeClient;");
              (new Function(clientJs))();
              const app = Object.create(globalThis.TvTimeClientClass.prototype);
              app.persistState = async () => {};

              const stickShow = {
                id: "tmdb_tv_248830",
                tmdbId: 248830,
                title: "Stick",
                year: 2025,
                status: "Returning Series",
                userStatus: "in_progress",
                totalEpisodes: 14,
                completedSeasons: { "1": 10 },
                watchedEpisodes: ["S02E01", "S02E02", "S02E03"],
                episodes: [
                  { code: "S02E01", season: 2, number: 1, title: "Sirens in Soft Spikes", airDate: "2026-11-03", runtimeMins: 30 },
                  { code: "S02E02", season: 2, number: 2, title: "Don't Be Summer Mayonnaise", airDate: "2026-11-03", runtimeMins: 36 },
                  { code: "S02E03", season: 2, number: 3, title: "Chip Off the Old Butch", airDate: "2026-11-10", runtimeMins: 38 },
                  { code: "S02E04", season: 2, number: 4, title: "The Boog", airDate: "2026-11-17", runtimeMins: 26 },
                  { code: "S02E05", season: 2, number: 5, title: "Go Time!", airDate: "2099-11-24", runtimeMins: 37 },
                  { code: "S02E06", season: 2, number: 6, title: "Jung and the Restless", airDate: "2099-12-01", runtimeMins: 41 },
                  { code: "S02E07", season: 2, number: 7, title: "Bogey Nights", airDate: "2099-12-08", runtimeMins: 35 }
                ]
              };
              app.state = { shows: [stickShow], movies: [] };
              app.detailItem = stickShow;
              app.detailType = "show";
              app.detailTab = "episodes";
              app.openSeasons = new Set([2]);

              // 1. Attempt to mark future S02E05 directly -> must be ignored!
              await app.toggleEpisodeCode("tmdb_tv_248830", "S02E05");
              const afterFutureClickWatched = [...stickShow.watchedEpisodes];

              // 2. Mark aired Nov 17 episode S02E04 -> catches up to all 14 aired episodes (status = completed),
              //    while S02E05..S02E07 remain Upcoming (NOT checked, NOT clickable)!
              await app.toggleEpisodeCode("tmdb_tv_248830", "S02E04");
              app.renderDetailSheet();
              const htmlAfterS02E04 = els["detail-body"].innerHTML;

              // 3. Uncheck S02E04 -> returns cleanly to 3/4 aired watched (in_progress)
              await app.toggleEpisodeCode("tmdb_tv_248830", "S02E04");

              // 4. Mark entire Season 2 via toggleSeasonWatched -> marks ONLY aired S02E01..S02E04, never S02E05..S02E07!
              await app.toggleSeasonWatched("tmdb_tv_248830", 2);

              const out = JSON.stringify({
                afterFutureClickWatched,
                hasFourOfFourAiredLabel: htmlAfterS02E04.includes("4/4 aired · 3 upcoming"),
                hasFutureToggleS02E05: htmlAfterS02E04.includes("S02E05&quot;)"),
                upcomingBadgeCount: htmlAfterS02E04.split(">Upcoming<").length - 1,
                afterSeasonToggleWatched: stickShow.watchedEpisodes,
                finalStatus: stickShow.userStatus
              });
              if (typeof console !== "undefined" && console.log) console.log(out);
              else if (typeof print !== "undefined") print(out);
            })();
            """
        )
        out = self._run_js(harness)
        self.assertEqual(out["afterFutureClickWatched"], ["S02E01", "S02E02", "S02E03"])
        self.assertTrue(out["hasFourOfFourAiredLabel"])
        self.assertFalse(out["hasFutureToggleS02E05"])
        self.assertEqual(out["upcomingBadgeCount"], 3)
        self.assertEqual(out["afterSeasonToggleWatched"], ["S02E01", "S02E02", "S02E03", "S02E04"])
        self.assertEqual(out["finalStatus"], "completed")

    def test_statistics_friend_direct_comparison(self):
        """Verifies Statistics friend picker computes side-by-side watch hours, series, episodes, movies, and shared titles in common."""
        import re as _re

        with open(os.path.join(ROOT_DIR, "cloudflare", "src", "ui", "WebAppView.js"), "r", encoding="utf-8") as f:
            view_src = _re.sub(r"^import\s+.*?;\s*$", "", f.read(), flags=_re.MULTILINE).replace("export const ", "const ")

        harness = (
            view_src
            + """
            (async () => {
              const els = {};
              globalThis.document = {
                getElementById: (id) => {
                  if (!els[id]) {
                    els[id] = { innerHTML: "", textContent: "", className: "", classList: { add: () => {}, remove: () => {}, toggle: () => {} } };
                  }
                  return els[id];
                }
              };
              const sStart = WEB_APP_HTML.lastIndexOf("<script>") + 8;
              const sEnd = WEB_APP_HTML.lastIndexOf("</script>");
              const clientJs = WEB_APP_HTML.slice(sStart, sEnd).replace("const app = new TvTimeClient();", "globalThis.TvTimeClientClass = TvTimeClient;");
              (new Function(clientJs))();
              const app = Object.create(globalThis.TvTimeClientClass.prototype);
              app.userId = "madhavsankar";
              app.userEmail = "madhavsankar@gmail.com";
              app.statsCompareFriendId = "anu.deoghare28";
              app.isLoadingStatsCompare = false;
              app.statsCompareError = "";
              app.state = {
                friends: ["anu.deoghare28"],
                shows: [
                  { id: "tmdb_tv_1396", tmdbId: 1396, title: "Breaking Bad", year: 2008, runtimeMins: 48, userStatus: "completed", totalEpisodes: 62, episodes: [], watchedEpisodes: [] },
                  { id: "tmdb_tv_248830", tmdbId: 248830, title: "Stick", year: 2025, runtimeMins: 30, userStatus: "in_progress", totalEpisodes: 14, completedSeasons: { "1": 10 }, watchedEpisodes: ["S02E01", "S02E02"], episodes: [] }
                ],
                movies: [
                  { id: "movie_50001", tmdbId: 50001, title: "Ghilli", year: 2004, runtimeMins: 160, watched: true },
                  { id: "movie_50002", tmdbId: 50002, title: "Watchlist Only", year: 2025, runtimeMins: 120, watched: false }
                ]
              };
              app.statsCompareCache = new Map([
                [
                  "anu.deoghare28",
                  {
                    userId: "anu.deoghare28",
                    shows: [
                      { id: "tmdb_tv_1396", tmdbId: 1396, title: "Breaking Bad", year: 2008, runtimeMins: 48, userStatus: "completed", totalEpisodes: 62, episodes: [], watchedEpisodes: [] },
                      { id: "tmdb_tv_74682", tmdbId: 74682, title: "I Am Not a Robot", year: 2017, runtimeMins: 35, userStatus: "completed", totalEpisodes: 32, episodes: [], watchedEpisodes: [] },
                      { id: "tmdb_tv_100", tmdbId: 100, title: "Friend Watchlist Show", year: 2025, runtimeMins: 45, userStatus: "interested", totalEpisodes: 10, episodes: [], watchedEpisodes: [] }
                    ],
                    movies: [
                      { id: "movie_50001", tmdbId: 50001, title: "Ghilli", year: 2004, runtimeMins: 160, watched: true },
                      { id: "movie_671", tmdbId: 671, title: "Harry Potter and the Philosopher's Stone", year: 2001, runtimeMins: 152, watched: true }
                    ]
                  }
                ]
              ]);
              app.renderProfile();
              const cmpHtml = els["stats-friend-compare-section"].innerHTML;
              const idxCommon = cmpHtml.indexOf("In Common");
              const idxNames = cmpHtml.indexOf("You (@madhavsankar)");
              const idxTotal = cmpHtml.indexOf("Total Watch Time");
              const idxSeriesCount = cmpHtml.indexOf("Series Watched");
              const idxEpisodes = cmpHtml.indexOf("Episodes Watched");
              const idxSeriesHours = cmpHtml.indexOf("Series Hours");
              const idxMoviesCount = cmpHtml.indexOf("Movies Watched");
              const idxMovieHours = cmpHtml.indexOf("Movie Hours");
              const out = JSON.stringify({
                hasCompareSectionInHtml: WEB_APP_HTML.includes('id="stats-friend-compare-section"'),
                hasFriendHandle: cmpHtml.includes("@anu.deoghare28"),
                hasInCommonTopBanner: cmpHtml.includes("In Common") && cmpHtml.includes("1 series · 1 movies"),
                hasOrderedSections:
                  idxCommon > -1 &&
                  idxCommon < idxNames &&
                  idxNames < idxTotal &&
                  idxTotal < idxSeriesCount &&
                  idxSeriesCount < idxEpisodes &&
                  idxEpisodes < idxSeriesHours &&
                  idxSeriesHours < idxMoviesCount &&
                  idxMoviesCount < idxMovieHours,
                hasEpisodesRow: cmpHtml.includes("Episodes Watched") && cmpHtml.includes(">74<") && cmpHtml.includes(">94<"),
                hasMoviesRow: cmpHtml.includes("Movies Watched")
              });
              if (typeof console !== "undefined" && console.log) console.log(out);
              else if (typeof print !== "undefined") print(out);
            })();
            """
        )
        out = self._run_js(harness)
        self.assertTrue(out["hasCompareSectionInHtml"])
        self.assertTrue(out["hasFriendHandle"])
        self.assertTrue(out["hasInCommonTopBanner"])
        self.assertTrue(out["hasOrderedSections"])
        self.assertTrue(out["hasEpisodesRow"])
        self.assertTrue(out["hasMoviesRow"])

    def test_imdb_tv_series_fallback_and_wikipedia_episode_count_only(self):
        """Verifies IMDb tvSeries fallback ('chandanamazha' -> imdb_tv_tt10339970) and Wikipedia episode-count-only resolution (1173 eps for Chandanamazha, 1524 eps for sparse 1-ep Parasparam)."""
        import re as _re

        def load_cf(rel):
            with open(os.path.join(ROOT_DIR, "cloudflare", "src", *rel), "r", encoding="utf-8") as f:
                s = _re.sub(r"^import\s+.*?;\s*$", "", f.read(), flags=_re.MULTILINE)
            return s.replace("export class ", "class ").replace("export function ", "function ").replace("export const ", "const ")

        svc_src = load_cf(("services", "LiveCatalogService.js"))
        repo_src = load_cf(("repositories", "FirestoreRepository.js"))
        view_src = load_cf(("ui", "WebAppView.js"))

        harness = (
            """
            function formatEpisodeCode(season, number) {
              return "S" + String(season || 1).padStart(2, "0") + "E" + String(number || 1).padStart(2, "0");
            }
            function stripHtml(raw) {
              return String(raw || "").replace(/<[^>]*>/g, " ").replace(/\\s+/g, " ").trim();
            }
            """
            + svc_src
            + "\n"
            + repo_src
            + "\n"
            + view_src
            + """
            (async () => {
              const sStart = WEB_APP_HTML.lastIndexOf("<script>") + 8;
              const sEnd = WEB_APP_HTML.lastIndexOf("</script>");
              const clientJs = WEB_APP_HTML.slice(sStart, sEnd).replace("const app = new TvTimeClient();", "globalThis.TvTimeClientClass = TvTimeClient;");
              (new Function(clientJs))();
              const app = Object.create(globalThis.TvTimeClientClass.prototype);

              const mockFetch = async (url) => {
                const u = String(url);
                if (u.includes("/search/multi")) return { ok: true, json: async () => ({ results: [] }) };
                if (u.includes("api.tvmaze.com")) return { ok: true, json: async () => [] };
                if (u.includes("/find/tt10339970")) return { ok: true, json: async () => ({ tv_results: [], movie_results: [] }) };
                if (u.includes("v3.sg.media-imdb.com") && u.toLowerCase().includes("chandanamazha")) {
                  return {
                    ok: true,
                    json: async () => ({
                      d: [
                        { id: "tt10339970", l: "Chandanamazha", qid: "tvSeries", y: 2014, s: "Meghna Vincent, Shalu Kurian" },
                        { id: "tt9999991", l: "Random NonIndian Sketch", qid: "tvSeries", y: 2019, s: "John Smith" },
                        { id: "tt0319164", l: "Chandanamarangal", qid: "movie", y: 2001 }
                      ]
                    })
                  };
                }
                if (u.includes("en.wikipedia.org") && u.includes("Random%20NonIndian%20Sketch")) {
                  return {
                    ok: true,
                    json: async () => ({
                      query: {
                        pages: {
                          "303": {
                            pageid: 303,
                            title: "Random NonIndian Sketch (TV series)",
                            extract: "Random NonIndian Sketch is an American comedy series.",
                            revisions: [{ "*": "{{Infobox television | num_episodes = 24 | runtime = 22 minutes | network = [[NBC]] }}" }]
                          }
                        }
                      }
                    })
                  };
                }
                if (u.includes("api.themoviedb.org/3/tv/73704?append_to_response=credits")) {
                  return {
                    ok: true,
                    json: async () => ({
                      id: 73704,
                      name: "Uppum Mulakum",
                      origin_country: ["IN"],
                      original_language: "ml",
                      first_air_date: "2015-12-14",
                      number_of_episodes: 21,
                      episode_run_time: [],
                      seasons: [{ season_number: 1, episode_count: 21 }, { season_number: 2, episode_count: 0 }]
                    })
                  };
                }
                if (u.includes("api.themoviedb.org/3/tv/73704?append_to_response=season/1")) {
                  const stub21 = [];
                  for (let i = 1; i <= 21; i++) stub21.push({ episode_number: i, name: "Episode " + i, air_date: "2015-12-14" });
                  return { ok: true, json: async () => ({ "season/1": { episodes: stub21 }, "season/2": { episodes: [] } }) };
                }
                if (u.includes("en.wikipedia.org") && u.includes("Uppum%20Mulakum")) {
                  return {
                    ok: true,
                    json: async () => ({
                      query: {
                        pages: {
                          "701": {
                            pageid: 701,
                            title: "Uppum Mulakum",
                            extract: "Uppum Mulakum is an Indian Malayalam sitcom.",
                            revisions: [{ "*": "{{Infobox television | num_episodes = 2400+ | runtime = 30 minutes | network = [[Flowers TV]] }}" }]
                          }
                        }
                      }
                    })
                  };
                }
                if (u.includes("api.themoviedb.org/3/tv/2638?append_to_response=credits")) {
                  return {
                    ok: true,
                    json: async () => ({
                      id: 2638,
                      name: "Banoo Main Teri Dulhann",
                      origin_country: ["IO"],
                      original_language: "en",
                      first_air_date: "",
                      number_of_episodes: 0,
                      episode_run_time: [],
                      seasons: []
                    })
                  };
                }
                if (u.includes("en.wikipedia.org") && u.includes("Banoo%20Main%20Teri%20Dulhann")) {
                  return {
                    ok: true,
                    json: async () => ({
                      query: {
                        pages: {
                          "702": {
                            pageid: 702,
                            title: "Banoo Main Teri Dulhann",
                            extract: "Banoo Main Teri Dulhann is an Indian soap opera.",
                            revisions: [{ "*": "{{Infobox television | first_aired = 14 August 2006 | num_episodes = 700 | runtime = 22 minutes | network = [[ZEE TV]] }}" }]
                          }
                        }
                      }
                    })
                  };
                }
                if (u.includes("api.themoviedb.org/3/tv/6812?append_to_response=credits")) {
                  return {
                    ok: true,
                    json: async () => ({
                      id: 6812,
                      name: "Idea Star Singer",
                      origin_country: ["IO"],
                      original_language: "en",
                      first_air_date: "",
                      number_of_episodes: 0,
                      episode_run_time: [],
                      seasons: [{ season_number: 6, episode_count: 0 }]
                    })
                  };
                }
                if (u.includes("en.wikipedia.org") && u.includes("Idea%20Star%20Singer")) {
                  return {
                    ok: true,
                    json: async () => ({
                      query: {
                        pages: {
                          "703": {
                            pageid: 703,
                            title: "Star Singer",
                            extract: "Star Singer is an Indian Malayalam music reality competition on Asianet.",
                            revisions: [{ "*": "{{Infobox television | first_aired = 22 April 2006 | runtime = 60–90 minutes | network = {{plainlist|\\n* [[Asianet (TV channel)|Asianet]]\\n}} | num_seasons = 10 }}" }]
                          }
                        }
                      }
                    })
                  };
                }
                if (u.includes("en.wikipedia.org") && u.includes("pageids=703")) {
                  return {
                    ok: true,
                    json: async () => ({
                      query: {
                        pages: {
                          "703": {
                            pageid: 703,
                            title: "Star Singer",
                            revisions: [{ "*": "{| class=\\"wikitable\\"\\n! Season !! Aired from !! Aired till !! Sponsors !! Telecast Frequency\\n|-\\n| 1 || 22 April 2006 || 18 February 2007 || [[Idea Cellular]] || Weekends only\\n|-\\n| 2 || 21 May 2007 || 26 April 2008 || Idea Cellular || Mon – Fri\\n|-\\n| 7 || 31 March 2014 || 21 September 2014 || Sunfeast || Mon – Fri\\n|}" }]
                          }
                        }
                      }
                    })
                  };
                }
                if (u.includes("api.themoviedb.org/3/tv/93352?append_to_response=credits")) {
                  return {
                    ok: true,
                    json: async () => ({
                      id: 93352,
                      name: "The Family Man",
                      origin_country: ["IN"],
                      original_language: "hi",
                      first_air_date: "2019-09-19",
                      number_of_episodes: 19,
                      episode_run_time: [49],
                      seasons: [{ season_number: 1, episode_count: 10 }, { season_number: 2, episode_count: 9 }]
                    })
                  };
                }
                if (u.includes("api.themoviedb.org/3/tv/93352?append_to_response=season/1,season/2")) {
                  const s1 = [], s2 = [];
                  for (let i = 1; i <= 10; i++) s1.push({ episode_number: i, name: "S1 Ep " + i, air_date: "2019-09-19", runtime: 49 });
                  for (let i = 1; i <= 9; i++) s2.push({ episode_number: i, name: "S2 Ep " + i, air_date: "2021-06-04", runtime: 49 });
                  return { ok: true, json: async () => ({ "season/1": { episodes: s1 }, "season/2": { episodes: s2 } }) };
                }
                if (u.includes("api.themoviedb.org/3/tv/888888?append_to_response=credits")) {
                  return {
                    ok: true,
                    json: async () => ({
                      id: 888888,
                      name: "Short British Miniseries",
                      origin_country: ["GB"],
                      original_language: "en",
                      first_air_date: "2023-05-01",
                      number_of_episodes: 2,
                      episode_run_time: [55],
                      seasons: [{ season_number: 1, episode_count: 2 }]
                    })
                  };
                }
                if (u.includes("api.themoviedb.org/3/tv/888888?append_to_response=season/1")) {
                  return {
                    ok: true,
                    json: async () => ({
                      "season/1": {
                        episodes: [
                          { episode_number: 1, name: "Part 1", air_date: "2023-05-01", runtime: 55 },
                          { episode_number: 2, name: "Part 2", air_date: "2023-05-08", runtime: 55 }
                        ]
                      }
                    })
                  };
                }
                if (u.includes("en.wikipedia.org") && u.includes("list=search")) {
                  return {
                    ok: true,
                    json: async () => ({
                      query: { search: [{ title: "List of programs broadcast by Asianet" }] }
                    })
                  };
                }
                if (u.includes("en.wikipedia.org") && u.includes("List%20of%20programs%20broadcast%20by%20Asianet")) {
                  return {
                    ok: true,
                    json: async () => ({
                      query: {
                        pages: {
                          "101": {
                            pageid: 101,
                            title: "List of programs broadcast by Asianet",
                            revisions: [{ "*": "{| class='wikitable'\\n!Series\\n!Premiere\\n!Original year\\n|-\\n|''The Night Manager''\\n|2023\\n|2017\\n|}\\n{| class='wikitable'\\n!Premiere date\\n!Series\\n!Last aired\\n!Notes\\n!Episodes\\n|-\\n|3 February 2014\\n|''Chandanamazha''<ref>news</ref>\\n|9 December 2017\\n|Hindi TV series\\n|1173\\n|}" }]
                          }
                        }
                      }
                    })
                  };
                }
                if (u.includes("en.wikipedia.org") && u.includes("Parasparam")) {
                  return {
                    ok: true,
                    json: async () => ({
                      query: {
                        pages: {
                          "202": {
                            pageid: 202,
                            title: "Parasparam (TV series)",
                            extract: "Parasparam is an Indian Malayalam soap opera.",
                            revisions: [{ "*": "{{Infobox television | num_episodes = 1,524 | runtime = 22 minutes | network = [[Asianet]] | last_aired = 31 August 2018 }}" }]
                          }
                        }
                      }
                    })
                  };
                }
                if (u.includes("api.themoviedb.org/3/tv/327059?append_to_response=credits")) {
                  return {
                    ok: true,
                    json: async () => ({
                      id: 327059,
                      name: "Parasparam",
                      first_air_date: "2013-07-22",
                      number_of_episodes: 1,
                      episode_run_time: [],
                      seasons: [{ season_number: 1, episode_count: 1 }]
                    })
                  };
                }
                if (u.includes("api.themoviedb.org/3/tv/327059?append_to_response=season/1")) {
                  return {
                    ok: true,
                    json: async () => ({
                      "season/1": { episodes: [{ episode_number: 1, name: "Episode 1", air_date: "2013-07-22" }] }
                    })
                  };
                }
                return { ok: true, json: async () => ({ results: [] }) };
              };

              const searchChandanamazha = await LiveCatalogService.searchMulti("chandanamazha", {
                page: 1,
                fetchImpl: mockFetch,
                env: { TMDB_API_KEY: "dummy" }
              });

              const chandanamazhaDetails = await LiveCatalogService.fetchFullShowFromTvMaze("imdb_tv_tt10339970", {
                title: "Chandanamazha",
                fetchImpl: mockFetch,
                env: { TMDB_API_KEY: "dummy" }
              });

              const parasparamDetails = await LiveCatalogService.fetchFullShowFromTvMaze("tmdb_tv_327059", {
                title: "Parasparam",
                fetchImpl: mockFetch,
                env: { TMDB_API_KEY: "dummy" }
              });

              const britishDetails = await LiveCatalogService.fetchFullShowFromTvMaze("tmdb_tv_888888", {
                title: "Short British Miniseries",
                fetchImpl: mockFetch,
                env: { TMDB_API_KEY: "dummy" }
              });
              const uppumDetails = await LiveCatalogService.fetchFullShowFromTvMaze("tmdb_tv_73704", {
                title: "Uppum Mulakum",
                fetchImpl: mockFetch,
                env: { TMDB_API_KEY: "dummy" }
              });
              const dulhanDetails = await LiveCatalogService.fetchFullShowFromTvMaze("tmdb_tv_2638", {
                title: "Banoo Main Teri Dulhann",
                fetchImpl: mockFetch,
                env: { TMDB_API_KEY: "dummy" }
              });
              const ideaStarDetails = await LiveCatalogService.fetchFullShowFromTvMaze("tmdb_tv_6812", {
                title: "Idea Star Singer",
                fetchImpl: mockFetch,
                env: { TMDB_API_KEY: "dummy" }
              });
              const familyManDetails = await LiveCatalogService.fetchFullShowFromTvMaze("tmdb_tv_93352", {
                title: "The Family Man",
                fetchImpl: mockFetch,
                env: { TMDB_API_KEY: "dummy" }
              });

              // Verify compacted normal show never flashes "Watched Episode Count" while loading seasons
              const mockBodyEl = { innerHTML: "" };
              globalThis.document = {
                getElementById: (id) => (id === "detail-body" ? mockBodyEl : null)
              };
              const compactedNormal = {
                id: "tmdb_tv_93352",
                tmdbId: 93352,
                title: "The Family Man",
                year: 2019,
                runtimeMins: 49,
                userStatus: "completed",
                totalEpisodes: 19,
                watchedEpisodes: [],
                episodes: [],
                _loadingGuide: true
              };
              app.state = { shows: [compactedNormal], movies: [] };
              app.detailItem = compactedNormal;
              app.detailType = "show";
              app.detailTab = "episodes";
              app.openSeasons = new Set();
              app.renderDetailSheet();
              const loadingHtml = mockBodyEl.innerHTML;
              delete globalThis.document;

              app.state = { shows: [], movies: [] };
              app.persistState = async () => {};
              const partialShow = {
                id: "imdb_tv_tt10339970",
                title: "Chandanamazha",
                year: 2014,
                runtimeMins: 22,
                userStatus: "interested",
                totalEpisodes: 1173,
                completedSeasons: {},
                watchedEpisodes: [],
                episodes: []
              };
              app.state.shows = [partialShow];
              await app.setCountOnlyProgress("imdb_tv_tt10339970", 350);
              const compactedPartial = FirestoreRepository.compactShowForStorage(app.state.shows[0]);
              const progPartial = app.computeShowProgress(compactedPartial);
              const wtPartial = app.computeShowWatchTime(compactedPartial);

              await app.setCountOnlyProgress("imdb_tv_tt10339970", 1173);
              const compactedFull = FirestoreRepository.compactShowForStorage(app.state.shows[0]);
              const progFull = app.computeShowProgress(compactedFull);

              // Long-press "Mark all episodes till here" on a 1-season 600-episode series + Pass-1 & Pass-2 compaction safety
              const bigSeasonEps = [];
              for (let i = 1; i <= 600; i++) {
                bigSeasonEps.push({
                  code: "S01E" + String(i).padStart(2, "0"),
                  season: 1,
                  number: i,
                  title: "Episode " + i,
                  airDate: "2020-01-01",
                  runtimeMins: 22
                });
              }
              const bigShow = {
                id: "tmdb_tv_999600",
                tmdbId: 999600,
                title: "Long Single Season Show",
                year: 2020,
                runtimeMins: 22,
                userStatus: "interested",
                totalEpisodes: 600,
                completedSeasons: {},
                watchedEpisodes: [],
                episodes: bigSeasonEps
              };
              app.state.shows.push(bigShow);
              await app.markEpisodesUpTo("tmdb_tv_999600", "S01E450", false);
              const pass1Big = app.compactShowForStorage(bigShow);
              const pass2Big = app.compactShowForStorage(pass1Big);
              const repoPass2Big = FirestoreRepository.compactShowForStorage(FirestoreRepository.compactShowForStorage(bigShow));
              const progBig = app.computeShowProgress(pass2Big);
              await app.markEpisodesUpTo("tmdb_tv_999600", "S01E200", true);
              const progBigTrimmed = app.computeShowProgress(bigShow);

              // Test Universal Watched Episode Count on a multi-season show with real episodes (The Family Man: 19 eps across S1 & S2)
              const fmShow = {
                id: "tmdb_tv_93352",
                tmdbId: 93352,
                title: "The Family Man",
                year: 2019,
                runtimeMins: 45,
                userStatus: "interested",
                totalEpisodes: 19,
                completedSeasons: {},
                watchedEpisodes: [],
                episodes: familyManDetails.episodes
              };
              app.state.shows.push(fmShow);
              mockBodyEl.innerHTML = "";
              globalThis.document = {
                getElementById: (id) => (id === "detail-body" ? mockBodyEl : null)
              };
              app.detailItem = fmShow;
              app.detailType = "show";
              app.detailTab = "episodes";
              app.openSeasons = new Set();
              app.renderDetailSheet();
              const fmEpisodesHtml = mockBodyEl.innerHTML;
              delete globalThis.document;
              // Shows with seasons & episodes must NOT render the Watched Episode Count box (only Season accordions)
              const fmHasCleanSeasonUiOnly = !fmEpisodesHtml.includes('id="count-only-watched-input"') && fmEpisodesHtml.includes("Season 1") && fmEpisodesHtml.includes("Season 2");
              await app.setCountOnlyProgress("tmdb_tv_93352", 12);
              const fmProgPartial = app.computeShowProgress(fmShow);
              const fmStatusPartial = fmShow.userStatus;
              const fmLastCodePartial = fmProgPartial.lastWatchedEp?.code;
              await app.setCountOnlyProgress("tmdb_tv_93352", 19);
              const fmProgFull = app.computeShowProgress(fmShow);
              const fmStatusFull = fmShow.userStatus;

              const searchShowIds = searchChandanamazha.results.filter((r) => r.mediaType === "show").map((r) => r.id);

              const out = JSON.stringify({
                searchTopId: searchChandanamazha.results[0]?.id,
                searchTopType: searchChandanamazha.results[0]?.mediaType,
                searchTopTotal: searchChandanamazha.results[0]?.totalEpisodes,
                searchShowIds,
                chandanamazhaTotal: chandanamazhaDetails?.totalEpisodes,
                chandanamazhaEpsLen: chandanamazhaDetails?.episodes?.length,
                chandanamazhaNet: chandanamazhaDetails?.network,
                parasparamTotal: parasparamDetails?.totalEpisodes,
                parasparamRt: parasparamDetails?.runtimeMins,
                parasparamEpsLen: parasparamDetails?.episodes?.length,
                britishEpsLen: britishDetails?.episodes?.length,
                britishCountOnly: Boolean(britishDetails?.countOnly),
                uppumTotal: uppumDetails?.totalEpisodes,
                uppumRt: uppumDetails?.runtimeMins,
                dulhanTotal: dulhanDetails?.totalEpisodes,
                dulhanYear: dulhanDetails?.year,
                ideaStarTotal: ideaStarDetails?.totalEpisodes,
                ideaStarRt: ideaStarDetails?.runtimeMins,
                ideaStarNet: ideaStarDetails?.network,
                familyManEpsLen: familyManDetails?.episodes?.length,
                familyManCountOnly: Boolean(familyManDetails?.countOnly),
                flashedCountOnNormal: loadingHtml.includes("Watched Episode Count"),
                showedLoadingSeasons: loadingHtml.includes("Loading seasons"),
                partialStatus: compactedPartial.userStatus,
                partialWatched: progPartial.watchedCount,
                partialTotal: progPartial.total,
                partialMins: wtPartial.minutes,
                fullStatus: compactedFull.userStatus,
                fullWatched: progFull.watchedCount,
                bigWatchedPass2: progBig.watchedCount,
                bigTotalPass2: progBig.total,
                bigLastNumPass2: progBig.lastWatchedEp?.number,
                bigRepoWatchedLen: repoPass2Big.watchedEpisodes?.length,
                bigTrimmedWatched: progBigTrimmed.watchedCount,
                fmHasCleanSeasonUiOnly,
                fmWatchedPartial: fmProgPartial.watchedCount,
                fmTotalPartial: fmProgPartial.total,
                fmStatusPartial,
                fmLastCodePartial,
                fmWatchedFull: fmProgFull.watchedCount,
                fmStatusFull
              });
              if (typeof console !== "undefined" && console.log) console.log(out);
              else if (typeof print !== "undefined") print(out);
            })();
            """
        )
        out = self._run_js(harness)
        self.assertEqual(out["searchTopId"], "imdb_tv_tt10339970")
        self.assertEqual(out["searchTopType"], "show")
        self.assertEqual(out["searchTopTotal"], 1173)
        self.assertEqual(out["searchShowIds"], ["imdb_tv_tt10339970"])
        self.assertEqual(out["chandanamazhaTotal"], 1173)
        self.assertEqual(out["chandanamazhaEpsLen"], 0)
        self.assertEqual(out["chandanamazhaNet"], "Asianet")
        self.assertEqual(out["parasparamTotal"], 1524)
        self.assertEqual(out["parasparamRt"], 22)
        self.assertEqual(out["parasparamEpsLen"], 0)
        self.assertEqual(out["britishEpsLen"], 2)
        self.assertFalse(out["britishCountOnly"])
        self.assertEqual(out["uppumTotal"], 2400)
        self.assertEqual(out["uppumRt"], 30)
        self.assertEqual(out["dulhanTotal"], 700)
        self.assertEqual(out["dulhanYear"], 2006)
        self.assertEqual(out["ideaStarTotal"], 330)
        self.assertEqual(out["ideaStarRt"], 75)
        self.assertEqual(out["ideaStarNet"], "Asianet")
        self.assertEqual(out["familyManEpsLen"], 19)
        self.assertFalse(out["familyManCountOnly"])
        self.assertFalse(out["flashedCountOnNormal"])
        self.assertTrue(out["showedLoadingSeasons"])
        self.assertEqual(out["partialStatus"], "stopped")
        self.assertEqual(out["partialWatched"], 350)
        self.assertEqual(out["partialTotal"], 1173)
        self.assertEqual(out["partialMins"], 350 * 22)
        self.assertEqual(out["fullStatus"], "completed")
        self.assertEqual(out["fullWatched"], 1173)
        self.assertEqual(out["bigWatchedPass2"], 450)
        self.assertEqual(out["bigTotalPass2"], 600)
        self.assertEqual(out["bigLastNumPass2"], 450)
        self.assertEqual(out["bigRepoWatchedLen"], 450)
        self.assertEqual(out["bigTrimmedWatched"], 200)
        self.assertTrue(out["fmHasCleanSeasonUiOnly"])
        self.assertEqual(out["fmWatchedPartial"], 12)
        self.assertEqual(out["fmTotalPartial"], 19)
        self.assertEqual(out["fmStatusPartial"], "stopped")
        self.assertEqual(out["fmLastCodePartial"], "S02E02")
        self.assertEqual(out["fmWatchedFull"], 19)
        self.assertEqual(out["fmStatusFull"], "completed")

    def test_upcoming_refresh_non_completed_and_broken_indian_seasons(self):
        """Verifies Upcoming refreshes newly announced release dates on in_progress/watchlist/legacy-Ended shows, resolves broken multi-season Indian stubs, and preserves clean 1-season web miniseries."""
        import re as _re

        def load_cf_module(rel_path):
            with open(os.path.join(ROOT_DIR, "cloudflare", "src", *rel_path), "r", encoding="utf-8") as f:
                src = f.read()
            src = _re.sub(r"^import\s+.*?;\s*$", "", src, flags=_re.MULTILINE)
            return src.replace("export class ", "class ").replace("export function ", "function ").replace("export const ", "const ")

        catalog_src = load_cf_module(("services", "LiveCatalogService.js"))
        view_src = load_cf_module(("ui", "WebAppView.js"))
        harness = (
            catalog_src
            + "\n"
            + view_src
            + """
            (async () => {
              const sStart = WEB_APP_HTML.lastIndexOf("<script>") + 8;
              const sEnd = WEB_APP_HTML.lastIndexOf("</script>");
              const clientJs = WEB_APP_HTML.slice(sStart, sEnd).replace("const app = new TvTimeClient();", "globalThis.TvTimeClientClass = TvTimeClient;");
              (new Function(clientJs))();
              const app = Object.create(globalThis.TvTimeClientClass.prototype);
              app.persistState = async () => {};

              // 1. Upcoming refresh for in_progress, watchlist (interested), and legacy tvmaze Ended->Returning shows
              app.state = {
                shows: [
                  {
                    id: "tmdb_tv_90282",
                    tmdbId: 90282,
                    title: "The Morning Show",
                    year: 2019,
                    status: "Ended",
                    userStatus: "in_progress",
                    totalEpisodes: 10,
                    completedSeasons: { "1": 5 },
                    watchedEpisodes: ["S02E01"],
                    episodes: [
                      { code: "S02E01", season: 2, number: 1, title: "Ep 1", airDate: "2021-09-17" },
                      { code: "S02E02", season: 2, number: 2, title: "Ep 2", airDate: "2021-09-24" },
                      { code: "S02E03", season: 2, number: 3, title: "Ep 3", airDate: "2021-10-01" },
                      { code: "S02E04", season: 2, number: 4, title: "Ep 4", airDate: "2021-10-08" },
                      { code: "S02E05", season: 2, number: 5, title: "Ep 5", airDate: "2021-10-15" }
                    ]
                  },
                  {
                    id: "tmdb_tv_224372",
                    tmdbId: 224372,
                    title: "A Knight of the Seven Kingdoms",
                    year: 2026,
                    status: "Returning Series",
                    userStatus: "interested",
                    totalEpisodes: 6,
                    completedSeasons: {},
                    watchedEpisodes: [],
                    episodes: []
                  },
                  {
                    id: "tvmaze_41632",
                    tvmazeId: 41632,
                    tmdbId: 0,
                    title: "Emily in Paris",
                    year: 2020,
                    status: "Ended",
                    userStatus: "completed",
                    totalEpisodes: 50,
                    completedSeasons: {},
                    watchedEpisodes: [],
                    episodes: []
                  }
                ],
                movies: []
              };

              globalThis.fetch = async (url) => {
                const u = String(url);
                if (u.includes("tmdb_tv_90282")) {
                  const fullEps = [];
                  for (let i = 1; i <= 5; i++) fullEps.push({ code: "S01E0" + i, season: 1, number: i, title: "S1E" + i, airDate: "2019-11-0" + i });
                  for (let i = 1; i <= 5; i++) fullEps.push({ code: "S02E0" + i, season: 2, number: i, title: "S2E" + i, airDate: "2021-09-1" + i });
                  fullEps.push({ code: "S03E01", season: 3, number: 1, title: "Season 3 Premiere", airDate: "2099-03-10" });
                  return { ok: true, json: async () => ({ status: "Returning Series", episodes: fullEps }) };
                }
                if (u.includes("tmdb_tv_224372")) {
                  const fullEps = [];
                  for (let i = 1; i <= 6; i++) fullEps.push({ code: "S01E0" + i, season: 1, number: i, title: "Ep " + i, airDate: "2026-06-0" + i });
                  fullEps.push({ code: "S02E01", season: 2, number: 1, title: "Season 2 Premiere", airDate: "2099-04-20" });
                  return { ok: true, json: async () => ({ status: "Returning Series", episodes: fullEps }) };
                }
                if (u.includes("tvmazeId=41632")) {
                  const fullEps = [];
                  for (let i = 1; i <= 50; i++) fullEps.push({ code: "S01E" + String(i).padStart(2, "0"), season: 1, number: i, title: "Ep " + i, airDate: "2022-01-01" });
                  fullEps.push({ code: "S06E01", season: 6, number: 1, title: "Bonjour Again", airDate: "2099-12-24" });
                  return { ok: true, json: async () => ({ status: "Returning Series", episodes: fullEps }) };
                }
                // 2. Broken Indian multi-season stub (D3: Dil Dostii Dance: S1=1 ep, S12=1 ep -> 857 on Wikipedia)
                if (u.includes("api.themoviedb.org/3/tv/41519?append_to_response=credits")) {
                  return {
                    ok: true,
                    json: async () => ({
                      id: 41519,
                      name: "D3: Dil Dostii Dance",
                      first_air_date: "2013-04-11",
                      original_language: "hi",
                      origin_country: ["IN"],
                      episode_run_time: [],
                      seasons: [{ season_number: 1, episode_count: 1 }, { season_number: 12, episode_count: 1 }]
                    })
                  };
                }
                if (u.includes("api.themoviedb.org/3/tv/41519?append_to_response=season/1,season/12")) {
                  return {
                    ok: true,
                    json: async () => ({
                      "season/1": { episodes: [{ episode_number: 1, name: "Episode 1", air_date: "2013-04-11" }] },
                      "season/12": { episodes: [{ episode_number: 1, name: "Episode 1", air_date: "2014-01-01" }] }
                    })
                  };
                }
                if (u.includes("en.wikipedia.org") && u.includes("list=search") && u.includes("Dil%20Dostii%20Dance")) {
                  return { ok: true, json: async () => ({ query: { search: [{ title: "Dil Dosti Dance" }] } }) };
                }
                if (u.includes("en.wikipedia.org") && u.includes("Dil%20Dosti%20Dance")) {
                  return {
                    ok: true,
                    json: async () => ({
                      query: {
                        pages: {
                          "501": {
                            pageid: 501,
                            title: "Dil Dosti Dance",
                            revisions: [{ "*": "{{Infobox television | country = India | network = [[Channel V India]] | first_aired = {{Start date|2011|4|11}} | last_aired = {{End date|2015|1|9}} | num_episodes = 857 | runtime = 22 minutes }}" }]
                          }
                        }
                      }
                    })
                  };
                }
                // 3. Clean 2024 1-season Malayalam YouTube miniseries (JAM!: 3 eps) must NOT be hijacked by 1999 Jam! (456 eps)
                if (u.includes("api.themoviedb.org/3/tv/271476?append_to_response=credits")) {
                  return {
                    ok: true,
                    json: async () => ({
                      id: 271476,
                      name: "JAM!",
                      first_air_date: "2024-08-10",
                      original_language: "ml",
                      origin_country: ["IN"],
                      episode_run_time: [25],
                      seasons: [{ season_number: 1, episode_count: 3 }]
                    })
                  };
                }
                if (u.includes("api.themoviedb.org/3/tv/271476?append_to_response=season/1")) {
                  return {
                    ok: true,
                    json: async () => ({
                      "season/1": {
                        episodes: [
                          { episode_number: 1, name: "Part 1", air_date: "2024-08-10", runtime: 24 },
                          { episode_number: 2, name: "Part 2", air_date: "2024-08-17", runtime: 25 },
                          { episode_number: 3, name: "Part 3", air_date: "2024-08-24", runtime: 26 }
                        ]
                      }
                    })
                  };
                }
                if (u.includes("en.wikipedia.org") && u.includes("JAM")) {
                  return {
                    ok: true,
                    json: async () => ({
                      query: {
                        pages: {
                          "601": {
                            pageid: 601,
                            title: "Jam!",
                            revisions: [{ "*": "{{Infobox television | country = United Kingdom | network = [[Cartoon Network]] | first_aired = {{Start date|1999|3|1}} | num_episodes = 456 }}" }]
                          }
                        }
                      }
                    })
                  };
                }
                return { ok: true, json: async () => ({ results: [] }) };
              };

              await app.refreshUpcomingEpisodesIfNeeded();
              const tmsNext = app.findNextUpcomingEpisode(app.state.shows[0], "2026-11-25");
              const tmsProg = app.computeShowProgress(app.state.shows[0], "2026-11-25");
              const knightNext = app.findNextUpcomingEpisode(app.state.shows[1], "2026-11-25");
              const emilyNext = app.findNextUpcomingEpisode(app.state.shows[2], "2026-11-25");

              const d3Res = await LiveCatalogService.fetchFullShowFromTvMaze("tmdb_tv_41519", {
                title: "D3: Dil Dostii Dance",
                fetchImpl: globalThis.fetch,
                env: { TMDB_API_KEY: "dummy" }
              });
              const jamRes = await LiveCatalogService.fetchFullShowFromTvMaze("tmdb_tv_271476", {
                title: "JAM!",
                fetchImpl: globalThis.fetch,
                env: { TMDB_API_KEY: "dummy" }
              });

              const out = JSON.stringify({
                tmsNextCode: tmsNext?.ep?.code,
                tmsWatchedPreserved: tmsProg.watchedCount,
                knightNextCode: knightNext?.ep?.code,
                emilyNextCode: emilyNext?.ep?.code,
                emilyStatus: app.state.shows[2].status,
                d3CountOnly: Boolean(d3Res?.countOnly),
                d3Total: d3Res?.totalEpisodes,
                jamCountOnly: Boolean(jamRes?.countOnly),
                jamEpsLen: jamRes?.episodes?.length
              });
              if (typeof console !== "undefined" && console.log) console.log(out);
              else if (typeof print !== "undefined") print(out);
            })();
            """
        )
        out = self._run_js(harness)
        self.assertEqual(out["tmsNextCode"], "S03E01")
        self.assertEqual(out["tmsWatchedPreserved"], 6)
        self.assertEqual(out["knightNextCode"], "S02E01")
        self.assertEqual(out["emilyNextCode"], "S06E01")
        self.assertEqual(out["emilyStatus"], "Returning Series")
        self.assertTrue(out["d3CountOnly"])
        self.assertEqual(out["d3Total"], 857)
        self.assertFalse(out["jamCountOnly"])
        self.assertEqual(out["jamEpsLen"], 3)

    def test_public_repo_security_invariants(self):
        """Verifies no hardcoded Firebase project IDs, API keys, or unencrypted APK artifacts exist in tracked files."""
        proc = subprocess.run(
            ["git", "ls-files"],
            cwd=ROOT_DIR,
            capture_output=True,
            text=True,
            check=True,
        )
        tracked_files = [f.strip() for f in proc.stdout.splitlines() if f.strip()]
        forbidden_Files = {"local.properties", "android/local.properties", "google-services.json", "android/app/google-services.json"}
        for tf in tracked_files:
            self.assertNotIn(tf, forbidden_Files, f"Sensitive config file {tf} must not be tracked in git")
            self.assertFalse(tf.endswith((".jks", ".keystore", ".apk")), f"Binary key/apk {tf} must not be tracked in git")
            if tf == "tests/test_worker.py" or tf.endswith(".jar"):
                continue
            full_path = os.path.join(ROOT_DIR, tf)
            if os.path.isfile(full_path):
                with open(full_path, "r", encoding="utf-8", errors="ignore") as f:
                    content = f.read()
                self.assertNotIn("places-tracker-989b5", content, f"Hardcoded Firebase project ID found in {tf}")
                self.assertNotIn("AIzaSy", content, f"Hardcoded Google API key found in {tf}")
                self.assertNotIn("GOCSPX-", content, f"Hardcoded Google OAuth secret found in {tf}")


if __name__ == "__main__":
    unittest.main()
