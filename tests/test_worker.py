import json
import os
import shutil
import subprocess
import unittest

ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


class TestCloudflareWorkerEngine(unittest.TestCase):
    def _run_js(self, harness: str) -> dict:
        node_bin = shutil.which("node")
        jsc_bin = "/System/Library/Frameworks/JavaScriptCore.framework/Versions/Current/Helpers/jsc"
        cmd = [node_bin, "-e", harness] if node_bin else [jsc_bin, "-e", harness]
        proc = subprocess.run(cmd, cwd=ROOT_DIR, capture_output=True, text=True, check=True)
        return json.loads(proc.stdout.strip().splitlines()[-1])

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
            const stats = LiveCatalogService.computeStats(defState);
            const docUrl = repo.docUrlForUser('demo_user');
            const normalizedUs = LiveCatalogService.normalizeUsProvidersFromTmdb({ flatrate: [{ provider_id: 8, provider_name: 'Netflix' }, { provider_id: 9, provider_name: 'Amazon Prime Video' }] }, '');
            const out = JSON.stringify({ code, stats, docUrl, showsCount: defState.shows.length, normalizedUs, platformsCount: LiveCatalogService.US_STREAMING_PLATFORMS.length });
            if (typeof console !== 'undefined' && console.log) console.log(out);
            else if (typeof print !== 'undefined') print(out);
            """
        )

        out = self._run_js(harness)
        self.assertEqual(out["code"], "S02E04")
        self.assertIn("tvtime_state/user_state_demo_user", out["docUrl"])
        self.assertEqual(out["showsCount"], 3)
        self.assertEqual(out["normalizedUs"], "Netflix / Prime Video")
        self.assertEqual(out["platformsCount"], 8)
        self.assertGreater(out["stats"]["totalEpisodesWatched"], 0)
        self.assertGreater(out["stats"]["totalMoviesWatched"], 0)
        self.assertGreater(out["stats"]["totalHours"], 0)
        self.assertEqual(out["stats"]["totalSeriesCount"], 3)
        self.assertEqual(out["stats"]["totalMoviesCount"], 3)

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
            globalThis.window = {
              location: { search: "" },
              history: {
                replaceState: () => {},
                pushState: () => {}
              },
              addEventListener: (evt, fn) => {
                if (evt === "popstate") popStateHandler = fn;
              }
            };
            globalThis.document = {
              getElementById: getEl,
              addEventListener: () => {},
              scrollingElement: { scrollTop: 0 },
              documentElement: { scrollTop: 0 }
            };
            globalThis.localStorage = {
              getItem: () => null,
              setItem: () => {},
              removeItem: () => {}
            };
            globalThis.fetch = async () => ({ ok: true, json: async () => ({ results: [] }) });

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
              futureUpcomingBucket: futureUpcoming ? futureUpcoming.bucket : ""
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
        self.assertIn("dao.deleteShow(show.id)", dialogs)
        self.assertIn("dao.deleteMovie(movie.id)", dialogs)

    def test_local_python_server_helpers(self):
        import server

        st = server.default_user_state("demo_user")
        stats = server.compute_stats(st)
        html = server.load_web_app_html()
        self.assertTrue(html.startswith("<!DOCTYPE html>"))
        self.assertNotIn("export const WEB_APP_HTML", html)
        self.assertEqual(st["userId"], "demo_user")
        self.assertEqual(len(st["shows"]), 3)
        self.assertEqual(st["shows"][0]["tvmazeId"], 44933)
        self.assertGreater(stats["totalMins"], 1000)

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
