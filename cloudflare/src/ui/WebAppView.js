// TV Time: Show & Movie Tracker — Web App View (1:1 clone of iOS App id6792364354)
export const WEB_APP_HTML = String.raw`<!DOCTYPE html>
<html lang="en" class="dark">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover" />
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
      background: #232329;
      border: 1.5px solid #3f3f46;
      display: flex;
      align-items: center;
      justify-content: center;
      color: #9ca3af;
      flex-shrink: 0;
      transition: transform 0.12s ease, background 0.15s ease, color 0.15s ease;
    }
    .tv-eye-btn:active {
      transform: scale(0.92);
    }
    .tv-check-btn {
      width: 44px;
      height: 44px;
      border-radius: 9999px;
      background: var(--tv-green);
      border: 1.5px solid var(--tv-green);
      color: #ffffff;
      display: flex;
      align-items: center;
      justify-content: center;
      font-weight: 800;
      flex-shrink: 0;
      transition: transform 0.12s ease, background 0.15s ease;
    }
    .tv-gray-eye-btn {
      width: 40px;
      height: 40px;
      border-radius: 9999px;
      background: #232329;
      border: 1.5px solid #3f3f46;
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
  <main class="max-w-xl mx-auto px-4 pt-4 space-y-4" style="padding-top: max(1rem, calc(env(safe-area-inset-top) + 0.5rem));">

    <!-- Pull-to-Refresh Indicator -->
    <div id="pull-refresh-indicator" class="hidden flex items-center justify-center overflow-hidden transition-all duration-150">
      <div class="tv-pill-bar px-4 py-1.5 rounded-full border border-yellow-400/40 flex items-center gap-2 text-xs font-extrabold tv-yellow-text shadow-lg">
        <span id="pull-refresh-icon" class="inline-block transition-transform">↻</span>
        <span id="pull-refresh-label">Pull down to refresh</span>
      </div>
    </div>

    <!-- ========================================================= -->
    <!-- TOP BAR WITH HAMBURGER MENU (Stats, Export, Sync, Account)-->
    <!-- ========================================================= -->
    <header class="flex items-center justify-between gap-3">
      <button
        id="hamburger-menu-btn"
        onclick="app.openHamburgerMenu()"
        class="tv-pill-bar px-3.5 py-2 rounded-full flex items-center gap-2 text-zinc-200 hover:text-yellow-400 transition border border-zinc-800/80"
        title="Open Menu"
      >
        <svg class="w-5 h-5 tv-yellow-text" fill="none" stroke="currentColor" stroke-width="2.4" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" d="M4 6h16M4 12h16M4 18h16" />
        </svg>
        <span class="text-xs font-extrabold tracking-tight text-white">TV Time</span>
      </button>
    </header>

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

      <!-- Dropdown Filter Row for Shows & Movies (Status Dropdown + Streaming Platform Multi-Select Dropdown) -->
      <div id="home-subfilter-media" class="flex items-center justify-between gap-2 flex-wrap">
        <div class="relative flex items-center">
          <select
            id="home-status-select"
            onchange="app.setMediaFilter(this.value)"
            class="tv-pill-bar pl-3.5 pr-8 py-2 rounded-full text-xs font-extrabold text-yellow-400 border border-zinc-800 focus:outline-none cursor-pointer appearance-none bg-[#1c1c22]"
          >
            <option value="in_progress">In progress</option>
            <option value="interested">Watchlist</option>
            <option value="completed">Completed</option>
            <option value="stopped">Stopped</option>
          </select>
          <span class="pointer-events-none absolute right-3 text-[10px] text-zinc-400">▾</span>
        </div>

        <!-- US Streaming Platform Multi-Select Dropdown (Home) -->
        <div id="home-us-platform-bar" class="relative flex items-center gap-1.5"></div>
      </div>

      <!-- Sub-Filter Row for Upcoming -->
      <div id="home-subfilter-upcoming" class="hidden flex items-center justify-between gap-2">
        <span class="text-sm font-semibold text-zinc-400 px-1">Upcoming Episodes</span>
      </div>

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
          placeholder="Search TV shows or movies..."
          oninput="app.onSearchInput(this.value)"
          class="w-full bg-transparent text-white text-sm focus:outline-none placeholder-zinc-500"
        />
        <button id="search-clear-btn" onclick="app.clearSearch()" class="text-yellow-400 font-bold text-sm px-1">✕</button>
      </div>

      <!-- Dropdown Filters (Streaming Platform Dropdown + Sort Dropdown) + Results Count -->
      <div class="space-y-2">
        <div class="flex items-center justify-between gap-2">
          <div id="search-us-platform-bar" class="relative flex items-center gap-1.5 min-w-0"></div>
          <div class="relative flex items-center shrink-0">
            <select
              id="search-sort-select"
              onchange="app.setSearchSort(this.value)"
              class="tv-pill-bar pl-3 pr-7 py-2 rounded-full text-xs font-bold text-yellow-400 border border-zinc-800 focus:outline-none cursor-pointer appearance-none bg-[#1c1c22]"
            >
              <option value="top">Top</option>
              <option value="newest">Newest</option>
              <option value="oldest">Oldest</option>
            </select>
            <span class="pointer-events-none absolute right-2.5 text-[10px] text-zinc-400">▾</span>
          </div>
        </div>
        <div id="search-count-label" class="text-xs font-medium text-zinc-400 px-1">Popular &amp; Trending</div>
      </div>

      <!-- Search Results Cards -->
      <div id="search-results-list" class="space-y-3"></div>
    </section>

    <!-- ========================================================= -->
    <!-- TAB 3: TINDER-STYLE SWIPE RECOMMENDATIONS (✨)            -->
    <!-- ========================================================= -->
    <section id="tab-swipe" class="hidden space-y-4">
      <div class="flex items-center justify-between gap-2">
        <div>
          <h2 class="text-base font-extrabold text-white flex items-center gap-1.5">
            <span>✨ For You</span>
            <span id="swipe-remaining-badge" class="px-2 py-0.5 rounded-full bg-zinc-800 text-[11px] font-bold tv-yellow-text">Recommendations</span>
          </h2>
        </div>
        <div class="flex items-center gap-1.5">
          <div class="relative flex items-center">
            <select
              id="swipe-filter-select"
              onchange="app.setSwipeMediaFilter(this.value)"
              class="tv-pill-bar pl-3 pr-7 py-1.5 rounded-full text-xs font-bold text-yellow-400 border border-zinc-800 focus:outline-none cursor-pointer appearance-none bg-[#1c1c22]"
            >
              <option value="all">All</option>
              <option value="show">Series</option>
              <option value="movie">Movies</option>
            </select>
            <span class="pointer-events-none absolute right-2.5 text-[10px] text-zinc-400">▾</span>
          </div>
          <button
            onclick="app.loadRecommendations(true)"
            title="Fetch more recommendations"
            class="tv-pill-bar w-8 h-8 rounded-full flex items-center justify-center text-yellow-400 hover:text-white text-xs font-bold transition"
          >
            ↻
          </button>
        </div>
      </div>

      <!-- Tinder Swipe Deck Container -->
      <div id="swipe-deck-container" class="relative min-h-[470px] flex flex-col items-center justify-center"></div>
    </section>

    <!-- ========================================================= -->
    <!-- FULL PAGE: STATISTICS (📊)                                -->
    <!-- ========================================================= -->
    <section id="tab-stats" class="hidden space-y-4">
      <div class="flex items-center gap-2.5">
        <button
          onclick="app.goBack()"
          class="tv-pill-bar w-9 h-9 rounded-full flex items-center justify-center text-zinc-300 hover:text-white border border-zinc-800"
          title="Back"
        >
          ←
        </button>
        <div>
          <h2 class="text-lg font-extrabold text-white">📊 Statistics</h2>
          <p class="text-xs text-zinc-400">Your watch hours, series, episodes &amp; movies</p>
        </div>
      </div>

      <!-- Hero Box: Total Watch Hours -->
      <div class="tv-card rounded-3xl p-6 space-y-2 border border-yellow-400/20">
        <div class="text-[11px] font-bold uppercase tracking-wider text-zinc-400">TOTAL WATCH TIME</div>
        <div id="stat-total-time" class="text-4xl font-black tv-yellow-text tracking-tight">0.0 hrs</div>
        <div id="stat-total-sub" class="text-xs text-zinc-400">Total watch hours (0d 0h)</div>
      </div>

      <!-- 2 Watch Hours Boxes: Series & Movies -->
      <div class="grid grid-cols-2 gap-3.5">
        <div class="tv-card rounded-3xl p-5 space-y-1">
          <div id="stat-series-time" class="text-2xl font-black text-white">0.0 hrs</div>
          <div class="text-xs font-extrabold tv-yellow-text">Series watch hours</div>
        </div>
        <div class="tv-card rounded-3xl p-5 space-y-1">
          <div id="stat-movies-time" class="text-2xl font-black text-white">0.0 hrs</div>
          <div class="text-xs font-extrabold tv-yellow-text">Movie watch hours</div>
        </div>
      </div>

      <!-- 3 Separate Count Boxes: Series | Episodes | Movies -->
      <div class="grid grid-cols-3 gap-3">
        <div class="tv-card rounded-2xl p-4 text-center space-y-1">
          <div id="stat-series-count" class="text-2xl font-black text-white">0</div>
          <div class="text-[11px] font-bold uppercase tracking-wider text-zinc-400">Series</div>
        </div>
        <div class="tv-card rounded-2xl p-4 text-center space-y-1">
          <div id="stat-episodes-count" class="text-2xl font-black tv-yellow-text">0</div>
          <div class="text-[11px] font-bold uppercase tracking-wider text-zinc-400">Episodes</div>
        </div>
        <div class="tv-card rounded-2xl p-4 text-center space-y-1">
          <div id="stat-movies-count" class="text-2xl font-black text-white">0</div>
          <div class="text-[11px] font-bold uppercase tracking-wider text-zinc-400">Movies</div>
        </div>
      </div>
    </section>

    <!-- ========================================================= -->
    <!-- FULL PAGE: RECENTLY WATCHED (🎬)                          -->
    <!-- ========================================================= -->
    <section id="tab-recent" class="hidden space-y-5">
      <div class="flex items-center gap-2.5">
        <button
          onclick="app.goBack()"
          class="tv-pill-bar w-9 h-9 rounded-full flex items-center justify-center text-zinc-300 hover:text-white border border-zinc-800"
          title="Back"
        >
          ←
        </button>
        <div>
          <h2 class="text-lg font-extrabold text-white">🎬 Recently Watched</h2>
          <p class="text-xs text-zinc-400">Your recently watched series &amp; movies</p>
        </div>
      </div>

      <!-- Recently watched Series Carousel -->
      <div class="tv-card rounded-3xl p-5 space-y-3">
        <div class="flex items-center justify-between">
          <h3 class="text-sm font-extrabold text-white">Recently watched <span id="label-recent-series-count" class="font-normal text-zinc-400">Series</span></h3>
          <button onclick="app.switchTab('home'); app.setHomeSegment('shows'); app.setMediaFilter('in_progress');" class="text-xs font-semibold tv-yellow-text">See all</button>
        </div>
        <div id="carousel-recent-series" class="flex gap-3 overflow-x-auto no-scrollbar pb-1"></div>
      </div>

      <!-- Recently watched Movies Carousel -->
      <div class="tv-card rounded-3xl p-5 space-y-3">
        <div class="flex items-center justify-between">
          <h3 class="text-sm font-extrabold text-white">Recently watched <span id="label-recent-movies-count" class="font-normal text-zinc-400">Movies</span></h3>
          <button onclick="app.switchTab('home'); app.setHomeSegment('movies'); app.setMediaFilter('completed');" class="text-xs font-semibold tv-yellow-text">See all</button>
        </div>
        <div id="carousel-recent-movies" class="flex gap-3 overflow-x-auto no-scrollbar pb-1"></div>
      </div>
    </section>

    <!-- ========================================================= -->
    <!-- FULL PAGE: IMPORT / EXPORT CSV & JSON (⇅)                 -->
    <!-- ========================================================= -->
    <section id="tab-import" class="hidden space-y-5">
      <div class="flex items-center gap-2.5">
        <button
          onclick="app.goBack()"
          class="tv-pill-bar w-9 h-9 rounded-full flex items-center justify-center text-zinc-300 hover:text-white border border-zinc-800"
          title="Back"
        >
          ←
        </button>
        <div>
          <h2 class="text-lg font-extrabold text-white">⇅ Import / Export CSV &amp; JSON</h2>
          <p class="text-xs text-zinc-400">Backup or restore your entire TV Time library</p>
        </div>
      </div>

      <div class="tv-card rounded-3xl p-6 space-y-5">
        <div class="flex items-center gap-3.5">
          <div class="w-12 h-12 rounded-2xl bg-zinc-800/90 flex items-center justify-center tv-yellow-text text-xl font-bold shrink-0">
            ⇅
          </div>
          <div>
            <h3 class="text-base font-extrabold text-white">Library Backup &amp; Restore</h3>
            <p class="text-xs text-zinc-400 mt-0.5">
              Export your shows, movies, episode progress, and recommendations history as CSV or JSON.
            </p>
          </div>
        </div>

        <div class="space-y-2.5 text-xs text-zinc-300 border-t border-zinc-800 pt-4">
          <div class="flex items-start gap-2.5">
            <span class="w-5 h-5 rounded-full bg-zinc-800 tv-yellow-text font-bold flex items-center justify-center shrink-0">1</span>
            <span>Export CSV (<code>.csv</code>) to view or edit your watch history in Excel or Google Sheets.</span>
          </div>
          <div class="flex items-start gap-2.5">
            <span class="w-5 h-5 rounded-full bg-zinc-800 tv-yellow-text font-bold flex items-center justify-center shrink-0">2</span>
            <span>Export JSON (<code>.json</code>) for full lossless backup and cross-device restore.</span>
          </div>
          <div class="flex items-start gap-2.5">
            <span class="w-5 h-5 rounded-full bg-zinc-800 tv-yellow-text font-bold flex items-center justify-center shrink-0">3</span>
            <span>Import a JSON backup file to restore or merge your shows and movies.</span>
          </div>
        </div>

        <div class="space-y-3 pt-2">
          <button onclick="app.exportBackupCsv()" class="w-full py-3.5 rounded-2xl tv-yellow-bg text-sm font-extrabold flex items-center justify-center gap-2">
            📊 Export Spreadsheet (.csv)
          </button>
          <button onclick="app.exportBackupJson()" class="w-full py-3.5 rounded-2xl tv-pill-bar border border-zinc-700 text-sm font-bold text-white hover:text-yellow-400 transition flex items-center justify-center gap-2">
            📤 Export Full Library Backup (.json)
          </button>
          <button onclick="document.getElementById('tvtime-import-file').click()" class="w-full py-3.5 rounded-2xl tv-pill-bar border border-zinc-700 text-sm font-bold tv-yellow-text hover:text-white transition flex items-center justify-center gap-2">
            📥 Import Backup File (.json)
          </button>
        </div>
      </div>
    </section>

    <!-- ========================================================= -->
    <!-- FULL PAGE: SETTINGS & GOOGLE ACCOUNT (⚙️)                 -->
    <!-- ========================================================= -->
    <section id="tab-settings" class="hidden space-y-5">
      <div class="flex items-center justify-between gap-2">
        <div class="flex items-center gap-2.5">
          <button
            onclick="app.goBack()"
            class="tv-pill-bar w-9 h-9 rounded-full flex items-center justify-center text-zinc-300 hover:text-white border border-zinc-800"
            title="Back"
          >
            ←
          </button>
          <div>
            <h2 class="text-lg font-extrabold text-white">⚙️ Settings</h2>
            <p class="text-xs text-zinc-400">Profile, cloud sync &amp; library backup</p>
          </div>
        </div>
        <button onclick="app.loadState(true)" class="tv-pill-bar px-3.5 py-2 rounded-full text-xs font-semibold tv-yellow-text flex items-center gap-1 border border-zinc-800">
          ↻ Sync
        </button>
      </div>

      <!-- Top Profile Card (Avatar, Name, Email & Import/Export) -->
      <div class="tv-card rounded-3xl p-4 space-y-3.5">
        <div class="flex items-center gap-3.5 min-w-0">
          <div class="relative cursor-pointer shrink-0" onclick="app.openAccountModal()">
            <div class="w-14 h-14 rounded-full p-0.5 bg-gradient-to-tr from-yellow-400 to-orange-500">
              <div id="profile-avatar-initials" class="w-full h-full rounded-full bg-zinc-900 flex items-center justify-center text-base font-extrabold tv-yellow-text">
                MA
              </div>
            </div>
          </div>
          <div class="min-w-0 flex-1">
            <div class="flex items-center gap-1.5">
              <h2 id="profile-display-name" class="text-base font-extrabold text-white truncate">Madhavsankar</h2>
              <button onclick="app.openAccountModal()" class="text-zinc-400 hover:text-white text-xs shrink-0">✎</button>
            </div>
            <p id="profile-handle" class="text-xs text-zinc-400 truncate">@madhavsankar</p>
          </div>
        </div>

        <div class="h-px bg-zinc-800/80"></div>

        <button onclick="app.switchTab('import')" class="w-full tv-pill-bar px-4 py-2.5 rounded-full text-xs font-bold text-zinc-200 hover:text-white flex items-center justify-center gap-1.5 border border-zinc-800">
          ⇅ Import / Export CSV &amp; JSON
        </button>
      </div>
    </section>

  </main>

  <!-- ========================================================= -->
  <!-- TV TIME LEFT NAVIGATION DRAWER (☰)                        -->
  <!-- ========================================================= -->
  <div id="hamburger-backdrop" onclick="app.closeHamburgerMenu()" class="hidden fixed inset-0 bg-black/75 backdrop-blur-sm z-40"></div>
  <aside
    id="hamburger-drawer"
    class="hidden fixed inset-y-0 left-0 w-72 bg-[#09090b] border-r border-zinc-800/80 z-50 flex flex-col justify-between overflow-y-auto shadow-2xl"
    style="padding-top: env(safe-area-inset-top); padding-bottom: env(safe-area-inset-bottom);"
  >
    <div>
      <!-- Top Brand & Account Header -->
      <div class="px-5 py-4 bg-[#16161a] border-b border-zinc-800/80 flex items-center justify-between gap-3">
        <div class="flex items-center gap-3 min-w-0">
          <div class="w-11 h-11 rounded-2xl tv-yellow-bg flex items-center justify-center shadow-md shrink-0">
            <svg class="w-6 h-6 text-[#09090b]" fill="currentColor" viewBox="0 0 24 24">
              <path d="M4 6a2 2 0 012-2h12a2 2 0 012 2v9a2 2 0 01-2 2h-5v2h2a1 1 0 110 2H9a1 1 0 110-2h2v-2H6a2 2 0 01-2-2V6zm6.5 2.25v4.5l4-2.25-4-2.25z" />
            </svg>
          </div>
          <div class="min-w-0">
            <div class="text-sm font-black tracking-widest uppercase tv-yellow-text">TV TIME</div>
            <div id="drawer-user-email" class="text-xs text-zinc-400 truncate">member</div>
          </div>
        </div>
        <button
          onclick="app.closeHamburgerMenu()"
          class="w-9 h-9 rounded-full tv-pill-bar border border-zinc-800 hover:border-yellow-400/50 text-zinc-400 hover:text-yellow-400 flex items-center justify-center text-sm font-bold shrink-0 transition"
        >
          ✕
        </button>
      </div>

      <!-- Section: HOME -->
      <div class="px-5 py-4 border-b border-zinc-800/80 space-y-1">
        <div class="text-[11px] font-bold uppercase tracking-widest text-zinc-500 pb-2">HOME</div>
        <button
          onclick="app.closeHamburgerMenu(); app.switchTab('home');"
          class="w-full py-2.5 px-2.5 rounded-2xl flex items-center gap-3.5 text-left text-sm font-semibold text-zinc-200 hover:bg-[#1c1c21] hover:text-yellow-400 transition group"
        >
          <span class="w-8 h-8 rounded-xl tv-pill-bar border border-zinc-800/80 flex items-center justify-center tv-yellow-text shrink-0">
            <svg class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="2.2" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" d="M3 10.5L12 3l9 7.5V20a1 1 0 01-1 1h-5.5v-5.5h-5V21H4a1 1 0 01-1-1v-9.5z" />
            </svg>
          </span>
          <span>Home</span>
        </button>
      </div>

      <!-- Section: MY STATISTICS -->
      <div class="px-5 py-4 border-b border-zinc-800/80 space-y-1">
        <div class="text-[11px] font-bold uppercase tracking-widest text-zinc-500 pb-2">MY STATISTICS</div>
        <button
          onclick="app.closeHamburgerMenu(); app.switchTab('recent');"
          class="w-full py-2.5 px-2.5 rounded-2xl flex items-center gap-3.5 text-left text-sm font-semibold text-zinc-200 hover:bg-[#1c1c21] hover:text-yellow-400 transition group"
        >
          <span class="w-8 h-8 rounded-xl tv-pill-bar border border-zinc-800/80 flex items-center justify-center tv-yellow-text shrink-0">
            <svg class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="2.2" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
          </span>
          <span>Recently Watched</span>
        </button>
        <button
          onclick="app.closeHamburgerMenu(); app.switchTab('stats');"
          class="w-full py-2.5 px-2.5 rounded-2xl flex items-center gap-3.5 text-left text-sm font-semibold text-zinc-200 hover:bg-[#1c1c21] hover:text-yellow-400 transition group"
        >
          <span class="w-8 h-8 rounded-xl tv-pill-bar border border-zinc-800/80 flex items-center justify-center tv-yellow-text shrink-0">
            <svg class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="2.2" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" d="M4 19h16M7 15v-4m5 4V7m5 8v-6" />
            </svg>
          </span>
          <span>Statistics</span>
        </button>
      </div>

      <!-- Section: TOOLS & SETTINGS -->
      <div class="px-5 py-4 space-y-1">
        <div class="text-[11px] font-bold uppercase tracking-widest text-zinc-500 pb-2">TOOLS &amp; SETTINGS</div>
        <button
          onclick="app.closeHamburgerMenu(); app.switchTab('import');"
          class="w-full py-2.5 px-2.5 rounded-2xl flex items-center gap-3.5 text-left text-sm font-semibold text-zinc-200 hover:bg-[#1c1c21] hover:text-yellow-400 transition group"
        >
          <span class="w-8 h-8 rounded-xl tv-pill-bar border border-zinc-800/80 flex items-center justify-center tv-yellow-text shrink-0">
            <svg class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="2.2" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" d="M7 16V4m0 0L3 8m4-4l4 4m6 0v12m0 0l4-4m-4 4l-4-4" />
            </svg>
          </span>
          <span>Import / Export CSV</span>
        </button>
        <button
          onclick="app.closeHamburgerMenu(); app.switchTab('settings');"
          class="w-full py-2.5 px-2.5 rounded-2xl flex items-center gap-3.5 text-left text-sm font-semibold text-zinc-200 hover:bg-[#1c1c21] hover:text-yellow-400 transition group"
        >
          <span class="w-8 h-8 rounded-xl tv-pill-bar border border-zinc-800/80 flex items-center justify-center tv-yellow-text shrink-0">
            <svg class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="2.2" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
              <circle cx="12" cy="12" r="3" />
            </svg>
          </span>
          <span>Settings</span>
        </button>
      </div>
    </div>

    <!-- Drawer Footer -->
    <div class="px-5 py-3.5 border-t border-zinc-800/80 text-xs font-semibold text-zinc-500 flex items-center justify-between">
      <span>TV Time</span>
      <span class="text-[10px] tv-yellow-text font-bold">Tracker</span>
    </div>
  </aside>

  <!-- ========================================================= -->
  <!-- BOTTOM NAVIGATION BAR (Home ⌂ | Search 🔍 | Swipe 🔥)     -->
  <!-- ========================================================= -->
  <nav class="fixed bottom-0 left-0 right-0 bg-[#09090b]/95 backdrop-blur-md border-t border-zinc-800/80 z-30">
    <div class="max-w-xl mx-auto grid grid-cols-3 h-16 items-center">
      <button onclick="app.switchTab('home')" id="nav-btn-home" class="flex flex-col items-center justify-center gap-0.5 tv-yellow-text">
        <svg class="w-6 h-6" fill="none" stroke="currentColor" stroke-width="2.2" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" d="M3 10.5L12 3l9 7.5V20a1 1 0 01-1 1h-5.5v-5.5h-5V21H4a1 1 0 01-1-1v-9.5z" />
        </svg>
        <span class="text-[10px] font-bold">Home</span>
      </button>
      <button onclick="app.switchTab('search')" id="nav-btn-search" class="flex flex-col items-center justify-center gap-0.5 text-zinc-400 hover:text-white">
        <svg class="w-6 h-6" fill="none" stroke="currentColor" stroke-width="2.2" viewBox="0 0 24 24">
          <circle cx="11" cy="11" r="7" />
          <path stroke-linecap="round" stroke-linejoin="round" d="M20 20l-4.35-4.35" />
        </svg>
        <span class="text-[10px] font-bold">Search</span>
      </button>
      <button onclick="app.switchTab('swipe')" id="nav-btn-swipe" class="flex flex-col items-center justify-center gap-0.5 text-zinc-400 hover:text-white">
        <svg class="w-6 h-6" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" d="M9.813 15.904L9 18.75l-.813-2.846a4.5 4.5 0 00-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 003.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 003.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 00-3.09 3.09zM18.259 8.715L18 9.75l-.259-1.035a3.375 3.375 0 00-2.455-2.456L14.25 6l1.036-.259a3.375 3.375 0 002.455-2.456L18 2.25l.259 1.035a3.375 3.375 0 002.456 2.456L21.75 6l-1.035.259a3.375 3.375 0 00-2.456 2.456z" />
        </svg>
        <span class="text-[10px] font-bold">For You</span>
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
  <!-- MODAL 3: EXPORT & IMPORT TV TIME LIBRARY                  -->
  <!-- ========================================================= -->
  <div id="import-modal" class="hidden fixed inset-0 bg-black/80 backdrop-blur-sm z-50 flex items-center justify-center p-4">
    <div class="bg-[#18181d] rounded-3xl p-6 w-full max-w-sm border border-zinc-800 space-y-4 text-center">
      <div class="w-12 h-12 rounded-full bg-zinc-800/90 mx-auto flex items-center justify-center tv-yellow-text text-xl font-bold">
        ⇅
      </div>
      <h3 class="text-lg font-extrabold text-white leading-snug">Export &amp; Import TV Time Library</h3>
      <p class="text-xs text-zinc-400 leading-relaxed">
        Export a complete backup of your shows, movies, episode progress, and followed actors as JSON or CSV — or restore a previous TV Time backup file.
      </p>
      <div class="text-left space-y-2.5 text-xs text-zinc-300">
        <div class="flex items-start gap-2.5">
          <span class="w-5 h-5 rounded-full bg-zinc-800 tv-yellow-text font-bold flex items-center justify-center shrink-0">1</span>
          <span>Export JSON (<code>.json</code>) for full lossless backup and cross-device restore.</span>
        </div>
        <div class="flex items-start gap-2.5">
          <span class="w-5 h-5 rounded-full bg-zinc-800 tv-yellow-text font-bold flex items-center justify-center shrink-0">2</span>
          <span>Export CSV (<code>.csv</code>) to open your watch history in Excel or Google Sheets.</span>
        </div>
        <div class="flex items-start gap-2.5">
          <span class="w-5 h-5 rounded-full bg-zinc-800 tv-yellow-text font-bold flex items-center justify-center shrink-0">3</span>
          <span>Import a JSON backup file to restore or merge your shows and movies.</span>
        </div>
      </div>
      <input id="tvtime-import-file" type="file" accept=".json,.csv,.txt" class="hidden" onchange="app.handleImportFile(event)" />
      <div class="space-y-2 pt-2">
        <button onclick="app.exportBackupJson()" class="w-full py-3 rounded-full tv-yellow-bg text-sm font-extrabold">
          📤 Export Library Backup (.json)
        </button>
        <button onclick="app.exportBackupCsv()" class="w-full py-2.5 rounded-full tv-pill-bar text-xs font-semibold text-white hover:text-yellow-400">
          📊 Export Spreadsheet (.csv)
        </button>
        <button onclick="document.getElementById('tvtime-import-file').click()" class="w-full py-2.5 rounded-full tv-pill-bar text-xs font-semibold tv-yellow-text hover:text-white">
          📥 Import Backup File (.json)
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
    const CHECK_SVG = '<svg class="w-5 h-5" fill="none" stroke="currentColor" stroke-width="2.6" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7"/></svg>';
    const EYE_SVG = CHECK_SVG;
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
        this.usPlatforms = ["Netflix", "Prime Video", "Hulu", "Max", "Disney+", "Apple TV+", "Paramount+", "Peacock"];
        this.selectedUsPlatforms = new Set();

        this.searchQuery = "";
        this.searchSort = "top"; // "top" | "newest" | "oldest"
        this.searchResults = [];
        this.searchTimer = null;

        this.detailItem = null;
        this.detailType = "show"; // "show" | "movie"
        this.detailTab = "episodes"; // "info" | "episodes"
        this.openSeasons = new Set();
        this.pendingScrollSeason = null;

        this.swipeMediaFilter = "all"; // "all" | "show" | "movie"
        this.recPool = [];
        this.recPage = 1;
        this.isLoadingRecs = false;
        this.tabHistory = [];
        this._handlingPopState = false;

        this.state = {
          userId: this.userId,
          email: this.userEmail || this.userId,
          shows: [],
          movies: [],
          actors: [],
          decidedIds: [],
          followingUsers: ["partner"],
        };

        this.loadLocalCache();
        this.render();
        this.initAuthAndLoad();
        this.runDefaultDiscover();
        this.loadRecommendations(false);
        this.initPullToRefresh();
        this.initBackNavigation();

        document.addEventListener("visibilitychange", () => {
          if (!document.hidden && this.googleIdToken) {
            this.loadState(false);
          }
        });
      }

      initPullToRefresh() {
        let startY = 0;
        let startX = 0;
        let pullDy = 0;
        let pulling = false;
        let refreshing = false;

        window.addEventListener(
          "touchstart",
          (e) => {
            if (refreshing) return;
            if (window.scrollY > 2) return;
            const target = e.target;
            if (
              target &&
              target.closest &&
              (target.closest("#swipe-active-card") ||
                target.closest("#detail-sheet") ||
                target.closest("#hamburger-drawer") ||
                target.closest("#following-sheet"))
            ) {
              return;
            }
            const t = e.touches && e.touches[0];
            if (!t) return;
            startY = t.clientY;
            startX = t.clientX;
            pullDy = 0;
            pulling = true;
          },
          { passive: true }
        );

        window.addEventListener(
          "touchmove",
          (e) => {
            if (!pulling || refreshing) return;
            if (window.scrollY > 2) {
              pulling = false;
              return;
            }
            const t = e.touches && e.touches[0];
            if (!t) return;
            const dy = t.clientY - startY;
            const dx = t.clientX - startX;
            if (dy <= 12 || Math.abs(dx) > dy) {
              return;
            }
            pullDy = Math.min(110, dy);
            const ind = document.getElementById("pull-refresh-indicator");
            const lbl = document.getElementById("pull-refresh-label");
            const icn = document.getElementById("pull-refresh-icon");
            if (ind) {
              ind.classList.remove("hidden");
              ind.style.height = Math.min(48, Math.round(pullDy * 0.5)) + "px";
            }
            if (lbl) {
              lbl.textContent = pullDy >= 65 ? "Release to refresh" : "Pull down to refresh";
            }
            if (icn) {
              icn.style.transform = "rotate(" + Math.round(pullDy * 3) + "deg)";
            }
          },
          { passive: true }
        );

        const finishPull = async () => {
          if (!pulling || refreshing) return;
          pulling = false;
          const ind = document.getElementById("pull-refresh-indicator");
          const lbl = document.getElementById("pull-refresh-label");
          if (pullDy >= 65) {
            refreshing = true;
            if (ind) ind.style.height = "40px";
            if (lbl) lbl.textContent = "Refreshing...";
            await this.refreshApp();
            refreshing = false;
          }
          pullDy = 0;
          if (ind) {
            ind.style.height = "0px";
            setTimeout(() => ind.classList.add("hidden"), 150);
          }
        };

        window.addEventListener("touchend", finishPull, { passive: true });
        window.addEventListener("touchcancel", finishPull, { passive: true });
      }

      async refreshApp() {
        await this.loadState(true);
        if (this.activeTab === "search") {
          if (this.searchQuery && this.searchQuery.trim()) {
            this.onSearchInput(this.searchQuery);
          } else {
            await this.runDefaultDiscover();
          }
        } else if (this.activeTab === "swipe") {
          await this.loadRecommendations(false);
        }
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
            if (!Array.isArray(data.decidedIds)) data.decidedIds = [];
            this.state = data;
            if (data.email) {
              this.userEmail = String(data.email).trim().toLowerCase();
              this.userId = String(data.userId || this.userEmail.split("@")[0]);
              localStorage.setItem("tvtime_user_email", this.userEmail);
              localStorage.setItem("tvtime_user_id", this.userId);
            }
            this.saveLocalCache();
            this.render();
            if (this.recPool.length === 0) {
              this.loadRecommendations(false);
            }
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
              decidedIds: Array.isArray(this.state.decidedIds) ? this.state.decidedIds : [],
              followingUsers: this.state.followingUsers,
            }),
          });
        } catch (_) {}
      }

      initBackNavigation() {
        try {
          window.history.replaceState({ tab: "home", segment: "shows" }, "");
        } catch (_) {}

        window.addEventListener("popstate", () => {
          this._handlingPopState = true;
          try {
            const detailOpen = !document.getElementById("detail-sheet")?.classList.contains("hidden");
            const drawerOpen = !document.getElementById("hamburger-drawer")?.classList.contains("hidden");
            const importOpen = !document.getElementById("import-modal")?.classList.contains("hidden");
            const accountOpen = !document.getElementById("account-modal")?.classList.contains("hidden");
            const followingOpen = !document.getElementById("following-sheet")?.classList.contains("hidden");

            if (importOpen) {
              this.closeImportModal();
            } else if (accountOpen) {
              this.closeAccountModal();
            } else if (followingOpen) {
              this.closeFollowingSheet();
            } else if (detailOpen) {
              this.closeDetailSheet();
            } else if (drawerOpen) {
              this.closeHamburgerMenu();
            } else if (this.tabHistory.length > 0) {
              const prevTab = this.tabHistory.pop();
              this.switchTab(prevTab, true);
            } else if (this.activeTab !== "home") {
              this.switchTab("home", true);
            } else if (this.homeSegment !== "shows") {
              this.setHomeSegment("shows");
            }
          } finally {
            this._handlingPopState = false;
          }
        });
      }

      pushHistoryEntry(tag) {
        if (this._handlingPopState) return;
        try {
          window.history.pushState({ tag: tag || "nav", tab: this.activeTab, segment: this.homeSegment }, "");
        } catch (_) {}
      }

      goBack() {
        if (this.tabHistory.length > 0) {
          const prevTab = this.tabHistory.pop();
          this.switchTab(prevTab, true);
        } else {
          this.switchTab("home", true);
        }
      }

      openHamburgerMenu() {
        this.renderProfile();
        this.pushHistoryEntry("drawer");
        document.getElementById("hamburger-backdrop")?.classList.remove("hidden");
        document.getElementById("hamburger-drawer")?.classList.remove("hidden");
      }

      closeHamburgerMenu() {
        document.getElementById("hamburger-backdrop")?.classList.add("hidden");
        document.getElementById("hamburger-drawer")?.classList.add("hidden");
      }

      switchTab(tab, fromBack = false) {
        const resolvedTab = tab === "profile" ? "stats" : tab;
        if (!fromBack && resolvedTab !== this.activeTab) {
          this.tabHistory.push(this.activeTab);
          if (this.tabHistory.length > 12) this.tabHistory.shift();
          this.pushHistoryEntry("tab_" + resolvedTab);
        }
        this.activeTab = resolvedTab;
        ["home", "search", "swipe", "stats", "recent", "import", "settings"].forEach((t) => {
          const sec = document.getElementById("tab-" + t);
          if (sec) sec.classList.toggle("hidden", t !== resolvedTab);
        });
        ["home", "search", "swipe"].forEach((t) => {
          const btn = document.getElementById("nav-btn-" + t);
          if (btn) {
            btn.className =
              t === resolvedTab
                ? "flex flex-col items-center justify-center gap-0.5 tv-yellow-text"
                : "flex flex-col items-center justify-center gap-0.5 text-zinc-400 hover:text-white";
          }
        });
        if (resolvedTab === "swipe" && this.getFilteredRecommendations().length === 0) {
          this.loadRecommendations(false);
        }
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
        const platBar = document.getElementById("home-us-platform-bar");
        if (mediaSub) mediaSub.classList.toggle("hidden", seg === "upcoming");
        if (platBar) platBar.classList.toggle("hidden", seg === "upcoming");
        if (upSub) upSub.classList.toggle("hidden", seg !== "upcoming");

        const statusSelect = document.getElementById("home-status-select");
        if (seg === "movies") {
          if (this.mediaFilter !== "completed") {
            this.mediaFilter = "interested";
          }
          if (statusSelect) {
            statusSelect.innerHTML =
              '<option value="interested">Watchlist</option>' +
              '<option value="completed">Watched</option>';
            statusSelect.value = this.mediaFilter;
          }
        } else if (seg === "shows") {
          if (!["in_progress", "interested", "completed", "stopped"].includes(this.mediaFilter)) {
            this.mediaFilter = "in_progress";
          }
          if (statusSelect) {
            statusSelect.innerHTML =
              '<option value="in_progress">In progress</option>' +
              '<option value="interested">Watchlist</option>' +
              '<option value="completed">Completed</option>' +
              '<option value="stopped">Stopped</option>';
            statusSelect.value = this.mediaFilter;
          }
        } else if (seg === "upcoming") {
          this.refreshUpcomingEpisodesIfNeeded();
        }
        this.setMediaFilter(this.mediaFilter);
      }

      setMediaFilter(filter) {
        this.mediaFilter = filter;
        const statusSelect = document.getElementById("home-status-select");
        if (statusSelect && statusSelect.value !== filter) {
          statusSelect.value = filter;
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

      getEffectiveShowStatus(show) {
        const prog = this.computeShowProgress(show);
        const raw = String(show.userStatus || "").toLowerCase();
        if (prog.isCompleted) return "completed";
        if (prog.hasStarted) return raw === "stopped" ? "stopped" : "in_progress";
        return raw === "stopped" ? "stopped" : "interested";
      }

      matchesUsPlatforms(rawText) {
        if (!this.selectedUsPlatforms || this.selectedUsPlatforms.size === 0) return true;
        const s = String(rawText || "").toLowerCase();
        for (const plat of this.selectedUsPlatforms) {
          const p = plat.toLowerCase();
          if (p === "netflix" && s.includes("netflix")) return true;
          if (p === "prime video" && (s.includes("prime") || s.includes("amazon"))) return true;
          if (p === "hulu" && (s.includes("hulu") || s.includes("fx"))) return true;
          if (p === "max" && (s.includes("max") || s.includes("hbo"))) return true;
          if (p === "disney+" && s.includes("disney")) return true;
          if (p === "apple tv+" && s.includes("apple")) return true;
          if (p === "paramount+" && (s.includes("paramount") || s.includes("cbs") || s.includes("showtime"))) return true;
          if (p === "peacock" && (s.includes("peacock") || s.includes("nbc"))) return true;
        }
        return false;
      }

      toggleUsPlatform(platform) {
        if (!platform || platform === "clear" || platform === "all") {
          this.selectedUsPlatforms.clear();
        } else if (this.selectedUsPlatforms.has(platform)) {
          this.selectedUsPlatforms.delete(platform);
        } else {
          this.selectedUsPlatforms.add(platform);
        }
        this.renderPlatformBars();
        this.renderHome();
        if (!this.searchQuery.trim()) {
          this.runDefaultDiscover();
        } else {
          this.renderSearch();
        }
      }

      renderPlatformBars() {
        const selectedArr = Array.from(this.selectedUsPlatforms);
        const hasFilter = selectedArr.length > 0;
        const summaryLabel = !hasFilter
          ? "Streaming"
          : selectedArr.length === 1
          ? selectedArr[0]
          : "Streaming (" + selectedArr.length + ")";
        const clearBtn = hasFilter
          ? '<button onclick="app.toggleUsPlatform(&quot;clear&quot;)" title="Remove streaming filter" class="w-7 h-7 rounded-full text-xs font-extrabold bg-rose-500/20 text-rose-300 border border-rose-500/40 hover:bg-rose-500/30 flex items-center justify-center shrink-0 transition">✕</button>'
          : "";
        const itemsHtml = this.usPlatforms
          .map((p) => {
            const active = this.selectedUsPlatforms.has(p);
            return (
              '<button type="button" onclick="app.toggleUsPlatform(&quot;' +
              escapeHtml(p) +
              '&quot;)" class="w-full px-3 py-1.5 rounded-xl text-left text-xs font-bold flex items-center justify-between transition ' +
              (active
                ? "tv-yellow-bg"
                : "text-zinc-300 hover:bg-zinc-800 hover:text-white") +
              '">' +
              "<span>" +
              escapeHtml(p) +
              "</span>" +
              "<span>" +
              (active ? "✓" : "") +
              "</span>" +
              "</button>"
            );
          })
          .join("");

        const buildDropdown = (alignRight) =>
          '<details class="relative group">' +
          '<summary class="list-none tv-pill-bar px-3.5 py-2 rounded-full text-xs font-bold cursor-pointer flex items-center gap-1.5 border ' +
          (hasFilter ? "border-yellow-400 tv-yellow-text" : "border-zinc-800 text-zinc-200") +
          '">' +
          '<span class="max-w-[140px] truncate">' +
          escapeHtml(summaryLabel) +
          "</span>" +
          '<span class="text-[10px] text-zinc-400">▾</span>' +
          "</summary>" +
          '<div class="absolute ' +
          (alignRight ? "right-0" : "left-0") +
          ' mt-1.5 w-48 bg-[#18181d] border border-zinc-700 rounded-2xl p-2 shadow-2xl z-30 space-y-1">' +
          (hasFilter
            ? '<button type="button" onclick="app.toggleUsPlatform(&quot;clear&quot;)" class="w-full px-3 py-1.5 rounded-xl text-left text-xs font-extrabold text-rose-400 hover:bg-zinc-800">✕ Remove filter</button><div class="h-px bg-zinc-800 my-1"></div>'
            : "") +
          itemsHtml +
          "</div>" +
          "</details>" +
          clearBtn;

        const homeBar = document.getElementById("home-us-platform-bar");
        const searchBar = document.getElementById("search-us-platform-bar");
        if (homeBar) homeBar.innerHTML = buildDropdown(true);
        if (searchBar) searchBar.innerHTML = buildDropdown(false);
      }

      render() {
        this.renderPlatformBars();
        this.renderHome();
        this.renderSearch();
        this.renderProfile();
        this.renderSwipeDeck();
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
        const effStatus = this.getEffectiveShowStatus(show);
        const poster =
          show.posterUrl ||
          "https://via.placeholder.com/200x300/1c1c22/facc15?text=" + encodeURIComponent(show.title || "Show");
        const nextCodeFormatted = prog.nextEp
          ? prog.nextEp.code.replace(/^S([0-9]+)E([0-9]+)$/, "S$1·E$2")
          : "Caught up";

        let statusBadge = "Watchlist";
        let statusClass = "text-sky-400";
        if (effStatus === "completed") {
          statusBadge = "Watched";
          statusClass = "text-emerald-400";
        } else if (effStatus === "in_progress") {
          statusBadge = "In Progress";
          statusClass = "tv-yellow-text";
        } else if (effStatus === "stopped") {
          statusBadge = "Stopped";
          statusClass = "text-rose-400";
        }

        let statusLineHtml = "";
        let actionBtnHtml = "";
        const netLabel = show.network ? " · " + escapeHtml(show.network) : "";
        if (effStatus === "completed") {
          statusLineHtml =
            '<div class="text-[11px] font-bold ' +
            statusClass +
            '">TV Show' +
            netLabel +
            " · " +
            statusBadge +
            "</div>" +
            '<div class="text-xs text-zinc-400">Completed · <span class="font-bold tv-yellow-text">All ' +
            prog.total +
            " episodes watched</span></div>";
          actionBtnHtml =
            '<button title="Toggle full series watched / unwatched" onclick="event.stopPropagation(); app.toggleShowWatched(&quot;' +
            escapeHtml(show.id) +
            '&quot;)" class="tv-check-btn">' +
            CHECK_SVG +
            "</button>";
        } else if (effStatus === "stopped") {
          statusLineHtml =
            '<div class="text-[11px] font-bold ' +
            statusClass +
            '">TV Show' +
            netLabel +
            " · " +
            statusBadge +
            "</div>" +
            '<div class="text-xs text-zinc-400">Stopped watching (' +
            prog.watchedCount +
            "/" +
            prog.total +
            " watched)</div>";
          actionBtnHtml =
            '<button title="Mark full series watched" onclick="event.stopPropagation(); app.toggleShowWatched(&quot;' +
            escapeHtml(show.id) +
            '&quot;)" class="tv-eye-btn">' +
            EYE_SVG +
            "</button>";
        } else {
          statusLineHtml =
            '<div class="text-[11px] font-bold ' +
            statusClass +
            '">TV Show' +
            netLabel +
            " · " +
            statusBadge +
            "</div>" +
            '<div class="w-28 h-1.5 bg-zinc-800 rounded-full overflow-hidden my-1">' +
            '<div class="h-full bg-emerald-500 rounded-full" style="width: ' +
            (prog.watchedCount > 0 ? Math.max(8, prog.pct) : 0) +
            '%"></div></div>' +
            '<div class="text-xs text-zinc-400">' +
            prog.leftCount +
            " episode" +
            (prog.leftCount === 1 ? "" : "s") +
            ' left &nbsp;<span class="font-extrabold tv-yellow-text">' +
            escapeHtml(nextCodeFormatted) +
            "</span></div>";
          actionBtnHtml =
            '<button title="Mark full series watched" onclick="event.stopPropagation(); app.toggleShowWatched(&quot;' +
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
          '<div class="flex items-center gap-2 min-w-0">' +
          '<h3 class="text-base font-extrabold text-white truncate min-w-0 flex-1">' +
          escapeHtml(show.title) +
          "</h3>" +
          '<span class="px-2 py-0.5 rounded-md bg-zinc-800 text-[11px] font-semibold text-zinc-300 shrink-0">' +
          escapeHtml(show.year || 2024) +
          "</span>" +
          "</div>" +
          statusLineHtml +
          '<div class="flex items-center gap-2 text-xs font-bold text-zinc-200 pt-0.5 flex-wrap">' +
          '<span class="tv-yellow-text">★</span><span>' +
          Number(show.rating || 8.0).toFixed(1) +
          "</span>" +
          '<span class="px-2 py-0.5 rounded-full bg-zinc-800 text-[10px] font-bold tv-yellow-text truncate">📋 Episodes (' +
          prog.watchedCount +
          "/" +
          prog.total +
          ")</span>" +
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
          '<div class="flex items-center gap-2 min-w-0">' +
          '<h3 class="text-base font-extrabold text-white truncate min-w-0 flex-1">' +
          escapeHtml(movie.title) +
          "</h3>" +
          '<span class="px-2 py-0.5 rounded-md bg-zinc-800 text-[11px] font-semibold text-zinc-300 shrink-0">' +
          escapeHtml(movie.year || 2024) +
          "</span>" +
          "</div>" +
          '<div class="text-xs font-bold truncate ' +
          (movie.watched ? "text-emerald-400" : "tv-yellow-text") +
          '">Movie' +
          (movie.streamingService ? " · " + escapeHtml(movie.streamingService) : "") +
          " · " +
          (Number(movie.runtimeMins) || 120) +
          "m · " +
          (movie.watched ? "Watched" : "Watchlist") +
          "</div>" +
          '<div class="text-xs text-zinc-400 truncate">' +
          escapeHtml(castLine) +
          "</div>" +
          '<div class="flex items-center gap-1 text-xs font-bold text-zinc-200 pt-0.5">' +
          '<span class="tv-yellow-text">★</span><span>' +
          Number(movie.rating || 8.0).toFixed(1) +
          "</span>" +
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
          const filteredShows = shows.filter((s) => this.matchesUsPlatforms(s.network));
          const startedInProgress = [];
          const notStarted = [];
          const completed = [];
          const stopped = [];
          for (const s of filteredShows) {
            const eff = this.getEffectiveShowStatus(s);
            if (eff === "stopped") stopped.push(s);
            else if (eff === "completed") completed.push(s);
            else if (eff === "in_progress") startedInProgress.push(s);
            else notStarted.push(s);
          }

          if (this.mediaFilter === "completed") {
            listEl.innerHTML =
              completed.map((s) => this.renderShowCard(s)).join("") ||
              '<div class="tv-card rounded-2xl p-8 text-center text-xs text-zinc-400">No completed shows matching filter.</div>';
          } else if (this.mediaFilter === "interested") {
            listEl.innerHTML =
              notStarted.map((s) => this.renderShowCard(s)).join("") ||
              '<div class="tv-card rounded-2xl p-8 text-center text-xs text-zinc-400">No shows in your Watchlist matching filter.</div>';
          } else if (this.mediaFilter === "stopped") {
            listEl.innerHTML =
              stopped.map((s) => this.renderShowCard(s)).join("") ||
              '<div class="tv-card rounded-2xl p-8 text-center text-xs text-zinc-400">No stopped shows matching filter.</div>';
          } else if (this.mediaFilter === "all") {
            listEl.innerHTML =
              filteredShows.map((s) => this.renderShowCard(s)).join("") ||
              '<div class="tv-card rounded-2xl p-8 text-center text-xs text-zinc-400">Your Watch List is empty. Tap 🔍 below to add shows.</div>';
          } else {
            listEl.innerHTML =
              startedInProgress.map((s) => this.renderShowCard(s)).join("") ||
              '<div class="tv-card rounded-2xl p-8 text-center text-xs text-zinc-400">No shows in progress matching filter. Tap 🔍 to search and add series!</div>';
          }
          return;
        }

        if (this.homeSegment === "movies") {
          const platformMovies = movies.filter((m) => this.matchesUsPlatforms(m.streamingService));
          const filtered =
            this.mediaFilter === "completed"
              ? platformMovies.filter((m) => m.watched)
              : platformMovies.filter((m) => !m.watched);
          listEl.innerHTML =
            filtered.map((m) => this.renderMovieCard(m)).join("") ||
            '<div class="tv-card rounded-2xl p-8 text-center text-xs text-zinc-400">No movies in this view yet.</div>';
          return;
        }

        // Upcoming Segment
        this.renderUpcomingList(listEl);
      }

      formatRelativeCountdownBucket(daysUntil) {
        if (daysUntil <= 0) return "Today";
        if (daysUntil === 1) return "Tomorrow";
        if (daysUntil < 7) return "In " + daysUntil + " days";
        if (daysUntil < 14) return "Next week";
        if (daysUntil < 30) return "In " + Math.floor(daysUntil / 7) + " weeks";
        if (daysUntil < 60) return "Next month";
        return "In " + Math.max(2, Math.floor(daysUntil / 30)) + " months";
      }

      findNextUpcomingEpisode(show, todayIso) {
        const refIso = todayIso || new Date().toISOString().slice(0, 10);
        const refParts = String(refIso).split("-").map(Number);
        if (refParts.length !== 3 || refParts.some((n) => Number.isNaN(n))) return null;
        const refUtcMs = Date.UTC(refParts[0], refParts[1] - 1, refParts[2]);
        const episodes = Array.isArray(show?.episodes) ? show.episodes : [];
        let best = null;
        for (const ep of episodes) {
          const rawDate = String(ep?.airDate || "").trim();
          if (!/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(rawDate)) continue;
          if (rawDate < refIso) continue;
          const parts = rawDate.split("-").map(Number);
          const epUtcMs = Date.UTC(parts[0], parts[1] - 1, parts[2]);
          const daysUntil = Math.round((epUtcMs - refUtcMs) / 86400000);
          if (daysUntil < 0) continue;
          if (
            !best ||
            daysUntil < best.daysUntil ||
            (daysUntil === best.daysUntil &&
              (Number(ep.season) < Number(best.ep.season) ||
                (Number(ep.season) === Number(best.ep.season) && Number(ep.number) < Number(best.ep.number))))
          ) {
            best = {
              ep,
              daysUntil,
              bucket: this.formatRelativeCountdownBucket(daysUntil),
            };
          }
        }
        return best;
      }

      async refreshUpcomingEpisodesIfNeeded() {
        if (this._refreshingUpcoming) return;
        this._refreshingUpcoming = true;
        try {
          const shows = Array.isArray(this.state.shows) ? this.state.shows : [];
          let updatedAny = false;
          for (const s of shows) {
            if (s.userStatus === "stopped") continue;
            const isEnded = String(s.status || "").toLowerCase() === "ended";
            const eps = Array.isArray(s.episodes) ? s.episodes : [];
            if (isEnded && eps.length > 1) continue;
            const lookupKey = s.tvmazeId || s.id;
            if (!lookupKey) continue;
            try {
              const res = await fetch("/api/show-details?tvmazeId=" + encodeURIComponent(lookupKey));
              if (res.ok) {
                const full = await res.json();
                if (full && Array.isArray(full.episodes) && full.episodes.length > eps.length) {
                  s.episodes = full.episodes;
                  if (full.status) s.status = full.status;
                  updatedAny = true;
                }
              }
            } catch (_) {}
          }
          if (updatedAny) {
            await this.persistState();
          }
        } finally {
          this._refreshingUpcoming = false;
        }
      }

      renderUpcomingList(listEl) {
        const shows = Array.isArray(this.state.shows) ? this.state.shows : [];
        const todayIso = new Date().toISOString().slice(0, 10);
        const upcomingItems = [];

        for (const s of shows) {
          if (s.userStatus === "stopped") continue;
          const nextUp = this.findNextUpcomingEpisode(s, todayIso);
          if (!nextUp) continue;
          upcomingItems.push({
            show: s,
            ep: nextUp.ep,
            daysUntil: nextUp.daysUntil,
            dateBadge: nextUp.ep.airDate || "Upcoming",
            bucket: nextUp.bucket,
          });
        }

        upcomingItems.sort((a, b) => a.daysUntil - b.daysUntil || String(a.show.title || "").localeCompare(String(b.show.title || "")));

        if (upcomingItems.length === 0) {
          listEl.innerHTML =
            '<div class="tv-card rounded-2xl p-8 text-center space-y-1.5">' +
            '<div class="text-sm font-bold text-white">No upcoming episodes scheduled</div>' +
            '<div class="text-xs text-zinc-400">None of your tracked shows have future air dates announced yet.</div>' +
            "</div>";
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
          const codeFormatted = String(item.ep.code || "").replace(/^S([0-9]+)E([0-9]+)$/, "S$1·E$2");
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
            '<div class="text-xs font-bold tv-yellow-text">TV Show · ' +
            escapeHtml(item.show.network || "Streaming") +
            "</div>" +
            '<div class="text-xs font-semibold text-zinc-300">' +
            escapeHtml(codeFormatted) +
            " — " +
            escapeHtml(item.ep.title || "Next Episode") +
            "</div>" +
            "</div>" +
            "</div>";
        }
        listEl.innerHTML = html;
      }

      async toggleShowWatched(showId) {
        const show = (this.state.shows || []).find((s) => s.id === showId);
        if (!show) return;
        const eff = this.getEffectiveShowStatus(show);
        if (eff === "completed") {
          await this.setShowStatus(showId, "interested");
        } else {
          const lookupKey = show.tvmazeId || show.id;
          if (lookupKey && (!show.episodes || show.episodes.length <= 1)) {
            try {
              const res = await fetch("/api/show-details?tvmazeId=" + encodeURIComponent(lookupKey));
              if (res.ok) {
                const full = await res.json();
                if (full && Array.isArray(full.episodes) && full.episodes.length > 0) {
                  show.episodes = full.episodes;
                  if (full.cast?.length) show.cast = full.cast;
                }
              }
            } catch (_) {}
          }
          await this.setShowStatus(showId, "completed");
        }
      }

      async markNextEpisodeWatched(showId) {
        const show = (this.state.shows || []).find((s) => s.id === showId);
        if (!show) return;
        const prog = this.computeShowProgress(show);
        if (!prog.nextEp) return;
        const set = new Set(show.watchedEpisodes || []);
        set.add(prog.nextEp.code);
        show.watchedEpisodes = Array.from(set).sort();
        const totalEps = (show.episodes || []).length || 1;
        show.userStatus = set.size >= totalEps ? "completed" : "in_progress";
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

      async setMovieStatus(movieId, isWatched) {
        const movie = (this.state.movies || []).find((m) => m.id === movieId);
        if (!movie) return;
        if (!isWatched && !movie.watched) {
          await this.removeItemFromLibrary(movieId);
          return;
        }
        movie.watched = Boolean(isWatched);
        movie.watchedAt = movie.watched ? new Date().toISOString().slice(0, 10) : "";
        await this.persistState();
      }

      async setShowStatus(showId, status) {
        const show = (this.state.shows || []).find((s) => s.id === showId);
        if (!show) return;
        const eps = Array.isArray(show.episodes) ? show.episodes : [];
        const eff = this.getEffectiveShowStatus(show);
        if (status === "completed") {
          if (eff === "completed") {
            show.watchedEpisodes = [];
            show.userStatus = "interested";
          } else {
            show.watchedEpisodes = eps.map((e) => e.code).sort();
            show.userStatus = "completed";
          }
          this.openSeasons = new Set();
        } else if (status === "stopped") {
          if (eff === "completed") return;
          if (eff === "stopped") {
            show.userStatus = (show.watchedEpisodes || []).length > 0 ? "in_progress" : "interested";
          } else {
            show.userStatus = "stopped";
          }
        } else if (status === "interested") {
          if ((show.watchedEpisodes || []).length > 0) return;
          show.watchedEpisodes = [];
          show.userStatus = "interested";
        }
        show.lastWatchedAt = new Date().toISOString();
        await this.persistState();
      }

      // =========================================================
      // SEARCH / DISCOVER LOGIC
      // =========================================================
      async runDefaultDiscover() {
        try {
          const provCsv = Array.from(this.selectedUsPlatforms).join(",");
          const url = provCsv
            ? "/api/discover-providers?providers=" + encodeURIComponent(provCsv)
            : "/api/search?q=" + encodeURIComponent("Game of Thrones");
          const res = await fetch(url);
          if (res.ok) {
            const data = await res.json();
            this.searchResults = (data.results || []).filter((r) => r.mediaType !== "actor");
            this.renderSearch();
          }
        } catch (_) {}
      }

      onSearchInput(val) {
        this.searchQuery = String(val || "");
        if (this.searchTimer) clearTimeout(this.searchTimer);
        this.searchTimer = setTimeout(async () => {
          const q = this.searchQuery.trim();
          if (!q) {
            await this.runDefaultDiscover();
            return;
          }
          try {
            const res = await fetch("/api/search?q=" + encodeURIComponent(q));
            if (res.ok) {
              const data = await res.json();
              this.searchResults = (data.results || []).filter((r) => r.mediaType !== "actor");
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
        const sortSel = document.getElementById("search-sort-select");
        if (sortSel && sortSel.value !== sortKey) {
          sortSel.value = sortKey;
        }
        this.renderSearch();
      }

      renderSearch() {
        const listEl = document.getElementById("search-results-list");
        const countEl = document.getElementById("search-count-label");
        if (!listEl) return;

        const items = [...(this.searchResults || [])].filter((r) => {
          if (r.mediaType === "actor") return false;
          if (this.searchQuery.trim() && this.selectedUsPlatforms.size > 0) {
            return this.matchesUsPlatforms(r.network || r.streamingService || r.director);
          }
          return true;
        });
        if (this.searchSort === "newest") {
          items.sort((a, b) => (Number(b.year) || 0) - (Number(a.year) || 0));
        } else if (this.searchSort === "oldest") {
          items.sort((a, b) => (Number(a.year) || 0) - (Number(b.year) || 0));
        }

        if (countEl) {
          countEl.textContent = items.length + " results";
        }

        const showById = new Map((this.state.shows || []).map((s) => [s.id, s]));
        const movieById = new Map((this.state.movies || []).map((m) => [m.id, m]));

        listEl.innerHTML =
          items
            .map((item) => {
              const existingShow = showById.get(item.id);
              const existingMovie = movieById.get(item.id);
              const showStatus = existingShow ? this.getEffectiveShowStatus(existingShow) : "";
              const isInWatchlist =
                (existingShow && showStatus === "interested") ||
                (existingMovie && !existingMovie.watched);
              const isCompleted =
                (existingShow && showStatus === "completed") ||
                (existingMovie && Boolean(existingMovie.watched));

              const badgeClass = item.mediaType === "movie" ? "text-sky-400" : "tv-yellow-text";
              const platInfo = item.network || item.streamingService || "";
              const badgeLabel =
                (item.mediaType === "movie" ? "Movie" : "TV Show") +
                (platInfo ? " · " + platInfo : "");
              const subtitle =
                item.overview || item.director || item.network || "Tap + for Watchlist or ✓ to mark Completed";
              const poster =
                item.posterUrl ||
                "https://via.placeholder.com/200x300/1c1c22/facc15?text=" + encodeURIComponent(item.title || "Item");

              const watchlistBtnClass = isInWatchlist
                ? "w-10 h-10 rounded-full tv-yellow-bg flex items-center justify-center shrink-0 transition active:scale-95"
                : "w-10 h-10 rounded-full bg-[#232329] border border-zinc-700 tv-yellow-text flex items-center justify-center shrink-0 transition hover:border-yellow-400/60 active:scale-95";
              const completedBtnClass = isCompleted
                ? "w-10 h-10 rounded-full bg-emerald-500 border border-emerald-500 text-white flex items-center justify-center shrink-0 transition active:scale-95"
                : "w-10 h-10 rounded-full bg-[#232329] border border-zinc-700 text-zinc-400 flex items-center justify-center shrink-0 transition hover:border-emerald-500/60 hover:text-white active:scale-95";

              const rightBtns =
                '<div class="flex items-center gap-2 shrink-0">' +
                '<button title="' +
                (isInWatchlist ? "In Watchlist (Tap to remove)" : "Add to Watchlist") +
                '" onclick="event.stopPropagation(); app.quickToggleSearchWatchlist(&quot;' +
                escapeHtml(item.id) +
                '&quot;)" class="' +
                watchlistBtnClass +
                '">' +
                PLUS_SVG +
                "</button>" +
                '<button title="' +
                (isCompleted ? "Completed (Tap to move to Watchlist)" : "Mark as Completed") +
                '" onclick="event.stopPropagation(); app.quickMarkSearchCompleted(&quot;' +
                escapeHtml(item.id) +
                '&quot;)" class="' +
                completedBtnClass +
                '">' +
                CHECK_SVG +
                "</button>" +
                "</div>";

              return (
                '<div onclick="app.addOrOpenSearchResult(&quot;' +
                escapeHtml(item.id) +
                '&quot;)" class="tv-card rounded-2xl p-3 flex items-center gap-3.5 cursor-pointer hover:border-zinc-700 transition">' +
                '<img src="' +
                escapeHtml(poster) +
                '" class="w-16 h-24 object-cover rounded-xl shrink-0 bg-zinc-900" />' +
                '<div class="flex-1 min-w-0 space-y-0.5">' +
                '<div class="flex items-center gap-2 min-w-0">' +
                '<h3 class="text-base font-extrabold text-white truncate min-w-0 flex-1">' +
                escapeHtml(item.title) +
                "</h3>" +
                '<span class="px-2 py-0.5 rounded-md bg-zinc-800 text-[11px] font-semibold text-zinc-300 shrink-0">' +
                escapeHtml(item.year || 2024) +
                "</span>" +
                "</div>" +
                '<div class="text-xs font-bold truncate ' +
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
                rightBtns +
                "</div>"
              );
            })
            .join("") ||
          '<div class="tv-card rounded-2xl p-8 text-center text-xs text-zinc-400">No results found. Try searching another title!</div>';
      }

      async quickToggleSearchWatchlist(itemId) {
        const existingShow = (this.state.shows || []).find((s) => s.id === itemId);
        if (existingShow) {
          const eff = this.getEffectiveShowStatus(existingShow);
          if (eff === "interested") {
            await this.removeLibraryItem("show", itemId);
          } else {
            await this.setShowStatus(itemId, "interested");
          }
          return;
        }
        const existingMovie = (this.state.movies || []).find((m) => m.id === itemId);
        if (existingMovie) {
          if (!existingMovie.watched) {
            await this.removeLibraryItem("movie", itemId);
          } else {
            await this.setMovieStatus(itemId, false);
          }
          return;
        }
        await this.addSearchResult(itemId);
      }

      async quickMarkSearchCompleted(itemId) {
        const existingShow = (this.state.shows || []).find((s) => s.id === itemId);
        if (existingShow) {
          const eff = this.getEffectiveShowStatus(existingShow);
          if (eff === "completed") {
            await this.setShowStatus(itemId, "interested");
          } else {
            await this.setShowStatus(itemId, "completed");
          }
          return;
        }
        const existingMovie = (this.state.movies || []).find((m) => m.id === itemId);
        if (existingMovie) {
          await this.setMovieStatus(itemId, !existingMovie.watched);
          return;
        }
        await this.addSearchResult(itemId);
        const addedShow = (this.state.shows || []).find((s) => s.id === itemId);
        if (addedShow) {
          await this.setShowStatus(itemId, "completed");
          return;
        }
        const addedMovie = (this.state.movies || []).find((m) => m.id === itemId);
        if (addedMovie) {
          await this.setMovieStatus(itemId, true);
        }
      }

      async addSearchResult(itemId) {
        const item = (this.searchResults || []).find((r) => r.id === itemId);
        if (!item) return;

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

        // TV Show -> fetch full episode guide from TMDB or TVMaze
        if (!(this.state.shows || []).some((s) => s.id === item.id)) {
          let fullShow = null;
          const lookupKey = item.tvmazeId || item.id;
          if (lookupKey) {
            try {
              const res = await fetch("/api/show-details?tvmazeId=" + encodeURIComponent(lookupKey));
              if (res.ok) fullShow = await res.json();
            } catch (_) {}
          }
          const newShow = {
            ...(fullShow || item),
            userStatus: "interested",
            userRating: 0,
            reaction: "",
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
          const applyInitialSeasons = (s) => {
            const eff = this.getEffectiveShowStatus(s);
            const prog = this.computeShowProgress(s);
            if (eff === "in_progress" && prog.nextEp) {
              const targetSeason = Number(prog.nextEp.season) || 1;
              this.openSeasons = new Set([targetSeason]);
              this.pendingScrollSeason = targetSeason;
            } else {
              this.openSeasons = new Set();
              this.pendingScrollSeason = null;
            }
          };
          applyInitialSeasons(show);
          // Lazily enrich episodes from TMDB or TVMaze if only stub episodes exist
          const lookupKey = show.tvmazeId || show.id;
          if (lookupKey && (!show.episodes || show.episodes.length <= 1)) {
            fetch("/api/show-details?tvmazeId=" + encodeURIComponent(lookupKey))
              .then((r) => (r.ok ? r.json() : null))
              .then((full) => {
                if (full && Array.isArray(full.episodes) && full.episodes.length > 0) {
                  show.episodes = full.episodes;
                  if (show.userStatus === "completed") {
                    show.watchedEpisodes = full.episodes.map((e) => e.code).sort();
                  }
                  if (full.cast?.length) show.cast = full.cast;
                  show.userStatus = this.getEffectiveShowStatus(show);
                  applyInitialSeasons(show);
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
        this.pushHistoryEntry("detail");
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
        show.userStatus = this.getEffectiveShowStatus(show);
        show.lastWatchedAt = new Date().toISOString();
        await this.persistState();
      }

      async toggleSeasonWatched(showId, seasonNum) {
        const show = (this.state.shows || []).find((s) => s.id === showId);
        if (!show) return;
        const seasonEps = (show.episodes || []).filter((e) => Number(e.season) === Number(seasonNum));
        const set = new Set(show.watchedEpisodes || []);
        const allWatched = seasonEps.length > 0 && seasonEps.every((e) => set.has(e.code));
        for (const e of seasonEps) {
          if (allWatched) set.delete(e.code);
          else set.add(e.code);
        }
        show.watchedEpisodes = Array.from(set).sort();
        show.userStatus = this.getEffectiveShowStatus(show);
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

      async removeItemFromLibrary(id) {
        this.state.shows = (this.state.shows || []).filter((s) => s.id !== id);
        this.state.movies = (this.state.movies || []).filter((m) => m.id !== id);
        this.closeDetailSheet();
        await this.persistState();
      }

      renderShowStatusHeaderHtml(show) {
        const eff = this.getEffectiveShowStatus(show);
        const prog = this.computeShowProgress(show);
        const isCompleted = eff === "completed";
        const isStopped = eff === "stopped";
        const canStop = !isCompleted;
        const canSetWatchlist = prog.watchedCount === 0;
        const isInWatchlist = eff === "interested" && prog.watchedCount === 0;

        let badgeHtml = '<span class="text-xs font-extrabold text-sky-400">🔖 Watchlist</span>';
        if (eff === "completed") {
          badgeHtml = '<span class="text-xs font-extrabold text-emerald-400">✓ Completed</span>';
        } else if (eff === "in_progress") {
          badgeHtml =
            '<span class="text-xs font-extrabold tv-yellow-text">▶ In Progress (' +
            prog.watchedCount +
            "/" +
            prog.total +
            " watched)</span>";
        } else if (eff === "stopped") {
          badgeHtml =
            '<span class="text-xs font-extrabold text-rose-400">⏹ Stopped (' +
            prog.watchedCount +
            "/" +
            prog.total +
            " watched)</span>";
        }

        const completedBtnHtml =
          '<button onclick="app.setShowStatus(&quot;' +
          escapeHtml(show.id) +
          '&quot;, &quot;completed&quot;)" class="px-3 py-2 rounded-full text-xs font-extrabold transition ' +
          (isCompleted ? "tv-yellow-bg" : "bg-zinc-800 text-zinc-200 hover:text-white") +
          '">✓ Completed</button>';

        const stoppedBtnHtml =
          '<button ' +
          (canStop
            ? 'onclick="app.setShowStatus(&quot;' + escapeHtml(show.id) + '&quot;, &quot;stopped&quot;)" '
            : "disabled ") +
          'class="px-3 py-2 rounded-full text-xs font-extrabold transition ' +
          (isStopped
            ? "tv-yellow-bg"
            : canStop
            ? "bg-zinc-800 text-zinc-200 hover:text-white"
            : "bg-zinc-900 text-zinc-600 cursor-not-allowed") +
          '">' +
          (isStopped ? "⏹ Stopped ✓" : "⏹ Stopped") +
          "</button>";

        const watchlistBtnHtml =
          '<button ' +
          (isInWatchlist
            ? 'onclick="app.removeItemFromLibrary(&quot;' + escapeHtml(show.id) + '&quot;)" '
            : canSetWatchlist
            ? 'onclick="app.setShowStatus(&quot;' + escapeHtml(show.id) + '&quot;, &quot;interested&quot;)" '
            : "disabled ") +
          'class="w-full py-2 px-3 rounded-full text-xs font-extrabold transition border ' +
          (isInWatchlist
            ? "tv-yellow-bg border-yellow-400"
            : canSetWatchlist
            ? "bg-zinc-800 text-zinc-200 border-zinc-700 hover:text-white"
            : "bg-zinc-900 text-zinc-500 border-zinc-800 cursor-not-allowed") +
          '">' +
          (isInWatchlist
            ? "🔖 In Watchlist"
            : canSetWatchlist
            ? "🔖 Add to Watchlist"
            : "🔖 Watchlist (Only for unwatched series)") +
          "</button>";

        return (
          '<div class="tv-card rounded-2xl p-3 space-y-2.5 mb-3">' +
          '<div class="flex items-center justify-between gap-2">' +
          badgeHtml +
          '<button onclick="app.removeItemFromLibrary(&quot;' +
          escapeHtml(show.id) +
          '&quot;)" class="text-[11px] font-bold text-rose-400 hover:underline">✕ Not Watched (Remove)</button>' +
          "</div>" +
          '<div class="grid grid-cols-2 gap-2">' +
          completedBtnHtml +
          stoppedBtnHtml +
          "</div>" +
          watchlistBtnHtml +
          "</div>"
        );
      }

      renderMovieStatusHeaderHtml(movie) {
        return (
          '<div class="tv-card rounded-2xl p-3 space-y-2 mb-3">' +
          '<div class="flex items-center justify-between">' +
          '<span class="text-[11px] font-bold uppercase text-zinc-400">Movie Status</span>' +
          '<button onclick="app.removeItemFromLibrary(&quot;' +
          escapeHtml(movie.id) +
          '&quot;)" class="text-[11px] font-bold text-rose-400 hover:underline">✕ Not Watched (Remove)</button>' +
          "</div>" +
          '<div class="grid grid-cols-2 gap-2">' +
          '<button onclick="app.setMovieStatus(&quot;' +
          escapeHtml(movie.id) +
          '&quot;, false)" class="py-2.5 rounded-full text-xs font-extrabold transition ' +
          (!movie.watched ? "tv-yellow-bg" : "bg-zinc-800 text-zinc-300 hover:text-white") +
          '">🔖 Watchlist</button>' +
          '<button onclick="app.setMovieStatus(&quot;' +
          escapeHtml(movie.id) +
          '&quot;, true)" class="py-2.5 rounded-full text-xs font-extrabold transition ' +
          (movie.watched ? "tv-yellow-bg" : "bg-zinc-800 text-zinc-300 hover:text-white") +
          '">✓ Watched</button>' +
          "</div>" +
          "</div>"
        );
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

        const statusHeader =
          this.detailType === "movie"
            ? this.renderMovieStatusHeaderHtml(item)
            : this.renderShowStatusHeaderHtml(item);

        if (this.detailTab === "info") {
          bodyEl.innerHTML =
            statusHeader +
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
            "</div>" +
            "</div>" +
            "</div>" +
            '<p class="text-xs text-zinc-300 leading-relaxed">' +
            escapeHtml(item.overview || "No synopsis available.") +
            "</p>";
          return;
        }

        // Episodes Tab (Season Accordions + Episode Thumbnails + Yellow Check / Gray Eye)
        const episodes = Array.isArray(item.episodes) ? item.episodes : [];
        const watchedSet = new Set(item.watchedEpisodes || []);
        const bySeason = new Map();
        for (const ep of episodes) {
          const sNum = Number(ep.season) || 1;
          if (!bySeason.has(sNum)) bySeason.set(sNum, []);
          bySeason.get(sNum).push(ep);
        }

        let html = statusHeader;
        for (const [seasonNum, sEps] of bySeason.entries()) {
          const isOpen = this.openSeasons.has(seasonNum);
          const watchedInSeason = sEps.filter((e) => watchedSet.has(e.code)).length;
          const allSeasonWatched = sEps.length > 0 && watchedInSeason === sEps.length;
          html +=
            '<div id="detail-season-' +
            seasonNum +
            '" class="tv-card rounded-2xl overflow-hidden mb-3">' +
            '<div onclick="app.toggleSeasonAccordion(' +
            seasonNum +
            ')" class="p-3.5 flex items-center justify-between cursor-pointer hover:bg-zinc-800/40">' +
            '<div class="flex items-center gap-3">' +
            '<button title="Mark entire season watched or unwatched" onclick="event.stopPropagation(); app.toggleSeasonWatched(&quot;' +
            escapeHtml(item.id) +
            '&quot;, ' +
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
            watchedInSeason +
            "/" +
            sEps.length +
            " watched &nbsp;" +
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
                '&quot;, &quot;' +
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
        if (this.pendingScrollSeason) {
          const targetSeason = this.pendingScrollSeason;
          this.pendingScrollSeason = null;
          setTimeout(() => {
            const el = document.getElementById("detail-season-" + targetSeason);
            if (el && typeof el.scrollIntoView === "function") {
              el.scrollIntoView({ behavior: "smooth", block: "start" });
            }
          }, 40);
        }
      }

      // =========================================================
      // PROFILE, STATS, WATCHLISTS & TV TIME EXPORT/IMPORT LOGIC
      // =========================================================
      renderProfile() {
        const shows = Array.isArray(this.state.shows) ? this.state.shows : [];
        const movies = Array.isArray(this.state.movies) ? this.state.movies : [];

        const cleanUser = String(this.userId || "member");
        const emailLabel = String(this.userEmail || "@" + cleanUser);
        const nameEl = document.getElementById("profile-display-name");
        const handleEl = document.getElementById("profile-handle");
        const initEl = document.getElementById("profile-avatar-initials");
        const drawerEmailEl = document.getElementById("drawer-user-email");
        const settingsEmailEl = document.getElementById("settings-email-display");
        if (nameEl) nameEl.textContent = cleanUser.charAt(0).toUpperCase() + cleanUser.slice(1);
        if (handleEl) handleEl.textContent = emailLabel;
        if (initEl) initEl.textContent = cleanUser.slice(0, 2).toUpperCase();
        if (drawerEmailEl) drawerEmailEl.textContent = emailLabel;
        if (settingsEmailEl) settingsEmailEl.textContent = emailLabel;

        let epCount = 0;
        let showMins = 0;
        for (const s of shows) {
          const w = (s.watchedEpisodes || []).length;
          epCount += w;
          showMins += w * (Number(s.runtimeMins) || 45);
        }
        let movCount = 0;
        let movMins = 0;
        for (const m of movies) {
          if (m.watched) {
            movCount += 1;
            movMins += Number(m.runtimeMins) || 120;
          }
        }
        const totalMins = showMins + movMins;
        const fmtHours = (mins) => (Math.round((mins / 60) * 10) / 10).toFixed(1) + " hrs";
        const fmtDaysHours = (mins) => {
          const d = Math.floor(mins / 1440);
          const h = Math.floor((mins % 1440) / 60);
          return d + "d " + h + "h";
        };

        const completedShows = shows.filter((s) => this.getEffectiveShowStatus(s) === "completed");
        const activeOrCompletedShows = shows.filter((s) => {
          const st = this.getEffectiveShowStatus(s);
          return st === "in_progress" || st === "completed";
        });
        const watchedMovies = movies.filter((m) => m.watched);

        const totalEl = document.getElementById("stat-total-time");
        const totalSubEl = document.getElementById("stat-total-sub");
        const sTimeEl = document.getElementById("stat-series-time");
        const sCountEl = document.getElementById("stat-series-count");
        const epCountEl = document.getElementById("stat-episodes-count");
        const mTimeEl = document.getElementById("stat-movies-time");
        const mCountEl = document.getElementById("stat-movies-count");

        if (totalEl) totalEl.textContent = fmtHours(totalMins);
        if (totalSubEl) totalSubEl.textContent = "Total watch hours (" + fmtDaysHours(totalMins) + ")";
        if (sTimeEl) sTimeEl.textContent = fmtHours(showMins);
        if (mTimeEl) mTimeEl.textContent = fmtHours(movMins);
        if (sCountEl) sCountEl.textContent = String(completedShows.length);
        if (epCountEl) epCountEl.textContent = String(epCount);
        if (mCountEl) mCountEl.textContent = String(watchedMovies.length);

        const recSeriesCountEl = document.getElementById("label-recent-series-count");
        const recMoviesCountEl = document.getElementById("label-recent-movies-count");
        if (recSeriesCountEl) recSeriesCountEl.textContent = "Series (" + activeOrCompletedShows.length + ")";
        if (recMoviesCountEl) recMoviesCountEl.textContent = "Movies (" + watchedMovies.length + ")";

        // 1. Recently watched Series
        const recSeriesEl = document.getElementById("carousel-recent-series");
        if (recSeriesEl) {
          recSeriesEl.innerHTML =
            activeOrCompletedShows
              .map((s) => {
                const prog = this.computeShowProgress(s);
                const eff = this.getEffectiveShowStatus(s);
                const badge =
                  eff === "completed"
                    ? "Watched (" + prog.watchedCount + "/" + prog.total + ")"
                    : "In Progress (" + prog.watchedCount + "/" + prog.total + ")";
                return (
                  '<div onclick="app.openItemById(&quot;' +
                  escapeHtml(s.id) +
                  '&quot;)" class="w-28 shrink-0 cursor-pointer space-y-1">' +
                  '<img src="' +
                  escapeHtml(s.posterUrl || "") +
                  '" class="w-28 h-40 object-cover rounded-2xl bg-zinc-900" />' +
                  '<div class="text-xs font-bold text-white truncate">' +
                  escapeHtml(s.title) +
                  "</div>" +
                  '<div class="text-[11px] font-extrabold ' +
                  (eff === "completed" ? "text-emerald-400" : "tv-yellow-text") +
                  '">' +
                  escapeHtml(badge) +
                  "</div>" +
                  "</div>"
                );
              })
              .join("") || '<p class="text-xs text-zinc-400 py-2">No watched or in-progress series yet.</p>';
        }

        // 4. Watched Movies
        const recMoviesEl = document.getElementById("carousel-recent-movies");
        if (recMoviesEl) {
          recMoviesEl.innerHTML =
            watchedMovies
              .map(
                (m) =>
                  '<div onclick="app.openItemById(&quot;' +
                  escapeHtml(m.id) +
                  '&quot;)" class="w-28 shrink-0 cursor-pointer space-y-1">' +
                  '<img src="' +
                  escapeHtml(m.posterUrl || "") +
                  '" class="w-28 h-40 object-cover rounded-2xl bg-zinc-900" />' +
                  '<div class="text-xs font-bold text-white truncate">' +
                  escapeHtml(m.title) +
                  "</div>" +
                  '<div class="text-[11px] font-extrabold text-emerald-400">Watched</div>' +
                  "</div>"
              )
              .join("") || '<p class="text-xs text-zinc-400 py-2">No watched movies yet.</p>';
        }
      }

      // =========================================================
      // TINDER-STYLE SWIPE RECOMMENDATIONS ENGINE
      // =========================================================
      isItemInDbOrDecided(item) {
        if (!item) return true;
        const normTitle = String(item.title || "").trim().toLowerCase();
        const shows = Array.isArray(this.state.shows) ? this.state.shows : [];
        const movies = Array.isArray(this.state.movies) ? this.state.movies : [];
        const decidedSet = new Set(Array.isArray(this.state.decidedIds) ? this.state.decidedIds : []);
        for (const a of (this.state.actors || [])) {
          if (a && a.role === "decided_rec") {
            if (a.id) decidedSet.add(a.id);
            if (a.name) decidedSet.add("title_" + String(a.name).trim().toLowerCase());
          }
        }
        if (decidedSet.has(item.id) || (normTitle && decidedSet.has("title_" + normTitle))) {
          return true;
        }
        if (item.tmdbId && decidedSet.has("tmdb_" + item.mediaType + "_" + item.tmdbId)) {
          return true;
        }
        for (const s of shows) {
          if (s.id === item.id) return true;
          if (item.tmdbId && Number(s.tmdbId) === Number(item.tmdbId)) return true;
          if (item.tvmazeId && Number(s.tvmazeId) === Number(item.tvmazeId)) return true;
          if (normTitle && String(s.title || "").trim().toLowerCase() === normTitle) return true;
        }
        for (const m of movies) {
          if (m.id === item.id) return true;
          if (item.tmdbId && Number(m.tmdbId) === Number(item.tmdbId)) return true;
          if (normTitle && String(m.title || "").trim().toLowerCase() === normTitle) return true;
        }
        return false;
      }

      markItemDecided(item) {
        if (!item) return;
        if (!Array.isArray(this.state.decidedIds)) {
          this.state.decidedIds = [];
        }
        const set = new Set(this.state.decidedIds);
        if (item.id) set.add(item.id);
        const normTitle = String(item.title || "").trim().toLowerCase();
        if (normTitle) set.add("title_" + normTitle);
        if (item.tmdbId) set.add("tmdb_" + item.mediaType + "_" + item.tmdbId);
        this.state.decidedIds = Array.from(set);

        if (!Array.isArray(this.state.actors)) {
          this.state.actors = [];
        }
        if (item.id && !this.state.actors.some((a) => a.id === item.id && a.role === "decided_rec")) {
          this.state.actors.push({
            id: item.id,
            name: normTitle || item.id,
            role: "decided_rec",
            imageUrl: "",
            imdbUrl: "",
            knownFor: "",
          });
        }
      }

      getFilteredRecommendations() {
        return (this.recPool || []).filter((item) => {
          if (this.swipeMediaFilter !== "all" && item.mediaType !== this.swipeMediaFilter) {
            return false;
          }
          return !this.isItemInDbOrDecided(item);
        });
      }

      setSwipeMediaFilter(filter) {
        this.swipeMediaFilter = filter || "all";
        const sel = document.getElementById("swipe-filter-select");
        if (sel && sel.value !== this.swipeMediaFilter) {
          sel.value = this.swipeMediaFilter;
        }
        this.renderSwipeDeck();
      }

      async loadRecommendations(incrementPage = false) {
        if (this.isLoadingRecs) return;
        this.isLoadingRecs = true;
        if (incrementPage) {
          this.recPage = (this.recPage % 5) + 1;
        }
        this.renderSwipeDeck();

        try {
          const shows = Array.isArray(this.state.shows) ? this.state.shows : [];
          const movies = Array.isArray(this.state.movies) ? this.state.movies : [];

          // Prioritize watched / in-progress series & watched movies as recommendation seeds
          const watchedShows = shows.filter((s) => {
            const eff = this.getEffectiveShowStatus(s);
            return eff === "completed" || eff === "in_progress" || (s.watchedEpisodes || []).length > 0;
          });
          const seedShows = (watchedShows.length > 0 ? watchedShows : shows).slice(0, 5);
          const watchedMovies = movies.filter((m) => m.watched);
          const seedMovies = (watchedMovies.length > 0 ? watchedMovies : movies).slice(0, 5);

          const tvSeedsParam = seedShows
            .map((s) => (Number(s.tmdbId) || 0) + ":" + String(s.title || "").replace(/[|:]/g, " "))
            .join("|");
          const movieSeedsParam = seedMovies
            .map((m) => (Number(m.tmdbId) || 0) + ":" + String(m.title || "").replace(/[|:]/g, " "))
            .join("|");

          const url =
            "/api/recommendations?tvSeeds=" +
            encodeURIComponent(tvSeedsParam) +
            "&movieSeeds=" +
            encodeURIComponent(movieSeedsParam) +
            "&page=" +
            this.recPage;
          const res = await fetch(url);
          if (res.ok) {
            const data = await res.json();
            const existingIds = new Set((this.recPool || []).map((r) => r.id));
            for (const item of (data.results || [])) {
              if (item && item.id && !existingIds.has(item.id)) {
                existingIds.add(item.id);
                this.recPool.push(item);
              }
            }
          }
        } catch (_) {}

        this.isLoadingRecs = false;
        this.renderSwipeDeck();
      }

      async decideSwipeCard(action) {
        const deck = this.getFilteredRecommendations();
        const item = deck[0];
        if (!item) return;

        // Animate active card out before committing
        const cardEl = document.getElementById("swipe-active-card");
        if (cardEl) {
          cardEl.style.transition = "transform 0.22s ease-out, opacity 0.22s ease-out";
          if (action === "ignore") {
            cardEl.style.transform = "translate(-120%, 10px) rotate(-18deg)";
          } else if (action === "completed") {
            cardEl.style.transform = "translate(120%, 10px) rotate(18deg)";
          } else {
            cardEl.style.transform = "translate(0px, -110%) scale(0.92)";
          }
          cardEl.style.opacity = "0";
          await new Promise((r) => setTimeout(r, 180));
        }

        this.markItemDecided(item);
        this.recPool = (this.recPool || []).filter((r) => r.id !== item.id);

        if (action === "watchlist") {
          if (item.mediaType === "movie") {
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
          } else {
            let fullShow = null;
            const lookupKey = item.tvmazeId || item.id;
            if (lookupKey) {
              try {
                const res = await fetch("/api/show-details?tvmazeId=" + encodeURIComponent(lookupKey));
                if (res.ok) fullShow = await res.json();
              } catch (_) {}
            }
            const newShow = {
              ...(fullShow || item),
              userStatus: "interested",
              userRating: 0,
              reaction: "",
              isFavorite: false,
              rewatchCount: 0,
              watchedEpisodes: [],
              episodes: fullShow?.episodes || [
                { code: "S01E01", season: 1, number: 1, title: "Pilot", airDate: String(item.year || 2024) + "-01-01", runtimeMins: item.runtimeMins || 45 },
              ],
            };
            this.state.shows = [newShow, ...(this.state.shows || [])];
          }
        } else if (action === "completed") {
          if (item.mediaType === "movie") {
            this.state.movies = [
              {
                ...item,
                watched: true,
                watchedAt: new Date().toISOString().slice(0, 10),
                userRating: 0,
                reaction: "",
                rewatchCount: 0,
                isFavorite: false,
              },
              ...(this.state.movies || []),
            ];
          } else {
            let fullShow = null;
            const lookupKey = item.tvmazeId || item.id;
            if (lookupKey) {
              try {
                const res = await fetch("/api/show-details?tvmazeId=" + encodeURIComponent(lookupKey));
                if (res.ok) fullShow = await res.json();
              } catch (_) {}
            }
            const eps = fullShow?.episodes || [
              { code: "S01E01", season: 1, number: 1, title: "Pilot", airDate: String(item.year || 2024) + "-01-01", runtimeMins: item.runtimeMins || 45 },
            ];
            const newShow = {
              ...(fullShow || item),
              userStatus: "completed",
              userRating: 0,
              reaction: "",
              isFavorite: false,
              rewatchCount: 0,
              lastWatchedAt: new Date().toISOString(),
              watchedEpisodes: eps.map((e) => e.code).sort(),
              episodes: eps,
            };
            this.state.shows = [newShow, ...(this.state.shows || [])];
          }
        }

        await this.persistState();
        if (this.getFilteredRecommendations().length <= 2 && !this.isLoadingRecs) {
          this.loadRecommendations(true);
        }
      }

      renderSwipeDeck() {
        const container = document.getElementById("swipe-deck-container");
        const remBadge = document.getElementById("swipe-remaining-badge");
        if (!container) return;

        const deck = this.getFilteredRecommendations();
        if (remBadge) {
          remBadge.textContent = deck.length + " picks";
        }

        if (deck.length === 0) {
          container.innerHTML =
            '<div class="tv-card rounded-3xl p-8 w-full text-center space-y-4">' +
            '<div class="text-4xl">🎬</div>' +
            '<div class="text-base font-extrabold text-white">' +
            (this.isLoadingRecs ? "Finding personalized recommendations..." : "All caught up on this batch!") +
            "</div>" +
            '<p class="text-xs text-zinc-400">Based on your watched series and movies, we filter out everything already in your library or previously decided.</p>' +
            '<button onclick="app.loadRecommendations(true)" class="px-5 py-2.5 rounded-full tv-yellow-bg text-xs font-extrabold">' +
            (this.isLoadingRecs ? "Loading..." : "Load More Recommendations") +
            "</button>" +
            "</div>";
          return;
        }

        const topItem = deck[0];
        const nextItem = deck[1] || null;
        const poster =
          topItem.posterUrl ||
          "https://via.placeholder.com/400x600/18181d/facc15?text=" + encodeURIComponent(topItem.title || "Pick");
        const isMovie = topItem.mediaType === "movie";
        const typeBadge = isMovie ? "MOVIE" : "TV SERIES";
        const typeColor = isMovie
          ? "bg-sky-500/20 text-sky-300 border-sky-400/40"
          : "bg-yellow-400/20 text-yellow-300 border-yellow-400/40";

        const nextCardHtml = nextItem
          ? '<div class="w-full max-w-sm mx-auto tv-card rounded-3xl h-[410px] scale-[0.95] translate-y-3 opacity-45 pointer-events-none overflow-hidden border border-zinc-800"></div>'
          : "";

        container.innerHTML =
          '<div class="relative w-full max-w-sm mx-auto flex flex-col items-center">' +
          '<div class="relative w-full h-[430px] flex items-center justify-center">' +
          nextCardHtml +
          '<div id="swipe-active-card" class="absolute inset-0 tv-card rounded-3xl overflow-hidden border border-zinc-700/80 shadow-2xl flex flex-col cursor-grab active:cursor-grabbing touch-none">' +
          // Stamp overlays for drag visual feedback
          '<div id="swipe-stamp-completed" class="opacity-0 pointer-events-none absolute top-6 left-5 z-20 px-3.5 py-1 rounded-xl border-2 border-emerald-400 bg-emerald-950/80 text-emerald-300 font-black text-sm tracking-wider -rotate-12">✓ COMPLETED</div>' +
          '<div id="swipe-stamp-ignore" class="opacity-0 pointer-events-none absolute top-6 right-5 z-20 px-3.5 py-1 rounded-xl border-2 border-rose-400 bg-rose-950/80 text-rose-300 font-black text-sm tracking-wider rotate-12">✕ IGNORE</div>' +
          '<div id="swipe-stamp-watchlist" class="opacity-0 pointer-events-none absolute bottom-32 inset-x-0 mx-auto w-max z-20 px-4 py-1 rounded-xl border-2 border-yellow-400 bg-zinc-950/90 tv-yellow-text font-black text-sm tracking-wider">🔖 + WATCHLIST</div>' +
          // Poster with gradient
          '<div class="relative h-64 w-full bg-zinc-900 shrink-0 overflow-hidden">' +
          '<img src="' +
          escapeHtml(poster) +
          '" draggable="false" class="w-full h-full object-cover pointer-events-none" />' +
          '<div class="absolute inset-0 bg-gradient-to-t from-[#16161a] via-[#16161a]/30 to-transparent"></div>' +
          '<div class="absolute top-3 left-3 right-3 flex items-center justify-between gap-2">' +
          '<span class="px-2.5 py-1 rounded-full text-[10px] font-black uppercase border backdrop-blur-md ' +
          typeColor +
          '">' +
          typeBadge +
          "</span>" +
          '<span class="px-2.5 py-1 rounded-full text-xs font-extrabold bg-black/75 text-white backdrop-blur-md flex items-center gap-1"><span class="tv-yellow-text">★</span>' +
          Number(topItem.rating || 7.9).toFixed(1) +
          "</span>" +
          "</div>" +
          '<div class="absolute bottom-2 left-3.5 right-3.5">' +
          '<div class="text-[11px] font-bold tv-yellow-text truncate">' +
          escapeHtml(topItem.becauseOf || "Recommended for you") +
          "</div>" +
          '<h3 class="text-xl font-black text-white leading-tight truncate">' +
          escapeHtml(topItem.title) +
          ' <span class="text-sm font-bold text-zinc-400">(' +
          escapeHtml(topItem.year || 2024) +
          ")</span></h3>" +
          "</div>" +
          "</div>" +
          // Synopsis & metadata
          '<div class="p-4 flex-1 flex flex-col justify-between bg-[#16161a]">' +
          '<p class="text-xs text-zinc-300 line-clamp-4 leading-relaxed">' +
          escapeHtml(topItem.overview || "Swipe right to mark completed, swipe up to add to your watchlist, or swipe left to ignore.") +
          "</p>" +
          '<div class="flex items-center justify-between gap-1 text-[11px] text-zinc-500 pt-2 border-t border-zinc-800/80 flex-wrap">' +
          '<span>👈 Left: Ignore</span>' +
          '<span>👆 Up: Watchlist</span>' +
          '<span>👉 Right: Completed</span>' +
          "</div>" +
          "</div>" +
          "</div>" +
          "</div>" +
          // 3 Tinder Action Buttons
          '<div class="flex items-center justify-center gap-5 pt-4 w-full">' +
          '<button onclick="app.decideSwipeCard(&quot;ignore&quot;)" class="flex flex-col items-center gap-1 group">' +
          '<div class="w-14 h-14 rounded-full bg-zinc-900 border-2 border-rose-500/60 text-rose-400 group-hover:bg-rose-500 group-hover:text-white flex items-center justify-center text-xl font-black shadow-lg transition">✕</div>' +
          '<span class="text-[11px] font-bold text-zinc-400 group-hover:text-rose-400">Ignore</span>' +
          "</button>" +
          '<button onclick="app.decideSwipeCard(&quot;watchlist&quot;)" class="flex flex-col items-center gap-1 group">' +
          '<div class="w-14 h-14 rounded-full bg-zinc-900 border-2 border-yellow-400/70 tv-yellow-text group-hover:bg-yellow-400 group-hover:text-black flex items-center justify-center text-lg font-black shadow-lg transition">🔖</div>' +
          '<span class="text-[11px] font-bold text-zinc-400 group-hover:text-yellow-400">+ Watchlist</span>' +
          "</button>" +
          '<button onclick="app.decideSwipeCard(&quot;completed&quot;)" class="flex flex-col items-center gap-1 group">' +
          '<div class="w-14 h-14 rounded-full bg-zinc-900 border-2 border-emerald-500/70 text-emerald-400 group-hover:bg-emerald-500 group-hover:text-white flex items-center justify-center text-xl font-black shadow-lg transition">' +
          CHECK_SVG +
          "</div>" +
          '<span class="text-[11px] font-bold text-zinc-400 group-hover:text-emerald-400">Completed</span>' +
          "</button>" +
          "</div>" +
          "</div>";

        this.bindSwipeCardGestures();
      }

      bindSwipeCardGestures() {
        const card = document.getElementById("swipe-active-card");
        if (!card) return;
        const stampComp = document.getElementById("swipe-stamp-completed");
        const stampIgn = document.getElementById("swipe-stamp-ignore");
        const stampWl = document.getElementById("swipe-stamp-watchlist");

        let startX = 0;
        let startY = 0;
        let dx = 0;
        let dy = 0;
        let dragging = false;

        card.onpointerdown = (e) => {
          dragging = true;
          startX = e.clientX;
          startY = e.clientY;
          dx = 0;
          dy = 0;
          card.style.transition = "none";
          try {
            card.setPointerCapture(e.pointerId);
          } catch (_) {}
        };

        card.onpointermove = (e) => {
          if (!dragging) return;
          dx = e.clientX - startX;
          dy = e.clientY - startY;
          const rot = dx * 0.06;
          card.style.transform = "translate(" + dx + "px, " + dy + "px) rotate(" + rot + "deg)";

          if (stampComp) stampComp.style.opacity = dx > 35 ? String(Math.min(1, (dx - 35) / 70)) : "0";
          if (stampIgn) stampIgn.style.opacity = dx < -35 ? String(Math.min(1, (-dx - 35) / 70)) : "0";
          if (stampWl) stampWl.style.opacity = dy < -35 && Math.abs(dx) < 70 ? String(Math.min(1, (-dy - 35) / 65)) : "0";
        };

        const finishDrag = () => {
          if (!dragging) return;
          dragging = false;
          if (dx > 85) {
            this.decideSwipeCard("completed");
          } else if (dx < -85) {
            this.decideSwipeCard("ignore");
          } else if (dy < -75 && Math.abs(dx) < 85) {
            this.decideSwipeCard("watchlist");
          } else {
            card.style.transition = "transform 0.2s ease-out";
            card.style.transform = "translate(0px, 0px) rotate(0deg)";
            if (stampComp) stampComp.style.opacity = "0";
            if (stampIgn) stampIgn.style.opacity = "0";
            if (stampWl) stampWl.style.opacity = "0";
          }
        };

        card.onpointerup = finishDrag;
        card.onpointercancel = finishDrag;
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

      exportBackupCsv() {
        const q = String.fromCharCode(34);
        const nl = String.fromCharCode(10);
        const esc = (val) => q + String(val == null ? "" : val).split(q).join(q + q) + q;
        const rows = ["Type,Title,Year,Rating,Status,Progress,RuntimeMinutes,Favorite"];
        const shows = Array.isArray(this.state.shows) ? this.state.shows : [];
        for (const s of shows) {
          const eps = Array.isArray(s.episodes) ? s.episodes : [];
          const watched = (s.watchedEpisodes || []).length;
          const eff = this.getEffectiveShowStatus(s);
          const status = eff === "completed" ? "Watched" : eff === "in_progress" ? "In Progress" : eff === "stopped" ? "Stopped" : "Watchlist";
          rows.push(
            [
              esc("Show"),
              esc(s.title),
              esc(s.year),
              esc(s.rating),
              esc(status),
              esc(watched + "/" + eps.length + " eps"),
              esc(s.runtimeMins || 45),
              esc(Boolean(s.isFavorite)),
            ].join(",")
          );
        }
        const movies = Array.isArray(this.state.movies) ? this.state.movies : [];
        for (const m of movies) {
          rows.push(
            [
              esc("Movie"),
              esc(m.title),
              esc(m.year),
              esc(m.rating),
              esc(m.watched ? "Watched" : "Watchlist"),
              esc(m.watched ? "1/1" : "0/1"),
              esc(m.runtimeMins || 120),
              esc(Boolean(m.isFavorite)),
            ].join(",")
          );
        }
        const blob = new Blob([rows.join(nl)], { type: "text/csv" });
        const url = URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = url;
        a.download = "tvtime-library-" + this.userId + ".csv";
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
