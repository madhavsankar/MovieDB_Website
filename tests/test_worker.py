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
                if (rawUrl.includes("/person/6193/movie_credits")) {
                  return {
                    ok: true,
                    json: async () => ({
                      cast: [
                        { id: 27205, title: "Inception", release_date: "2010-07-16", popularity: 120, vote_count: 35000, vote_average: 8.4, genre_ids: [28, 878], character: "Dom Cobb", poster_path: "/inc.jpg", overview: "Dream heist." },
                        { id: 597, title: "Titanic", release_date: "1997-11-18", popularity: 110, vote_count: 24000, vote_average: 7.9, genre_ids: [18, 10749], character: "Jack Dawson", poster_path: "/tit.jpg", overview: "Ocean liner." },
                        { id: 999999, title: "Archive Self Short", release_date: "2020-01-01", popularity: 5, vote_count: 12, vote_average: 6.0, genre_ids: [99], character: "Self (archive footage)", overview: "Doc." }
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
                allMovies: res.results.every((r) => r.mediaType === "movie"),
                titles: res.results.map((r) => r.title),
                firstDirector: res.results[0] ? res.results[0].director : "",
                firstCast: res.results[0] ? res.results[0].cast : []
              });
              if (typeof console !== "undefined" && console.log) console.log(out);
              else if (typeof print !== "undefined") print(out);
            })();
            """
        )
        out = self._run_js(harness)
        self.assertFalse(out["hasActorItems"])
        self.assertTrue(out["allMovies"])
        self.assertEqual(out["titles"], ["Inception", "Titanic"])
        self.assertEqual(out["firstDirector"], "Starring Leonardo DiCaprio")
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
                return { ok: true, json: async () => ({ results: [] }) };
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
