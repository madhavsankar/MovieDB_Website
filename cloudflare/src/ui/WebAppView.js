// TV Time: Show & Movie Tracker — Web App View (1:1 clone of iOS App id6792364354)
export const WEB_APP_HTML = String.raw`<!DOCTYPE html>
<html lang="en" class="dark">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
  <title>TV Time: Show & Movie Tracker</title>
  <link rel="manifest" href="/manifest.json" />
  <script src="https://cdn.tailwindcss.com"></script>
  <script src="https://accounts.google.com/gsi/client" async defer></script>
  <style>
    :root {
      --tv-yellow: #facc15;
      --tv-bg: #09090b;
      --tv-card: #16161a;
      --tv-pill: #1c1c21;
      --tv-border: #27272f;
      --tv-green: #22c55e;
      --tv-cyan: #38bdf8;
    }
    body {
      background: var(--tv-bg);
      color: #ffffff;
      font-family: -apple-system, BlinkMacSystemFont, "SF Pro Display", "Inter", Roboto, sans-serif;
      -webkit-tap-highlight-color: transparent;
      overflow-x: hidden;
    }
    .tv-card {
      background: var(--tv-card);
      border: 1px solid rgba(255, 255, 255, 0.05);
    }
    .tv-pill-bar {
      background: var(--tv-pill);
    }
    .tv-yellow-bg {
      background-color: var(--tv-yellow) !important;
      color: #09090b !important;
    }
    .tv-yellow-text {
      color: var(--tv-yellow) !important;
    }
    .tv-eye-btn {
      width: 44px;
      height: 44px;
      border-radius: 9999px;
      border: 2px solid var(--tv-yellow);
      display: flex;
      align-items: center;
      justify-content: center;
      color: var(--tv-yellow);
      flex-shrink: 0;
      transition: transform 0.12s ease, background 0.15s ease;
    }
    .tv-eye-btn:active {
      transform: scale(0.92);
    }
    .tv-check-btn {
      width: 44px;
      height: 44px;
      border-radius: 9999px;
      background: var(--tv-yellow);
      color: #09090b;
      display: flex;
      align-items: center;
      justify-content: center;
      font-weight: 800;
      flex-shrink: 0;
      transition: transform 0.12s ease;
    }
    .tv-gray-eye-btn {
      width: 40px;
      height: 40px;
      border-radius: 9999px;
      background: #232329;
      color: #9ca3af;
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
    }
    .no-scrollbar::-webkit-scrollbar { display: none; }
    .no-scrollbar { -ms-overflow-style: none; scrollbar-width: none; }
    .shelf-3d-room {
      background: radial-gradient(circle at 50% 10%, #1e1035 0%, #070510 75%);
      perspective: 900px;
    }
    .shelf-row {
      border-bottom: 10px solid #a16207;
      box-shadow: 0 12px 24px rgba(0, 0, 0, 0.85);
      transform: rotateX(4deg);
    }
    .neon-shows {
      color: #34d399;
      text-shadow: 0 0 12px rgba(52, 211, 153, 0.85);
    }
    .neon-watchlist {
      color: #d946ef;
      text-shadow: 0 0 14px rgba(217, 70, 239, 0.9);
    }
  </style>
</head>
<body class="min-h-screen pb-28 select-none">
  <main class="max-w-xl mx-auto px-4 pt-4 space-y-4">

    <!-- ========================================================= -->
    <!-- TAB 1: HOME (Shows | Movies | Upcoming)                   -->
    <!-- ========================================================= -->
    <section id="tab-home" class="space-y-4">
      <!-- Top Segmented Pill Bar: Shows | Movies | Upcoming -->
      <div class="tv-pill-bar p-1.5 rounded-full grid grid-cols-3 gap-1">
        <button id="top-seg-shows" onclick="app.setHomeSegment('shows')" class="py-2.5 rounded-full text-sm font-bold tv-yellow-bg transition">
          Shows
        </button>
        <button id="top-seg-movies" onclick="app.setHomeSegment('movies')" class="py-2.5 rounded-full text-sm font-semibold text-zinc-400 hover:text-white transition">
          Movies
        </button>
        <button id="top-seg-upcoming" onclick="app.setHomeSegment('upcoming')" class="py-2.5 rounded-full text-sm font-semibold text-zinc-400 hover:text-white transition">
          Upcoming
        </button>
      </div>

      <!-- Sub-Filter Row for Shows & Movies: Watch List | [In progress | Completed] | 3D Toggle -->
      <div id="home-subfilter-media" class="flex items-center justify-between gap-2">
        <button id="sub-watchlist-label" onclick="app.setMediaFilter('all')" class="text-sm font-semibold text-zinc-400 hover:text-white transition px-1">
          Watch List
        </button>
        <div class="flex items-center gap-2">
          <div class="tv-pill-bar p-1 rounded-full flex items-center gap-1">
            <button id="sub-inprogress-btn" onclick="app.setMediaFilter('in_progress')" class="px-4 py-1.5 rounded-full text-xs font-bold tv-yellow-bg transition">
              In progress
            </button>
            <button id="sub-completed-btn" onclick="app.setMediaFilter('completed')" class="px-4 py-1.5 rounded-full text-xs font-semibold text-zinc-400 hover:text-white transition">
              Completed
            </button>
          </div>
          <button
            id="btn-3d-toggle"
            onclick="app.toggle3DView()"
            title="3D Watch List Shelf View"
            class="tv-pill-bar w-9 h-9 rounded-full flex items-center justify-center text-zinc-300 hover:text-yellow-400 transition"
          >
            ⊞
          </button>
        </div>
      </div>

      <!-- Sub-Filter Row for Upcoming: Upcoming | [Watchlist | Actors] -->
      <div id="home-subfilter-upcoming" class="hidden flex items-center justify-between gap-2">
        <span class="text-sm font-semibold text-zinc-400 px-1">Upcoming</span>
        <div class="tv-pill-bar p-1 rounded-full flex items-center gap-1">
          <button id="up-watchlist-btn" onclick="app.setUpcomingFilter('watchlist')" class="px-4 py-1.5 rounded-full text-xs font-bold tv-yellow-bg transition">
            Watchlist
          </button>
          <button id="up-actors-btn" onclick="app.setUpcomingFilter('actors')" class="px-4 py-1.5 rounded-full text-xs font-semibold text-zinc-400 hover:text-white transition">
            Actors
          </button>
        </div>
      </div>

      <!-- 3D Shelf View Container (Toggleable) -->
      <div id="home-3d-container" class="hidden shelf-3d-room rounded-3xl p-5 border border-zinc-800 space-y-6"></div>

      <!-- Main List Container -->
      <div id="home-list-container" class="space-y-3"></div>
    </section>

    <!-- ========================================================= -->
    <!-- TAB 2: SEARCH / DISCOVER (🔍)                             -->
    <!-- ========================================================= -->
    <section id="tab-search" class="hidden space-y-4">
      <!-- Search Pill Input -->
      <div class="tv-pill-bar rounded-full px-4 py-3 flex items-center gap-3 border border-zinc-800/80">
        <span class="text-zinc-400 text-base">🔍</span>
        <input
          id="search-input"
          type="text"
          placeholder="Search TV shows, movies, or actors..."
          oninput="app.onSearchInput(this.value)"
          class="w-full bg-transparent text-white text-sm focus:outline-none placeholder-zinc-500"
        />
        <button id="search-clear-btn" onclick="app.clearSearch()" class="text-yellow-400 font-bold text-sm px-1">✕</button>
      </div>

      <!-- Results Count + Sort Bar: Top | Newest | Oldest -->
      <div class="flex items-center justify-between gap-2">
        <span id="search-count-label" class="text-xs font-medium text-zinc-400">Popular & Trending</span>
        <div class="tv-pill-bar p-1 rounded-full flex items-center gap-1">
          <button id="sort-top" onclick="app.setSearchSort('top')" class="px-3.5 py-1.5 rounded-full text-xs font-bold tv-yellow-bg transition">
            Top
          </button>
          <button id="sort-newest" onclick="app.setSearchSort('newest')" class="px-3.5 py-1.5 rounded-full text-xs font-semibold text-zinc-400 hover:text-white transition">
            Newest
          </button>
          <button id="sort-oldest" onclick="app.setSearchSort('oldest')" class="px-3.5 py-1.5 rounded-full text-xs font-semibold text-zinc-400 hover:text-white transition">
            Oldest
          </button>
        </div>
      </div>

      <!-- Search Results Cards -->
      <div id="search-results-list" class="space-y-3"></div>
    </section>

    <!-- ========================================================= -->
    <!-- TAB 3: PROFILE & STATS (👤)                               -->
    <!-- ========================================================= -->
    <section id="tab-profile" class="hidden space-y-6">
      <!-- Top Profile Header: Avatar, Name + Edit, @handle, Yellow Action Button -->
      <div class="flex items-center justify-between gap-3 pt-1">
        <div class="flex items-center gap-3.5">
          <div class="relative">
            <div
              id="profile-avatar"
              onclick="app.openAccountModal()"
              class="w-16 h-16 rounded-full bg-gradient-to-tr from-yellow-400 via-amber-500 to-orange-500 p-0.5 cursor-pointer"
            >
              <div class="w-full h-full rounded-full bg-zinc-900 flex items-center justify-center text-xl font-extrabold text-yellow-400" id="profile-avatar-initials">
                TV
              </div>
            </div>
            <button
              onclick="app.openAccountModal()"
              class="absolute -bottom-0.5 -right-0.5 w-6 h-6 rounded-full tv-yellow-bg flex items-center justify-center text-[11px] font-bold shadow"
              title="Google Account & Sync"
            >
              🔐
            </button>
          </div>
          <div>
            <div class="flex items-center gap-1.5">
              <h2 id="profile-display-name" class="text-lg font-extrabold text-white">Profile</h2>
              <button onclick="app.openAccountModal()" class="text-zinc-400 hover:text-white text-xs">✎</button>
            </div>
            <p id="profile-handle" class="text-xs text-zinc-400">@member</p>
          </div>
        </div>

        <div class="flex items-center gap-2">
          <!-- TV Time Import Button -->
          <button
            onclick="app.openImportModal()"
            title="Import Original TV Time Export"
            class="tv-pill-bar w-11 h-11 rounded-full flex items-center justify-center text-yellow-400 hover:bg-zinc-800 transition text-base"
          >
            📥
          </button>
          <!-- Follow / Following Modal Trigger -->
          <button
            onclick="app.openFollowingSheet()"
            title="Following & Actors"
            class="w-11 h-11 rounded-full tv-yellow-bg flex items-center justify-center text-base font-bold shadow"
          >
            👤+
          </button>
        </div>
      </div>

      <!-- Followers / Following Row -->
      <div class="flex items-center gap-8 px-1">
        <button onclick="app.openFollowingSheet('users')" class="text-left group">
          <div id="profile-followers-count" class="text-base font-extrabold text-white group-hover:text-yellow-400">2</div>
          <div class="text-xs text-zinc-400">Followers</div>
        </button>
        <button onclick="app.openFollowingSheet('actors')" class="text-left group">
          <div id="profile-following-count" class="text-base font-extrabold text-white group-hover:text-yellow-400">5</div>
          <div class="text-xs text-zinc-400">Following</div>
        </button>
        <button onclick="app.loadState(true)" class="ml-auto tv-pill-bar px-3.5 py-1.5 rounded-full text-xs font-semibold text-zinc-300 hover:text-yellow-400 transition">
          ↻ Sync Cloud
        </button>
      </div>

      <!-- TOTAL WATCH TIME Card -->
      <div class="tv-card rounded-3xl p-5 space-y-4">
        <div class="text-[11px] font-bold uppercase tracking-wider text-zinc-400">TOTAL WATCH TIME</div>
        <div id="stat-total-time" class="text-4xl font-black tv-yellow-text tracking-tight">0d 0h</div>
        <div class="flex items-center justify-between pt-1">
          <div>
            <div id="stat-series-time" class="text-base font-extrabold text-white">0d 0h</div>
            <div id="stat-series-sub" class="text-xs text-zinc-400">Series · 0 eps</div>
          </div>
          <div class="text-right">
            <div id="stat-movies-time" class="text-base font-extrabold text-white">0d 0h</div>
            <div id="stat-movies-sub" class="text-xs text-zinc-400">Movies · 0</div>
          </div>
        </div>
      </div>

      <!-- Favorite Shows Carousel -->
      <div class="space-y-2.5">
        <div class="flex items-center justify-between">
          <h3 class="text-base font-extrabold text-white">Favorite <span class="font-normal text-zinc-400">Shows</span></h3>
          <button onclick="app.switchTab('home'); app.setHomeSegment('shows');" class="text-xs font-semibold tv-yellow-text">See all</button>
        </div>
        <div id="carousel-favorites" class="flex gap-3 overflow-x-auto no-scrollbar pb-1"></div>
      </div>

      <!-- Recently Watched Series Carousel -->
      <div class="space-y-2.5">
        <div class="flex items-center justify-between">
          <h3 class="text-base font-extrabold text-white">Recently <span class="font-normal text-zinc-400">Watched Series</span></h3>
          <button onclick="app.switchTab('home'); app.setHomeSegment('shows');" class="text-xs font-semibold tv-yellow-text">See all</button>
        </div>
        <div id="carousel-recent-series" class="flex gap-3 overflow-x-auto no-scrollbar pb-1"></div>
      </div>

      <!-- Recently Watched Movies Carousel -->
      <div class="space-y-2.5">
        <div class="flex items-center justify-between">
          <h3 class="text-base font-extrabold text-white">Recently <span class="font-normal text-zinc-400">Watched Movies</span></h3>
          <button onclick="app.switchTab('home'); app.setHomeSegment('movies');" class="text-xs font-semibold tv-yellow-text">See all</button>
        </div>
        <div id="carousel-recent-movies" class="flex gap-3 overflow-x-auto no-scrollbar pb-1"></div>
      </div>
    </section>

  </main>

  <!-- ========================================================= -->
  <!-- BOTTOM NAVIGATION BAR (Home ⌂ | Search 🔍 | Profile 👤)    -->
  <!-- ========================================================= -->
  <nav class="fixed bottom-0 left-0 right-0 bg-[#09090b]/95 backdrop-blur-md border-t border-zinc-800/80 z-30">
    <div class="max-w-xl mx-auto grid grid-cols-3 h-16 items-center">
      <button onclick="app.switchTab('home')" id="nav-btn-home" class="flex flex-col items-center justify-center gap-1 tv-yellow-text">
        <svg class="w-6 h-6" fill="none" stroke="currentColor" stroke-width="2.2" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" d="M3 10.5L12 3l9 7.5V20a1 1 0 01-1 1h-5.5v-5.5h-5V21H4a1 1 0 01-1-1v-9.5z" />
        </svg>
      </button>
      <button onclick="app.switchTab('search')" id="nav-btn-search" class="flex flex-col items-center justify-center gap-1 text-zinc-400 hover:text-white">
        <svg class="w-6 h-6" fill="none" stroke="currentColor" stroke-width="2.2" viewBox="0 0 24 24">
          <circle cx="11" cy="11" r="7" />
          <path stroke-linecap="round" stroke-linejoin="round" d="M20 20l-4.35-4.35" />
        </svg>
      </button>
      <button onclick="app.switchTab('profile')" id="nav-btn-profile" class="flex flex-col items-center justify-center gap-1 text-zinc-400 hover:text-white">
        <svg class="w-6 h-6" fill="none" stroke="currentColor" stroke-width="2.2" viewBox="0 0 24 24">
          <circle cx="12" cy="8" r="4" />
          <path stroke-linecap="round" stroke-linejoin="round" d="M5.5 21a6.5 6.5 0 0113 0" />
        </svg>
      </button>
    </div>
  </nav>

  <!-- ========================================================= -->
  <!-- MODAL 1: SHOW / MOVIE DETAIL BOTTOM SHEET (Info | Episodes)-->
  <!-- ========================================================= -->
  <div id="detail-sheet-backdrop" onclick="app.closeDetailSheet()" class="hidden fixed inset-0 bg-black/75 backdrop-blur-sm z-40"></div>
  <div
    id="detail-sheet"
    class="hidden fixed inset-x-0 bottom-0 max-h-[88vh] bg-[#121216] rounded-t-3xl border-t border-zinc-800 z-50 flex flex-col max-w-xl mx-auto overflow-hidden"
  >
    <!-- Grab Handle -->
    <div class="pt-3 pb-1 flex justify-center shrink-0" onclick="app.closeDetailSheet()">
      <div class="w-11 h-1.5 rounded-full bg-zinc-700"></div>
    </div>

    <!-- Top Tabs: Info | Episodes -->
    <div id="detail-tabs-bar" class="grid grid-cols-2 border-b border-zinc-800 shrink-0">
      <button id="detail-tab-info" onclick="app.setDetailTab('info')" class="py-3 text-sm font-bold text-zinc-400 border-b-2 border-transparent transition">
        Info
      </button>
      <button id="detail-tab-episodes" onclick="app.setDetailTab('episodes')" class="py-3 text-sm font-bold text-white border-b-2 border-yellow-400 transition">
        Episodes
      </button>
    </div>

    <!-- Scrollable Detail Content -->
    <div id="detail-body" class="p-4 overflow-y-auto space-y-4 flex-1"></div>
  </div>

  <!-- ========================================================= -->
  <!-- MODAL 2: FOLLOWING SHEET (Users | Actors)                 -->
  <!-- ========================================================= -->
  <div id="following-sheet" class="hidden fixed inset-0 bg-black/80 backdrop-blur-sm z-50 flex items-end justify-center">
    <div class="bg-[#121216] w-full max-w-xl rounded-t-3xl border-t border-zinc-800 max-h-[85vh] flex flex-col p-4 space-y-4">
      <div class="flex justify-center" onclick="app.closeFollowingSheet()">
        <div class="w-11 h-1.5 rounded-full bg-zinc-700 cursor-pointer"></div>
      </div>
      <div class="flex items-center justify-between">
        <h3 class="text-base font-extrabold text-white mx-auto">Following</h3>
        <button onclick="app.closeFollowingSheet()" class="text-zinc-400 hover:text-white text-sm">✕</button>
      </div>
      <div class="tv-pill-bar p-1 rounded-full grid grid-cols-2 gap-1">
        <button id="fol-tab-users" onclick="app.renderFollowingList('users')" class="py-2 rounded-full text-xs font-bold text-zinc-400">
          Users
        </button>
        <button id="fol-tab-actors" onclick="app.renderFollowingList('actors')" class="py-2 rounded-full text-xs font-bold tv-yellow-bg">
          Actors
        </button>
      </div>
      <div id="following-list-body" class="overflow-y-auto space-y-3 flex-1 pb-6"></div>
    </div>
  </div>

  <!-- ========================================================= -->
  <!-- MODAL 3: BRING YOUR ORIGINAL TV TIME HISTORY OVER         -->
  <!-- ========================================================= -->
  <div id="import-modal" class="hidden fixed inset-0 bg-black/80 backdrop-blur-sm z-50 flex items-center justify-center p-4">
    <div class="bg-[#18181d] rounded-3xl p-6 w-full max-w-sm border border-zinc-800 space-y-4 text-center">
      <div class="w-12 h-12 rounded-full bg-zinc-800/90 mx-auto flex items-center justify-center tv-yellow-text text-xl font-bold">
        ↓
      </div>
      <h3 class="text-lg font-extrabold text-white leading-snug">Bring your original TV Time history over</h3>
      <p class="text-xs text-zinc-400 leading-relaxed">
        The original TV Time app shut down. If you asked them for a copy of your data before it went, you can load it here and pick up where you left off — your shows and movies, which episodes you'd seen, and the watch time that goes with them.
      </p>
      <div class="text-left space-y-2.5 text-xs text-zinc-300">
        <div class="flex items-start gap-2.5">
          <span class="w-5 h-5 rounded-full bg-zinc-800 tv-yellow-text font-bold flex items-center justify-center shrink-0">1</span>
          <span>Upload your TV Time export (<code>.json</code> or <code>.csv</code>) or backup file.</span>
        </div>
        <div class="flex items-start gap-2.5">
          <span class="w-5 h-5 rounded-full bg-zinc-800 tv-yellow-text font-bold flex items-center justify-center shrink-0">2</span>
          <span>Choose that file below — it is parsed directly on your device.</span>
        </div>
        <div class="flex items-start gap-2.5">
          <span class="w-5 h-5 rounded-full bg-zinc-800 tv-yellow-text font-bold flex items-center justify-center shrink-0">3</span>
          <span>Check what was found, then confirm the import.</span>
        </div>
      </div>
      <input id="tvtime-import-file" type="file" accept=".json,.csv,.txt" class="hidden" onchange="app.handleImportFile(event)" />
      <div class="space-y-2 pt-2">
        <button onclick="document.getElementById('tvtime-import-file').click()" class="w-full py-3 rounded-full tv-yellow-bg text-sm font-extrabold">
          Choose file
        </button>
        <button onclick="app.exportBackupJson()" class="w-full py-2.5 rounded-full tv-pill-bar text-xs font-semibold text-zinc-300 hover:text-white">
          Export Current Library Backup (.json)
        </button>
        <button onclick="app.closeImportModal()" class="w-full py-2 text-xs font-bold text-white">
          Cancel
        </button>
      </div>
    </div>
  </div>

  <!-- ========================================================= -->
  <!-- MODAL 4: GOOGLE ACCOUNT & CLOUD SYNC                      -->
  <!-- ========================================================= -->
  <div id="account-modal" class="hidden fixed inset-0 bg-black/80 backdrop-blur-sm z-50 flex items-center justify-center p-4">
    <div class="bg-[#18181d] rounded-3xl p-6 w-full max-w-sm border border-zinc-800 space-y-4">
      <div class="flex items-center justify-between">
        <h3 class="text-base font-extrabold text-white">🔐 Google Account</h3>
        <button onclick="app.closeAccountModal()" class="text-zinc-400 hover:text-white text-sm">✕</button>
      </div>
      <p class="text-xs text-zinc-400">
        Synced with Firebase Cloud Firestore (<code>tvtime_state/user_state_{userId}</code>). Only allowlisted household Google accounts are permitted.
      </p>
      <div class="space-y-2">
        <label class="text-[11px] font-bold uppercase text-zinc-400">Signed-In Google Account</label>
        <input id="acc-user-input" type="text" readonly placeholder="Not signed in" class="w-full bg-zinc-900 border border-zinc-700 rounded-xl px-3.5 py-2.5 text-sm text-zinc-200 focus:outline-none" />
      </div>
      <div class="flex justify-between items-center gap-2 pt-2">
        <button onclick="app.signOutGoogle()" class="px-4 py-2 rounded-full bg-rose-500/20 text-rose-300 border border-rose-500/40 text-xs font-extrabold hover:bg-rose-500/30">
          Sign Out / Switch Account
        </button>
        <button onclick="app.closeAccountModal()" class="px-5 py-2 rounded-full tv-yellow-bg text-xs font-extrabold">Done</button>
      </div>
    </div>
  </div>

  <script>
    const EYE_SVG = '<svg class="w-5 h-5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"/><circle cx="12" cy="12" r="3"/></svg>';
    const CHECK_SVG = '<svg class="w-5 h-5" fill="none" stroke="currentColor" stroke-width="2.6" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7"/></svg>';
    const PLUS_SVG = '<svg class="w-5 h-5" fill="none" stroke="currentColor" stroke-width="2.4" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M12 5v14m-7-7h14"/></svg>';

    function escapeHtml(s) {
      return String(s || "")
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;");
    }

    class TvTimeClient {
      constructor() {
        this.googleIdToken = localStorage.getItem("tvtime_google_id_token") || "";
        this.userEmail = localStorage.getItem("tvtime_user_email") || "";
        const rawStored = (this.userEmail || localStorage.getItem("tvtime_user_id") || "").trim();
        this.userId = rawStored && rawStored !== "default" ? rawStored.split("@")[0] : "default";
        this.activeTab = "home"; // "home" | "search" | "profile"
        this.homeSegment = "shows"; // "shows" | "movies" | "upcoming"
        this.mediaFilter = "in_progress"; // "all" | "in_progress" | "completed"
        this.upcomingFilter = "watchlist"; // "watchlist" | "actors"
        this.is3DView = false;
        this.havenStartedCollapsed = false;

        this.searchQuery = "";
        this.searchSort = "top"; // "top" | "newest" | "oldest"
        this.searchResults = [];
        this.searchTimer = null;

        this.detailItem = null;
        this.detailType = "show"; // "show" | "movie"
        this.detailTab = "episodes"; // "info" | "episodes"
        this.openSeasons = new Set([1, 2]);

        this.state = {
          userId: this.userId,
          email: this.userEmail || this.userId,
          shows: [],
          movies: [],
          actors: [],
          followingUsers: ["partner"],
        };

        this.loadLocalCache();
        this.render();
        this.initAuthAndLoad();
        this.runDefaultDiscover();

        document.addEventListener("visibilitychange", () => {
          if (!document.hidden && this.googleIdToken) {
            this.loadState(false);
          }
        });
      }

      decodeJwtPayload(token) {
        try {
          const parts = String(token || "").split(".");
          if (parts.length < 2) return null;
          const b64 = parts[1].replace(/-/g, "+").replace(/_/g, "/");
          return JSON.parse(atob(b64));
        } catch (_) {
          return null;
        }
      }

      isGoogleTokenValid() {
        if (!this.googleIdToken) return false;
        const payload = this.decodeJwtPayload(this.googleIdToken);
        if (!payload || !payload.email || !payload.exp) return false;
        return payload.exp * 1000 > Date.now() + 30000;
      }

      async initAuthAndLoad() {
        await this.ensureGoogleSignIn();
        this.loadLocalCache();
        await this.loadState(false);
      }

      ensureGoogleSignIn(initialError = "") {
        if (this.isGoogleTokenValid() && !initialError) {
          const payload = this.decodeJwtPayload(this.googleIdToken);
          if (payload && payload.email) {
            this.userEmail = String(payload.email).trim().toLowerCase();
            this.userId = this.userEmail.split("@")[0];
            localStorage.setItem("tvtime_user_email", this.userEmail);
            localStorage.setItem("tvtime_user_id", this.userId);
          }
          return Promise.resolve(this.userId);
        }

        return new Promise((resolve) => {
          const checkAndShow = async () => {
            try {
              const cfgRes = await fetch("/api/auth-config", { cache: "no-store" });
              const cfg = await cfgRes.json();
              const clientId = String(cfg.googleClientId || "").trim();
              if (!clientId && !initialError) {
                resolve(this.userId);
                return;
              }

              let modal = document.getElementById("tvtime-auth-modal");
              if (modal) modal.remove();
              modal = document.createElement("div");
              modal.id = "tvtime-auth-modal";
              modal.className = "fixed inset-0 z-50 bg-[#09090b]/95 backdrop-blur-md flex items-center justify-center p-4";
              modal.innerHTML =
                '<div class="bg-[#16161a] rounded-3xl p-6 w-full max-w-sm border border-yellow-400/30 space-y-4 shadow-2xl text-center">' +
                '<div>' +
                '<div class="text-3xl mb-2">📺</div>' +
                '<h2 class="text-base font-extrabold text-white">Sign in to TV Time</h2>' +
                '<p class="text-xs text-zinc-400 mt-1">Sign in with your allowlisted Google account to access your shows &amp; movies:</p>' +
                '</div>' +
                '<div id="tvtime-google-btn" class="flex justify-center py-2 min-h-[48px] items-center">' +
                '<span class="text-xs text-zinc-400">Loading Google Sign-In...</span>' +
                '</div>' +
                '<div id="tvtime-auth-error" class="' +
                (initialError ? "" : "hidden ") +
                'text-xs text-rose-400 font-medium bg-rose-500/10 border border-rose-500/30 rounded-xl p-2.5">' +
                escapeHtml(initialError || "") +
                '</div>' +
                '</div>';
              document.body.appendChild(modal);

              const errEl = document.getElementById("tvtime-auth-error");
              const btnContainer = document.getElementById("tvtime-google-btn");

              let tries = 0;
              while (!(window.google && window.google.accounts && window.google.accounts.id) && tries < 40) {
                await new Promise((r) => setTimeout(r, 150));
                tries++;
              }
              if (!(window.google && window.google.accounts && window.google.accounts.id)) {
                if (errEl) {
                  errEl.textContent = "Unable to load Google Sign-In SDK. Check your connection and reload.";
                  errEl.classList.remove("hidden");
                }
                return;
              }
              window.google.accounts.id.initialize({
                client_id: clientId,
                callback: (resp) => {
                  const token = resp && resp.credential ? resp.credential : "";
                  const payload = this.decodeJwtPayload(token);
                  if (!token || !payload || !payload.email) {
                    if (errEl) {
                      errEl.textContent = "Google Sign-In did not return a valid email token.";
                      errEl.classList.remove("hidden");
                    }
                    return;
                  }
                  this.googleIdToken = token;
                  this.userEmail = String(payload.email).trim().toLowerCase();
                  this.userId = this.userEmail.split("@")[0];
                  localStorage.setItem("tvtime_google_id_token", this.googleIdToken);
                  localStorage.setItem("tvtime_user_email", this.userEmail);
                  localStorage.setItem("tvtime_user_id", this.userId);
                  modal.remove();
                  resolve(this.userId);
                },
              });
              if (btnContainer) {
                btnContainer.innerHTML = "";
                window.google.accounts.id.renderButton(btnContainer, {
                  theme: "filled_black",
                  size: "large",
                  shape: "pill",
                  text: "signin_with",
                  width: 260,
                });
              }
              try {
                window.google.accounts.id.prompt();
              } catch (_) {}
            } catch (_) {
              resolve(this.userId);
            }
          };
          checkAndShow();
        });
      }

      signOutGoogle() {
        this.googleIdToken = "";
        this.userEmail = "";
        this.userId = "default";
        localStorage.removeItem("tvtime_google_id_token");
        localStorage.removeItem("tvtime_user_email");
        localStorage.removeItem("tvtime_user_id");
        try {
          if (window.google && window.google.accounts && window.google.accounts.id) {
            window.google.accounts.id.disableAutoSelect();
          }
        } catch (_) {}
        window.location.reload();
      }

      loadLocalCache() {
        try {
          const raw = localStorage.getItem("tvtime_state_cache_" + this.userId);
          if (raw) {
            const parsed = JSON.parse(raw);
            if (parsed && Array.isArray(parsed.shows)) {
              this.state = parsed;
            }
          }
        } catch (_) {}
      }

      saveLocalCache() {
        try {
          localStorage.setItem("tvtime_state_cache_" + this.userId, JSON.stringify(this.state));
        } catch (_) {}
      }

      getHeaders() {
        const h = {
          "Content-Type": "application/json",
          "X-User-Id": this.userId,
        };
        if (this.googleIdToken) {
          h["Authorization"] = "Bearer " + this.googleIdToken;
        }
        return h;
      }

      async loadState(showToast = false) {
        try {
          const res = await fetch("/api/state", {
            headers: this.getHeaders(),
          });
          if (res.status === 401 || res.status === 403) {
            const errBody = await res.json().catch(() => ({}));
            this.googleIdToken = "";
            localStorage.removeItem("tvtime_google_id_token");
            try {
              if (window.google && window.google.accounts && window.google.accounts.id) {
                window.google.accounts.id.disableAutoSelect();
              }
            } catch (_) {}
            await this.ensureGoogleSignIn(
              errBody.error || "Please sign in with an allowlisted Google account."
            );
            return this.loadState(showToast);
          }
          if (res.ok) {
            const data = await res.json();
            this.state = data;
            if (data.email) {
              this.userEmail = String(data.email).trim().toLowerCase();
              this.userId = String(data.userId || this.userEmail.split("@")[0]);
              localStorage.setItem("tvtime_user_email", this.userEmail);
              localStorage.setItem("tvtime_user_id", this.userId);
            }
            this.saveLocalCache();
            this.render();
          }
        } catch (_) {}
      }

      async persistState() {
        this.saveLocalCache();
        this.render();
        try {
          await fetch("/api/state", {
            method: "POST",
            headers: this.getHeaders(),
            body: JSON.stringify({
              userId: this.userId,
              email: this.state.email || this.userId,
              shows: this.state.shows,
              movies: this.state.movies,
              actors: this.state.actors,
              followingUsers: this.state.followingUsers,
            }),
          });
        } catch (_) {}
      }

      switchTab(tab) {
        this.activeTab = tab;
        ["home", "search", "profile"].forEach((t) => {
          const sec = document.getElementById("tab-" + t);
          const btn = document.getElementById("nav-btn-" + t);
          if (sec) sec.classList.toggle("hidden", t !== tab);
          if (btn) {
            btn.className =
              t === tab
                ? "flex flex-col items-center justify-center gap-1 tv-yellow-text"
                : "flex flex-col items-center justify-center gap-1 text-zinc-400 hover:text-white";
          }
        });
        this.render();
      }

      setHomeSegment(seg) {
        this.homeSegment = seg;
        ["shows", "movies", "upcoming"].forEach((s) => {
          const btn = document.getElementById("top-seg-" + s);
          if (btn) {
            btn.className =
              s === seg
                ? "py-2.5 rounded-full text-sm font-bold tv-yellow-bg transition"
                : "py-2.5 rounded-full text-sm font-semibold text-zinc-400 hover:text-white transition";
          }
        });
        const mediaSub = document.getElementById("home-subfilter-media");
        const upSub = document.getElementById("home-subfilter-upcoming");
        if (mediaSub) mediaSub.classList.toggle("hidden", seg === "upcoming");
        if (upSub) upSub.classList.toggle("hidden", seg !== "upcoming");
        this.renderHome();
      }

      setMediaFilter(filter) {
        this.mediaFilter = filter;
        const wl = document.getElementById("sub-watchlist-label");
        const ip = document.getElementById("sub-inprogress-btn");
        const cp = document.getElementById("sub-completed-btn");
        if (wl) {
          wl.className =
            filter === "all"
              ? "text-sm font-extrabold tv-yellow-text transition px-1"
              : "text-sm font-semibold text-zinc-400 hover:text-white transition px-1";
        }
        if (ip) {
          ip.className =
            filter === "in_progress"
              ? "px-4 py-1.5 rounded-full text-xs font-bold tv-yellow-bg transition"
              : "px-4 py-1.5 rounded-full text-xs font-semibold text-zinc-400 hover:text-white transition";
        }
        if (cp) {
          cp.className =
            filter === "completed"
              ? "px-4 py-1.5 rounded-full text-xs font-bold tv-yellow-bg transition"
              : "px-4 py-1.5 rounded-full text-xs font-semibold text-zinc-400 hover:text-white transition";
        }
        this.renderHome();
      }

      setUpcomingFilter(filter) {
        this.upcomingFilter = filter;
        const wl = document.getElementById("up-watchlist-btn");
        const ac = document.getElementById("up-actors-btn");
        if (wl) {
          wl.className =
            filter === "watchlist"
              ? "px-4 py-1.5 rounded-full text-xs font-bold tv-yellow-bg transition"
              : "px-4 py-1.5 rounded-full text-xs font-semibold text-zinc-400 hover:text-white transition";
        }
        if (ac) {
          ac.className =
            filter === "actors"
              ? "px-4 py-1.5 rounded-full text-xs font-bold tv-yellow-bg transition"
              : "px-4 py-1.5 rounded-full text-xs font-semibold text-zinc-400 hover:text-white transition";
        }
        this.renderHome();
      }

      toggle3DView() {
        this.is3DView = !this.is3DView;
        const btn = document.getElementById("btn-3d-toggle");
        if (btn) {
          btn.className = this.is3DView
            ? "w-9 h-9 rounded-full flex items-center justify-center tv-yellow-bg font-bold transition"
            : "tv-pill-bar w-9 h-9 rounded-full flex items-center justify-center text-zinc-300 hover:text-yellow-400 transition";
        }
        this.renderHome();
      }

      computeShowProgress(show) {
        const episodes = Array.isArray(show.episodes) ? show.episodes : [];
        const watchedSet = new Set(show.watchedEpisodes || []);
        const total = episodes.length || 10;
        const watchedCount = watchedSet.size;
        const leftCount = Math.max(0, total - watchedCount);
        let nextEp = null;
        for (const ep of episodes) {
          if (!watchedSet.has(ep.code)) {
            nextEp = ep;
            break;
          }
        }
        if (!nextEp && leftCount > 0) {
          const nextNum = watchedCount + 1;
          nextEp = {
            code: "S01E" + String(nextNum).padStart(2, "0"),
            season: 1,
            number: nextNum,
            title: "Episode " + nextNum,
            airDate: "",
          };
        }
        const pct = total > 0 ? Math.min(100, Math.round((watchedCount / total) * 100)) : 0;
        return {
          total,
          watchedCount,
          leftCount,
          nextEp,
          pct,
          isCompleted: total > 0 && leftCount === 0,
          hasStarted: watchedCount > 0,
        };
      }

      render() {
        this.renderHome();
        this.renderSearch();
        this.renderProfile();
        if (this.detailItem) {
          this.renderDetailSheet();
        }
      }

      render3DShelfView(items) {
        const box = document.getElementById("home-3d-container");
        if (!box) return;
        const chunk1 = items.slice(0, 6);
        const chunk2 = items.slice(6, 12);
        const renderRow = (arr) =>
          arr
            .map(
              (it) =>
                '<div onclick="app.openItemById(&quot;' +
                escapeHtml(it.id) +
                '&quot;)" class="w-20 shrink-0 cursor-pointer transform hover:-translate-y-1.5 transition">' +
                '<img src="' +
                escapeHtml(it.posterUrl || "https://via.placeholder.com/160x240/1f1f28/facc15?text=" + encodeURIComponent(it.title)) +
                '" class="w-20 h-28 object-cover rounded-md shadow-lg border border-white/10" />' +
                '<p class="text-[10px] font-bold text-zinc-200 truncate mt-1 text-center">' +
                escapeHtml(it.title) +
                "</p></div>"
            )
            .join("");

        box.innerHTML =
          '<div class="flex items-center justify-between">' +
          '<span class="text-lg font-black neon-shows tracking-wide">Shows</span>' +
          '<span class="text-xl font-black neon-watchlist tracking-wider">Watch List 3D</span>' +
          "</div>" +
          '<div class="shelf-row pb-3 pt-1 flex gap-3 overflow-x-auto no-scrollbar">' +
          (renderRow(chunk1) || '<p class="text-xs text-zinc-400 py-4">Add shows or movies to view your 3D shelf</p>') +
          "</div>" +
          (chunk2.length
            ? '<div class="shelf-row pb-3 pt-1 flex gap-3 overflow-x-auto no-scrollbar">' + renderRow(chunk2) + "</div>"
            : "");
      }

      renderShowCard(show) {
        const prog = this.computeShowProgress(show);
        const poster =
          show.posterUrl ||
          "https://via.placeholder.com/200x300/1c1c22/facc15?text=" + encodeURIComponent(show.title || "Show");
        const nextCodeFormatted = prog.nextEp
          ? prog.nextEp.code.replace(/^S([0-9]+)E([0-9]+)$/, "S$1·E$2")
          : "Caught up";

        let statusLineHtml = "";
        let actionBtnHtml = "";
        if (prog.isCompleted) {
          statusLineHtml =
            '<div class="text-[11px] font-bold tv-yellow-text">TV Show</div>' +
            '<div class="text-xs text-zinc-400">Caught up · <span class="font-bold tv-yellow-text">All episodes watched</span></div>';
          actionBtnHtml =
            '<button onclick="event.stopPropagation(); app.openItemById(&quot;' +
            escapeHtml(show.id) +
            '&quot;)" class="w-10 h-10 flex items-center justify-center text-emerald-400 font-bold">' +
            CHECK_SVG +
            "</button>";
        } else {
          statusLineHtml =
            (!prog.hasStarted ? '<div class="text-[11px] font-bold tv-yellow-text">TV Show</div>' : "") +
            '<div class="w-28 h-1.5 bg-zinc-800 rounded-full overflow-hidden my-1">' +
            '<div class="h-full bg-emerald-500 rounded-full" style="width: ' +
            Math.max(8, prog.pct) +
            '%"></div></div>' +
            '<div class="text-xs text-zinc-400">' +
            prog.leftCount +
            " episode" +
            (prog.leftCount === 1 ? "" : "s") +
            ' left &nbsp;<span class="font-extrabold tv-yellow-text">' +
            escapeHtml(nextCodeFormatted) +
            "</span></div>";
          actionBtnHtml =
            '<button title="Mark ' +
            escapeHtml(nextCodeFormatted) +
            ' watched" onclick="event.stopPropagation(); app.markNextEpisodeWatched(&quot;' +
            escapeHtml(show.id) +
            '&quot;)" class="tv-eye-btn">' +
            EYE_SVG +
            "</button>";
        }

        return (
          '<div onclick="app.openItemById(&quot;' +
          escapeHtml(show.id) +
          '&quot;)" class="tv-card rounded-2xl p-3 flex items-center gap-3.5 cursor-pointer hover:border-zinc-700 transition">' +
          '<img src="' +
          escapeHtml(poster) +
          '" alt="' +
          escapeHtml(show.title) +
          '" class="w-16 h-24 object-cover rounded-xl shrink-0 bg-zinc-900" />' +
          '<div class="flex-1 min-w-0 space-y-0.5">' +
          '<div class="flex items-center gap-2 flex-wrap">' +
          '<h3 class="text-base font-extrabold text-white truncate">' +
          escapeHtml(show.title) +
          "</h3>" +
          '<span class="px-2 py-0.5 rounded-md bg-zinc-800 text-[11px] font-semibold text-zinc-300">' +
          escapeHtml(show.year || 2024) +
          "</span>" +
          (show.status === "Ended"
            ? '<span class="px-1.5 py-0.5 rounded bg-rose-500/20 text-rose-300 text-[10px] font-bold">Ended</span>'
            : "") +
          "</div>" +
          statusLineHtml +
          '<div class="flex items-center gap-1 text-xs font-bold text-zinc-200 pt-0.5">' +
          '<span class="tv-yellow-text">★</span><span>' +
          Number(show.rating || 8.0).toFixed(1) +
          "</span>" +
          "</div>" +
          "</div>" +
          actionBtnHtml +
          "</div>"
        );
      }

      renderMovieCard(movie) {
        const poster =
          movie.posterUrl ||
          "https://via.placeholder.com/200x300/1c1c22/38bdf8?text=" + encodeURIComponent(movie.title || "Movie");
        const castLine = Array.isArray(movie.cast) && movie.cast.length
          ? movie.cast.join(", ")
          : movie.director || movie.streamingService || "Feature Film";

        const rightBtn = movie.watched
          ? '<button title="Toggle watched" onclick="event.stopPropagation(); app.toggleMovieWatched(&quot;' +
            escapeHtml(movie.id) +
            '&quot;)" class="tv-check-btn">' +
            CHECK_SVG +
            "</button>"
          : '<button title="Mark movie watched" onclick="event.stopPropagation(); app.toggleMovieWatched(&quot;' +
            escapeHtml(movie.id) +
            '&quot;)" class="tv-eye-btn">' +
            EYE_SVG +
            "</button>";

        return (
          '<div onclick="app.openItemById(&quot;' +
          escapeHtml(movie.id) +
          '&quot;)" class="tv-card rounded-2xl p-3 flex items-center gap-3.5 cursor-pointer hover:border-zinc-700 transition">' +
          '<img src="' +
          escapeHtml(poster) +
          '" alt="' +
          escapeHtml(movie.title) +
          '" class="w-16 h-24 object-cover rounded-xl shrink-0 bg-zinc-900" />' +
          '<div class="flex-1 min-w-0 space-y-0.5">' +
          '<div class="flex items-center gap-2 flex-wrap">' +
          '<h3 class="text-base font-extrabold text-white truncate">' +
          escapeHtml(movie.title) +
          "</h3>" +
          '<span class="px-2 py-0.5 rounded-md bg-zinc-800 text-[11px] font-semibold text-zinc-300">' +
          escapeHtml(movie.year || 2024) +
          "</span>" +
          "</div>" +
          '<div class="text-xs font-bold text-sky-400">Movie · ' +
          (Number(movie.runtimeMins) || 120) +
          "m</div>" +
          '<div class="text-xs text-zinc-400 truncate">' +
          escapeHtml(castLine) +
          "</div>" +
          '<div class="flex items-center gap-1 text-xs font-bold text-zinc-200 pt-0.5">' +
          '<span class="tv-yellow-text">★</span><span>' +
          Number(movie.rating || 8.0).toFixed(1) +
          "</span>" +
          (movie.rewatchCount > 0
            ? '<span class="ml-2 text-[11px] text-amber-300">↻ ' + movie.rewatchCount + "x</span>"
            : "") +
          "</div>" +
          "</div>" +
          rightBtn +
          "</div>"
        );
      }

      renderHome() {
        const listEl = document.getElementById("home-list-container");
        const shelfEl = document.getElementById("home-3d-container");
        if (!listEl) return;

        const shows = Array.isArray(this.state.shows) ? this.state.shows : [];
        const movies = Array.isArray(this.state.movies) ? this.state.movies : [];

        if (this.is3DView && this.homeSegment !== "upcoming") {
          if (shelfEl) shelfEl.classList.remove("hidden");
          this.render3DShelfView(this.homeSegment === "shows" ? shows : movies);
        } else if (shelfEl) {
          shelfEl.classList.add("hidden");
        }

        if (this.homeSegment === "shows") {
          const startedInProgress = [];
          const notStarted = [];
          const completed = [];
          for (const s of shows) {
            const prog = this.computeShowProgress(s);
            if (prog.isCompleted) completed.push(s);
            else if (prog.hasStarted) startedInProgress.push(s);
            else notStarted.push(s);
          }

          if (this.mediaFilter === "completed") {
            listEl.innerHTML =
              completed.map((s) => this.renderShowCard(s)).join("") ||
              '<div class="tv-card rounded-2xl p-8 text-center text-xs text-zinc-400">No completed shows yet.</div>';
          } else if (this.mediaFilter === "all") {
            listEl.innerHTML =
              shows.map((s) => this.renderShowCard(s)).join("") ||
              '<div class="tv-card rounded-2xl p-8 text-center text-xs text-zinc-400">Your Watch List is empty. Tap 🔍 below to add shows.</div>';
          } else {
            let html = startedInProgress.map((s) => this.renderShowCard(s)).join("");
            if (notStarted.length > 0) {
              html +=
                '<div class="flex items-center gap-3 py-2 cursor-pointer" onclick="app.havenStartedCollapsed = !app.havenStartedCollapsed; app.renderHome();">' +
                '<div class="h-px bg-zinc-800 flex-1"></div>' +
                '<span class="text-xs font-medium text-zinc-400">Haven&apos;t started yet ' +
                (this.havenStartedCollapsed ? "∨" : "∧") +
                "</span>" +
                '<div class="h-px bg-zinc-800 flex-1"></div>' +
                "</div>";
              if (!this.havenStartedCollapsed) {
                html += notStarted.map((s) => this.renderShowCard(s)).join("");
              }
            }
            listEl.innerHTML =
              html ||
              '<div class="tv-card rounded-2xl p-8 text-center text-xs text-zinc-400">No shows in progress. Tap 🔍 to search and add series!</div>';
          }
          return;
        }

        if (this.homeSegment === "movies") {
          const filtered =
            this.mediaFilter === "completed"
              ? movies.filter((m) => m.watched)
              : this.mediaFilter === "in_progress"
              ? movies.filter((m) => !m.watched)
              : movies;
          listEl.innerHTML =
            filtered.map((m) => this.renderMovieCard(m)).join("") ||
            '<div class="tv-card rounded-2xl p-8 text-center text-xs text-zinc-400">No movies in this view yet.</div>';
          return;
        }

        // Upcoming Segment (Watchlist | Actors)
        this.renderUpcomingList(listEl);
      }

      renderUpcomingList(listEl) {
        const shows = Array.isArray(this.state.shows) ? this.state.shows : [];
        const actors = Array.isArray(this.state.actors) ? this.state.actors : [];
        const upcomingItems = [];

        for (const s of shows) {
          const prog = this.computeShowProgress(s);
          const ep = prog.nextEp || (s.episodes || [])[(s.episodes || []).length - 1];
          if (!ep) continue;
          const leadActor = (Array.isArray(s.cast) && s.cast[0]) || "Lead Cast";
          const matchedActorObj = actors.find((a) =>
            (s.cast || []).some((c) => String(c).toLowerCase() === String(a.name).toLowerCase())
          );
          if (this.upcomingFilter === "actors" && !matchedActorObj && actors.length > 0) {
            // Still include if user has actors or fallback to lead cast
          }
          upcomingItems.push({
            show: s,
            ep,
            actorName: matchedActorObj ? matchedActorObj.name : leadActor,
            actorImg:
              matchedActorObj?.imageUrl ||
              "https://via.placeholder.com/80x80/27272a/facc15?text=" + encodeURIComponent(leadActor.slice(0, 1)),
            dateBadge: ep.airDate || "Thursday 30th",
            bucket: ep.number % 2 === 0 ? "5 days" : "1 week",
          });
        }

        if (upcomingItems.length === 0) {
          listEl.innerHTML =
            '<div class="tv-card rounded-2xl p-8 text-center text-xs text-zinc-400">No upcoming releases found.</div>';
          return;
        }

        let html = "";
        let lastBucket = "";
        for (const item of upcomingItems) {
          if (item.bucket !== lastBucket) {
            lastBucket = item.bucket;
            html +=
              '<div class="flex items-center gap-3 py-1">' +
              '<div class="h-px bg-zinc-800 flex-1"></div>' +
              '<span class="text-xs font-medium text-zinc-400">' +
              escapeHtml(item.bucket) +
              "</span>" +
              '<div class="h-px bg-zinc-800 flex-1"></div>' +
              "</div>";
          }
          const codeFormatted = item.ep.code.replace(/^S([0-9]+)E([0-9]+)$/, "S$1·E$2");
          html +=
            '<div onclick="app.openItemById(&quot;' +
            escapeHtml(item.show.id) +
            '&quot;)" class="tv-card rounded-2xl p-3.5 flex items-center gap-3.5 cursor-pointer hover:border-zinc-700 transition">' +
            '<img src="' +
            escapeHtml(item.show.posterUrl || "") +
            '" class="w-16 h-24 object-cover rounded-xl shrink-0 bg-zinc-900" />' +
            '<div class="flex-1 min-w-0 space-y-1">' +
            '<div class="flex items-start justify-between gap-2">' +
            '<h3 class="text-base font-extrabold text-white leading-snug">' +
            escapeHtml(item.show.title) +
            "</h3>" +
            '<span class="px-2.5 py-1 rounded-full bg-zinc-800 text-[11px] font-semibold text-zinc-300 shrink-0">' +
            escapeHtml(item.dateBadge) +
            "</span>" +
            "</div>" +
            '<div class="text-xs font-bold tv-yellow-text">TV Show</div>' +
            '<div class="text-xs font-semibold text-zinc-400">' +
            escapeHtml(codeFormatted) +
            "</div>" +
            '<div class="inline-flex items-center gap-2 bg-zinc-800/80 rounded-full pr-3 pl-1 py-0.5 mt-1">' +
            '<img src="' +
            escapeHtml(item.actorImg) +
            '" class="w-5 h-5 rounded-full object-cover" />' +
            '<span class="text-xs font-medium text-zinc-200">' +
            escapeHtml(item.actorName) +
            "</span>" +
            "</div>" +
            "</div>" +
            "</div>";
        }
        listEl.innerHTML = html;
      }

      async markNextEpisodeWatched(showId) {
        const show = (this.state.shows || []).find((s) => s.id === showId);
        if (!show) return;
        const prog = this.computeShowProgress(show);
        if (!prog.nextEp) return;
        const set = new Set(show.watchedEpisodes || []);
        set.add(prog.nextEp.code);
        show.watchedEpisodes = Array.from(set).sort();
        show.lastWatchedAt = new Date().toISOString();
        await this.persistState();
      }

      async toggleMovieWatched(movieId) {
        const movie = (this.state.movies || []).find((m) => m.id === movieId);
        if (!movie) return;
        movie.watched = !movie.watched;
        movie.watchedAt = movie.watched ? new Date().toISOString().slice(0, 10) : "";
        await this.persistState();
      }

      // =========================================================
      // SEARCH / DISCOVER LOGIC
      // =========================================================
      async runDefaultDiscover() {
        try {
          const res = await fetch("/api/search?q=" + encodeURIComponent("Game of Thrones"));
          if (res.ok) {
            const data = await res.json();
            this.searchResults = data.results || [];
            this.renderSearch();
          }
        } catch (_) {}
      }

      onSearchInput(val) {
        this.searchQuery = String(val || "");
        if (this.searchTimer) clearTimeout(this.searchTimer);
        this.searchTimer = setTimeout(async () => {
          const q = this.searchQuery.trim() || "Game of Thrones";
          try {
            const res = await fetch("/api/search?q=" + encodeURIComponent(q));
            if (res.ok) {
              const data = await res.json();
              this.searchResults = data.results || [];
              this.renderSearch();
            }
          } catch (_) {}
        }, 260);
      }

      clearSearch() {
        const inp = document.getElementById("search-input");
        if (inp) inp.value = "";
        this.searchQuery = "";
        this.runDefaultDiscover();
      }

      setSearchSort(sortKey) {
        this.searchSort = sortKey;
        ["top", "newest", "oldest"].forEach((k) => {
          const btn = document.getElementById("sort-" + k);
          if (btn) {
            btn.className =
              k === sortKey
                ? "px-3.5 py-1.5 rounded-full text-xs font-bold tv-yellow-bg transition"
                : "px-3.5 py-1.5 rounded-full text-xs font-semibold text-zinc-400 hover:text-white transition";
          }
        });
        this.renderSearch();
      }

      renderSearch() {
        const listEl = document.getElementById("search-results-list");
        const countEl = document.getElementById("search-count-label");
        if (!listEl) return;

        const items = [...(this.searchResults || [])];
        if (this.searchSort === "newest") {
          items.sort((a, b) => (Number(b.year) || 0) - (Number(a.year) || 0));
        } else if (this.searchSort === "oldest") {
          items.sort((a, b) => (Number(a.year) || 0) - (Number(b.year) || 0));
        }

        if (countEl) {
          countEl.textContent = items.length + " results";
        }

        const existingShowIds = new Set((this.state.shows || []).map((s) => s.id));
        const existingMovieIds = new Set((this.state.movies || []).map((m) => m.id));
        const existingActorIds = new Set((this.state.actors || []).map((a) => a.id));

        listEl.innerHTML =
          items
            .map((item) => {
              const isInLib =
                existingShowIds.has(item.id) ||
                existingMovieIds.has(item.id) ||
                existingActorIds.has(item.id);
              const badgeClass =
                item.mediaType === "movie"
                  ? "text-sky-400"
                  : item.mediaType === "actor"
                  ? "text-emerald-400"
                  : "tv-yellow-text";
              const badgeLabel =
                item.mediaType === "movie"
                  ? "Movie"
                  : item.mediaType === "actor"
                  ? "Actor"
                  : "TV Show";
              const subtitle =
                item.overview || item.director || item.network || "Tap + to add to your TV Time tracker";
              const poster =
                item.posterUrl ||
                "https://via.placeholder.com/200x300/1c1c22/facc15?text=" + encodeURIComponent(item.title || "Item");

              const rightBtn = isInLib
                ? '<button onclick="event.stopPropagation(); app.openItemById(&quot;' +
                  escapeHtml(item.id) +
                  '&quot;)" class="tv-check-btn">' +
                  CHECK_SVG +
                  "</button>"
                : '<button title="Add to Watch List" onclick="event.stopPropagation(); app.addSearchResult(&quot;' +
                  escapeHtml(item.id) +
                  '&quot;)" class="tv-eye-btn">' +
                  PLUS_SVG +
                  "</button>";

              return (
                '<div onclick="app.addOrOpenSearchResult(&quot;' +
                escapeHtml(item.id) +
                '&quot;)" class="tv-card rounded-2xl p-3 flex items-center gap-3.5 cursor-pointer hover:border-zinc-700 transition">' +
                '<img src="' +
                escapeHtml(poster) +
                '" class="w-16 h-24 object-cover rounded-xl shrink-0 bg-zinc-900" />' +
                '<div class="flex-1 min-w-0 space-y-0.5">' +
                '<div class="flex items-center gap-2 flex-wrap">' +
                '<h3 class="text-base font-extrabold text-white truncate">' +
                escapeHtml(item.title) +
                "</h3>" +
                '<span class="px-2 py-0.5 rounded-md bg-zinc-800 text-[11px] font-semibold text-zinc-300">' +
                escapeHtml(item.year || 2024) +
                "</span>" +
                "</div>" +
                '<div class="text-xs font-bold ' +
                badgeClass +
                '">' +
                badgeLabel +
                "</div>" +
                '<div class="text-xs text-zinc-400 truncate">' +
                escapeHtml(subtitle) +
                "</div>" +
                '<div class="flex items-center gap-1 text-xs font-bold text-zinc-200 pt-0.5">' +
                '<span class="tv-yellow-text">★</span><span>' +
                Number(item.rating || 7.8).toFixed(1) +
                "</span>" +
                "</div>" +
                "</div>" +
                rightBtn +
                "</div>"
              );
            })
            .join("") ||
          '<div class="tv-card rounded-2xl p-8 text-center text-xs text-zinc-400">No results found. Try searching another title!</div>';
      }

      async addSearchResult(itemId) {
        const item = (this.searchResults || []).find((r) => r.id === itemId);
        if (!item) return;

        if (item.mediaType === "actor") {
          if (!(this.state.actors || []).some((a) => a.id === item.id)) {
            this.state.actors = [
              {
                id: item.id,
                name: item.title,
                role: "Acting",
                imageUrl: item.posterUrl,
                imdbUrl: item.imdbUrl || "https://www.imdb.com/find/?q=" + encodeURIComponent(item.title),
              },
              ...(this.state.actors || []),
            ];
            await this.persistState();
          }
          return;
        }

        if (item.mediaType === "movie") {
          if (!(this.state.movies || []).some((m) => m.id === item.id)) {
            this.state.movies = [
              {
                ...item,
                watched: false,
                watchedAt: "",
                userRating: 0,
                reaction: "",
                rewatchCount: 0,
                isFavorite: false,
              },
              ...(this.state.movies || []),
            ];
            await this.persistState();
          }
          return;
        }

        // TV Show -> fetch full episode guide from TVMaze
        if (!(this.state.shows || []).some((s) => s.id === item.id)) {
          let fullShow = null;
          if (item.tvmazeId) {
            try {
              const res = await fetch("/api/show-details?tvmazeId=" + encodeURIComponent(item.tvmazeId));
              if (res.ok) fullShow = await res.json();
            } catch (_) {}
          }
          const newShow = {
            ...(fullShow || item),
            userStatus: "watching",
            userRating: 0,
            reaction: "😍",
            isFavorite: false,
            rewatchCount: 0,
            watchedEpisodes: [],
            episodes: fullShow?.episodes || [
              { code: "S01E01", season: 1, number: 1, title: "Pilot", airDate: String(item.year || 2024) + "-01-01", runtimeMins: item.runtimeMins || 45 },
            ],
          };
          this.state.shows = [newShow, ...(this.state.shows || [])];
          await this.persistState();
        }
      }

      async addOrOpenSearchResult(itemId) {
        await this.addSearchResult(itemId);
        this.openItemById(itemId);
      }

      // =========================================================
      // SHOW / MOVIE DETAIL BOTTOM SHEET (Info | Episodes)
      // =========================================================
      async openItemById(id) {
        const show = (this.state.shows || []).find((s) => s.id === id);
        if (show) {
          this.detailItem = show;
          this.detailType = "show";
          this.detailTab = "episodes";
          // Lazily enrich episodes from TVMaze if only stub episodes exist
          if (show.tvmazeId && (!show.episodes || show.episodes.length <= 1)) {
            fetch("/api/show-details?tvmazeId=" + encodeURIComponent(show.tvmazeId))
              .then((r) => (r.ok ? r.json() : null))
              .then((full) => {
                if (full && Array.isArray(full.episodes) && full.episodes.length > 0) {
                  show.episodes = full.episodes;
                  if (full.cast?.length) show.cast = full.cast;
                  this.persistState();
                }
              })
              .catch(() => {});
          }
          this.showDetailSheetModal();
          return;
        }
        const movie = (this.state.movies || []).find((m) => m.id === id);
        if (movie) {
          this.detailItem = movie;
          this.detailType = "movie";
          this.detailTab = "info";
          this.showDetailSheetModal();
        }
      }

      showDetailSheetModal() {
        document.getElementById("detail-sheet-backdrop")?.classList.remove("hidden");
        document.getElementById("detail-sheet")?.classList.remove("hidden");
        this.renderDetailSheet();
      }

      closeDetailSheet() {
        document.getElementById("detail-sheet-backdrop")?.classList.add("hidden");
        document.getElementById("detail-sheet")?.classList.add("hidden");
        this.detailItem = null;
      }

      setDetailTab(tab) {
        this.detailTab = tab;
        this.renderDetailSheet();
      }

      toggleSeasonAccordion(seasonNum) {
        if (this.openSeasons.has(seasonNum)) {
          this.openSeasons.delete(seasonNum);
        } else {
          this.openSeasons.add(seasonNum);
        }
        this.renderDetailSheet();
      }

      async toggleEpisodeCode(showId, epCode) {
        const show = (this.state.shows || []).find((s) => s.id === showId);
        if (!show) return;
        const set = new Set(show.watchedEpisodes || []);
        if (set.has(epCode)) {
          set.delete(epCode);
        } else {
          set.add(epCode);
        }
        show.watchedEpisodes = Array.from(set).sort();
        show.lastWatchedAt = new Date().toISOString();
        await this.persistState();
      }

      async toggleSeasonWatched(showId, seasonNum) {
        const show = (this.state.shows || []).find((s) => s.id === showId);
        if (!show) return;
        const seasonEps = (show.episodes || []).filter((e) => Number(e.season) === Number(seasonNum));
        const set = new Set(show.watchedEpisodes || []);
        const allWatched = seasonEps.every((e) => set.has(e.code));
        for (const e of seasonEps) {
          if (allWatched) set.delete(e.code);
          else set.add(e.code);
        }
        show.watchedEpisodes = Array.from(set).sort();
        show.lastWatchedAt = new Date().toISOString();
        await this.persistState();
      }

      async toggleFavoriteItem(id) {
        const show = (this.state.shows || []).find((s) => s.id === id);
        if (show) {
          show.isFavorite = !show.isFavorite;
          await this.persistState();
          return;
        }
        const movie = (this.state.movies || []).find((m) => m.id === id);
        if (movie) {
          movie.isFavorite = !movie.isFavorite;
          await this.persistState();
        }
      }

      async incrementRewatch(id) {
        const show = (this.state.shows || []).find((s) => s.id === id);
        if (show) {
          show.rewatchCount = (Number(show.rewatchCount) || 0) + 1;
          await this.persistState();
          return;
        }
        const movie = (this.state.movies || []).find((m) => m.id === id);
        if (movie) {
          movie.rewatchCount = (Number(movie.rewatchCount) || 0) + 1;
          await this.persistState();
        }
      }

      async setItemReaction(id, emoji) {
        const show = (this.state.shows || []).find((s) => s.id === id);
        if (show) {
          show.reaction = emoji;
          await this.persistState();
          return;
        }
        const movie = (this.state.movies || []).find((m) => m.id === id);
        if (movie) {
          movie.reaction = emoji;
          await this.persistState();
        }
      }

      async removeItemFromLibrary(id) {
        this.state.shows = (this.state.shows || []).filter((s) => s.id !== id);
        this.state.movies = (this.state.movies || []).filter((m) => m.id !== id);
        this.closeDetailSheet();
        await this.persistState();
      }

      renderDetailSheet() {
        const item = this.detailItem;
        const bodyEl = document.getElementById("detail-body");
        const infoTabBtn = document.getElementById("detail-tab-info");
        const epsTabBtn = document.getElementById("detail-tab-episodes");
        if (!item || !bodyEl) return;

        if (this.detailType === "movie") {
          if (epsTabBtn) epsTabBtn.classList.add("hidden");
          this.detailTab = "info";
        } else if (epsTabBtn) {
          epsTabBtn.classList.remove("hidden");
        }

        if (infoTabBtn) {
          infoTabBtn.className =
            this.detailTab === "info"
              ? "py-3 text-sm font-bold text-white border-b-2 border-yellow-400 transition"
              : "py-3 text-sm font-bold text-zinc-400 border-b-2 border-transparent transition";
        }
        if (epsTabBtn) {
          epsTabBtn.className =
            this.detailTab === "episodes"
              ? "py-3 text-sm font-bold text-white border-b-2 border-yellow-400 transition"
              : "py-3 text-sm font-bold text-zinc-400 border-b-2 border-transparent transition";
        }

        if (this.detailTab === "info") {
          const reactions = ["😍", "🔥", "🤯", "😂", "😢", "😴"];
          const reactHtml = reactions
            .map(
              (em) =>
                '<button onclick="app.setItemReaction(&quot;' +
                escapeHtml(item.id) +
                "', '" +
                em +
                '&quot;)" class="w-9 h-9 rounded-full ' +
                (item.reaction === em ? "tv-yellow-bg" : "bg-zinc-800") +
                ' text-base flex items-center justify-center">' +
                em +
                "</button>"
            )
            .join("");

          bodyEl.innerHTML =
            '<div class="flex gap-4 items-start">' +
            '<img src="' +
            escapeHtml(item.posterUrl || "") +
            '" class="w-24 h-36 object-cover rounded-2xl bg-zinc-900 shrink-0" />' +
            '<div class="space-y-1.5 flex-1">' +
            '<h2 class="text-xl font-black text-white">' +
            escapeHtml(item.title) +
            "</h2>" +
            '<p class="text-xs font-semibold text-zinc-400">' +
            escapeHtml(item.year || 2024) +
            " · " +
            escapeHtml(item.network || item.streamingService || "Streaming") +
            " · " +
            (Number(item.runtimeMins) || 45) +
            "m</p>" +
            '<div class="flex items-center gap-2 pt-1 flex-wrap">' +
            '<button onclick="app.toggleFavoriteItem(&quot;' +
            escapeHtml(item.id) +
            '&quot;)" class="px-3 py-1.5 rounded-full text-xs font-bold ' +
            (item.isFavorite ? "tv-yellow-bg" : "bg-zinc-800 text-zinc-200") +
            '">' +
            (item.isFavorite ? "★ Favorited" : "☆ Favorite") +
            "</button>" +
            '<button onclick="app.incrementRewatch(&quot;' +
            escapeHtml(item.id) +
            '&quot;)" class="px-3 py-1.5 rounded-full bg-zinc-800 text-xs font-bold text-zinc-200">↻ Rewatch (' +
            (Number(item.rewatchCount) || 0) +
            ")</button>" +
            "</div>" +
            "</div>" +
            "</div>" +
            '<p class="text-xs text-zinc-300 leading-relaxed">' +
            escapeHtml(item.overview || "No synopsis available.") +
            "</p>" +
            '<div class="space-y-1.5">' +
            '<div class="text-[11px] font-bold uppercase text-zinc-400">Your Reaction Badge</div>' +
            '<div class="flex gap-2">' +
            reactHtml +
            "</div></div>" +
            '<div class="pt-3 border-t border-zinc-800 flex justify-between items-center">' +
            '<button onclick="app.removeItemFromLibrary(&quot;' +
            escapeHtml(item.id) +
            '&quot;)" class="text-xs font-bold text-rose-400 hover:underline">Remove from Watch List</button>' +
            (this.detailType === "movie"
              ? '<button onclick="app.toggleMovieWatched(&quot;' +
                escapeHtml(item.id) +
                '&quot;)" class="px-4 py-2 rounded-full tv-yellow-bg text-xs font-extrabold">' +
                (item.watched ? "✓ Watched" : "Mark Watched") +
                "</button>"
              : "") +
            "</div>";
          return;
        }

        // Episodes Tab (Matches Screenshot 4: Season Accordions + Episode Thumbnails + Yellow Check / Gray Eye)
        const episodes = Array.isArray(item.episodes) ? item.episodes : [];
        const watchedSet = new Set(item.watchedEpisodes || []);
        const bySeason = new Map();
        for (const ep of episodes) {
          const sNum = Number(ep.season) || 1;
          if (!bySeason.has(sNum)) bySeason.set(sNum, []);
          bySeason.get(sNum).push(ep);
        }

        let html = "";
        for (const [seasonNum, sEps] of bySeason.entries()) {
          const isOpen = this.openSeasons.has(seasonNum);
          const allSeasonWatched = sEps.length > 0 && sEps.every((e) => watchedSet.has(e.code));
          html +=
            '<div class="tv-card rounded-2xl overflow-hidden mb-3">' +
            '<div onclick="app.toggleSeasonAccordion(' +
            seasonNum +
            ')" class="p-3.5 flex items-center justify-between cursor-pointer hover:bg-zinc-800/40">' +
            '<div class="flex items-center gap-3">' +
            '<button onclick="event.stopPropagation(); app.toggleSeasonWatched(&quot;' +
            escapeHtml(item.id) +
            "', " +
            seasonNum +
            ')" class="' +
            (allSeasonWatched ? "tv-check-btn !w-9 !h-9" : "tv-gray-eye-btn !w-9 !h-9") +
            '">' +
            (allSeasonWatched ? CHECK_SVG : EYE_SVG) +
            "</button>" +
            '<span class="text-base font-extrabold text-white">Season ' +
            seasonNum +
            "</span>" +
            "</div>" +
            '<span class="text-xs font-semibold text-zinc-400">' +
            sEps.length +
            " episodes &nbsp;" +
            (isOpen ? "∧" : "∨") +
            "</span>" +
            "</div>";

          if (isOpen) {
            html += '<div class="divide-y divide-zinc-800/70 px-3 pb-2">';
            for (const ep of sEps) {
              const isWatched = watchedSet.has(ep.code);
              const thumb =
                ep.imageUrl ||
                item.posterUrl ||
                "https://via.placeholder.com/160x90/1f1f26/facc15?text=" + encodeURIComponent(ep.code);
              html +=
                '<div onclick="app.toggleEpisodeCode(&quot;' +
                escapeHtml(item.id) +
                "', '" +
                escapeHtml(ep.code) +
                '&quot;)" class="py-3 flex items-center gap-3 cursor-pointer">' +
                '<img src="' +
                escapeHtml(thumb) +
                '" class="w-24 h-14 object-cover rounded-xl bg-zinc-900 shrink-0" />' +
                '<div class="flex-1 min-w-0">' +
                '<div class="text-sm font-bold text-white truncate">' +
                ep.number +
                ". " +
                escapeHtml(ep.title) +
                "</div>" +
                '<div class="text-xs text-zinc-400 mt-0.5">' +
                escapeHtml(ep.airDate || "Aired") +
                "</div>" +
                "</div>" +
                '<button class="' +
                (isWatched ? "tv-check-btn !w-10 !h-10" : "tv-gray-eye-btn") +
                '">' +
                (isWatched ? CHECK_SVG : EYE_SVG) +
                "</button>" +
                "</div>";
            }
            html += "</div>";
          }
          html += "</div>";
        }

        bodyEl.innerHTML = html || '<p class="text-xs text-zinc-400 text-center py-6">No episodes found.</p>';
      }

      // =========================================================
      // PROFILE, STATS, FOLLOWING & TV TIME IMPORT LOGIC
      // =========================================================
      renderProfile() {
        const shows = Array.isArray(this.state.shows) ? this.state.shows : [];
        const movies = Array.isArray(this.state.movies) ? this.state.movies : [];
        const actors = Array.isArray(this.state.actors) ? this.state.actors : [];
        const users = Array.isArray(this.state.followingUsers) ? this.state.followingUsers : ["partner"];

        const cleanUser = String(this.userId || "member");
        const nameEl = document.getElementById("profile-display-name");
        const handleEl = document.getElementById("profile-handle");
        const initEl = document.getElementById("profile-avatar-initials");
        if (nameEl) nameEl.textContent = cleanUser.charAt(0).toUpperCase() + cleanUser.slice(1);
        if (handleEl) handleEl.textContent = "@" + cleanUser;
        if (initEl) initEl.textContent = cleanUser.slice(0, 2).toUpperCase();

        const folCountEl = document.getElementById("profile-following-count");
        if (folCountEl) folCountEl.textContent = String(actors.length + users.length);

        let epCount = 0;
        let showMins = 0;
        for (const s of shows) {
          const w = (s.watchedEpisodes || []).length;
          const mult = 1 + (Number(s.rewatchCount) || 0);
          epCount += w * mult;
          showMins += w * (Number(s.runtimeMins) || 45) * mult;
        }
        let movCount = 0;
        let movMins = 0;
        for (const m of movies) {
          if (m.watched) {
            const mult = 1 + (Number(m.rewatchCount) || 0);
            movCount += mult;
            movMins += (Number(m.runtimeMins) || 120) * mult;
          }
        }
        const totalMins = showMins + movMins;
        const fmtDaysHours = (mins) => {
          const d = Math.floor(mins / 1440);
          const h = Math.floor((mins % 1440) / 60);
          return d + "d " + h + "h";
        };

        const totalEl = document.getElementById("stat-total-time");
        const sTimeEl = document.getElementById("stat-series-time");
        const sSubEl = document.getElementById("stat-series-sub");
        const mTimeEl = document.getElementById("stat-movies-time");
        const mSubEl = document.getElementById("stat-movies-sub");

        if (totalEl) totalEl.textContent = fmtDaysHours(totalMins);
        if (sTimeEl) sTimeEl.textContent = fmtDaysHours(showMins);
        if (sSubEl) sSubEl.textContent = "Series · " + epCount + " eps";
        if (mTimeEl) mTimeEl.textContent = fmtDaysHours(movMins);
        if (mSubEl) mSubEl.textContent = "Movies · " + movCount;

        // Carousels
        const favEl = document.getElementById("carousel-favorites");
        if (favEl) {
          const favs = shows.filter((s) => s.isFavorite);
          const list = favs.length ? favs : shows;
          favEl.innerHTML = list
            .map(
              (s) =>
                '<div onclick="app.openItemById(&quot;' +
                escapeHtml(s.id) +
                '&quot;)" class="w-28 shrink-0 cursor-pointer space-y-1">' +
                '<div class="relative">' +
                '<img src="' +
                escapeHtml(s.posterUrl || "") +
                '" class="w-28 h-40 object-cover rounded-2xl bg-zinc-900" />' +
                "</div>" +
                '<div class="text-xs font-bold text-white truncate">' +
                escapeHtml(s.title) +
                "</div>" +
                "</div>"
            )
            .join("");
        }

        const recSeriesEl = document.getElementById("carousel-recent-series");
        if (recSeriesEl) {
          recSeriesEl.innerHTML = shows
            .map((s) => {
              const watched = s.watchedEpisodes || [];
              const lastCode = (watched[watched.length - 1] || "S01E01").replace(/^S([0-9]+)E([0-9]+)$/, "S$1·E$2");
              return (
                '<div onclick="app.openItemById(&quot;' +
                escapeHtml(s.id) +
                '&quot;)" class="w-28 shrink-0 cursor-pointer space-y-1">' +
                '<div class="relative">' +
                '<img src="' +
                escapeHtml(s.posterUrl || "") +
                '" class="w-28 h-40 object-cover rounded-2xl bg-zinc-900" />' +
                (s.reaction
                  ? '<span class="absolute top-1.5 right-1.5 w-6 h-6 rounded-full bg-zinc-900/90 flex items-center justify-center text-xs shadow">' +
                    escapeHtml(s.reaction) +
                    "</span>"
                  : "") +
                "</div>" +
                '<div class="text-xs font-bold text-white truncate">' +
                escapeHtml(s.title) +
                "</div>" +
                '<div class="text-xs font-extrabold tv-yellow-text">' +
                escapeHtml(lastCode) +
                "</div>" +
                "</div>"
              );
            })
            .join("");
        }

        const recMoviesEl = document.getElementById("carousel-recent-movies");
        if (recMoviesEl) {
          const watchedMovies = movies.filter((m) => m.watched);
          const list = watchedMovies.length ? watchedMovies : movies;
          recMoviesEl.innerHTML = list
            .map(
              (m) =>
                '<div onclick="app.openItemById(&quot;' +
                escapeHtml(m.id) +
                '&quot;)" class="w-28 shrink-0 cursor-pointer space-y-1">' +
                '<div class="relative">' +
                '<img src="' +
                escapeHtml(m.posterUrl || "") +
                '" class="w-28 h-40 object-cover rounded-2xl bg-zinc-900" />' +
                (m.reaction
                  ? '<span class="absolute top-1.5 right-1.5 w-6 h-6 rounded-full bg-zinc-900/90 flex items-center justify-center text-xs shadow">' +
                    escapeHtml(m.reaction) +
                    "</span>"
                  : "") +
                "</div>" +
                '<div class="text-xs font-bold text-white truncate">' +
                escapeHtml(m.title) +
                "</div>" +
                "</div>"
            )
            .join("");
        }
      }

      openFollowingSheet(tab = "actors") {
        document.getElementById("following-sheet")?.classList.remove("hidden");
        this.renderFollowingList(tab);
      }

      closeFollowingSheet() {
        document.getElementById("following-sheet")?.classList.add("hidden");
      }

      renderFollowingList(tab = "actors") {
        const uBtn = document.getElementById("fol-tab-users");
        const aBtn = document.getElementById("fol-tab-actors");
        const bodyEl = document.getElementById("following-list-body");
        if (uBtn) {
          uBtn.className =
            tab === "users"
              ? "py-2 rounded-full text-xs font-bold tv-yellow-bg"
              : "py-2 rounded-full text-xs font-bold text-zinc-400";
        }
        if (aBtn) {
          aBtn.className =
            tab === "actors"
              ? "py-2 rounded-full text-xs font-bold tv-yellow-bg"
              : "py-2 rounded-full text-xs font-bold text-zinc-400";
        }
        if (!bodyEl) return;

        if (tab === "users") {
          const otherUser = "partner";
          bodyEl.innerHTML =
            '<div class="tv-card rounded-2xl p-3.5 flex items-center justify-between">' +
            '<div class="flex items-center gap-3">' +
            '<div class="w-12 h-12 rounded-full bg-zinc-800 flex items-center justify-center font-extrabold tv-yellow-text">' +
            escapeHtml(otherUser.slice(0, 2).toUpperCase()) +
            "</div>" +
            '<div><div class="text-sm font-bold text-white">@' +
            escapeHtml(otherUser) +
            '</div><div class="text-xs tv-yellow-text font-semibold">Shared Household Profile · Synced</div></div>' +
            "</div>" +
            '<button onclick="app.closeFollowingSheet(); app.openAccountModal();" class="px-3.5 py-1.5 rounded-full tv-yellow-bg text-xs font-extrabold">Account</button>' +
            "</div>";
          return;
        }

        const actors = Array.isArray(this.state.actors) ? this.state.actors : [];
        bodyEl.innerHTML =
          actors
            .map(
              (a) =>
                '<div class="flex items-center justify-between py-2 px-1">' +
                '<div class="flex items-center gap-3.5">' +
                '<img src="' +
                escapeHtml(a.imageUrl || "https://via.placeholder.com/80x80/27272a/facc15?text=" + encodeURIComponent(a.name.slice(0, 1))) +
                '" class="w-12 h-12 rounded-full object-cover bg-zinc-800" />' +
                '<div><div class="text-sm font-extrabold text-white">' +
                escapeHtml(a.name) +
                '</div><div class="text-xs text-zinc-400">' +
                escapeHtml(a.role || "Acting") +
                "</div></div>" +
                "</div>" +
                '<a href="' +
                escapeHtml(a.imdbUrl || "https://www.imdb.com/find/?q=" + encodeURIComponent(a.name)) +
                '" target="_blank" rel="noopener" class="px-2.5 py-1 rounded bg-amber-400/20 text-amber-300 border border-amber-400/40 text-[11px] font-extrabold">IMDb</a>' +
                "</div>"
            )
            .join("") || '<p class="text-xs text-zinc-400 text-center py-6">Search actors in 🔍 to follow them.</p>';
      }

      openImportModal() {
        document.getElementById("import-modal")?.classList.remove("hidden");
      }

      closeImportModal() {
        document.getElementById("import-modal")?.classList.add("hidden");
      }

      async handleImportFile(event) {
        const file = event?.target?.files?.[0];
        if (!file) return;
        try {
          const text = await file.text();
          const parsed = JSON.parse(text);
          if (Array.isArray(parsed.shows)) {
            this.state.shows = parsed.shows;
          }
          if (Array.isArray(parsed.movies)) {
            this.state.movies = parsed.movies;
          }
          if (Array.isArray(parsed.actors)) {
            this.state.actors = parsed.actors;
          }
          this.closeImportModal();
          await this.persistState();
          alert("Imported TV Time history (" + this.state.shows.length + " shows, " + this.state.movies.length + " movies)!");
        } catch (_) {
          alert("Could not parse import file. Please select a valid TV Time JSON export.");
        }
      }

      exportBackupJson() {
        const blob = new Blob([JSON.stringify(this.state, null, 2)], { type: "application/json" });
        const url = URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = url;
        a.download = "tvtime-backup-" + this.userId + ".json";
        a.click();
        URL.revokeObjectURL(url);
      }

      openAccountModal() {
        const inp = document.getElementById("acc-user-input");
        if (inp) inp.value = this.userEmail || this.userId || "Not signed in";
        document.getElementById("account-modal")?.classList.remove("hidden");
      }

      closeAccountModal() {
        document.getElementById("account-modal")?.classList.add("hidden");
      }
    }

    const app = new TvTimeClient();
  </script>
</body>
</html>
`;
