import json
import os
import shutil
import subprocess
import unittest

ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


class TestCloudflareWorkerEngine(unittest.TestCase):
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
            const repo = new FirestoreRepository({ FIREBASE_PROJECT_ID: 'places-tracker-989b5' });
            const defState = repo.defaultState('madhavsankar');
            const stats = LiveCatalogService.computeStats(defState);
            const docUrl = repo.docUrlForUser('madhavsankar');
            const out = JSON.stringify({ code, stats, docUrl, showsCount: defState.shows.length });
            if (typeof console !== 'undefined' && console.log) console.log(out);
            else if (typeof print !== 'undefined') print(out);
            """
        )

        node_bin = shutil.which("node")
        jsc_bin = "/System/Library/Frameworks/JavaScriptCore.framework/Versions/Current/Helpers/jsc"
        cmd = [node_bin, "-e", harness] if node_bin else [jsc_bin, "-e", harness]
        proc = subprocess.run(cmd, cwd=ROOT_DIR, capture_output=True, text=True, check=True)
        out = json.loads(proc.stdout.strip().splitlines()[-1])

        self.assertEqual(out["code"], "S02E04")
        self.assertIn("tvtime_state/user_state_madhavsankar", out["docUrl"])
        self.assertEqual(out["showsCount"], 3)
        self.assertGreater(out["stats"]["totalEpisodesWatched"], 0)
        self.assertGreater(out["stats"]["totalMoviesWatched"], 0)

    def test_local_python_server_helpers(self):
        import server

        st = server.default_user_state("madhavsankar")
        stats = server.compute_stats(st)
        self.assertEqual(st["userId"], "madhavsankar")
        self.assertEqual(len(st["shows"]), 3)
        self.assertEqual(st["shows"][0]["tvmazeId"], 44933)
        self.assertGreater(stats["totalMins"], 1000)


if __name__ == "__main__":
    unittest.main()
