/**
 * TVTime Tracker — Modular Cloudflare Worker Entrypoint.
 *
 * Architecture:
 *   - `cloudflare/src/repositories/FirestoreRepository.js` -> Multi-user Cloud Firestore (`tvtime_state/user_state_{userId}`)
 *   - `cloudflare/src/services/LiveCatalogService.js`      -> TVMaze (100% Free/Keyless Live Episodes) + TMDB + iTunes Movies + Gemini AI
 *   - `cloudflare/src/mcp/McpRouter.js`                    -> Streamable HTTP MCP Server (`/mcp`)
 *   - `cloudflare/src/controllers/HttpRouter.js`           -> HTTP & REST API controller
 *   - `cloudflare/src/ui/WebAppView.js`                    -> TV Time 4-Tab UI (Shows: Watch Next / Upcoming / Library, Movies, Discover, Profile & Stats)
 */

import { HttpRouter } from "./src/controllers/HttpRouter.js";

export default {
  async fetch(request, env) {
    const router = new HttpRouter(env);
    return router.handle(request);
  },
};

