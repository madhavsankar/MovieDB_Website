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
const TEN_YEARS_SECONDS = 10 * 365 * 86400; // 315,360,000 seconds (10 years)
let CACHED_GOOGLE_JWKS = null;
let CACHED_GOOGLE_JWKS_AT = 0;

function toBase64Url(bytes) {
  let binary = "";
  const arr = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  for (let i = 0; i < arr.length; i++) {
    binary += String.fromCharCode(arr[i]);
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function fromBase64Url(b64url) {
  const b64 = String(b64url || "").replace(/-/g, "+").replace(/_/g, "/");
  const padded = b64 + "===".slice((b64.length + 3) % 4);
  const binary = atob(padded);
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    out[i] = binary.charCodeAt(i);
  }
  return out;
}

function parseCookieValue(cookieHeader, name) {
  if (!cookieHeader) return "";
  const parts = String(cookieHeader).split(";");
  for (const part of parts) {
    const eq = part.indexOf("=");
    if (eq === -1) continue;
    const k = part.slice(0, eq).trim();
    if (k === name) {
      return decodeURIComponent(part.slice(eq + 1).trim());
    }
  }
  return "";
}

export class HttpRouter {
  constructor(env) {
    this.env = env;
    this.repo = new FirestoreRepository(env);
    this.mcp = new McpRouter(this.repo);
  }

  _getGoogleClientId() {
    return String(this.env?.GOOGLE_CLIENT_ID || "").trim();
  }

  async _getSessionHmacKey() {
    const secretMaterial = [
      "tvtime_10yr_session_v1",
      String(this.env?.SESSION_SECRET || ""),
      String(this.env?.GOOGLE_CLIENT_ID || ""),
      String(this.env?.FIREBASE_PROJECT_ID || ""),
      String(this.env?.ALLOWED_EMAILS || ""),
    ].join("::");
    const enc = new TextEncoder().encode(secretMaterial);
    return crypto.subtle.importKey(
      "raw",
      enc,
      { name: "HMAC", hash: "SHA-256" },
      false,
      ["sign", "verify"]
    );
  }

  async _mintTenYearSessionToken(email) {
    const cleanEmail = String(email || "").trim().toLowerCase();
    const nowSec = Math.floor(Date.now() / 1000);
    const payload = {
      email: cleanEmail,
      email_verified: true,
      iat: nowSec,
      exp: nowSec + TEN_YEARS_SECONDS,
    };
    const payloadB64 = toBase64Url(new TextEncoder().encode(JSON.stringify(payload)));
    const signingInput = `tvtime_session.${payloadB64}`;
    const key = await this._getSessionHmacKey();
    const sigBuf = await crypto.subtle.sign(
      "HMAC",
      key,
      new TextEncoder().encode(signingInput)
    );
    return `${signingInput}.${toBase64Url(sigBuf)}`;
  }

  async _verifyTenYearSessionToken(token) {
    if (!token || !String(token).startsWith("tvtime_session.")) return null;
    try {
      const parts = String(token).split(".");
      if (parts.length !== 3) return null;
      const signingInput = `${parts[0]}.${parts[1]}`;
      const sigBytes = fromBase64Url(parts[2]);
      const key = await this._getSessionHmacKey();
      const valid = await crypto.subtle.verify(
        "HMAC",
        key,
        sigBytes,
        new TextEncoder().encode(signingInput)
      );
      if (!valid) return null;
      const payload = JSON.parse(new TextDecoder().decode(fromBase64Url(parts[1])));
      if (!payload || !payload.email || !payload.exp) return null;
      if (Number(payload.exp) * 1000 <= Date.now()) return null;
      return String(payload.email).trim().toLowerCase();
    } catch (_) {
      return null;
    }
  }

  async _verifyGoogleJwtViaJwks(token) {
    try {
      const parts = String(token || "").split(".");
      if (parts.length !== 3) return null;
      const header = JSON.parse(new TextDecoder().decode(fromBase64Url(parts[0])));
      const payload = JSON.parse(new TextDecoder().decode(fromBase64Url(parts[1])));
      if (!header || !header.kid || !payload || !payload.email) return null;

      const expectedAud = this._getGoogleClientId();
      if (expectedAud && payload.aud !== expectedAud) return null;
      if (
        payload.iss !== "https://accounts.google.com" &&
        payload.iss !== "accounts.google.com"
      ) {
        return null;
      }
      if (String(payload.email_verified) !== "true") return null;

      const now = Date.now();
      if (!CACHED_GOOGLE_JWKS || now - CACHED_GOOGLE_JWKS_AT > 3600_000) {
        const res = await fetch("https://www.googleapis.com/oauth2/v3/certs");
        if (res.ok) {
          const data = await res.json();
          if (Array.isArray(data?.keys)) {
            CACHED_GOOGLE_JWKS = data.keys;
            CACHED_GOOGLE_JWKS_AT = now;
          }
        }
      }
      const jwk = (CACHED_GOOGLE_JWKS || []).find((k) => k.kid === header.kid);
      if (!jwk) return null;

      const pubKey = await crypto.subtle.importKey(
        "jwk",
        jwk,
        { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
        false,
        ["verify"]
      );
      const signingInput = new TextEncoder().encode(`${parts[0]}.${parts[1]}`);
      const sigBytes = fromBase64Url(parts[2]);
      const verified = await crypto.subtle.verify(
        "RSASSA-PKCS1-v1_5",
        pubKey,
        sigBytes,
        signingInput
      );
      if (!verified) return null;
      return String(payload.email).trim().toLowerCase();
    } catch (_) {
      return null;
    }
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
      const sessionToken = await this._mintTenYearSessionToken(cfAccessEmail);
      return { ok: true, email: cfAccessEmail, sessionToken };
    }

    const authHeader = request.headers.get("Authorization") || "";
    const bearerToken = authHeader.startsWith("Bearer ")
      ? authHeader.slice(7).trim()
      : "";
    const cookieHeader = request.headers.get("Cookie") || "";
    const cookieToken =
      parseCookieValue(cookieHeader, "tvtime_session") ||
      parseCookieValue(cookieHeader, "tvtime_auth_token");
    const token =
      bearerToken ||
      (request.headers.get("X-Google-Id-Token") || "").trim() ||
      cookieToken ||
      (url.searchParams.get("id_token") || "").trim();

    if (!token) {
      return {
        ok: false,
        status: 401,
        error: "Please sign in with your Google account.",
      };
    }

    // 1. Check if token is already a 10-year HMAC session token
    const sessionEmail = await this._verifyTenYearSessionToken(token);
    if (sessionEmail) {
      if (!this._isEmailAllowed(sessionEmail)) {
        return {
          ok: false,
          status: 403,
          error: `Access denied for ${sessionEmail}. Only allowlisted Google accounts are permitted.`,
        };
      }
      return { ok: true, email: sessionEmail, sessionToken: token };
    }

    // Check cookie fallback if bearerToken was an expired Google token
    if (cookieToken && cookieToken !== token) {
      const cookieSessionEmail = await this._verifyTenYearSessionToken(cookieToken);
      if (cookieSessionEmail && this._isEmailAllowed(cookieSessionEmail)) {
        return { ok: true, email: cookieSessionEmail, sessionToken: cookieToken };
      }
    }

    // 2. Verify Google ID token (in-memory cache -> tokeninfo -> Google JWKS RS256 verification)
    let verifiedEmail = "";
    const cached = VERIFIED_GOOGLE_TOKENS.get(token);
    if (cached && cached.expMs > Date.now()) {
      verifiedEmail = cached.email;
    } else {
      try {
        const res = await fetch(
          `https://oauth2.googleapis.com/tokeninfo?id_token=${encodeURIComponent(token)}`
        );
        if (res.ok) {
          const info = await res.json();
          const expectedAud = this._getGoogleClientId();
          if (expectedAud && info.aud !== expectedAud) {
            return {
              ok: false,
              status: 401,
              error: "Invalid Google OAuth client audience.",
            };
          }
          if (String(info.email_verified) === "true" && info.email) {
            verifiedEmail = String(info.email).trim().toLowerCase();
          }
        }
      } catch (_) {}

      // Fallback: Cryptographically verify Google RS256 signature via Google's public JWKS
      // so a previously signed-in user's Google JWT is seamlessly upgraded to a 10-year session token
      if (!verifiedEmail) {
        verifiedEmail = (await this._verifyGoogleJwtViaJwks(token)) || "";
      }

      if (!verifiedEmail) {
        return {
          ok: false,
          status: 401,
          error: "Google session invalid. Please sign in again.",
        };
      }

      VERIFIED_GOOGLE_TOKENS.set(token, {
        email: verifiedEmail,
        expMs: Date.now() + TEN_YEARS_SECONDS * 1000,
      });
    }

    if (!this._isEmailAllowed(verifiedEmail)) {
      return {
        ok: false,
        status: 403,
        error: `Access denied for ${verifiedEmail}. Only allowlisted Google accounts are permitted.`,
      };
    }

    const sessionToken = await this._mintTenYearSessionToken(verifiedEmail);
    return { ok: true, email: verifiedEmail, sessionToken };
  }

  json(data, status = 200, extraHeaders = {}) {
    return new Response(JSON.stringify(data), {
      status,
      headers: {
        "Content-Type": "application/json; charset=utf-8",
        ...CORS_HEADERS,
        ...extraHeaders,
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
      const page = Number(url.searchParams.get("page") || 1) || 1;
      if (providers && !q.trim()) {
        const data = await LiveCatalogService.discoverByUsProviders(providers, {
          page,
          env: this.env,
        });
        return this.json(data);
      }
      const data = await LiveCatalogService.searchMulti(q, { page, env: this.env });
      return this.json(data);
    }

    if (request.method === "GET" && path === "/api/discover-providers") {
      const providers = url.searchParams.get("providers") || "";
      const page = Number(url.searchParams.get("page") || 1) || 1;
      const data = await LiveCatalogService.discoverByUsProviders(providers, {
        page,
        env: this.env,
      });
      return this.json(data);
    }

    if (request.method === "GET" && path === "/api/recommendations") {
      const parseSeeds = (raw) =>
        String(raw || "")
          .split("|")
          .map((s) => s.trim())
          .filter(Boolean)
          .map((entry) => {
            const idx = entry.indexOf(":");
            if (idx === -1) return { tmdbId: Number(entry) || 0, title: entry };
            return {
              tmdbId: Number(entry.slice(0, idx)) || 0,
              title: entry.slice(idx + 1).trim(),
            };
          });
      const tvSeeds = parseSeeds(url.searchParams.get("tvSeeds") || "");
      const movieSeeds = parseSeeds(url.searchParams.get("movieSeeds") || "");
      const page = Number(url.searchParams.get("page") || 1) || 1;
      const data = await LiveCatalogService.fetchRecommendations({
        tvSeeds,
        movieSeeds,
        page,
        env: this.env,
      });
      return this.json(data);
    }

    if (request.method === "GET" && path === "/api/show-details") {
      const id = url.searchParams.get("tvmazeId") || url.searchParams.get("id") || "";
      const title = url.searchParams.get("title") || "";
      const show = await LiveCatalogService.fetchFullShowFromTvMaze(id, { title, env: this.env });
      if (!show) return this.json({ error: "Show not found" }, 404);
      return this.json(show);
    }

    let userParam = "default";
    let sessionToken = "";
    let sessionHeaders = {};
    if ((path.startsWith("/api/") && path !== "/api/health") || path === "/mcp") {
      const auth = await this._verifyGoogleRequest(request, url);
      if (!auth.ok) {
        return this.json(
          { error: auth.error, authRequired: true },
          auth.status || 401
        );
      }
      userParam = auth.email;
      sessionToken = auth.sessionToken || "";
      if (sessionToken) {
        sessionHeaders = {
          "Set-Cookie": `tvtime_session=${encodeURIComponent(sessionToken)}; Path=/; Max-Age=${TEN_YEARS_SECONDS}; SameSite=Lax; Secure`,
        };
      }
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
        return this.json(
          {
            ...state,
            sessionToken,
            stats: LiveCatalogService.computeStats(state),
          },
          200,
          sessionHeaders
        );
      }

      if (path === "/api/friends/search") {
        try {
          const q = url.searchParams.get("q") || "";
          const users = await this.repo.searchUsersByUsername(userParam, q);
          return this.json({ users }, 200, sessionHeaders);
        } catch (err) {
          return this.json(
            { error: err?.message || "Unable to search users right now." },
            502,
            sessionHeaders
          );
        }
      }

      if (path === "/api/friends/library") {
        try {
          const friendId = url.searchParams.get("friendId") || "";
          const library = await this.repo.getFriendLibrary(userParam, friendId);
          if (!library) {
            return this.json(
              { error: "You can only view the series and movies of accepted friends." },
              403,
              sessionHeaders
            );
          }
          return this.json({ library }, 200, sessionHeaders);
        } catch (err) {
          return this.json(
            { error: err?.message || "Unable to load friend's library right now. Please try again." },
            502,
            sessionHeaders
          );
        }
      }
    }

    if (request.method === "POST") {
      if (path === "/mcp") {
        return this.mcp.handle(request, userParam);
      }

      const body = await request.json().catch(() => ({}));

      if (path === "/api/friends/request") {
        try {
          const state = await this.repo.sendFriendRequest(userParam, body.targetUserId);
          return this.json({ ok: true, state }, 200, sessionHeaders);
        } catch (err) {
          return this.json({ error: err?.message || "Unable to send friend request." }, 400, sessionHeaders);
        }
      }

      if (path === "/api/friends/respond") {
        try {
          const state = await this.repo.respondToFriendRequest(
            userParam,
            body.requesterUserId,
            body.action
          );
          return this.json({ ok: true, state }, 200, sessionHeaders);
        } catch (err) {
          return this.json({ error: err?.message || "Unable to respond to friend request." }, 400, sessionHeaders);
        }
      }

      if (path === "/api/friends/remove") {
        try {
          const state = await this.repo.removeFriendOrRequest(userParam, body.friendUserId);
          return this.json({ ok: true, state }, 200, sessionHeaders);
        } catch (err) {
          return this.json({ error: err?.message || "Unable to remove friend." }, 400, sessionHeaders);
        }
      }

      if (path === "/api/state") {
        const state = {
          userId: FirestoreRepository.sanitizeUserId(userParam),
          email: userParam,
          shows: Array.isArray(body.shows) ? body.shows : [],
          movies: Array.isArray(body.movies) ? body.movies : [],
          actors: Array.isArray(body.actors) ? body.actors : [],
          decidedIds: Array.isArray(body.decidedIds) ? body.decidedIds : [],
          followingUsers: Array.isArray(body.followingUsers) ? body.followingUsers : [],
          friends: Array.isArray(body.friends) ? body.friends : undefined,
          incomingFriendRequests: Array.isArray(body.incomingFriendRequests)
            ? body.incomingFriendRequests
            : undefined,
          outgoingFriendRequests: Array.isArray(body.outgoingFriendRequests)
            ? body.outgoingFriendRequests
            : undefined,
        };
        await this.repo.saveUserState(userParam, state);
        const saved = await this.repo.getUserState(userParam);
        return this.json(
          {
            ...saved,
            sessionToken,
            stats: LiveCatalogService.computeStats(saved),
          },
          200,
          sessionHeaders
        );
      }
    }

    return this.json({ error: "Not found" }, 404);
  }
}

