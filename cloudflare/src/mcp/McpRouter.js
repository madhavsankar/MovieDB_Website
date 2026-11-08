/**
 * Streamable HTTP MCP Router (`/mcp`) for TV Time: Show & Movie Tracker Cloudflare Worker.
 */

import { LiveCatalogService, formatEpisodeCode } from "../services/LiveCatalogService.js";

export const MCP_TOOLS = [
  {
    name: "get_watch_stats",
    description: "Get TV Time profile watch time stats, in-progress shows, and movie watchlist.",
    inputSchema: {
      type: "object",
      properties: {
        user: { type: "string", description: "User email or ID" },
      },
    },
  },
  {
    name: "search_catalog",
    description: "Search live TV shows, movies, and actors from TVMaze and iTunes.",
    inputSchema: {
      type: "object",
      properties: {
        query: { type: "string", description: "Show, movie, or actor name" },
      },
      required: ["query"],
    },
  },
  {
    name: "mark_episode_watched",
    description: "Mark a specific TV episode (or all episodes up to it) as watched.",
    inputSchema: {
      type: "object",
      properties: {
        user: { type: "string" },
        show_title: { type: "string", description: "Title of the TV show" },
        season: { type: "integer", default: 1 },
        episode: { type: "integer", default: 1 },
        mark_previous: { type: "boolean", default: false },
      },
      required: ["show_title", "season", "episode"],
    },
  },
  {
    name: "mark_movie_watched",
    description: "Mark a movie as watched or add it to the watchlist.",
    inputSchema: {
      type: "object",
      properties: {
        user: { type: "string" },
        movie_title: { type: "string" },
        watched: { type: "boolean", default: true },
        rating: { type: "integer", default: 5 },
      },
      required: ["movie_title"],
    },
  },
];

export class McpRouter {
  constructor(repo) {
    this.repo = repo;
  }

  async handle(request, defaultUser = "default") {
    const body = await request.json().catch(() => ({}));
    const { id = null, method, params = {} } = body;

    if (method === "initialize") {
      return Response.json({
        jsonrpc: "2.0",
        id,
        result: {
          protocolVersion: "2024-11-05",
          serverInfo: { name: "tvtime-tracker-mcp", version: "1.0.0" },
          capabilities: { tools: {} },
        },
      });
    }

    if (method === "tools/list") {
      return Response.json({
        jsonrpc: "2.0",
        id,
        result: { tools: MCP_TOOLS },
      });
    }

    if (method === "tools/call") {
      const name = params.name;
      const args = params.arguments || {};
      const userId = args.user || defaultUser;
      const state = await this.repo.getUserState(userId);
      let result = {};

      if (name === "get_watch_stats") {
        result = {
          userId: state.userId,
          stats: LiveCatalogService.computeStats(state),
          showsCount: state.shows.length,
          moviesCount: state.movies.length,
          shows: state.shows.map((s) => ({
            title: s.title,
            watchedCount: (s.watchedEpisodes || []).length,
            totalEpisodes: (s.episodes || []).length,
          })),
          movies: state.movies.map((m) => ({
            title: m.title,
            watched: m.watched,
            userRating: m.userRating,
          })),
        };
      } else if (name === "search_catalog") {
        result = await LiveCatalogService.searchMulti(args.query || "");
      } else if (name === "mark_episode_watched") {
        const targetTitle = String(args.show_title || "").toLowerCase();
        const show = state.shows.find((s) => String(s.title || "").toLowerCase().includes(targetTitle));
        if (!show) {
          result = { error: `Show '${args.show_title}' not found in user library.` };
        } else {
          const targetCode = formatEpisodeCode(args.season, args.episode);
          const watchedSet = new Set(show.watchedEpisodes || []);
          if (args.mark_previous && Array.isArray(show.episodes)) {
            for (const ep of show.episodes) {
              if (
                ep.season < Number(args.season) ||
                (ep.season === Number(args.season) && ep.number <= Number(args.episode))
              ) {
                watchedSet.add(ep.code);
              }
            }
          } else {
            watchedSet.add(targetCode);
          }
          show.watchedEpisodes = Array.from(watchedSet).sort();
          show.lastWatchedAt = new Date().toISOString();
          await this.repo.saveUserState(userId, state);
          result = { show: show.title, watchedEpisodesCount: show.watchedEpisodes.length, latestCode: targetCode };
        }
      } else if (name === "mark_movie_watched") {
        const targetTitle = String(args.movie_title || "").toLowerCase();
        let movie = state.movies.find((m) => String(m.title || "").toLowerCase().includes(targetTitle));
        if (!movie) {
          movie = {
            id: `movie_${Date.now()}`,
            title: args.movie_title,
            year: 2025,
            runtimeMins: 120,
            genres: ["Movie"],
            rating: 8.0,
            watched: Boolean(args.watched),
            watchedAt: args.watched ? new Date().toISOString().slice(0, 10) : "",
            userRating: Number(args.rating) || 0,
            isFavorite: false,
          };
          state.movies.unshift(movie);
        } else {
          movie.watched = Boolean(args.watched);
          if (movie.watched) {
            movie.watchedAt = new Date().toISOString().slice(0, 10);
          }
          if (args.rating) {
            movie.userRating = Number(args.rating);
          }
        }
        await this.repo.saveUserState(userId, state);
        result = { movie };
      } else {
        return Response.json({
          jsonrpc: "2.0",
          id,
          error: { code: -32601, message: `Unknown tool: ${name}` },
        });
      }

      return Response.json({
        jsonrpc: "2.0",
        id,
        result: {
          content: [{ type: "text", text: JSON.stringify(result, null, 2) }],
        },
      });
    }

    return Response.json({
      jsonrpc: "2.0",
      id,
      error: { code: -32600, message: "Unsupported JSON-RPC method" },
    });
  }
}

