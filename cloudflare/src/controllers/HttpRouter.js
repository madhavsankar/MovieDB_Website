/**
 * HTTP & REST API Controller for TV Time: Show & Movie Tracker Cloudflare Worker.
 */

import { FirestoreRepository } from "../repositories/FirestoreRepository.js";
import { LiveCatalogService } from "../services/LiveCatalogService.js";
import { McpRouter } from "../mcp/McpRouter.js";
import { WEB_APP_HTML } from "../ui/WebAppView.js";

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization, X-User-Id, X-Google-Id-Token",
};

const VERIFIED_GOOGLE_TOKENS = new Map();

export class HttpRouter {
  constructor(env) {
    this.env = env;
    this.repo = new FirestoreRepository(env);
    this.mcp = new McpRouter(this.repo);
  }

  _getGoogleClientId() {
    return String(this.env?.GOOGLE_CLIENT_ID || "").trim();
  }

  _isEmailAllowed(email) {
    const cleanEmail = String(email || "").trim().toLowerCase();
    if (!cleanEmail) return false;
    const allowedRaw = String(this.env?.ALLOWED_EMAILS || "").trim();
    if (!allowedRaw) return false;
    const allowedSet = new Set(
      allowedRaw
        .split(",")
        .flatMap((entry) => {
          const clean = entry.trim().toLowerCase();
          if (!clean) return [];
          return [clean, clean.split("@")[0], FirestoreRepository.sanitizeUserId(clean)];
        })
    );
    return allowedSet.has(cleanEmail) || allowedSet.has(cleanEmail.split("@")[0]);
  }

  async _verifyGoogleRequest(request, url) {
    const cfAccessEmail = (
      request.headers.get("Cf-Access-Authenticated-User-Email") || ""
    )
      .trim()
      .toLowerCase();
    if (cfAccessEmail && this._isEmailAllowed(cfAccessEmail)) {
      return { ok: true, email: cfAccessEmail };
    }

    const authHeader = request.headers.get("Authorization") || "";
    const bearerToken = authHeader.startsWith("Bearer ")
      ? authHeader.slice(7).trim()
      : "";
    const token =
      bearerToken ||
      (request.headers.get("X-Google-Id-Token") || "").trim() ||
      (url.searchParams.get("id_token") || "").trim();

    if (!token) {
      return {
        ok: false,
        status: 401,
        error: "Please sign in with your Google account.",
      };
    }

    let verifiedEmail = "";
    const cached = VERIFIED_GOOGLE_TOKENS.get(token);
    if (cached && cached.expMs > Date.now()) {
      verifiedEmail = cached.email;
    } else {
      try {
        const res = await fetch(
          `https://oauth2.googleapis.com/tokeninfo?id_token=${encodeURIComponent(token)}`
        );
        if (!res.ok) {
          return {
            ok: false,
            status: 401,
            error: "Google session expired or invalid. Please sign in again.",
          };
        }
        const info = await res.json();
        const expectedAud = this._getGoogleClientId();
        if (expectedAud && info.aud !== expectedAud) {
          return {
            ok: false,
            status: 401,
            error: "Invalid Google OAuth client audience.",
          };
        }
        if (String(info.email_verified) !== "true" || !info.email) {
          return {
            ok: false,
            status: 401,
            error: "Google account email is not verified.",
          };
        }
        const expMs = Number(info.exp || 0) * 1000;
        if (expMs <= Date.now()) {
          return {
            ok: false,
            status: 401,
            error: "Google session expired. Please sign in again.",
          };
        }
        verifiedEmail = String(info.email).trim().toLowerCase();
        VERIFIED_GOOGLE_TOKENS.set(token, { email: verifiedEmail, expMs });
      } catch (_) {
        return {
          ok: false,
          status: 401,
          error: "Unable to verify Google token.",
        };
      }
    }

    if (!this._isEmailAllowed(verifiedEmail)) {
      return {
        ok: false,
        status: 403,
        error: `Access denied for ${verifiedEmail}. Only allowlisted Google accounts are permitted.`,
      };
    }

    return { ok: true, email: verifiedEmail };
  }

  json(data, status = 200) {
    return new Response(JSON.stringify(data), {
      status,
      headers: {
        "Content-Type": "application/json; charset=utf-8",
        ...CORS_HEADERS,
      },
    });
  }

  async handle(request) {
    if (request.method === "OPTIONS") {
      return this.json({ ok: true });
    }

    const url = new URL(request.url);
    const path = url.pathname;

    if (request.method === "GET" && path === "/api/auth-config") {
      return this.json({
        googleClientId: this._getGoogleClientId(),
      });
    }

    // Public catalog endpoints so live TMDB / TVMaze / iTunes search works seamlessly
    if (request.method === "GET" && path === "/api/search") {
      const q = url.searchParams.get("q") || "";
      const providers = url.searchParams.get("providers") || "";
      if (providers && !q.trim()) {
        const data = await LiveCatalogService.discoverByUsProviders(providers, { env: this.env });
        return this.json(data);
      }
      const data = await LiveCatalogService.searchMulti(q, { env: this.env });
      return this.json(data);
    }

    if (request.method === "GET" && path === "/api/discover-providers") {
      const providers = url.searchParams.get("providers") || "";
      const data = await LiveCatalogService.discoverByUsProviders(providers, { env: this.env });
      return this.json(data);
    }

    if (request.method === "GET" && path === "/api/show-details") {
      const id = url.searchParams.get("tvmazeId") || url.searchParams.get("id") || "";
      const show = await LiveCatalogService.fetchFullShowFromTvMaze(id, { env: this.env });
      if (!show) return this.json({ error: "Show not found" }, 404);
      return this.json(show);
    }

    let userParam = "default";
    if ((path.startsWith("/api/") && path !== "/api/health") || path === "/mcp") {
      const auth = await this._verifyGoogleRequest(request, url);
      if (!auth.ok) {
        return this.json(
          { error: auth.error, authRequired: true },
          auth.status || 401
        );
      }
      userParam = auth.email;
    }

    if (request.method === "GET") {
      if (path === "/" || path === "/index.html") {
        return new Response(WEB_APP_HTML, {
          status: 200,
          headers: {
            "Content-Type": "text/html; charset=utf-8",
            "Cache-Control": "no-cache, no-store, must-revalidate",
            ...CORS_HEADERS,
          },
        });
      }

      if (path === "/manifest.json") {
        return this.json({
          name: "TV Time: Show & Movie Tracker",
          short_name: "TV Time",
          start_url: "/",
          display: "standalone",
          background_color: "#09090b",
          theme_color: "#facc15",
        });
      }

      if (path === "/api/health") {
        return this.json({ status: "ok", service: "tvtime-tracker" });
      }

      if (path === "/api/state") {
        const state = await this.repo.getUserState(userParam);
        return this.json({
          ...state,
          stats: LiveCatalogService.computeStats(state),
        });
      }
    }

    if (request.method === "POST") {
      if (path === "/mcp") {
        return this.mcp.handle(request, userParam);
      }

      const body = await request.json().catch(() => ({}));

      if (path === "/api/state") {
        const state = {
          userId: FirestoreRepository.sanitizeUserId(userParam),
          email: userParam,
          shows: Array.isArray(body.shows) ? body.shows : [],
          movies: Array.isArray(body.movies) ? body.movies : [],
          actors: Array.isArray(body.actors) ? body.actors : [],
          followingUsers: Array.isArray(body.followingUsers) ? body.followingUsers : [],
        };
        await this.repo.saveUserState(userParam, state);
        return this.json({
          ...state,
          stats: LiveCatalogService.computeStats(state),
        });
      }
    }

    return this.json({ error: "Not found" }, 404);
  }
}

