#!/usr/bin/env python3
"""
Local Web & API Server for TV Time: Show & Movie Tracker.
Mirrors the Cloudflare Worker (`cloudflare/worker.js`) so you can run and test the full
Web App locally on macOS (`python3 server.py`) with live Firebase Cloud Firestore sync
(`places-tracker-989b5` -> `tvtime_state/user_state_{userId}`), TVMaze API, and Apple iTunes Search API.
"""

import json
import os
import re
import subprocess
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT_DIR = os.path.dirname(os.path.abspath(__file__))
JSC_BIN = "/System/Library/Frameworks/JavaScriptCore.framework/Versions/Current/Helpers/jsc"


def load_local_properties():
    props = {
        "FIREBASE_PROJECT_ID": os.environ.get("FIREBASE_PROJECT_ID", "places-tracker-989b5"),
        "DEFAULT_USER_ID": os.environ.get("DEFAULT_USER_ID", "madhavsankar"),
    }
    prop_path = os.path.join(ROOT_DIR, "android", "local.properties")
    if os.path.exists(prop_path):
        with open(prop_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                k, v = k.strip(), v.strip()
                if k and v:
                    props[k] = v
    return props


CONFIG = load_local_properties()
FIREBASE_PROJECT_ID = CONFIG.get("FIREBASE_PROJECT_ID", "places-tracker-989b5")


def load_web_app_html():
    view_path = os.path.join(ROOT_DIR, "cloudflare", "src", "ui", "WebAppView.js")
    with open(view_path, "r", encoding="utf-8") as f:
        src = f.read()
    start = src.find("<!DOCTYPE html>")
    end = src.rfind("</html>")
    if start != -1 and end != -1 and end > start:
        return src[start : end + len("</html>")]
    return "<h1>Error loading WebAppView.js</h1>"


def load_starter_seed():
    repo_path = os.path.join(ROOT_DIR, "cloudflare", "src", "repositories", "FirestoreRepository.js")
    with open(repo_path, "r", encoding="utf-8") as f:
        repo_src = (
            f.read()
            .replace("export const STARTER_SHOWS", "const STARTER_SHOWS")
            .replace("export const STARTER_MOVIES", "const STARTER_MOVIES")
            .replace("export const STARTER_ACTORS", "const STARTER_ACTORS")
            .replace("export class FirestoreRepository", "class FirestoreRepository")
        )
    harness = (
        repo_src
        + "\nconst r = new FirestoreRepository({FIREBASE_PROJECT_ID: 'places-tracker-989b5'});"
        + "\nprint(JSON.stringify(r.defaultState('madhavsankar')));"
    )
    proc = subprocess.run([JSC_BIN, "-e", harness], capture_output=True, text=True, check=True)
    return json.loads(proc.stdout.strip().splitlines()[-1])


STARTER_SEED = load_starter_seed()


def sanitize_user_id(raw):
    trimmed = str(raw or "").strip().lower()
    if not trimmed:
        return "default"
    without_domain = trimmed.split("@")[0].replace("_gmail.com", "")
    cleaned = re.sub(r"[^a-z0-9_.-]", "_", without_domain)[:64]
    return cleaned or "default"


def strip_html(html_str):
    return re.sub(r"<[^>]+>", "", str(html_str or "")).strip()


def format_ep_code(season, number):
    return f"S{int(season or 1):02d}E{int(number or 1):02d}"


def compute_stats(state):
    shows = state.get("shows") or []
    movies = state.get("movies") or []
    episode_mins = 0
    episodes_watched = 0
    for show in shows:
        watched_set = set(show.get("watchedEpisodes") or [])
        episodes_watched += len(watched_set)
        ep_map = {ep.get("code"): ep.get("runtimeMins") for ep in (show.get("episodes") or [])}
        default_rt = int(show.get("runtimeMins") or 45)
        for code in watched_set:
            episode_mins += int(ep_map.get(code) or default_rt)
    watched_movies = [m for m in movies if m.get("watched")]
    movie_mins = sum(
        int(m.get("runtimeMins") or 120) * (1 + int(m.get("rewatchCount") or 0))
        for m in watched_movies
    )
    return {
        "episodeMins": episode_mins,
        "episodesWatched": episodes_watched,
        "totalEpisodesWatched": episodes_watched,
        "showsTracked": len(shows),
        "movieMins": movie_mins,
        "moviesWatched": len(watched_movies),
        "totalMoviesWatched": len(watched_movies),
        "moviesTracked": len(movies),
        "totalMins": episode_mins + movie_mins,
    }


def http_get_json(url, timeout=10):
    req = urllib.request.Request(
        url,
        headers={"User-Agent": "TVTimeTracker/1.0 (macOS Local Web Server)"},
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def firestore_doc_url(user_id):
    clean = sanitize_user_id(user_id)
    if not FIREBASE_PROJECT_ID or clean == "default":
        return None
    return (
        f"https://firestore.googleapis.com/v1/projects/{FIREBASE_PROJECT_ID}"
        f"/databases/(default)/documents/tvtime_state/user_state_{clean}"
    )


def default_user_state(user_id):
    clean = sanitize_user_id(user_id)
    seed = json.loads(json.dumps(STARTER_SEED))
    seed["userId"] = clean
    seed["email"] = clean
    seed["updatedAt"] = datetime.now(timezone.utc).isoformat()
    return seed


def get_user_state_from_firestore(user_id):
    clean = sanitize_user_id(user_id)
    url = firestore_doc_url(clean)
    if not url:
        return default_user_state(clean)
    try:
        doc = http_get_json(url, timeout=8)
        payload_str = doc.get("fields", {}).get("payload", {}).get("stringValue")
        if not payload_str:
            return default_user_state(clean)
        parsed = json.loads(payload_str)
        return {
            "userId": clean,
            "email": parsed.get("email") or clean,
            "shows": parsed.get("shows") if isinstance(parsed.get("shows"), list) else [],
            "movies": parsed.get("movies") if isinstance(parsed.get("movies"), list) else [],
            "actors": parsed.get("actors") if isinstance(parsed.get("actors"), list) else default_user_state(clean)["actors"],
            "followingUsers": parsed.get("followingUsers") if isinstance(parsed.get("followingUsers"), list) else [],
            "updatedAt": parsed.get("updatedAt") or datetime.now(timezone.utc).isoformat(),
        }
    except Exception:
        return default_user_state(clean)


def save_user_state_to_firestore(user_id, state):
    clean = sanitize_user_id(user_id)
    url = firestore_doc_url(clean)
    if not url:
        return False
    shows = state.get("shows") if isinstance(state.get("shows"), list) else []
    movies = state.get("movies") if isinstance(state.get("movies"), list) else []
    actors = state.get("actors") if isinstance(state.get("actors"), list) else []
    following_users = state.get("followingUsers") if isinstance(state.get("followingUsers"), list) else []
    updated_at = datetime.now(timezone.utc).isoformat()

    payload_obj = {
        "userId": clean,
        "email": state.get("email") or clean,
        "shows": shows,
        "movies": movies,
        "actors": actors,
        "followingUsers": following_users,
        "updatedAt": updated_at,
    }
    firestore_body = {
        "fields": {
            "userId": {"stringValue": clean},
            "email": {"stringValue": payload_obj["email"]},
            "payload": {"stringValue": json.dumps(payload_obj)},
            "showCount": {"integerValue": str(len(shows))},
            "movieCount": {"integerValue": str(len(movies))},
            "actorCount": {"integerValue": str(len(actors))},
            "updatedAt": {"stringValue": updated_at},
        }
    }
    try:
        req = urllib.request.Request(
            url,
            data=json.dumps(firestore_body).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="PATCH",
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            return 200 <= resp.status < 300
    except Exception:
        return False


def search_multi(query):
    q = str(query or "").strip()
    if not q:
        return {"results": [], "shows": [], "movies": [], "actors": []}
    enc = urllib.parse.quote(q)

    def fetch_shows():
        try:
            items = http_get_json(f"https://api.tvmaze.com/search/shows?q={enc}")
            out = []
            for item in items[:12]:
                s = item.get("show") or {}
                if not s.get("id") or not s.get("name"):
                    continue
                img = (s.get("image") or {}).get("medium") or (s.get("image") or {}).get("original") or ""
                out.append({
                    "id": f"tvmaze_{s['id']}",
                    "tvmazeId": s["id"],
                    "mediaType": "show",
                    "title": s["name"],
                    "year": int(str(s.get("premiered") or "2024")[:4]) if str(s.get("premiered") or "")[:4].isdigit() else 2024,
                    "network": (s.get("network") or {}).get("name") or (s.get("webChannel") or {}).get("name") or "Streaming",
                    "status": s.get("status") or "Running",
                    "runtimeMins": int(s.get("averageRuntime") or s.get("runtime") or 45),
                    "genres": s.get("genres") or [],
                    "rating": float((s.get("rating") or {}).get("average") or 7.8),
                    "posterUrl": img.replace("http://", "https://"),
                    "overview": strip_html(s.get("summary") or ""),
                })
            return out
        except Exception:
            return []

    def fetch_movies():
        out = []
        seen = set()
        tmdb_token = (CONFIG.get("TMDB_READ_TOKEN") or os.environ.get("TMDB_READ_TOKEN") or "").strip()
        tmdb_key = (CONFIG.get("TMDB_API_KEY") or os.environ.get("TMDB_API_KEY") or "").strip()
        if tmdb_token or tmdb_key:
            try:
                tmdb_url = (
                    f"https://api.themoviedb.org/3/search/movie?query={enc}&include_adult=false&language=en-US&page=1"
                    if tmdb_token
                    else f"https://api.themoviedb.org/3/search/movie?query={enc}&include_adult=false&language=en-US&page=1&api_key={urllib.parse.quote(tmdb_key)}"
                )
                req_headers = {"User-Agent": "TVTimeTracker/1.0", "Accept": "application/json"}
                if tmdb_token:
                    req_headers["Authorization"] = f"Bearer {tmdb_token}"
                req = urllib.request.Request(tmdb_url, headers=req_headers)
                with urllib.request.urlopen(req, timeout=8) as r:
                    tmdb_data = json.loads(r.read().decode("utf-8"))
                for item in (tmdb_data.get("results") or [])[:12]:
                    title = item.get("title") or item.get("original_title")
                    if not item.get("id") or not title:
                        continue
                    rel = str(item.get("release_date") or "2024")[:4]
                    yr = int(rel) if rel.isdigit() else 2024
                    key = f"{title.lower()}_{yr}"
                    if key in seen:
                        continue
                    seen.add(key)
                    poster_path = item.get("poster_path") or ""
                    out.append({
                        "id": f"movie_{item['id']}",
                        "tmdbId": item["id"],
                        "mediaType": "movie",
                        "title": title,
                        "year": yr,
                        "runtimeMins": 125,
                        "genres": ["Movie"],
                        "director": "",
                        "streamingService": "Theaters / Streaming",
                        "rating": round(float(item.get("vote_average") or 7.8), 1),
                        "posterUrl": f"https://image.tmdb.org/t/p/w500{poster_path}" if poster_path else "",
                        "overview": item.get("overview") or "",
                    })
            except Exception:
                pass
        try:
            imdb_data = http_get_json(f"https://v3.sg.media-imdb.com/suggestion/x/{enc}.json")
            for item in (imdb_data.get("d") or []):
                if not item.get("id") or not item.get("l"):
                    continue
                qid = str(item.get("qid") or "").lower()
                if qid in ("movie", "feature", "tvmovie"):
                    key = f"{item['l'].lower()}_{item.get('y') or 2024}"
                    if key in seen:
                        continue
                    seen.add(key)
                    cast_list = [c.strip() for c in str(item.get("s") or "").split(",") if c.strip()]
                    out.append({
                        "id": f"movie_{item['id']}",
                        "tmdbId": int(re.sub(r"\D", "", str(item["id"])) or "0"),
                        "mediaType": "movie",
                        "title": item["l"],
                        "year": int(item.get("y") or 2024),
                        "runtimeMins": 125,
                        "genres": ["Movie"],
                        "cast": cast_list,
                        "director": cast_list[0] if cast_list else "",
                        "streamingService": "Theaters / Streaming",
                        "rating": 8.0,
                        "posterUrl": (item.get("i") or {}).get("imageUrl") or "",
                        "overview": f"Starring {item.get('s')}" if item.get("s") else "Feature Film",
                    })
        except Exception:
            pass
        try:
            data = http_get_json(f"https://itunes.apple.com/search?term={enc}&country=US&entity=movie&limit=10")
            for m in (data.get("results") or []):
                if not m.get("trackId") or not m.get("trackName"):
                    continue
                rel = str(m.get("releaseDate") or "2024")[:4]
                yr = int(rel) if rel.isdigit() else 2024
                key = f"{m['trackName'].lower()}_{yr}"
                if key in seen:
                    continue
                seen.add(key)
                art = re.sub(r"100x100bb\.jpg$", "600x900bb.jpg", str(m.get("artworkUrl100") or ""))
                out.append({
                    "id": f"movie_{m['trackId']}",
                    "tmdbId": m["trackId"],
                    "mediaType": "movie",
                    "title": m["trackName"],
                    "year": yr,
                    "runtimeMins": max(45, round(int(m.get("trackTimeMillis") or 7200000) / 60000)),
                    "genres": [m["primaryGenreName"]] if m.get("primaryGenreName") else ["Movie"],
                    "director": m.get("artistName") or "",
                    "streamingService": "Apple TV / Digital",
                    "rating": 7.8,
                    "posterUrl": art,
                    "overview": m.get("longDescription") or m.get("shortDescription") or "",
                })
        except Exception:
            pass
        return out

    def fetch_actors():
        try:
            items = http_get_json(f"https://api.tvmaze.com/search/people?q={enc}")
            out = []
            for item in items[:6]:
                p = item.get("person") or {}
                if not p.get("id") or not p.get("name"):
                    continue
                img = ((p.get("image") or {}).get("medium") or (p.get("image") or {}).get("original") or "").replace("http://", "https://")
                out.append({
                    "id": f"actor_{p['id']}",
                    "mediaType": "actor",
                    "name": p["name"],
                    "title": p["name"],
                    "year": int(str(p.get("birthday") or "1985")[:4]) if str(p.get("birthday") or "")[:4].isdigit() else 1985,
                    "role": "Acting",
                    "posterUrl": img,
                    "imageUrl": img,
                    "imdbUrl": f"https://www.imdb.com/find/?q={urllib.parse.quote(p['name'])}",
                    "knownFor": (p.get("country") or {}).get("name") or "Film & Television",
                    "overview": f"Actor ({(p.get('country') or {}).get('name') or 'International'})",
                })
            return out
        except Exception:
            return []

    with ThreadPoolExecutor(max_workers=3) as pool:
        f_s = pool.submit(fetch_shows)
        f_m = pool.submit(fetch_movies)
        f_a = pool.submit(fetch_actors)
        shows, movies, actors = f_s.result(), f_m.result(), f_a.result()
        return {
            "results": shows + movies + actors,
            "shows": shows,
            "movies": movies,
            "actors": actors,
        }


def fetch_full_show_from_tvmaze(tvmaze_id):
    num_id = re.sub(r"^tvmaze_", "", str(tvmaze_id or "").strip())
    if not num_id:
        return None
    try:
        s = http_get_json(f"https://api.tvmaze.com/shows/{urllib.parse.quote(num_id)}?embed[]=episodes&embed[]=cast")
        embedded_eps = (s.get("_embedded") or {}).get("episodes") or []
        embedded_cast = (s.get("_embedded") or {}).get("cast") or []
        episodes = []
        for ep in embedded_eps:
            if not ep.get("season") or not ep.get("number"):
                continue
            img = (ep.get("image") or {}).get("medium") or (ep.get("image") or {}).get("original") or ""
            episodes.append({
                "code": format_ep_code(ep["season"], ep["number"]),
                "season": int(ep["season"]),
                "number": int(ep["number"]),
                "title": ep.get("name") or f"Episode {ep['number']}",
                "airDate": ep.get("airdate") or "",
                "runtimeMins": int(ep.get("runtime") or s.get("averageRuntime") or s.get("runtime") or 45),
                "imageUrl": img.replace("http://", "https://"),
                "overview": strip_html(ep.get("summary") or ""),
            })
        cast_names = []
        for c in embedded_cast[:10]:
            name = (c.get("person") or {}).get("name")
            if name:
                cast_names.append(name)
        poster = (s.get("image") or {}).get("original") or (s.get("image") or {}).get("medium") or ""
        prem = str(s.get("premiered") or "2020")[:4]
        return {
            "id": f"tvmaze_{s['id']}",
            "tvmazeId": s["id"],
            "title": s.get("name") or "Show",
            "year": int(prem) if prem.isdigit() else 2020,
            "network": (s.get("network") or {}).get("name") or (s.get("webChannel") or {}).get("name") or "TV",
            "status": s.get("status") or "Running",
            "runtimeMins": int(s.get("averageRuntime") or s.get("runtime") or 45),
            "genres": s.get("genres") or [],
            "cast": cast_names,
            "rating": float((s.get("rating") or {}).get("average") or 0),
            "posterUrl": poster.replace("http://", "https://"),
            "overview": strip_html(s.get("summary") or ""),
            "userStatus": "watching",
            "userRating": 0,
            "reaction": "",
            "isFavorite": False,
            "rewatchCount": 0,
            "addedAt": datetime.now(timezone.utc).isoformat(),
            "lastWatchedAt": datetime.now(timezone.utc).isoformat(),
            "watchedEpisodes": [],
            "episodes": episodes,
        }
    except Exception:
        return None


class TvTimeHttpHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def _send_json(self, data, status=200):
        body = json.dumps(data).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization, X-User-Id, X-Google-Id-Token")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_html(self, html_str, status=200):
        body = html_str.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Cache-Control", "no-cache, no-store, must-revalidate")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self._send_json({"ok": True})

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        qs = urllib.parse.parse_qs(parsed.query)

        if path in ("/", "/index.html"):
            self._send_html(load_web_app_html())
            return

        if path == "/manifest.json":
            self._send_json({
                "name": "TV Time: Show & Movie Tracker",
                "short_name": "TV Time",
                "start_url": "/",
                "display": "standalone",
                "background_color": "#09090b",
                "theme_color": "#facc15",
            })
            return

        if path == "/api/health":
            self._send_json({
                "status": "ok",
                "service": "tvtime-tracker",
                "firebaseProjectId": FIREBASE_PROJECT_ID,
            })
            return

        if path == "/api/auth-config":
            self._send_json({"googleClientId": ""})
            return

        if path == "/api/search":
            q = (qs.get("q") or [""])[0]
            self._send_json(search_multi(q))
            return

        if path == "/api/show-details":
            tvmaze_id = (qs.get("tvmazeId") or [""])[0]
            show = fetch_full_show_from_tvmaze(tvmaze_id)
            if not show:
                self._send_json({"error": "Show not found"}, 404)
                return
            self._send_json(show)
            return

        if path == "/api/state":
            header_user = self.headers.get("X-User-Id") or ""
            target_user = (qs.get("user") or [header_user or CONFIG.get("DEFAULT_USER_ID", "madhavsankar")])[0]
            state = get_user_state_from_firestore(target_user)
            state["stats"] = compute_stats(state)
            self._send_json(state)
            return

        self._send_json({"error": "Not found"}, 404)

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        length = int(self.headers.get("Content-Length") or 0)
        raw_body = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
        try:
            body = json.loads(raw_body)
        except Exception:
            body = {}

        if path == "/api/state":
            header_user = self.headers.get("X-User-Id") or ""
            user_id = sanitize_user_id(body.get("userId") or header_user or CONFIG.get("DEFAULT_USER_ID", "madhavsankar"))
            state = {
                "userId": user_id,
                "email": body.get("email") or user_id,
                "shows": body.get("shows") if isinstance(body.get("shows"), list) else [],
                "movies": body.get("movies") if isinstance(body.get("movies"), list) else [],
                "actors": body.get("actors") if isinstance(body.get("actors"), list) else [],
                "followingUsers": body.get("followingUsers") if isinstance(body.get("followingUsers"), list) else [],
            }
            saved = save_user_state_to_firestore(user_id, state)
            state["savedToFirestore"] = saved
            state["stats"] = compute_stats(state)
            self._send_json(state)
            return

        self._send_json({"error": "Not found"}, 404)


def run_server(port=8095):
    server = ThreadingHTTPServer(("0.0.0.0", port), TvTimeHttpHandler)
    print(f"TV Time Web App listening on http://localhost:{port} (Firestore: {FIREBASE_PROJECT_ID})", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8095"))
    run_server(port)
