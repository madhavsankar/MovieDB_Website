package com.tvtime.tracker.ui

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.tvtime.tracker.BuildConfig
import com.tvtime.tracker.data.ActorEntity
import com.tvtime.tracker.data.FirestoreSyncService
import com.tvtime.tracker.data.FriendLibraryData
import com.tvtime.tracker.data.FriendUserSummary
import com.tvtime.tracker.data.LiveMediaCatalogService
import com.tvtime.tracker.data.MovieEntity
import com.tvtime.tracker.data.ShowEntity
import com.tvtime.tracker.data.SocialFriendsState
import com.tvtime.tracker.data.TvTimeDao
import com.tvtime.tracker.domain.EpisodeModel
import com.tvtime.tracker.domain.SearchResultItem
import com.tvtime.tracker.domain.TvTimeProgressCalculator
import com.tvtime.tracker.domain.WatchStatsSummary
import com.tvtime.tracker.ui.theme.TvTimePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvTimeMainScreen(
    dao: TvTimeDao,
    initialUserId: String,
    sharedSearchQuery: String? = null,
    onConsumeSharedQuery: () -> Unit = {},
    onSwitchUser: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentUserId by rememberSaveable { mutableStateOf(initialUserId) }
    var activeBottomTab by rememberSaveable { mutableStateOf("home") } // "home" | "search" | "swipe"
    var tabBackStack by rememberSaveable { mutableStateOf(listOf<String>()) }

    // Home Tab State
    var homeTopSegment by rememberSaveable { mutableStateOf("shows") } // "shows" | "movies" | "upcoming"
    var mediaSubFilter by rememberSaveable { mutableStateOf("in_progress") } // "in_progress" | "interested" | "completed" | "stopped"

    // Home & Search US Streaming Platform Filter State (Multi-select, persisted across rotation)
    var selectedUsPlatformsList by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val selectedUsPlatforms = remember(selectedUsPlatformsList) { selectedUsPlatformsList.toSet() }

    // Search Tab State
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchSort by rememberSaveable { mutableStateOf("top") } // "top" | "newest" | "oldest"
    var searchResults by remember { mutableStateOf<List<SearchResultItem>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchPage by remember { mutableIntStateOf(1) }
    var hasMoreSearchResults by remember { mutableStateOf(false) }
    var isLoadingMoreSearch by remember { mutableStateOf(false) }

    // Swipe Recommendations Tab State
    var swipeMediaFilter by rememberSaveable { mutableStateOf("all") } // "all" | "tv" | "movie"

    // Modals & Bottom Sheets
    var showHamburgerSheet by rememberSaveable { mutableStateOf(false) }
    var selectedShowForDetail by remember { mutableStateOf<ShowEntity?>(null) }
    var selectedMovieForDetail by remember { mutableStateOf<MovieEntity?>(null) }
    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    var showAccountSwitcherDialog by remember(currentUserId) {
        mutableStateOf(!FirestoreSyncService.isUserAllowed(currentUserId))
    }

    val switchTab: (String) -> Unit = { nextTab ->
        if (nextTab != activeBottomTab) {
            tabBackStack = (tabBackStack + activeBottomTab).takeLast(12)
            activeBottomTab = nextTab
        }
    }

    LaunchedEffect(sharedSearchQuery) {
        if (!sharedSearchQuery.isNullOrBlank()) {
            searchQuery = sharedSearchQuery
            switchTab("search")
            onConsumeSharedQuery()
        }
    }

    val navigateBack: () -> Unit = {
        when {
            showImportDialog -> showImportDialog = false
            selectedShowForDetail != null -> selectedShowForDetail = null
            selectedMovieForDetail != null -> selectedMovieForDetail = null
            showHamburgerSheet -> showHamburgerSheet = false
            tabBackStack.isNotEmpty() -> {
                val previous = tabBackStack.last()
                tabBackStack = tabBackStack.dropLast(1)
                activeBottomTab = previous
            }
            activeBottomTab != "home" -> activeBottomTab = "home"
            homeTopSegment != "shows" -> homeTopSegment = "shows"
        }
    }

    val canInterceptBack = showImportDialog ||
        selectedShowForDetail != null ||
        selectedMovieForDetail != null ||
        showHamburgerSheet ||
        tabBackStack.isNotEmpty() ||
        activeBottomTab != "home" ||
        homeTopSegment != "shows"

    BackHandler(enabled = canInterceptBack) {
        navigateBack()
    }

    LaunchedEffect(currentUserId) {
        if (FirestoreSyncService.isUserAllowed(currentUserId)) {
            FirestoreSyncService.pullFromFirestoreIfNeeded(dao, currentUserId)
        }
    }

    val rawShows by dao.observeShowsForUser(currentUserId).collectAsState(initial = emptyList())
    val rawMovies by dao.observeMoviesForUser(currentUserId).collectAsState(initial = emptyList())
    val shows by produceState<List<ShowEntity>>(initialValue = emptyList(), key1 = rawShows) {
        value = if (rawShows.isEmpty()) {
            emptyList()
        } else {
            withContext(Dispatchers.Default) { FirestoreSyncService.deduplicateShows(rawShows) }
        }
    }
    val movies by produceState<List<MovieEntity>>(initialValue = emptyList(), key1 = rawMovies) {
        value = if (rawMovies.isEmpty()) {
            emptyList()
        } else {
            withContext(Dispatchers.Default) { FirestoreSyncService.deduplicateMovies(rawMovies) }
        }
    }
    val actors by dao.observeActorsForUser(currentUserId).collectAsState(initial = emptyList())
    val decidedIds by produceState<Set<String>>(initialValue = emptySet(), key1 = actors) {
        value = if (actors.isEmpty()) {
            emptySet()
        } else {
            withContext(Dispatchers.Default) {
                actors.filter { it.role == "decided_rec" }
                    .flatMap { listOf(it.externalId, TvTimeProgressCalculator.canonicalizeDecidedId(it.externalId)) }
                    .filter { it.isNotBlank() }
                    .toSet()
            }
        }
    }

    var refreshTrigger by remember { mutableIntStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }
    var pullOffsetPx by remember { mutableFloatStateOf(0f) }
    val isPullVisible by remember { derivedStateOf { pullOffsetPx > 20f } }
    val isPullReadyToRelease by remember { derivedStateOf { pullOffsetPx >= 130f } }

    // Background poster/metadata enrichment for CSV-imported shows without posters
    LaunchedEffect(shows.size, currentUserId) {
        val missingPosters = shows.filter { it.posterUrl.isBlank() && it.title.isNotBlank() }
        if (missingPosters.isNotEmpty()) {
            var batchCount = 0
            for (s in missingPosters) {
                val results = LiveMediaCatalogService.searchMulti(s.title)
                val match = results.firstOrNull { it.mediaType == "show" } ?: results.firstOrNull()
                if (match != null && match.posterUrl.isNotBlank()) {
                    dao.updateShow(
                        s.copy(
                            posterUrl = match.posterUrl,
                            tvmazeId = if (s.tvmazeId != 0) s.tvmazeId else match.tvmazeId,
                            tmdbId = if (s.tmdbId != 0) s.tmdbId else match.tmdbId,
                            year = if (match.year > 0) match.year else s.year,
                            rating = if (match.rating > 0.0) match.rating else s.rating,
                            network = if (s.network.isBlank() || s.network == "Streaming") match.networkOrDirector.ifBlank { s.network } else s.network
                        )
                    )
                    batchCount++
                    if (batchCount >= 15) {
                        if (FirestoreSyncService.isUserAllowed(currentUserId)) {
                            FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                        }
                        batchCount = 0
                    }
                }
            }
            if (batchCount > 0 && FirestoreSyncService.isUserAllowed(currentUserId)) {
                FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
            }
        }
    }

    // Refresh episode lists for running/ongoing series ONLY when newly aired episodes arrive or upcoming schedule changes
    LaunchedEffect(homeTopSegment, refreshTrigger, currentUserId) {
        val candidates = shows.filter {
            it.userStatus != "stopped" &&
                !it.status.equals("Ended", ignoreCase = true) &&
                !it.status.equals("Canceled", ignoreCase = true)
        }
        if (candidates.isNotEmpty() && (homeTopSegment == "upcoming" || refreshTrigger > 0)) {
            val pendingUpdates = mutableListOf<ShowEntity>()
            for (s in candidates) {
                val compMeta = TvTimeProgressCalculator.parseCompactedMeta(s.episodesJson)
                val currentEps = compMeta.episodes
                val curUpcoming = TvTimeProgressCalculator.findNextUpcomingEpisode(currentEps)?.first
                val isCompactedCompleted = s.userStatus.equals("completed", ignoreCase = true) && compMeta.totalEpisodes > 1
                if (curUpcoming == null || currentEps.size <= 1) {
                    val (fetchedEps, fetchedCast) = LiveMediaCatalogService.fetchFullShowEpisodesAndCast(
                        tvmazeId = s.tvmazeId,
                        tmdbId = s.tmdbId,
                        title = s.title,
                        externalId = s.externalId
                    )
                    if (fetchedEps.isEmpty()) continue
                    val fetchedAired = TvTimeProgressCalculator.filterAiredEpisodes(fetchedEps)
                    val fetchedUpcoming = TvTimeProgressCalculator.findNextUpcomingEpisode(fetchedEps)?.first
                    if (isCompactedCompleted && fetchedAired.size <= compMeta.totalEpisodes) {
                        // Still caught up: keep compacted so Statistics watch hours never jump mid-screen!
                        val nextChanged = (curUpcoming?.code != fetchedUpcoming?.code) ||
                            (curUpcoming?.airDate != fetchedUpcoming?.airDate)
                        if (nextChanged) {
                            val updatedCompJson = TvTimeProgressCalculator.serializeCompactedMeta(
                                totalEpisodes = compMeta.totalEpisodes,
                                completedSeasons = emptyMap(),
                                episodes = if (fetchedUpcoming != null) listOf(fetchedUpcoming) else emptyList()
                            )
                            pendingUpdates.add(s.copy(episodesJson = updatedCompJson))
                        }
                    } else if (fetchedAired.size > compMeta.totalEpisodes || (!isCompactedCompleted && currentEps.size <= 1 && fetchedEps.size > currentEps.size)) {
                        val expandedWatched = TvTimeProgressCalculator.expandWatchedSetWithCompletedSeasons(
                            episodes = fetchedEps,
                            watchedCodes = TvTimeProgressCalculator.parseWatchedSet(s.watchedCodesCsv),
                            completedSeasons = compMeta.completedSeasons,
                            rawUserStatus = s.userStatus,
                            previousWatchedAiredCount = compMeta.totalEpisodes,
                            isOngoingShow = true
                        )
                        val nextProg = TvTimeProgressCalculator.computeShowProgress(
                            episodes = fetchedEps,
                            watchedCodes = expandedWatched,
                            rawUserStatus = s.userStatus
                        )
                        val nextEff = TvTimeProgressCalculator.deriveEffectiveShowStatus(nextProg, s.userStatus)
                        val updatedWatchedCsv = TvTimeProgressCalculator.serializeWatchedSet(expandedWatched)
                        pendingUpdates.add(
                            s.copy(
                                userStatus = nextEff,
                                episodesJson = TvTimeProgressCalculator.serializeEpisodeList(fetchedEps),
                                watchedCodesCsv = updatedWatchedCsv,
                                castCsv = if (s.castCsv.isBlank() && fetchedCast.isNotEmpty()) {
                                    fetchedCast.joinToString(", ")
                                } else s.castCsv
                            )
                        )
                    }
                }
            }
            if (pendingUpdates.isNotEmpty()) {
                for (upd in pendingUpdates) {
                    dao.updateShow(upd)
                }
                if (FirestoreSyncService.isUserAllowed(currentUserId)) {
                    FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                }
            }
        }
    }

    val triggerPullRefresh: () -> Unit = {
        if (!isRefreshing) {
            isRefreshing = true
            scope.launch {
                FirestoreSyncService.pullFromFirestoreIfNeeded(dao, currentUserId, force = true)
                FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                refreshTrigger += 1
                isRefreshing = false
                pullOffsetPx = 0f
                Toast.makeText(context, "Refreshed & synced", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val pullToRefreshNestedScroll = remember(activeBottomTab, isRefreshing) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (activeBottomTab == "swipe") return Offset.Zero
                if (source == NestedScrollSource.Drag && available.y < 0f && pullOffsetPx > 0f) {
                    val consumedY = available.y.coerceAtLeast(-pullOffsetPx)
                    pullOffsetPx = (pullOffsetPx + consumedY).coerceAtLeast(0f)
                    return Offset(0f, consumedY)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (activeBottomTab == "swipe" || isRefreshing) return Offset.Zero
                if (source == NestedScrollSource.Drag && available.y > 0f) {
                    pullOffsetPx = (pullOffsetPx + available.y * 0.55f).coerceAtMost(240f)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pullOffsetPx >= 130f && !isRefreshing) {
                    triggerPullRefresh()
                } else {
                    pullOffsetPx = 0f
                }
                return Velocity.Zero
            }
        }
    }

    // Initial Search & US Provider Discovery Results load
    LaunchedEffect(searchQuery, selectedUsPlatforms, refreshTrigger) {
        isSearching = true
        isLoadingMoreSearch = false
        searchPage = 1
        if (searchQuery.isNotBlank()) delay(250)
        if (searchQuery.isBlank() && selectedUsPlatforms.isNotEmpty()) {
            val paged = LiveMediaCatalogService.discoverByUsProvidersPaged(selectedUsPlatforms, page = 1)
            searchResults = paged.items
            hasMoreSearchResults = paged.hasMore
        } else {
            val paged = LiveMediaCatalogService.searchMultiPaged(searchQuery.ifBlank { "Game of Thrones" }, page = 1)
            val raw = paged.items.filter { it.mediaType != "actor" }
            searchResults = if (selectedUsPlatforms.isNotEmpty()) {
                raw.filter { LiveMediaCatalogService.matchesUsPlatforms(it.networkOrDirector, selectedUsPlatforms) }
            } else {
                raw
            }
            hasMoreSearchResults = paged.hasMore
        }
        isSearching = false
    }

    val loadMoreSearchResults: () -> Unit = {
        if (!isSearching && !isLoadingMoreSearch && hasMoreSearchResults && searchPage < 25) {
            isLoadingMoreSearch = true
            val nextPage = searchPage + 1
            val capturedQuery = searchQuery
            val capturedPlatforms = selectedUsPlatforms
            scope.launch {
                val paged = if (capturedQuery.isBlank() && capturedPlatforms.isNotEmpty()) {
                    LiveMediaCatalogService.discoverByUsProvidersPaged(capturedPlatforms, page = nextPage)
                } else {
                    LiveMediaCatalogService.searchMultiPaged(capturedQuery.ifBlank { "Game of Thrones" }, page = nextPage)
                }
                if (capturedQuery == searchQuery && capturedPlatforms == selectedUsPlatforms) {
                    val incoming = paged.items.filter { it.mediaType != "actor" }.let { list ->
                        if (capturedPlatforms.isNotEmpty() && capturedQuery.isNotBlank()) {
                            list.filter { LiveMediaCatalogService.matchesUsPlatforms(it.networkOrDirector, capturedPlatforms) }
                        } else {
                            list
                        }
                    }
                    val merged = searchResults.toMutableList()
                    for (cand in incoming) {
                        val isDup = merged.any { ex ->
                            ex.externalId == cand.externalId ||
                                (ex.mediaType == "movie" && cand.mediaType == "movie" && TvTimeProgressCalculator.doesMovieMatch(
                                    ex.externalId, ex.tmdbId, ex.title, ex.year,
                                    cand.externalId, cand.tmdbId, cand.title, cand.year
                                )) ||
                                (ex.mediaType != "movie" && cand.mediaType != "movie" && TvTimeProgressCalculator.doesShowMatch(
                                    ex.externalId, ex.tmdbId, ex.tvmazeId, ex.title, ex.year,
                                    cand.externalId, cand.tmdbId, cand.tvmazeId, cand.title, cand.year
                                ))
                        }
                        if (!isDup) merged.add(cand)
                    }
                    searchResults = merged
                    searchPage = nextPage
                    hasMoreSearchResults = paged.hasMore && paged.items.isNotEmpty()
                }
                isLoadingMoreSearch = false
            }
        }
    }

    val toggleUsPlatform: (String) -> Unit = { platform ->
        selectedUsPlatformsList = if (platform == "clear") {
            emptyList()
        } else if (selectedUsPlatformsList.contains(platform)) {
            selectedUsPlatformsList - platform
        } else {
            selectedUsPlatformsList + platform
        }
    }

    Scaffold(
        containerColor = TvTimePalette.Background,
        topBar = {
            Surface(
                color = TvTimePalette.Background,
                tonalElevation = 0.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Surface(
                        onClick = { showHamburgerSheet = true },
                        shape = RoundedCornerShape(50),
                        color = TvTimePalette.SurfacePill,
                        border = BorderStroke(1.dp, TvTimePalette.Border)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Open Menu",
                                tint = TvTimePalette.Yellow,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "TV Time",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
                                color = Color.White
                            )
                        }
                    }

                    Surface(
                        onClick = { switchTab("friends") },
                        shape = RoundedCornerShape(50),
                        color = TvTimePalette.SurfacePill,
                        border = BorderStroke(
                            1.dp,
                            if (activeBottomTab == "friends") TvTimePalette.Yellow else TvTimePalette.Border
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(text = "👥", fontSize = 14.sp)
                            Text(
                                text = "Friends",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
                                color = if (activeBottomTab == "friends") TvTimePalette.Yellow else Color.White
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = TvTimePalette.Background,
                contentColor = Color.White,
                tonalElevation = 0.dp
            ) {
                val navItems = listOf(
                    "home" to Icons.Default.Home,
                    "search" to Icons.Default.Search,
                    "swipe" to Icons.Default.Favorite
                )
                navItems.forEach { (key, icon) ->
                    val selected = activeBottomTab == key
                    NavigationBarItem(
                        selected = selected,
                        onClick = { switchTab(key) },
                        icon = {
                            if (key == "swipe") {
                                Text(
                                    text = "✨",
                                    fontSize = 20.sp,
                                    color = if (selected) TvTimePalette.Yellow else TvTimePalette.TextSecondary
                                )
                            } else {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = key,
                                    tint = if (selected) TvTimePalette.Yellow else TvTimePalette.TextSecondary,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = Color.Transparent
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(pullToRefreshNestedScroll)
        ) {
            val handleAddOrOpenCatalogItem: (SearchResultItem) -> Unit = { item ->
                scope.launch {
                    val isMovieItem = item.mediaType.equals("movie", ignoreCase = true) ||
                        item.externalId.startsWith("movie_")
                    if (isMovieItem) {
                        val cleanItemTmdbId = TvTimeProgressCalculator.cleanMovieTmdbId(item.externalId, item.tmdbId)
                        val freshMovies = dao.getMoviesForUser(currentUserId)
                        val existingMovie = freshMovies.firstOrNull { m ->
                            TvTimeProgressCalculator.doesMovieMatch(
                                m.externalId, m.tmdbId, m.title, m.year,
                                item.externalId, cleanItemTmdbId, item.title, item.year
                            )
                        }
                        if (existingMovie != null) {
                            selectedMovieForDetail = existingMovie
                        } else {
                            selectedMovieForDetail = MovieEntity(
                                id = 0L,
                                userId = currentUserId,
                                externalId = item.externalId,
                                tmdbId = cleanItemTmdbId,
                                title = item.title,
                                year = item.year,
                                runtimeMins = item.runtimeMins,
                                genresCsv = item.genresCsv,
                                castCsv = item.castCsv,
                                director = "",
                                streamingService = item.networkOrDirector,
                                rating = item.rating,
                                posterUrl = item.posterUrl,
                                overview = item.overview,
                                watched = false
                            )
                        }
                    } else {
                        val freshShows = dao.getShowsForUser(currentUserId)
                        val existing = freshShows.firstOrNull { s ->
                            TvTimeProgressCalculator.doesShowMatch(
                                s.externalId, s.tmdbId, s.tvmazeId, s.title, s.year,
                                item.externalId, item.tmdbId, item.tvmazeId, item.title, item.year
                            )
                        }
                        if (existing != null) {
                            selectedShowForDetail = existing
                        } else {
                            val guide = LiveMediaCatalogService.fetchFullShowGuide(
                                tvmazeId = item.tvmazeId,
                                tmdbId = item.tmdbId,
                                title = item.title,
                                externalId = item.externalId,
                                year = item.year
                            )
                            val isCountOnly = guide.countOnly || (guide.episodes.isEmpty() && guide.totalEpisodesOverride > 1)
                            val finalEpsJson = if (isCountOnly) {
                                TvTimeProgressCalculator.serializeCompactedMeta(
                                    totalEpisodes = guide.totalEpisodesOverride.coerceAtLeast(1),
                                    completedSeasons = emptyMap(),
                                    episodes = emptyList(),
                                    countOnly = true
                                )
                            } else {
                                val finalEps = if (guide.episodes.isNotEmpty()) guide.episodes else listOf(
                                    EpisodeModel("S01E01", 1, 1, "Pilot", "${item.year}-01-01", item.runtimeMins)
                                )
                                TvTimeProgressCalculator.serializeEpisodeList(finalEps)
                            }
                            selectedShowForDetail = ShowEntity(
                                id = 0L,
                                userId = currentUserId,
                                externalId = item.externalId,
                                tvmazeId = item.tvmazeId,
                                tmdbId = item.tmdbId,
                                title = item.title,
                                year = item.year,
                                network = guide.networkOverride.ifBlank { item.networkOrDirector },
                                status = guide.statusOverride.ifBlank { item.status },
                                runtimeMins = guide.runtimeMinsOverride.takeIf { it > 0 } ?: item.runtimeMins,
                                genresCsv = item.genresCsv,
                                castCsv = guide.cast.joinToString(", ").ifBlank { item.castCsv },
                                rating = item.rating,
                                posterUrl = item.posterUrl.ifBlank { guide.posterOverride },
                                overview = item.overview.ifBlank { guide.overviewOverride },
                                userStatus = "preview",
                                episodesJson = finalEpsJson
                            )
                        }
                    }
                }
            }

            val handleQuickWatchlistCatalogItem: (SearchResultItem) -> Unit = { item ->
                scope.launch {
                    val isMovieItem = item.mediaType.equals("movie", ignoreCase = true) || item.externalId.startsWith("movie_")
                    if (isMovieItem) {
                        val cleanItemTmdbId = TvTimeProgressCalculator.cleanMovieTmdbId(item.externalId, item.tmdbId)
                        val freshMovies = dao.getMoviesForUser(currentUserId)
                        val matches = freshMovies.filter { m ->
                            TvTimeProgressCalculator.doesMovieMatch(
                                m.externalId, m.tmdbId, m.title, m.year,
                                item.externalId, cleanItemTmdbId, item.title, item.year
                            )
                        }
                        val existingMovie = matches.firstOrNull()
                        if (existingMovie != null) {
                            if (!existingMovie.watched) {
                                matches.forEach { dao.deleteMovie(it.id) }
                            }
                        } else {
                            val newMovie = MovieEntity(
                                userId = currentUserId,
                                externalId = item.externalId,
                                tmdbId = cleanItemTmdbId,
                                title = item.title,
                                year = item.year,
                                runtimeMins = item.runtimeMins,
                                genresCsv = item.genresCsv,
                                castCsv = item.castCsv,
                                director = "",
                                streamingService = item.networkOrDirector,
                                rating = item.rating,
                                posterUrl = item.posterUrl,
                                overview = item.overview,
                                watched = false
                            )
                            dao.insertMovie(newMovie)
                        }
                        FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                    } else {
                        val freshShows = dao.getShowsForUser(currentUserId)
                        val matches = freshShows.filter { s ->
                            TvTimeProgressCalculator.doesShowMatch(
                                s.externalId, s.tmdbId, s.tvmazeId, s.title, s.year,
                                item.externalId, item.tmdbId, item.tvmazeId, item.title, item.year
                            )
                        }
                        val existing = matches.firstOrNull()
                        if (existing != null) {
                            val prog = TvTimeProgressCalculator.computeShowProgressFromJson(
                                existing.episodesJson,
                                existing.watchedCodesCsv,
                                existing.userStatus
                            )
                            val eff = TvTimeProgressCalculator.deriveEffectiveShowStatus(prog, existing.userStatus)
                            if (eff == "interested") {
                                matches.forEach { dao.deleteShow(it.id) }
                            } else if (prog.watchedCount == 0) {
                                dao.updateShow(existing.copy(userStatus = "interested", watchedCodesCsv = ""))
                            }
                            FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                        } else {
                            val fallbackEps = listOf(
                                EpisodeModel("S01E01", 1, 1, "Pilot", "${item.year}-01-01", item.runtimeMins)
                            )
                            val isInitialCountOnly = item.countOnly || item.externalId.startsWith("imdb_tv_")
                            val initialEpsJson = if (isInitialCountOnly && item.totalEpisodes > 1) {
                                TvTimeProgressCalculator.serializeCompactedMeta(
                                    totalEpisodes = item.totalEpisodes,
                                    completedSeasons = emptyMap(),
                                    episodes = emptyList(),
                                    countOnly = true
                                )
                            } else {
                                TvTimeProgressCalculator.serializeEpisodeList(fallbackEps)
                            }
                            val initialShow = ShowEntity(
                                userId = currentUserId,
                                externalId = item.externalId,
                                tvmazeId = item.tvmazeId,
                                tmdbId = item.tmdbId,
                                title = item.title,
                                year = item.year,
                                network = item.networkOrDirector,
                                status = item.status,
                                runtimeMins = item.runtimeMins,
                                genresCsv = item.genresCsv,
                                rating = item.rating,
                                posterUrl = item.posterUrl,
                                overview = item.overview,
                                userStatus = "interested",
                                episodesJson = initialEpsJson
                            )
                            val insertedId = dao.insertShow(initialShow)
                            FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                            val guide = LiveMediaCatalogService.fetchFullShowGuide(
                                tvmazeId = item.tvmazeId,
                                tmdbId = item.tmdbId,
                                title = item.title,
                                externalId = item.externalId,
                                year = item.year
                            )
                            val latestRow = dao.getShowsForUser(currentUserId).firstOrNull { it.id == insertedId }
                            if (latestRow != null && (guide.episodes.isNotEmpty() || guide.totalEpisodesOverride > 1 || guide.cast.isNotEmpty())) {
                                val isStillComp = latestRow.userStatus.equals("completed", ignoreCase = true)
                                val isCountOnly = guide.countOnly || (guide.episodes.isEmpty() && guide.totalEpisodesOverride > 1)
                                val nextEpsJson = if (isCountOnly) {
                                    TvTimeProgressCalculator.serializeCompactedMeta(
                                        totalEpisodes = guide.totalEpisodesOverride.coerceAtLeast(1),
                                        completedSeasons = emptyMap(),
                                        episodes = emptyList(),
                                        countOnly = true
                                    )
                                } else {
                                    val finalEps = if (guide.episodes.isNotEmpty()) guide.episodes else fallbackEps
                                    TvTimeProgressCalculator.serializeEpisodeList(finalEps)
                                }
                                val nextWatchedCsv = if (isStillComp && !isCountOnly) {
                                    val finalEps = if (guide.episodes.isNotEmpty()) guide.episodes else fallbackEps
                                    TvTimeProgressCalculator.serializeWatchedSet(finalEps.map { it.code }.toSet())
                                } else if (isStillComp && isCountOnly) {
                                    ""
                                } else {
                                    latestRow.watchedCodesCsv
                                }
                                dao.updateShow(
                                    latestRow.copy(
                                        castCsv = guide.cast.joinToString(", ").ifBlank { latestRow.castCsv },
                                        runtimeMins = guide.runtimeMinsOverride.takeIf { it > 0 } ?: latestRow.runtimeMins,
                                        posterUrl = latestRow.posterUrl.ifBlank { guide.posterOverride },
                                        overview = latestRow.overview.ifBlank { guide.overviewOverride },
                                        network = if (latestRow.network == "Streaming" || latestRow.network == "Television") guide.networkOverride.ifBlank { latestRow.network } else latestRow.network,
                                        watchedCodesCsv = nextWatchedCsv,
                                        episodesJson = nextEpsJson
                                    )
                                )
                                FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                            }
                        }
                    }
                }
            }

            val handleQuickCompletedCatalogItem: (SearchResultItem) -> Unit = { item ->
                scope.launch {
                    val isMovieItem = item.mediaType.equals("movie", ignoreCase = true) || item.externalId.startsWith("movie_")
                    if (isMovieItem) {
                        val cleanItemTmdbId = TvTimeProgressCalculator.cleanMovieTmdbId(item.externalId, item.tmdbId)
                        val freshMovies = dao.getMoviesForUser(currentUserId)
                        val matches = freshMovies.filter { m ->
                            TvTimeProgressCalculator.doesMovieMatch(
                                m.externalId, m.tmdbId, m.title, m.year,
                                item.externalId, cleanItemTmdbId, item.title, item.year
                            )
                        }
                        val existingMovie = matches.firstOrNull()
                        val todayIso = java.time.LocalDate.now().toString()
                        if (existingMovie != null) {
                            if (existingMovie.watched) {
                                matches.forEach { dao.deleteMovie(it.id) }
                            } else {
                                dao.updateMovie(
                                    existingMovie.copy(
                                        watched = true,
                                        watchedAt = todayIso
                                    )
                                )
                            }
                        } else {
                            val newMovie = MovieEntity(
                                userId = currentUserId,
                                externalId = item.externalId,
                                tmdbId = cleanItemTmdbId,
                                title = item.title,
                                year = item.year,
                                runtimeMins = item.runtimeMins,
                                genresCsv = item.genresCsv,
                                castCsv = item.castCsv,
                                director = "",
                                streamingService = item.networkOrDirector,
                                rating = item.rating,
                                posterUrl = item.posterUrl,
                                overview = item.overview,
                                watched = true,
                                watchedAt = todayIso
                            )
                            dao.insertMovie(newMovie)
                        }
                        FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                    } else {
                        val freshShows = dao.getShowsForUser(currentUserId)
                        val matches = freshShows.filter { s ->
                            TvTimeProgressCalculator.doesShowMatch(
                                s.externalId, s.tmdbId, s.tvmazeId, s.title, s.year,
                                item.externalId, item.tmdbId, item.tvmazeId, item.title, item.year
                            )
                        }
                        val existing = matches.firstOrNull()
                        if (existing != null) {
                            val prog = TvTimeProgressCalculator.computeShowProgressFromJson(
                                existing.episodesJson,
                                existing.watchedCodesCsv,
                                existing.userStatus
                            )
                            val eff = TvTimeProgressCalculator.deriveEffectiveShowStatus(prog, existing.userStatus)
                            if (eff == "completed") {
                                matches.forEach { dao.deleteShow(it.id) }
                                FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                            } else {
                                val eps = TvTimeProgressCalculator.parseEpisodeList(existing.episodesJson)
                                val fallbackEps = listOf(
                                    EpisodeModel("S01E01", 1, 1, "Pilot", "${existing.year}-01-01", existing.runtimeMins)
                                )
                                val baseEps = if (eps.isNotEmpty()) eps else fallbackEps
                                val airedBase = TvTimeProgressCalculator.filterAiredEpisodes(baseEps).ifEmpty { baseEps.take(1) }
                                val allCodes = airedBase.map { it.code }.toSet()
                                dao.updateShow(
                                    existing.copy(
                                        userStatus = "completed",
                                        watchedCodesCsv = TvTimeProgressCalculator.serializeWatchedSet(allCodes),
                                        episodesJson = if (eps.isNotEmpty()) existing.episodesJson else TvTimeProgressCalculator.serializeEpisodeList(baseEps)
                                    )
                                )
                                FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                if (eps.size <= 1) {
                                    val (fetchedEps, fetchedCast) = LiveMediaCatalogService.fetchFullShowEpisodesAndCast(
                                        tvmazeId = existing.tvmazeId.takeIf { it > 0 } ?: item.tvmazeId,
                                        tmdbId = existing.tmdbId.takeIf { it > 0 } ?: item.tmdbId,
                                        title = existing.title,
                                        externalId = existing.externalId
                                    )
                                    val latestRow = dao.getShowsForUser(currentUserId).firstOrNull { it.id == existing.id }
                                    if (latestRow != null && fetchedEps.isNotEmpty()) {
                                        val airedFetched = TvTimeProgressCalculator.filterAiredEpisodes(fetchedEps).ifEmpty { fetchedEps.take(1) }
                                        dao.updateShow(
                                            latestRow.copy(
                                                castCsv = fetchedCast.joinToString(", ").ifBlank { latestRow.castCsv },
                                                watchedCodesCsv = if (latestRow.userStatus.equals("completed", ignoreCase = true)) {
                                                    TvTimeProgressCalculator.serializeWatchedSet(airedFetched.map { it.code }.toSet())
                                                } else {
                                                    latestRow.watchedCodesCsv
                                                },
                                                episodesJson = TvTimeProgressCalculator.serializeEpisodeList(fetchedEps)
                                            )
                                        )
                                        FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                    }
                                }
                            }
                        } else {
                            val fallbackEps = listOf(
                                EpisodeModel("S01E01", 1, 1, "Pilot", "${item.year}-01-01", item.runtimeMins)
                            )
                            val isInitialCountOnly = item.countOnly || item.externalId.startsWith("imdb_tv_")
                            val initialEpsJson = if (isInitialCountOnly && item.totalEpisodes > 1) {
                                TvTimeProgressCalculator.serializeCompactedMeta(
                                    totalEpisodes = item.totalEpisodes,
                                    completedSeasons = emptyMap(),
                                    episodes = emptyList(),
                                    countOnly = true
                                )
                            } else {
                                TvTimeProgressCalculator.serializeEpisodeList(fallbackEps)
                            }
                            val initialShow = ShowEntity(
                                userId = currentUserId,
                                externalId = item.externalId,
                                tvmazeId = item.tvmazeId,
                                tmdbId = item.tmdbId,
                                title = item.title,
                                year = item.year,
                                network = item.networkOrDirector,
                                status = item.status,
                                runtimeMins = item.runtimeMins,
                                genresCsv = item.genresCsv,
                                rating = item.rating,
                                posterUrl = item.posterUrl,
                                overview = item.overview,
                                userStatus = "completed",
                                watchedCodesCsv = if (isInitialCountOnly) "" else TvTimeProgressCalculator.serializeWatchedSet(setOf("S01E01")),
                                episodesJson = initialEpsJson
                            )
                            val insertedId = dao.insertShow(initialShow)
                            FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                            val guide = LiveMediaCatalogService.fetchFullShowGuide(
                                tvmazeId = item.tvmazeId,
                                tmdbId = item.tmdbId,
                                title = item.title,
                                externalId = item.externalId,
                                year = item.year
                            )
                            val latestRow = dao.getShowsForUser(currentUserId).firstOrNull { it.id == insertedId }
                            if (latestRow != null && (guide.episodes.isNotEmpty() || guide.totalEpisodesOverride > 1 || guide.cast.isNotEmpty())) {
                                val isStillComp = latestRow.userStatus.equals("completed", ignoreCase = true)
                                val isCountOnly = guide.countOnly || (guide.episodes.isEmpty() && guide.totalEpisodesOverride > 1)
                                val nextEpsJson = if (isCountOnly) {
                                    TvTimeProgressCalculator.serializeCompactedMeta(
                                        totalEpisodes = guide.totalEpisodesOverride.coerceAtLeast(1),
                                        completedSeasons = emptyMap(),
                                        episodes = emptyList(),
                                        countOnly = true
                                    )
                                } else {
                                    val finalEps = if (guide.episodes.isNotEmpty()) guide.episodes else fallbackEps
                                    TvTimeProgressCalculator.serializeEpisodeList(finalEps)
                                }
                                val nextWatchedCsv = if (isStillComp && !isCountOnly) {
                                    val finalEps = if (guide.episodes.isNotEmpty()) guide.episodes else fallbackEps
                                    val airedFinal = TvTimeProgressCalculator.filterAiredEpisodes(finalEps).ifEmpty { finalEps.take(1) }
                                    TvTimeProgressCalculator.serializeWatchedSet(airedFinal.map { it.code }.toSet())
                                } else if (isStillComp && isCountOnly) {
                                    ""
                                } else {
                                    latestRow.watchedCodesCsv
                                }
                                dao.updateShow(
                                    latestRow.copy(
                                        castCsv = guide.cast.joinToString(", ").ifBlank { latestRow.castCsv },
                                        runtimeMins = guide.runtimeMinsOverride.takeIf { it > 0 } ?: latestRow.runtimeMins,
                                        posterUrl = latestRow.posterUrl.ifBlank { guide.posterOverride },
                                        overview = latestRow.overview.ifBlank { guide.overviewOverride },
                                        network = if (latestRow.network == "Streaming" || latestRow.network == "Television") guide.networkOverride.ifBlank { latestRow.network } else latestRow.network,
                                        watchedCodesCsv = nextWatchedCsv,
                                        episodesJson = nextEpsJson
                                    )
                                )
                                FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                            }
                        }
                    }
                }
            }

            when (activeBottomTab) {
                "home" -> {
                    HomeTabScreen(
                        shows = shows,
                        movies = movies,
                        topSegment = homeTopSegment,
                        onSelectTopSegment = { seg ->
                            homeTopSegment = seg
                            if (seg == "movies") {
                                if (mediaSubFilter != "interested" && mediaSubFilter != "completed") {
                                    mediaSubFilter = "interested"
                                }
                            } else if (seg == "shows") {
                                mediaSubFilter = "in_progress"
                            }
                        },
                        mediaFilter = mediaSubFilter,
                        onSelectMediaFilter = { mediaSubFilter = it },
                        selectedUsPlatforms = selectedUsPlatforms,
                        onToggleUsPlatform = toggleUsPlatform,
                        onOpenShow = { selectedShowForDetail = it },
                        onOpenMovie = { selectedMovieForDetail = it },
                        onMarkNextEpisode = { show ->
                            scope.launch {
                                val currentProg = TvTimeProgressCalculator.computeShowProgressFromJson(
                                    show.episodesJson,
                                    show.watchedCodesCsv,
                                    show.userStatus
                                )
                                val isAlreadyCompleted = show.userStatus == "completed" || currentProg.isCompleted
                                if (isAlreadyCompleted) {
                                    dao.deleteShow(show.id)
                                } else {
                                    var eps = TvTimeProgressCalculator.parseEpisodeList(show.episodesJson)
                                    var castCsv = show.castCsv
                                    val compMeta = TvTimeProgressCalculator.parseCompactedMeta(show.episodesJson)
                                    if (eps.size <= 1 || compMeta.completedSeasons.isNotEmpty()) {
                                        val (fetchedEps, fetchedCast) = LiveMediaCatalogService.fetchFullShowEpisodesAndCast(
                                            tvmazeId = show.tvmazeId,
                                            tmdbId = show.tmdbId,
                                            title = show.title,
                                            externalId = show.externalId
                                        )
                                        if (fetchedEps.isNotEmpty()) {
                                            eps = fetchedEps
                                        }
                                        if (fetchedCast.isNotEmpty() && castCsv.isBlank()) {
                                            castCsv = fetchedCast.joinToString(", ")
                                        }
                                    }
                                    val airedEps = TvTimeProgressCalculator.filterAiredEpisodes(eps).ifEmpty { eps.take(1) }
                                    val allCodes = airedEps.map { it.code.uppercase() }.toSet()
                                    val updatedShow = show.copy(
                                        watchedCodesCsv = TvTimeProgressCalculator.serializeWatchedSet(allCodes),
                                        userStatus = "completed",
                                        episodesJson = TvTimeProgressCalculator.serializeEpisodeList(eps),
                                        castCsv = castCsv
                                    )
                                    dao.updateShow(updatedShow)
                                }
                                FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                            }
                        },
                        onToggleMovieWatched = { movie ->
                            scope.launch {
                                if (movie.watched) {
                                    dao.deleteMovie(movie.id)
                                } else {
                                    val todayIso = java.time.LocalDate.now().toString()
                                    dao.updateMovie(movie.copy(watched = true, watchedAt = todayIso))
                                }
                                FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                            }
                        }
                    )
                }

                "search" -> {
                    SearchTabScreen(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        sortKey = searchSort,
                        onSortChange = { searchSort = it },
                        selectedUsPlatforms = selectedUsPlatforms,
                        onToggleUsPlatform = toggleUsPlatform,
                        results = searchResults,
                        isSearching = isSearching,
                        hasMoreResults = hasMoreSearchResults,
                        isLoadingMore = isLoadingMoreSearch,
                        onLoadMore = loadMoreSearchResults,
                        shows = shows,
                        movies = movies,
                        onAddOrOpenResult = handleAddOrOpenCatalogItem,
                        onQuickWatchlistToggle = handleQuickWatchlistCatalogItem,
                        onQuickCompletedToggle = handleQuickCompletedCatalogItem
                    )
                }

                "swipe" -> {
                    SwipeRecommendationsTabScreen(
                        shows = shows,
                        movies = movies,
                        decidedIds = decidedIds,
                        swipeFilter = swipeMediaFilter,
                        onSelectSwipeFilter = { swipeMediaFilter = it },
                        onOpenItem = handleAddOrOpenCatalogItem,
                        onSwipeDecision = { item, action ->
                            scope.launch {
                                val canonicalId = TvTimeProgressCalculator.canonicalizeDecidedId(
                                    item.externalId,
                                    item.mediaType,
                                    item.tmdbId
                                )
                                // 1. Record decision so we never suggest this again
                                if (canonicalId.isNotBlank() && !decidedIds.contains(canonicalId)) {
                                    dao.insertActor(
                                        ActorEntity(
                                            userId = currentUserId,
                                            externalId = canonicalId,
                                            name = item.title.lowercase(),
                                            role = "decided_rec"
                                        )
                                    )
                                }

                                // 2. If watchlist or completed, add to DB
                                if (action == "watchlist" || action == "completed") {
                                    val isCompleted = action == "completed"
                                    val cleanStreamingLabel = item.networkOrDirector.takeUnless {
                                        it.startsWith("Because", ignoreCase = true) ||
                                            it.startsWith("Popular", ignoreCase = true) ||
                                            it.startsWith("Acclaimed", ignoreCase = true) ||
                                            it.startsWith("Recommended", ignoreCase = true)
                                    } ?: "Streaming"
                                    if (item.mediaType.equals("movie", ignoreCase = true) || item.externalId.startsWith("movie_")) {
                                        val cleanTmdbId = TvTimeProgressCalculator.cleanMovieTmdbId(item.externalId, item.tmdbId)
                                        val existingMovie = movies.firstOrNull { m ->
                                            TvTimeProgressCalculator.doesMovieMatch(
                                                m.externalId, m.tmdbId, m.title, m.year,
                                                item.externalId, cleanTmdbId, item.title, item.year
                                            )
                                        }
                                        if (existingMovie == null) {
                                            val enriched = LiveMediaCatalogService.fetchMovieDetails(
                                                tmdbId = cleanTmdbId,
                                                title = item.title,
                                                externalId = item.externalId
                                            )
                                            dao.insertMovie(
                                                MovieEntity(
                                                    userId = currentUserId,
                                                    externalId = item.externalId,
                                                    tmdbId = cleanTmdbId,
                                                    title = item.title,
                                                    year = item.year,
                                                    runtimeMins = enriched?.runtimeMins ?: item.runtimeMins,
                                                    genresCsv = enriched?.genresCsv?.ifBlank { item.genresCsv } ?: item.genresCsv,
                                                    castCsv = enriched?.castCsv ?: "",
                                                    director = enriched?.director ?: "",
                                                    streamingService = enriched?.streamingService ?: cleanStreamingLabel,
                                                    rating = item.rating,
                                                    posterUrl = item.posterUrl,
                                                    overview = enriched?.overview?.ifBlank { item.overview } ?: item.overview,
                                                    watched = isCompleted
                                                )
                                            )
                                        }
                                    } else {
                                        val existingShow = shows.firstOrNull { s ->
                                            TvTimeProgressCalculator.doesShowMatch(
                                                s.externalId, s.tmdbId, s.tvmazeId, s.title, s.year,
                                                item.externalId, item.tmdbId, item.tvmazeId, item.title, item.year
                                            )
                                        }
                                        if (existingShow == null) {
                                            val guide = LiveMediaCatalogService.fetchFullShowGuide(
                                                tvmazeId = item.tvmazeId,
                                                tmdbId = item.tmdbId,
                                                title = item.title,
                                                externalId = item.externalId,
                                                year = item.year
                                            )
                                            val isCountOnly = guide.countOnly || (guide.episodes.isEmpty() && guide.totalEpisodesOverride > 1)
                                            val finalEps = if (guide.episodes.isNotEmpty()) guide.episodes else listOf(
                                                EpisodeModel("S01E01", 1, 1, "Pilot", "${item.year}-01-01", item.runtimeMins)
                                            )
                                            val watchedCsv = if (isCompleted && !isCountOnly) {
                                                val airedFinal = TvTimeProgressCalculator.filterAiredEpisodes(finalEps).ifEmpty { finalEps.take(1) }
                                                TvTimeProgressCalculator.serializeWatchedSet(
                                                    airedFinal.map { it.code.uppercase() }.toSet()
                                                )
                                            } else ""
                                            val epsJson = if (isCountOnly) {
                                                TvTimeProgressCalculator.serializeCompactedMeta(
                                                    totalEpisodes = guide.totalEpisodesOverride.coerceAtLeast(1),
                                                    completedSeasons = emptyMap(),
                                                    episodes = emptyList(),
                                                    countOnly = true
                                                )
                                            } else {
                                                TvTimeProgressCalculator.serializeEpisodeList(finalEps)
                                            }
                                            dao.insertShow(
                                                ShowEntity(
                                                    userId = currentUserId,
                                                    externalId = item.externalId,
                                                    tvmazeId = item.tvmazeId,
                                                    tmdbId = item.tmdbId,
                                                    title = item.title,
                                                    year = item.year,
                                                    network = guide.networkOverride.ifBlank { cleanStreamingLabel },
                                                    status = guide.statusOverride.ifBlank { item.status },
                                                    runtimeMins = guide.runtimeMinsOverride.takeIf { it > 0 } ?: item.runtimeMins,
                                                    genresCsv = item.genresCsv,
                                                    castCsv = guide.cast.joinToString(", "),
                                                    rating = item.rating,
                                                    posterUrl = item.posterUrl.ifBlank { guide.posterOverride },
                                                    overview = item.overview.ifBlank { guide.overviewOverride },
                                                    userStatus = if (isCompleted) "completed" else "interested",
                                                    watchedCodesCsv = watchedCsv,
                                                    episodesJson = epsJson
                                                )
                                            )
                                        }
                                    }
                                    val msg = if (isCompleted) "Marked ${item.title} completed ✓" else "Added ${item.title} to Watchlist"
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                                FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                            }
                        }
                    )
                }

                "stats" -> {
                    StatisticsFullPageScreen(
                        currentUserId = currentUserId,
                        shows = shows,
                        movies = movies,
                        onOpenFriends = { switchTab("friends") },
                        onBack = navigateBack
                    )
                }

                "recent" -> {
                    RecentlyWatchedFullPageScreen(
                        shows = shows,
                        movies = movies,
                        onOpenShow = { selectedShowForDetail = it },
                        onUnwatchShow = { show ->
                            scope.launch {
                                dao.deleteShow(show.id)
                                FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                            }
                        },
                        onOpenMovie = { selectedMovieForDetail = it },
                        onBack = navigateBack
                    )
                }

                "import" -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                onClick = navigateBack,
                                shape = CircleShape,
                                color = TvTimePalette.SurfacePill,
                                border = BorderStroke(1.dp, TvTimePalette.Border),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("←", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "⇅ Import / Export CSV & JSON",
                                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                                    color = Color.White
                                )
                                Text(
                                    text = "Backup or restore your entire TV Time library across devices.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TvTimePalette.TextSecondary
                                )
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = TvTimePalette.SurfaceCard,
                            border = BorderStroke(1.dp, TvTimePalette.Border),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = { showImportDialog = true },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = TvTimePalette.Yellow,
                                        contentColor = TvTimePalette.OnYellow
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Open Export / Import Options (.csv & .json)", fontWeight = FontWeight.ExtraBold)
                                }
                            }
                        }
                    }
                }

                "settings" -> {
                    SettingsFullPageScreen(
                        currentUserId = currentUserId,
                        onOpenImport = {
                            switchTab("import")
                            showImportDialog = true
                        },
                        onOpenAccountSwitch = { showAccountSwitcherDialog = true },
                        onSyncNow = {
                            scope.launch {
                                FirestoreSyncService.pullFromFirestoreIfNeeded(dao, currentUserId, force = true)
                                val ok = FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                Toast.makeText(
                                    context,
                                    if (ok) "Synced with Cloud Firestore" else "Saved locally",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onBack = navigateBack
                    )
                }

                "friends" -> {
                    FriendsFullPageScreen(
                        currentUserId = currentUserId,
                        myShows = shows,
                        myMovies = movies,
                        dao = dao,
                        onOpenCatalogItem = handleAddOrOpenCatalogItem,
                        onBack = navigateBack
                    )
                }

                "categories" -> {
                    CategoriesFullPageScreen(
                        shows = shows,
                        movies = movies,
                        onAddOrOpenResult = handleAddOrOpenCatalogItem,
                        onQuickWatchlistToggle = handleQuickWatchlistCatalogItem,
                        onQuickCompletedToggle = handleQuickCompletedCatalogItem,
                        onBack = navigateBack
                    )
                }
            }

            // Pull-to-Refresh Top Floating Pill Indicator (isolated via derivedStateOf so drag pixels never recompose root)
            if (isPullVisible || isRefreshing) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = TvTimePalette.SurfacePill,
                    border = BorderStroke(1.dp, TvTimePalette.Yellow.copy(alpha = 0.5f)),
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                ) {
                    Text(
                        text = when {
                            isRefreshing -> "↻ Refreshing..."
                            isPullReadyToRelease -> "↻ Release to refresh"
                            else -> "↻ Pull down to refresh"
                        },
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
                        color = TvTimePalette.Yellow,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }

    // TV Time Left Navigation Drawer Overlay
    if (showHamburgerSheet) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Backdrop Scrim
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.75f))
                    .clickable { showHamburgerSheet = false }
            )

            // Left Navigation Drawer Panel (TV Time Theme)
            Surface(
                color = TvTimePalette.Background,
                border = BorderStroke(1.dp, TvTimePalette.Border),
                modifier = Modifier
                    .fillMaxHeight()
                    .width(290.dp)
                    .align(Alignment.CenterStart)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                    ) {
                        // Top Brand & Account Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(TvTimePalette.SurfaceCard)
                                .padding(horizontal = 18.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = TvTimePalette.Yellow,
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "▶",
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Black,
                                            color = TvTimePalette.OnYellow
                                        )
                                    }
                                }
                                Column {
                                    Text(
                                        text = "TV TIME",
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = FontWeight.Black,
                                            letterSpacing = 1.5.sp
                                        ),
                                        color = TvTimePalette.Yellow
                                    )
                                    Text(
                                        text = currentUserId,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TvTimePalette.TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Surface(
                                onClick = { showHamburgerSheet = false },
                                shape = CircleShape,
                                color = TvTimePalette.SurfacePill,
                                border = BorderStroke(1.dp, TvTimePalette.Border),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("✕", color = TvTimePalette.TextSecondary, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        HorizontalDivider(color = TvTimePalette.Border)

                        // HOME Section
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "HOME",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.2.sp
                                ),
                                color = TvTimePalette.TextSecondary,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            LeftDrawerItemRow(icon = "⌂", label = "Home") {
                                showHamburgerSheet = false
                                switchTab("home")
                            }
                            LeftDrawerItemRow(icon = "👥", label = "Friends") {
                                showHamburgerSheet = false
                                switchTab("friends")
                            }
                            LeftDrawerItemRow(icon = "🗂️", label = "Categories") {
                                showHamburgerSheet = false
                                switchTab("categories")
                            }
                        }

                        HorizontalDivider(color = TvTimePalette.Border)

                        // MY STATISTICS Section
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "MY STATISTICS",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.2.sp
                                ),
                                color = TvTimePalette.TextSecondary,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            LeftDrawerItemRow(icon = "◷", label = "Recently Watched") {
                                showHamburgerSheet = false
                                switchTab("recent")
                            }
                            LeftDrawerItemRow(icon = "▤", label = "Statistics") {
                                showHamburgerSheet = false
                                switchTab("stats")
                            }
                        }

                        HorizontalDivider(color = TvTimePalette.Border)

                        // TOOLS & SETTINGS Section
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "TOOLS & SETTINGS",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.2.sp
                                ),
                                color = TvTimePalette.TextSecondary,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            LeftDrawerItemRow(icon = "⇅", label = "Import / Export CSV") {
                                showHamburgerSheet = false
                                switchTab("import")
                                showImportDialog = true
                            }
                            LeftDrawerItemRow(icon = "⚙", label = "Settings") {
                                showHamburgerSheet = false
                                switchTab("settings")
                            }
                        }
                    }

                    // Footer
                    Column {
                        HorizontalDivider(color = TvTimePalette.Border)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "TV Time",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = TvTimePalette.TextSecondary
                            )
                            Text(
                                text = "Tracker",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = TvTimePalette.Yellow
                            )
                        }
                    }
                }
            }
        }
    }

    // Show Detail Bottom Sheet
    selectedShowForDetail?.let { activeShow ->
        val latestFromList = shows.firstOrNull {
            (activeShow.id != 0L && it.id == activeShow.id) ||
                it.externalId == activeShow.externalId
        } ?: activeShow
        ShowDetailBottomSheet(
            show = latestFromList,
            dao = dao,
            currentUserId = currentUserId,
            onDismiss = { selectedShowForDetail = null }
        )
    }

    // Movie Detail Bottom Sheet
    selectedMovieForDetail?.let { activeMovie ->
        val latestMovie = movies.firstOrNull {
            (activeMovie.id != 0L && it.id == activeMovie.id) ||
                it.externalId == activeMovie.externalId
        } ?: activeMovie
        MovieDetailBottomSheet(
            movie = latestMovie,
            dao = dao,
            currentUserId = currentUserId,
            onDismiss = { selectedMovieForDetail = null }
        )
    }

    val exportJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val jsonText = FirestoreSyncService.exportUserStateToJson(dao, currentUserId)
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(jsonText.toByteArray(Charsets.UTF_8))
                    }
                    Toast.makeText(context, "Exported TV Time library backup (.json)!", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val csvText = FirestoreSyncService.exportUserStateToCsv(dao, currentUserId)
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(csvText.toByteArray(Charsets.UTF_8))
                    }
                    Toast.makeText(context, "Exported TV Time spreadsheet (.csv)!", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, "CSV export failed: ${it.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val importJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val rawText = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                        it.readText()
                    } ?: ""
                    val count = FirestoreSyncService.importUserStateFromJson(dao, currentUserId, rawText)
                    if (count > 0) {
                        Toast.makeText(context, "Imported $count items & synced to cloud!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "No valid TV Time items found in file.", Toast.LENGTH_SHORT).show()
                    }
                }.onFailure {
                    Toast.makeText(context, "Import failed: ${it.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // TV Time History Export & Import Dialog
    if (showImportDialog) {
        TvTimeImportHistoryDialog(
            onDismiss = { showImportDialog = false },
            onExportJson = {
                showImportDialog = false
                val cleanUser = FirestoreSyncService.sanitizeUserId(currentUserId).substringBefore("@")
                exportJsonLauncher.launch("tvtime-backup-$cleanUser.json")
            },
            onExportCsv = {
                showImportDialog = false
                val cleanUser = FirestoreSyncService.sanitizeUserId(currentUserId).substringBefore("@")
                exportCsvLauncher.launch("tvtime-library-$cleanUser.csv")
            },
            onChooseImportFile = {
                showImportDialog = false
                importJsonLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
            }
        )
    }

    // 2-User Account & Google Sign-In Gate Dialog (Matches PackTrack & PlacesTracker)
    if (showAccountSwitcherDialog) {
        var errorText by remember { mutableStateOf("") }
        val isInitialPrompt = !FirestoreSyncService.isUserAllowed(currentUserId)

        val googleLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                val account = task.getResult(ApiException::class.java)
                val email = account?.email?.trim()?.lowercase() ?: ""
                if (email.isBlank() || !FirestoreSyncService.isUserAllowed(email)) {
                    errorText = if (email.isBlank()) {
                        "Google Sign-In did not return an email address."
                    } else {
                        "Access denied for $email. Only allowlisted Google accounts are permitted."
                    }
                    return@rememberLauncherForActivityResult
                }
                val clean = FirestoreSyncService.sanitizeUserId(email)
                currentUserId = clean
                onSwitchUser(clean)
                showAccountSwitcherDialog = false
                scope.launch {
                    FirestoreSyncService.pullFromFirestoreIfNeeded(dao, clean)
                }
            } catch (e: ApiException) {
                if (e.statusCode != 12501) {
                    errorText = "Google Sign-In failed (code ${e.statusCode}). Make sure your SHA-1 fingerprint is registered in Firebase."
                }
            }
        }

        AlertDialog(
            onDismissRequest = {
                if (!isInitialPrompt) {
                    showAccountSwitcherDialog = false
                }
            },
            containerColor = Color(0xFF18181D),
            title = {
                Text(
                    if (isInitialPrompt) "Sign in to TV Time" else "Switch Google Account",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Sign in with your allowlisted Google account to access and sync your shows & movies:",
                        style = MaterialTheme.typography.bodySmall,
                        color = TvTimePalette.TextSecondary
                    )
                    if (errorText.isNotEmpty()) {
                        Text(
                            text = errorText,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFF87171)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        errorText = ""
                        val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                            .requestEmail()
                        val webClientId = BuildConfig.GOOGLE_CLIENT_ID.trim()
                        if (webClientId.isNotEmpty()) {
                            gsoBuilder.requestIdToken(webClientId)
                        }
                        val client = GoogleSignIn.getClient(context, gsoBuilder.build())
                        client.signOut().addOnCompleteListener {
                            googleLauncher.launch(client.signInIntent)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TvTimePalette.Yellow,
                        contentColor = TvTimePalette.OnYellow
                    )
                ) {
                    Text("Sign in with Google", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                if (!isInitialPrompt) {
                    TextButton(onClick = { showAccountSwitcherDialog = false }) {
                        Text("Cancel", color = Color.White)
                    }
                }
            }
        )
    }
}


@Composable
private fun LeftDrawerItemRow(
    icon: String,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = TvTimePalette.SurfacePill,
                border = BorderStroke(1.dp, TvTimePalette.Border),
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = icon,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = TvTimePalette.Yellow
                    )
                }
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White
            )
        }
    }
}

@Composable
private fun TvFilterDropdown(
    prefixLabel: String,
    selectedKey: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val activeLabel = options.firstOrNull { it.first == selectedKey }?.second
        ?: options.firstOrNull()?.second.orEmpty()

    Box(modifier = modifier) {
        Surface(
            onClick = { expanded = !expanded },
            shape = RoundedCornerShape(50),
            color = TvTimePalette.SurfacePill,
            border = BorderStroke(1.dp, TvTimePalette.Border)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                if (prefixLabel.isNotBlank()) {
                    Text(
                        text = "$prefixLabel:",
                        style = MaterialTheme.typography.labelSmall,
                        color = TvTimePalette.TextSecondary,
                        maxLines = 1
                    )
                }
                Text(
                    text = "$activeLabel ▾",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = TvTimePalette.Yellow,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            ),
            modifier = Modifier.background(Color(0xFF18181D))
        ) {
            options.forEach { (key, label) ->
                val isSelected = key == selectedKey
                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) TvTimePalette.Yellow else Color.White,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                            if (isSelected) {
                                Text(
                                    text = "✓",
                                    color = TvTimePalette.Yellow,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier.padding(start = 12.dp)
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(key)
                    }
                )
            }
        }
    }
}

@Composable
private fun UsStreamingPlatformFilterBar(
    selectedPlatforms: Set<String>,
    onTogglePlatform: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val hasSelection = selectedPlatforms.isNotEmpty()
    val summaryText = when {
        !hasSelection -> "Streaming ▾"
        selectedPlatforms.size == 1 -> "${selectedPlatforms.first()} ▾"
        else -> "Streaming (${selectedPlatforms.size}) ▾"
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(modifier = Modifier.weight(1f, fill = false)) {
            Surface(
                onClick = { expanded = !expanded },
                shape = RoundedCornerShape(50),
                color = TvTimePalette.SurfacePill,
                border = BorderStroke(
                    1.dp,
                    if (hasSelection) TvTimePalette.Yellow else TvTimePalette.Border
                )
            ) {
                Text(
                    text = summaryText,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (hasSelection) TvTimePalette.Yellow else Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp)
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true
                ),
                modifier = Modifier.background(Color(0xFF18181D))
            ) {
                if (hasSelection) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "✕ Remove filter",
                                color = Color(0xFFF87171),
                                fontWeight = FontWeight.Bold
                            )
                        },
                        onClick = {
                            onTogglePlatform("clear")
                            expanded = false
                        }
                    )
                    HorizontalDivider(color = TvTimePalette.Border)
                }

                LiveMediaCatalogService.US_PLATFORMS.forEach { platform ->
                    val active = selectedPlatforms.contains(platform)
                    DropdownMenuItem(
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = platform,
                                    color = if (active) TvTimePalette.Yellow else Color.White,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                                )
                                if (active) {
                                    Text(
                                        text = "✓",
                                        color = TvTimePalette.Yellow,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.padding(start = 12.dp)
                                    )
                                }
                            }
                        },
                        onClick = {
                            onTogglePlatform(platform)
                            expanded = false
                        }
                    )
                }
            }
        }

        if (hasSelection) {
            Surface(
                onClick = { onTogglePlatform("clear") },
                shape = CircleShape,
                color = Color(0x26EF4444),
                border = BorderStroke(1.dp, Color(0x66EF4444)),
                modifier = Modifier.size(30.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "✕",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFFF87171)
                    )
                }
            }
        }
    }
}


@Composable
private fun HomeTabScreen(
    shows: List<ShowEntity>,
    movies: List<MovieEntity>,
    topSegment: String,
    onSelectTopSegment: (String) -> Unit,
    mediaFilter: String,
    onSelectMediaFilter: (String) -> Unit,
    selectedUsPlatforms: Set<String>,
    onToggleUsPlatform: (String) -> Unit,
    onOpenShow: (ShowEntity) -> Unit,
    onOpenMovie: (MovieEntity) -> Unit,
    onMarkNextEpisode: (ShowEntity) -> Unit,
    onToggleMovieWatched: (MovieEntity) -> Unit
) {
    val platformFilteredShows = remember(shows, selectedUsPlatforms) {
        if (selectedUsPlatforms.isEmpty()) shows
        else shows.filter { LiveMediaCatalogService.matchesUsPlatforms(it.network, selectedUsPlatforms) }
    }
    val platformFilteredMovies = remember(movies, selectedUsPlatforms) {
        if (selectedUsPlatforms.isEmpty()) movies
        else movies.filter { LiveMediaCatalogService.matchesUsPlatforms(it.streamingService.ifBlank { it.director }, selectedUsPlatforms) }
    }
    val partitionedShowsByStatus = remember(platformFilteredShows) {
        val inProg = ArrayList<ShowEntity>()
        val watchlist = ArrayList<ShowEntity>()
        val comp = ArrayList<ShowEntity>()
        val stop = ArrayList<ShowEntity>()
        for (s in platformFilteredShows) {
            val prog = TvTimeProgressCalculator.computeShowProgressFromJson(
                s.episodesJson,
                s.watchedCodesCsv,
                s.userStatus
            )
            when (TvTimeProgressCalculator.deriveEffectiveShowStatus(prog, s.userStatus)) {
                "completed" -> comp.add(s)
                "interested" -> watchlist.add(s)
                "stopped" -> stop.add(s)
                else -> inProg.add(s)
            }
        }
        mapOf(
            "in_progress" to inProg,
            "interested" to watchlist,
            "completed" to comp,
            "stopped" to stop
        )
    }
    val watchedPlatformMovies = remember(platformFilteredMovies) { platformFilteredMovies.filter { it.watched } }
    val watchlistPlatformMovies = remember(platformFilteredMovies) { platformFilteredMovies.filter { !it.watched } }
    val memoizedUpcomingEntries = remember(platformFilteredShows, topSegment) {
        if (topSegment != "upcoming") {
            emptyList()
        } else {
            platformFilteredShows
                .filter { it.userStatus != "stopped" }
                .mapNotNull { show ->
                    val eps = TvTimeProgressCalculator.parseEpisodeList(show.episodesJson)
                    val found = TvTimeProgressCalculator.findNextUpcomingEpisode(eps) ?: return@mapNotNull null
                    val (nextEp, daysUntil) = found
                    val bucket = TvTimeProgressCalculator.formatRelativeCountdownBucket(daysUntil)
                    Triple(show, nextEp to daysUntil, bucket)
                }
                .sortedWith(
                    compareBy<Triple<ShowEntity, Pair<EpisodeModel, Long>, String>> { it.second.second }
                        .thenBy { it.second.first.airDate }
                        .thenBy { it.first.title }
                )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Top Segmented Pill Bar: Shows | Movies | Upcoming
        item(key = "top_segmented_bar") {
            Surface(
                shape = RoundedCornerShape(50),
                color = TvTimePalette.SurfacePill,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(5.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        "shows" to "Shows",
                        "movies" to "Movies",
                        "upcoming" to "Upcoming"
                    ).forEach { (key, label) ->
                        val active = topSegment == key
                        Surface(
                            onClick = { onSelectTopSegment(key) },
                            shape = RoundedCornerShape(50),
                            color = if (active) TvTimePalette.Yellow else Color.Transparent,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 14.sp),
                                    color = if (active) TvTimePalette.OnYellow else TvTimePalette.TextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Dropdown Filter Row (Status Dropdown + US Streaming Platform Dropdown)
        item(key = "subfilter_row") {
            if (topSegment == "shows") {
                val showStatusOptions = listOf(
                    "in_progress" to "In progress",
                    "interested" to "Watchlist",
                    "completed" to "Completed",
                    "stopped" to "Stopped"
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TvFilterDropdown(
                        prefixLabel = "",
                        selectedKey = mediaFilter,
                        options = showStatusOptions,
                        onSelect = onSelectMediaFilter
                    )
                    UsStreamingPlatformFilterBar(
                        selectedPlatforms = selectedUsPlatforms,
                        onTogglePlatform = onToggleUsPlatform
                    )
                }
            } else if (topSegment == "movies") {
                val movieStatusOptions = listOf(
                    "interested" to "Watchlist",
                    "completed" to "Watched"
                )
                val effectiveMovieFilter = if (mediaFilter == "completed") "completed" else "interested"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TvFilterDropdown(
                        prefixLabel = "",
                        selectedKey = effectiveMovieFilter,
                        options = movieStatusOptions,
                        onSelect = onSelectMediaFilter
                    )
                    UsStreamingPlatformFilterBar(
                        selectedPlatforms = selectedUsPlatforms,
                        onTogglePlatform = onToggleUsPlatform
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Upcoming Episodes",
                        style = MaterialTheme.typography.titleMedium,
                        color = TvTimePalette.TextSecondary,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }

        // 3. Main Content List
        if (topSegment == "shows") {
            val activeShowsList = partitionedShowsByStatus[mediaFilter]
                ?: partitionedShowsByStatus["in_progress"].orEmpty()

            if (activeShowsList.isEmpty()) {
                item(key = "empty_shows_state") {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = TvTimePalette.SurfaceCard,
                        border = BorderStroke(1.dp, TvTimePalette.Border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "📺 No series match this filter",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = if (selectedUsPlatforms.isNotEmpty()) {
                                    "Try clearing your Streaming filter or switching status tabs."
                                } else {
                                    "Search for a TV show or swipe through For You to add series."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = TvTimePalette.TextSecondary,
                                textAlign = TextAlign.Center
                            )
                            if (selectedUsPlatforms.isNotEmpty()) {
                                TextButton(onClick = { onToggleUsPlatform("clear") }) {
                                    Text("✕ Clear Streaming Filter", color = TvTimePalette.Yellow, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            } else {
                items(activeShowsList, key = { it.id }) { show ->
                    TvShowWatchCard(
                        show = show,
                        onCardClick = { onOpenShow(show) },
                        onEyeClick = { onMarkNextEpisode(show) }
                    )
                }
            }
        } else if (topSegment == "movies") {
            val filteredMovies = if (mediaFilter == "completed") {
                watchedPlatformMovies
            } else {
                watchlistPlatformMovies
            }
            if (filteredMovies.isEmpty()) {
                item(key = "empty_movies_state") {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = TvTimePalette.SurfaceCard,
                        border = BorderStroke(1.dp, TvTimePalette.Border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "🎬 No movies match this filter",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = if (selectedUsPlatforms.isNotEmpty()) {
                                    "Try clearing your Streaming filter or switching between Watchlist and Watched."
                                } else {
                                    "Search for a movie or swipe through For You to add movies."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = TvTimePalette.TextSecondary,
                                textAlign = TextAlign.Center
                            )
                            if (selectedUsPlatforms.isNotEmpty()) {
                                TextButton(onClick = { onToggleUsPlatform("clear") }) {
                                    Text("✕ Clear Streaming Filter", color = TvTimePalette.Yellow, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            } else {
                items(filteredMovies, key = { it.id }) { movie ->
                    TvMovieWatchCard(
                        movie = movie,
                        onCardClick = { onOpenMovie(movie) },
                        onToggleWatched = { onToggleMovieWatched(movie) }
                    )
                }
            }
        } else {
            // Upcoming Segment: Strictly future episodes (airDate >= today), sorted chronologically
            val upcomingEntries = memoizedUpcomingEntries

            if (upcomingEntries.isEmpty()) {
                item(key = "empty_upcoming_state") {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = TvTimePalette.SurfaceCard,
                        border = BorderStroke(1.dp, TvTimePalette.Border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "📅 No upcoming episodes scheduled",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "None of your tracked series have announced future air dates right now. New episodes will appear here automatically once their release dates are announced.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TvTimePalette.TextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                itemsIndexed(upcomingEntries, key = { _, triple -> "up_${triple.first.id}" }) { index, triple ->
                    val show = triple.first
                    val nextEp = triple.second.first
                    val bucket = triple.third
                    val prevBucket = upcomingEntries.getOrNull(index - 1)?.third
                    val showBucketHeader = index == 0 || bucket != prevBucket

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (showBucketHeader) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                HorizontalDivider(modifier = Modifier.weight(1f), color = TvTimePalette.Border)
                                Text(
                                    text = bucket,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TvTimePalette.TextSecondary
                                )
                                HorizontalDivider(modifier = Modifier.weight(1f), color = TvTimePalette.Border)
                            }
                        }

                        Surface(
                            onClick = { onOpenShow(show) },
                            shape = RoundedCornerShape(20.dp),
                            color = TvTimePalette.SurfaceCard,
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                NetworkPosterImage(url = show.posterUrl, title = show.title)
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Text(
                                            text = show.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = Color(0xFF27272F)
                                        ) {
                                            Text(
                                                text = nextEp.airDate,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TvTimePalette.TextSecondary,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = "TV Show",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TvTimePalette.Yellow,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${nextEp.formattedDotCode} — ${nextEp.title}",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                        color = TvTimePalette.TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun SearchTabScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    sortKey: String,
    onSortChange: (String) -> Unit,
    selectedUsPlatforms: Set<String>,
    onToggleUsPlatform: (String) -> Unit,
    results: List<SearchResultItem>,
    isSearching: Boolean,
    hasMoreResults: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    shows: List<ShowEntity>,
    movies: List<MovieEntity>,
    onAddOrOpenResult: (SearchResultItem) -> Unit,
    onQuickWatchlistToggle: (SearchResultItem) -> Unit,
    onQuickCompletedToggle: (SearchResultItem) -> Unit
) {
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    val currentOnAddOrOpenResult by rememberUpdatedState(onAddOrOpenResult)
    val currentOnQuickWatchlistToggle by rememberUpdatedState(onQuickWatchlistToggle)
    val currentOnQuickCompletedToggle by rememberUpdatedState(onQuickCompletedToggle)
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    val sortedResults = remember(results, sortKey) {
        when (sortKey) {
            "newest" -> results.sortedByDescending { it.year }
            "oldest" -> results.sortedBy { it.year }
            else -> results
        }
    }
    // Pre-index library movies & shows once so scrolling Search cards does O(1) lookups instead of scanning 900+ items per row
    val searchMoviesByExt = remember(movies) { movies.filter { it.externalId.isNotBlank() }.associateBy { it.externalId } }
    val searchMoviesByTmdb = remember(movies) {
        val map = HashMap<Int, MovieEntity>(movies.size * 2 + 1)
        for (m in movies) {
            val ct = TvTimeProgressCalculator.cleanMovieTmdbId(m.externalId, m.tmdbId)
            if (ct > 0) map.putIfAbsent(ct, m)
        }
        map
    }
    val searchMoviesByTitle = remember(movies) { movies.groupBy { m -> TvTimeProgressCalculator.normalizeRecTitle(m.title) } }
    val searchShowsIndex = remember(shows) {
        val byExt = HashMap<String, Triple<ShowEntity, String, Int>>(shows.size * 2 + 1)
        val byTmdb = HashMap<Int, Triple<ShowEntity, String, Int>>(shows.size * 2 + 1)
        val byMaze = HashMap<Int, Triple<ShowEntity, String, Int>>(shows.size * 2 + 1)
        val byTitle = HashMap<String, MutableList<Triple<ShowEntity, String, Int>>>(shows.size * 2 + 1)
        for (s in shows) {
            val prog = TvTimeProgressCalculator.computeShowProgressFromJson(s.episodesJson, s.watchedCodesCsv, s.userStatus)
            val eff = TvTimeProgressCalculator.deriveEffectiveShowStatus(prog, s.userStatus)
            val entry = Triple(s, eff, prog.watchedCount)
            if (s.externalId.isNotBlank()) byExt.putIfAbsent(s.externalId, entry)
            if (s.tmdbId > 0) byTmdb.putIfAbsent(s.tmdbId, entry)
            if (s.tvmazeId > 0) byMaze.putIfAbsent(s.tvmazeId, entry)
            val nt = TvTimeProgressCalculator.normalizeRecTitle(s.title)
            if (nt.isNotEmpty()) byTitle.getOrPut(nt) { mutableListOf() }.add(entry)
        }
        Triple(byExt, byTmdb to byMaze, byTitle)
    }

    val shouldLoadMore by remember {
        androidx.compose.runtime.derivedStateOf {
            val totalItems = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 2 && lastVisible >= totalItems - 5
        }
    }

    LaunchedEffect(shouldLoadMore, hasMoreResults, isSearching, isLoadingMore, sortedResults.size) {
        if (shouldLoadMore && hasMoreResults && !isSearching && !isLoadingMore) {
            currentOnLoadMore()
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Pill Search Bar
        item(key = "search_bar") {
            Surface(
                shape = RoundedCornerShape(50),
                color = TvTimePalette.SurfacePill,
                border = BorderStroke(1.dp, TvTimePalette.Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = TvTimePalette.TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    TextField(
                        value = query,
                        onValueChange = onQueryChange,
                        placeholder = {
                            Text(
                                "Search TV shows or movies...",
                                color = TvTimePalette.TextSecondary,
                                fontSize = 14.sp
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = TvTimePalette.Yellow
                            )
                        }
                    }
                }
            }
        }

        // 2. Dropdown Filter Row: US Streaming Platform Dropdown + Sort Dropdown
        item(key = "search_sort_row") {
            val sortOptions = listOf(
                "top" to "Top",
                "newest" to "Newest",
                "oldest" to "Oldest"
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    UsStreamingPlatformFilterBar(
                        selectedPlatforms = selectedUsPlatforms,
                        onTogglePlatform = onToggleUsPlatform
                    )
                    TvFilterDropdown(
                        prefixLabel = "",
                        selectedKey = sortKey,
                        options = sortOptions,
                        onSelect = onSortChange
                    )
                }
                Text(
                    text = if (isSearching) "Searching..." else "${sortedResults.size}${if (hasMoreResults) "+" else ""} results",
                    style = MaterialTheme.typography.bodySmall,
                    color = TvTimePalette.TextSecondary
                )
            }
        }

        // 3. Result Cards (ID-strict + year matching so searching "Dad" only ticks the exact matching entry)
        items(sortedResults, key = { it.externalId }) { item ->
            val isMovieItem = item.mediaType.equals("movie", ignoreCase = true) || item.externalId.startsWith("movie_")
            var isInWatchlist = false
            var isCompleted = false
            var canShowWatchlist = true

            if (isMovieItem) {
                val cleanTmdbId = TvTimeProgressCalculator.cleanMovieTmdbId(item.externalId, item.tmdbId)
                val matchedMovie = searchMoviesByExt[item.externalId]
                    ?: (if (cleanTmdbId > 0) searchMoviesByTmdb[cleanTmdbId] else null)
                    ?: searchMoviesByTitle[TvTimeProgressCalculator.normalizeRecTitle(item.title)]?.firstOrNull { m ->
                        TvTimeProgressCalculator.doesMovieMatch(
                            m.externalId, m.tmdbId, m.title, m.year,
                            item.externalId, cleanTmdbId, item.title, item.year
                        )
                    }
                if (matchedMovie != null) {
                    isInWatchlist = !matchedMovie.watched
                    isCompleted = matchedMovie.watched
                    canShowWatchlist = !matchedMovie.watched
                }
            } else {
                val (byExt, tmdbAndMaze, byTitle) = searchShowsIndex
                val (byTmdb, byMaze) = tmdbAndMaze
                val matchedEntry = byExt[item.externalId]
                    ?: (if (item.tmdbId > 0) byTmdb[item.tmdbId] else null)
                    ?: (if (item.tvmazeId > 0) byMaze[item.tvmazeId] else null)
                    ?: byTitle[TvTimeProgressCalculator.normalizeRecTitle(item.title)]?.firstOrNull { (s, _, _) ->
                        TvTimeProgressCalculator.doesShowMatch(
                            s.externalId, s.tmdbId, s.tvmazeId, s.title, s.year,
                            item.externalId, item.tmdbId, item.tvmazeId, item.title, item.year
                        )
                    }
                if (matchedEntry != null) {
                    val (_, eff, watchedCnt) = matchedEntry
                    isInWatchlist = eff == "interested"
                    isCompleted = eff == "completed"
                    canShowWatchlist = watchedCnt == 0 && eff != "completed"
                }
            }

            TvSearchResultCard(
                item = item,
                isInWatchlist = isInWatchlist,
                isCompleted = isCompleted,
                canShowWatchlist = canShowWatchlist,
                onCardClick = { currentOnAddOrOpenResult(item) },
                onWatchlistClick = { currentOnQuickWatchlistToggle(item) },
                onCompletedClick = { currentOnQuickCompletedToggle(item) }
            )
        }

        if (isLoadingMore) {
            item(key = "search_loading_more") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Loading more...",
                        style = MaterialTheme.typography.bodySmall,
                        color = TvTimePalette.TextSecondary
                    )
                }
            }
        }
    }
}


@Composable
private fun SwipeRecommendationsTabScreen(
    shows: List<ShowEntity>,
    movies: List<MovieEntity>,
    decidedIds: Set<String>,
    swipeFilter: String,
    onSelectSwipeFilter: (String) -> Unit,
    onOpenItem: (SearchResultItem) -> Unit,
    onSwipeDecision: (SearchResultItem, String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var rawDeck by remember { mutableStateOf<List<SearchResultItem>>(emptyList()) }
    var deckWasSeeded by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var currentPage by remember { mutableStateOf(1) }
    var localDismissedIds by remember { mutableStateOf(emptySet<String>()) }

    val loadMore: (Int, Boolean) -> Unit = { pageToFetch, resetExisting ->
        if (!isLoading) {
            isLoading = true
            val hasSeedsNow = shows.isNotEmpty() || movies.isNotEmpty()
            scope.launch {
                val fetched = LiveMediaCatalogService.fetchRecommendations(
                    shows = shows,
                    movies = movies,
                    page = pageToFetch
                )
                val baseList = if (resetExisting) emptyList() else rawDeck
                val merged = baseList.toMutableList()
                for (cand in fetched) {
                    val isDup = merged.any { ex ->
                        ex.externalId == cand.externalId ||
                            (ex.mediaType == "movie" && cand.mediaType == "movie" && TvTimeProgressCalculator.doesRecMatchMovie(
                                ex.externalId, ex.tmdbId, ex.title, ex.year,
                                cand.externalId, cand.tmdbId, cand.title, cand.year
                            )) ||
                            (ex.mediaType != "movie" && cand.mediaType != "movie" && TvTimeProgressCalculator.doesRecMatchShow(
                                ex.externalId, ex.tmdbId, ex.tvmazeId, ex.title, ex.year,
                                cand.externalId, cand.tmdbId, cand.tvmazeId, cand.title, cand.year
                            ))
                    }
                    if (!isDup) merged.add(cand)
                }
                rawDeck = merged
                if (hasSeedsNow) deckWasSeeded = true
                isLoading = false
            }
        }
    }

    LaunchedEffect(shows.size, movies.size) {
        val hasLibrary = shows.isNotEmpty() || movies.isNotEmpty()
        if (rawDeck.isEmpty() || (hasLibrary && !deckWasSeeded)) {
            currentPage = 1
            loadMore(1, hasLibrary && !deckWasSeeded)
        }
    }

    val filteredDeck by produceState<List<SearchResultItem>>(
        initialValue = emptyList(),
        rawDeck,
        shows,
        movies,
        decidedIds,
        localDismissedIds,
        swipeFilter
    ) {
        value = if (rawDeck.isEmpty()) {
            emptyList()
        } else {
            withContext(Dispatchers.Default) {
                val movByExt = HashSet<String>(movies.size * 2 + 1)
                val movByTmdb = HashSet<Int>(movies.size * 2 + 1)
                val movByTitle = HashMap<String, MutableList<MovieEntity>>(movies.size * 2 + 1)
                for (m in movies) {
                    if (m.externalId.isNotBlank()) movByExt.add(m.externalId)
                    val ct = TvTimeProgressCalculator.cleanMovieTmdbId(m.externalId, m.tmdbId)
                    if (ct > 0) movByTmdb.add(ct)
                    val nt = TvTimeProgressCalculator.normalizeRecTitle(m.title)
                    if (nt.isNotEmpty()) movByTitle.getOrPut(nt) { mutableListOf() }.add(m)
                }
                val showByExt = HashSet<String>(shows.size * 2 + 1)
                val showByTmdb = HashSet<Int>(shows.size * 2 + 1)
                val showByMaze = HashSet<Int>(shows.size * 2 + 1)
                val showByTitle = HashMap<String, MutableList<ShowEntity>>(shows.size * 2 + 1)
                for (s in shows) {
                    if (s.externalId.isNotBlank()) showByExt.add(s.externalId)
                    if (s.tmdbId > 0) showByTmdb.add(s.tmdbId)
                    if (s.tvmazeId > 0) showByMaze.add(s.tvmazeId)
                    val nt = TvTimeProgressCalculator.normalizeRecTitle(s.title)
                    if (nt.isNotEmpty()) showByTitle.getOrPut(nt) { mutableListOf() }.add(s)
                }
                rawDeck.filter { item ->
                    val canonicalId = TvTimeProgressCalculator.canonicalizeDecidedId(
                        item.externalId,
                        item.mediaType,
                        item.tmdbId
                    )
                    if (decidedIds.contains(item.externalId) ||
                        decidedIds.contains(canonicalId) ||
                        localDismissedIds.contains(item.externalId) ||
                        localDismissedIds.contains(canonicalId)
                    ) {
                        return@filter false
                    }
                    val isMovieItem = item.mediaType.equals("movie", ignoreCase = true) || item.externalId.startsWith("movie_")
                    val matchesMediaFilter = when (swipeFilter) {
                        "all" -> true
                        "tv" -> !isMovieItem
                        "movie" -> isMovieItem
                        else -> item.mediaType == swipeFilter
                    }
                    if (!matchesMediaFilter) return@filter false
                    val alreadyInLibrary = if (isMovieItem) {
                        val cleanTmdb = TvTimeProgressCalculator.cleanMovieTmdbId(item.externalId, item.tmdbId)
                        (item.externalId.isNotBlank() && movByExt.contains(item.externalId)) ||
                            (cleanTmdb > 0 && movByTmdb.contains(cleanTmdb)) ||
                            (movByTitle[TvTimeProgressCalculator.normalizeRecTitle(item.title)]?.any { m ->
                                TvTimeProgressCalculator.doesRecMatchMovie(
                                    m.externalId, m.tmdbId, m.title, m.year,
                                    item.externalId, cleanTmdb, item.title, item.year
                                )
                            } == true)
                    } else {
                        (item.externalId.isNotBlank() && showByExt.contains(item.externalId)) ||
                            (item.tmdbId > 0 && showByTmdb.contains(item.tmdbId)) ||
                            (item.tvmazeId > 0 && showByMaze.contains(item.tvmazeId)) ||
                            (showByTitle[TvTimeProgressCalculator.normalizeRecTitle(item.title)]?.any { s ->
                                TvTimeProgressCalculator.doesRecMatchShow(
                                    s.externalId, s.tmdbId, s.tvmazeId, s.title, s.year,
                                    item.externalId, item.tmdbId, item.tvmazeId, item.title, item.year
                                )
                            } == true)
                    }
                    !alreadyInLibrary
                }
            }
        }
    }

    LaunchedEffect(filteredDeck.size) {
        if (filteredDeck.size <= 3 && !isLoading && rawDeck.isNotEmpty() && currentPage < 10) {
            val nextPage = currentPage + 1
            currentPage = nextPage
            loadMore(nextPage, false)
        }
    }

    val topItem = filteredDeck.firstOrNull()
    val nextItem = filteredDeck.getOrNull(1)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header + Filter Dropdown
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "✨ For You",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = Color.White
            )

            TvFilterDropdown(
                prefixLabel = "",
                selectedKey = swipeFilter,
                options = listOf(
                    "all" to "All",
                    "tv" to "Series",
                    "movie" to "Movies"
                ),
                onSelect = onSelectSwipeFilter
            )
        }

        // Main Deck Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading && topItem == null) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = TvTimePalette.SurfaceCard,
                    border = BorderStroke(1.dp, TvTimePalette.Border),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("🍿", fontSize = 42.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Finding recommendations for you...",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Analyzing your watched series & movies",
                            style = MaterialTheme.typography.bodySmall,
                            color = TvTimePalette.TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else if (topItem == null) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = TvTimePalette.SurfaceCard,
                    border = BorderStroke(1.dp, TvTimePalette.Border),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("✨", fontSize = 42.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "You're all caught up!",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Load more recommendations based on your library.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TvTimePalette.TextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = {
                                val next = currentPage + 1
                                currentPage = next
                                loadMore(next, false)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TvTimePalette.Yellow,
                                contentColor = TvTimePalette.OnYellow
                            )
                        ) {
                            Text("↻ Load More Suggestions", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                // Background Next Card Preview
                if (nextItem != null) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = Color(0xFF141418),
                        border = BorderStroke(1.dp, TvTimePalette.Border),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {}
                }

                // Active Draggable Top Card
                var dragX by remember(topItem.externalId) { mutableStateOf(0f) }
                var dragY by remember(topItem.externalId) { mutableStateOf(0f) }

                val handleDecision: (String) -> Unit = { action ->
                    localDismissedIds = localDismissedIds + topItem.externalId
                    onSwipeDecision(topItem, action)
                }

                Surface(
                    onClick = { onOpenItem(topItem) },
                    shape = RoundedCornerShape(28.dp),
                    color = Color(0xFF18181D),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                    modifier = Modifier
                        .fillMaxSize()
                        .offset { IntOffset(dragX.roundToInt(), dragY.roundToInt()) }
                        .graphicsLayer {
                            rotationZ = (dragX * 0.04f).coerceIn(-15f, 15f)
                        }
                        .pointerInput(topItem.externalId) {
                            detectDragGestures(
                                onDragEnd = {
                                    when {
                                        dragX > 180f -> handleDecision("completed")
                                        dragX < -180f -> handleDecision("ignore")
                                        dragY < -160f && kotlin.math.abs(dragX) < 150f -> handleDecision("watchlist")
                                        else -> {
                                            dragX = 0f
                                            dragY = 0f
                                        }
                                    }
                                },
                                onDragCancel = {
                                    dragX = 0f
                                    dragY = 0f
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragX += dragAmount.x
                                    dragY += dragAmount.y
                                }
                            )
                        }
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Poster Banner Area
                            BoxWithConstraints(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(0.58f)
                                    .background(Color(0xFF101014)),
                                contentAlignment = Alignment.Center
                            ) {
                                val posterHeight = (maxHeight * 0.84f).coerceIn(130.dp, 290.dp)
                                val posterWidth = posterHeight * 0.68f
                                NetworkPosterImage(
                                    url = topItem.posterUrl,
                                    title = topItem.title,
                                    width = posterWidth,
                                    height = posterHeight,
                                    cornerRadius = 18.dp
                                )

                                // Top Badges
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .align(Alignment.TopStart)
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = Color.Black.copy(alpha = 0.75f),
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
                                    ) {
                                        Text(
                                            text = if (topItem.mediaType == "movie") "MOVIE" else "TV SERIES",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                                            color = TvTimePalette.Yellow,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                        )
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = Color.Black.copy(alpha = 0.75f),
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
                                    ) {
                                        Text(
                                            text = "★ ${"%.1f".format(topItem.rating)}",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                        )
                                    }
                                }
                            }

                            // Details Area
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(0.42f)
                                    .padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val recommendationReason = listOf(topItem.networkOrDirector, topItem.status)
                                    .firstOrNull {
                                        it.startsWith("Because", ignoreCase = true) ||
                                            it.startsWith("Popular", ignoreCase = true) ||
                                            it.startsWith("Recommended", ignoreCase = true)
                                    }
                                    ?: "Recommended for you"
                                Text(
                                    text = "✨ $recommendationReason",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                                    color = Color(0xFF4ADE80),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = topItem.title,
                                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${topItem.year} · ${topItem.genresCsv} · ${topItem.runtimeMins}m",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TvTimePalette.Yellow,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = topItem.overview,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TvTimePalette.TextSecondary,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Swipe Dynamic Stamp Overlays
                        if (dragX > 60f) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color.Black.copy(alpha = 0.6f),
                                border = BorderStroke(3.dp, Color(0xFF4ADE80)),
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(24.dp)
                            ) {
                                Text(
                                    text = "✓ COMPLETED",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                                    color = Color(0xFF4ADE80),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        } else if (dragX < -60f) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color.Black.copy(alpha = 0.6f),
                                border = BorderStroke(3.dp, Color(0xFFF87171)),
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(24.dp)
                            ) {
                                Text(
                                    text = "✕ IGNORE",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                                    color = Color(0xFFF87171),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        } else if (dragY < -60f) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color.Black.copy(alpha = 0.6f),
                                border = BorderStroke(3.dp, TvTimePalette.Yellow),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 24.dp)
                            ) {
                                Text(
                                    text = "WATCHLIST",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                                    color = TvTimePalette.Yellow,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Bottom 3 Action Buttons: Ignore (Left) | + Watchlist (Up) | Completed (Right)
        if (topItem != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Ignore
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        onClick = {
                            localDismissedIds = localDismissedIds + topItem.externalId
                            onSwipeDecision(topItem, "ignore")
                        },
                        shape = CircleShape,
                        color = Color(0x1FF87171),
                        border = BorderStroke(2.dp, Color(0x99F87171)),
                        modifier = Modifier.size(56.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("✕", fontSize = 22.sp, color = Color(0xFFF87171), fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(
                        text = "Ignore",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = TvTimePalette.TextSecondary
                    )
                }

                // Watchlist
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        onClick = {
                            localDismissedIds = localDismissedIds + topItem.externalId
                            onSwipeDecision(topItem, "watchlist")
                        },
                        shape = CircleShape,
                        color = Color(0x24FACC15),
                        border = BorderStroke(2.dp, TvTimePalette.Yellow),
                        modifier = Modifier.size(64.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            WatchlistBookmarkIcon(
                                tint = TvTimePalette.Yellow,
                                size = 26.dp,
                                filled = true
                            )
                        }
                    }
                    Text(
                        text = "Watchlist",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = TvTimePalette.Yellow
                    )
                }

                // Completed
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        onClick = {
                            localDismissedIds = localDismissedIds + topItem.externalId
                            onSwipeDecision(topItem, "completed")
                        },
                        shape = CircleShape,
                        color = Color(0x2422C55E),
                        border = BorderStroke(2.dp, Color(0xFF4ADE80)),
                        modifier = Modifier.size(56.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("✓", fontSize = 22.sp, color = Color(0xFF4ADE80), fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(
                        text = "Completed",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFF4ADE80)
                    )
                }
            }
        }
    }
}

private data class FriendStatsComparisonSummary(
    val friendStats: WatchStatsSummary,
    val friendWatchedShowsCount: Int,
    val friendWatchedMoviesCount: Int,
    val sharedSeriesCount: Int,
    val sharedMoviesCount: Int
)

@Composable
private fun StatHeadToHeadRow(
    label: String,
    myValueNum: Long,
    friendValueNum: Long,
    myFormatted: String,
    friendFormatted: String,
    mySub: String = "",
    friendSub: String = ""
) {
    val total = (myValueNum + friendValueNum).coerceAtLeast(1L)
    val myFraction = if (myValueNum == 0L && friendValueNum == 0L) {
        0.5f
    } else {
        (myValueNum.toFloat() / total.toFloat()).coerceIn(0.06f, 0.94f)
    }
    val myLeads = myValueNum > friendValueNum
    val friendLeads = friendValueNum > myValueNum

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = TvTimePalette.SurfacePill,
        border = BorderStroke(1.dp, TvTimePalette.Border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    Text(
                        text = myFormatted,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Black),
                        color = if (myLeads) TvTimePalette.Yellow else Color(0xFFE4E4E7),
                        maxLines = 1
                    )
                    if (mySub.isNotEmpty()) {
                        Text(
                            text = mySub,
                            style = MaterialTheme.typography.labelSmall,
                            color = TvTimePalette.TextSecondary,
                            fontSize = 10.sp
                        )
                    }
                }
                Text(
                    text = label.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                    color = TvTimePalette.TextSecondary,
                    textAlign = TextAlign.Center,
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f)
                )
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(
                        text = friendFormatted,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Black),
                        color = if (friendLeads) Color(0xFF34D399) else Color(0xFFE4E4E7),
                        maxLines = 1
                    )
                    if (friendSub.isNotEmpty()) {
                        Text(
                            text = friendSub,
                            style = MaterialTheme.typography.labelSmall,
                            color = TvTimePalette.TextSecondary,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF09090B)),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(myFraction)
                        .fillMaxHeight()
                        .background(TvTimePalette.Yellow)
                )
                Box(
                    modifier = Modifier
                        .weight((1f - myFraction).coerceAtLeast(0.06f))
                        .fillMaxHeight()
                        .background(Color(0xFF34D399))
                )
            }
        }
    }
}

@Composable
private fun StatisticsFullPageScreen(
    currentUserId: String,
    shows: List<ShowEntity>,
    movies: List<MovieEntity>,
    onOpenFriends: () -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var socialState by remember { mutableStateOf(FirestoreSyncService.getCachedSocialState(currentUserId)) }
    var selectedFriendId by rememberSaveable { mutableStateOf("") }
    var friendLibCache by remember { mutableStateOf<Map<String, FriendLibraryData>>(emptyMap()) }
    var isLoadingFriend by remember { mutableStateOf(false) }
    var friendError by remember { mutableStateOf("") }
    var comparisonSummary by remember { mutableStateOf<FriendStatsComparisonSummary?>(null) }

    LaunchedEffect(currentUserId) {
        if (FirestoreSyncService.isUserAllowed(currentUserId)) {
            socialState = FirestoreSyncService.fetchSocialFriendsState(currentUserId)
        }
    }

    val loadCompareFriend: (String, Boolean) -> Unit = { targetFriend, forceRefresh ->
        val clean = targetFriend.trim().lowercase()
        if (clean.isEmpty() || (clean == selectedFriendId && !forceRefresh)) {
            selectedFriendId = ""
            friendError = ""
            comparisonSummary = null
        } else {
            selectedFriendId = clean
            friendError = ""
            if (!forceRefresh && friendLibCache.containsKey(clean)) {
                isLoadingFriend = false
            } else {
                isLoadingFriend = true
                scope.launch {
                    val res = FirestoreSyncService.fetchFriendLibrary(currentUserId, clean)
                    val lib = res.getOrNull()
                    if (res.isSuccess && lib != null) {
                        friendLibCache = friendLibCache + (clean to lib)
                        friendError = ""
                    } else {
                        friendError = res.exceptionOrNull()?.message ?: "Could not load @$clean's stats."
                    }
                    isLoadingFriend = false
                }
            }
        }
    }

    val activeFriendLib = friendLibCache[selectedFriendId]
    LaunchedEffect(selectedFriendId, activeFriendLib, shows, movies) {
        if (selectedFriendId.isEmpty() || activeFriendLib == null) {
            comparisonSummary = null
            return@LaunchedEffect
        }
        comparisonSummary = withContext(Dispatchers.Default) {
            var fEpCount = 0
            var fShowMins = 0
            val fActiveShows = ArrayList<ShowEntity>(activeFriendLib.shows.size)
            for (fs in activeFriendLib.shows) {
                val (wCnt, wMins) = TvTimeProgressCalculator.computeShowWatchMinutesFromJson(
                    episodesJson = fs.episodesJson,
                    watchedCodesCsv = fs.watchedCodesCsv,
                    rawUserStatus = fs.userStatus,
                    defaultRuntimeMins = fs.runtimeMins
                )
                fEpCount += wCnt
                fShowMins += wMins
                val prog = TvTimeProgressCalculator.computeShowProgressFromJson(
                    fs.episodesJson,
                    fs.watchedCodesCsv,
                    fs.userStatus
                )
                val eff = TvTimeProgressCalculator.deriveEffectiveShowStatus(prog, fs.userStatus)
                if (eff == "completed" || eff == "in_progress" || (eff == "stopped" && prog.watchedCount > 0)) {
                    fActiveShows.add(fs)
                }
            }
            var fMovCount = 0
            var fMovMins = 0
            val fWatchedMovies = ArrayList<MovieEntity>(activeFriendLib.movies.size)
            for (fm in activeFriendLib.movies) {
                if (fm.watched) {
                    fMovCount++
                    fMovMins += fm.runtimeMins.coerceAtLeast(1)
                    fWatchedMovies.add(fm)
                }
            }

            // Shared watched series & movies
            val myMovTmdbs = HashSet<Int>(movies.size * 2 + 1)
            val myMovTitles = HashSet<String>(movies.size * 2 + 1)
            for (mm in movies) {
                if (!mm.watched) continue
                val t = TvTimeProgressCalculator.cleanMovieTmdbId(mm.externalId, mm.tmdbId)
                if (t > 0) myMovTmdbs.add(t)
                val nt = TvTimeProgressCalculator.normalizeRecTitle(mm.title)
                if (nt.isNotEmpty()) myMovTitles.add(nt)
            }
            var sharedMovies = 0
            for (fm in fWatchedMovies) {
                val ft = TvTimeProgressCalculator.cleanMovieTmdbId(fm.externalId, fm.tmdbId)
                val fnt = TvTimeProgressCalculator.normalizeRecTitle(fm.title)
                if ((ft > 0 && myMovTmdbs.contains(ft)) || (ft <= 0 && fnt.isNotEmpty() && myMovTitles.contains(fnt))) {
                    sharedMovies++
                }
            }

            val myShowTmdbs = HashSet<Int>(shows.size * 2 + 1)
            val myShowMazes = HashSet<Int>(shows.size * 2 + 1)
            val myShowTitles = HashSet<String>(shows.size * 2 + 1)
            for (ms in shows) {
                val p = TvTimeProgressCalculator.computeShowProgressFromJson(ms.episodesJson, ms.watchedCodesCsv, ms.userStatus)
                val eff = TvTimeProgressCalculator.deriveEffectiveShowStatus(p, ms.userStatus)
                if (!(eff == "completed" || eff == "in_progress" || (eff == "stopped" && p.watchedCount > 0))) continue
                if (ms.tmdbId > 0) myShowTmdbs.add(ms.tmdbId)
                if (ms.tvmazeId > 0) myShowMazes.add(ms.tvmazeId)
                val nt = TvTimeProgressCalculator.normalizeRecTitle(ms.title)
                if (nt.isNotEmpty()) myShowTitles.add(nt)
            }
            var sharedSeries = 0
            for (fs in fActiveShows) {
                val ft = fs.tmdbId
                val fmz = fs.tvmazeId
                val fnt = TvTimeProgressCalculator.normalizeRecTitle(fs.title)
                if ((ft > 0 && myShowTmdbs.contains(ft)) ||
                    (fmz > 0 && myShowMazes.contains(fmz)) ||
                    (ft <= 0 && fmz <= 0 && fnt.isNotEmpty() && myShowTitles.contains(fnt))
                ) {
                    sharedSeries++
                }
            }

            FriendStatsComparisonSummary(
                friendStats = WatchStatsSummary(
                    totalMinutes = fShowMins + fMovMins,
                    showMinutes = fShowMins,
                    movieMinutes = fMovMins,
                    totalEpisodesWatched = fEpCount,
                    totalMoviesWatched = fMovCount,
                    totalSeriesCount = activeFriendLib.shows.size,
                    totalMoviesCount = activeFriendLib.movies.size
                ),
                friendWatchedShowsCount = fActiveShows.size,
                friendWatchedMoviesCount = fMovCount,
                sharedSeriesCount = sharedSeries,
                sharedMoviesCount = sharedMovies
            )
        }
    }

    val stats = remember(shows, movies) {
        var epCount = 0
        var showMins = 0
        for (s in shows) {
            val (watchedCount, watchedMinutes) = TvTimeProgressCalculator.computeShowWatchMinutesFromJson(
                episodesJson = s.episodesJson,
                watchedCodesCsv = s.watchedCodesCsv,
                rawUserStatus = s.userStatus,
                defaultRuntimeMins = s.runtimeMins
            )
            epCount += watchedCount
            showMins += watchedMinutes
        }
        var movieCount = 0
        var movieMins = 0
        for (m in movies) {
            if (m.watched) {
                movieCount += 1
                movieMins += m.runtimeMins.coerceAtLeast(1)
            }
        }
        WatchStatsSummary(
            totalMinutes = showMins + movieMins,
            showMinutes = showMins,
            movieMinutes = movieMins,
            totalEpisodesWatched = epCount,
            totalMoviesWatched = movieCount,
            totalSeriesCount = shows.size,
            totalMoviesCount = movies.size
        )
    }

    val watchedShowsCount = remember(shows) {
        shows.count { s ->
            val prog = TvTimeProgressCalculator.computeShowProgressFromJson(
                s.episodesJson,
                s.watchedCodesCsv,
                s.userStatus
            )
            val eff = TvTimeProgressCalculator.deriveEffectiveShowStatus(prog, s.userStatus)
            eff == "completed" || eff == "in_progress" || (eff == "stopped" && prog.watchedCount > 0)
        }
    }
    val watchedMoviesCount = remember(movies) { movies.count { it.watched } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "stats_page_header") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    onClick = onBack,
                    shape = CircleShape,
                    color = TvTimePalette.SurfacePill,
                    border = BorderStroke(1.dp, TvTimePalette.Border),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("←", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "📊 Statistics",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                        color = Color.White
                    )
                    Text(
                        text = "Your total watch time, series, episodes, and movies breakdown.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TvTimePalette.TextSecondary
                    )
                }
            }
        }

        // 1. Hero Total Watch Hours Box
        item(key = "total_watch_time_card") {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = TvTimePalette.SurfaceCard,
                border = BorderStroke(1.dp, TvTimePalette.Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "TOTAL WATCH TIME",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                        color = TvTimePalette.TextSecondary
                    )
                    Text(
                        text = stats.formattedTotalHours,
                        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Black),
                        color = TvTimePalette.Yellow
                    )
                    Text(
                        text = "Total watch hours (${stats.formattedTotal})",
                        style = MaterialTheme.typography.bodySmall,
                        color = TvTimePalette.TextSecondary
                    )
                }
            }
        }

        // 2. Two-Column Watch Hours Boxes: Series & Movies
        item(key = "breakdown_boxes") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Series Watch Hours Box
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = TvTimePalette.SurfaceCard,
                    border = BorderStroke(1.dp, TvTimePalette.Border),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stats.formattedSeriesHours,
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Series watch hours",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                            color = TvTimePalette.Yellow,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Movies Watch Hours Box
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = TvTimePalette.SurfaceCard,
                    border = BorderStroke(1.dp, TvTimePalette.Border),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stats.formattedMoviesHours,
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Movie watch hours",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                            color = TvTimePalette.Yellow,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // 3. Three-Column Separate Count Boxes (Series, Episodes, Movies)
        item(key = "count_boxes") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatCountCard(
                    value = watchedShowsCount.toString(),
                    label = "SERIES",
                    valueColor = Color.White,
                    modifier = Modifier.weight(1f)
                )
                StatCountCard(
                    value = stats.totalEpisodesWatched.toString(),
                    label = "EPISODES",
                    valueColor = TvTimePalette.Yellow,
                    modifier = Modifier.weight(1f)
                )
                StatCountCard(
                    value = watchedMoviesCount.toString(),
                    label = "MOVIES",
                    valueColor = Color.White,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 4. Direct Head-to-Head Friend Comparison Card
        item(key = "friend_stats_comparison_card") {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = TvTimePalette.SurfaceCard,
                border = BorderStroke(1.dp, TvTimePalette.Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "⚔️ Compare with Friend",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                                color = Color.White
                            )
                            Text(
                                text = "Head-to-head watch time & library comparison",
                                style = MaterialTheme.typography.bodySmall,
                                color = TvTimePalette.TextSecondary
                            )
                        }
                        if (selectedFriendId.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Surface(
                                    onClick = { loadCompareFriend(selectedFriendId, true) },
                                    shape = RoundedCornerShape(50),
                                    color = TvTimePalette.SurfacePill,
                                    border = BorderStroke(1.dp, TvTimePalette.Border)
                                ) {
                                    Text(
                                        text = "↻",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = TvTimePalette.Yellow,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                                Surface(
                                    onClick = { loadCompareFriend("", false) },
                                    shape = RoundedCornerShape(50),
                                    color = TvTimePalette.SurfacePill,
                                    border = BorderStroke(1.dp, TvTimePalette.Border)
                                ) {
                                    Text(
                                        text = "✕",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = TvTimePalette.TextSecondary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        } else if (socialState.friends.isEmpty()) {
                            Button(
                                onClick = onOpenFriends,
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = TvTimePalette.Yellow,
                                    contentColor = TvTimePalette.OnYellow
                                )
                            ) {
                                Text("👥 Friends", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }

                    if (socialState.friends.isEmpty()) {
                        Text(
                            text = "Add a friend to compare watch hours, series, episodes & movies side by side.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TvTimePalette.TextSecondary
                        )
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(socialState.friends, key = { "cmp_$it" }) { friendUid ->
                                val isSel = friendUid.equals(selectedFriendId, ignoreCase = true)
                                Surface(
                                    onClick = { loadCompareFriend(friendUid, false) },
                                    shape = RoundedCornerShape(50),
                                    color = if (isSel) TvTimePalette.Yellow else TvTimePalette.SurfacePill,
                                    border = BorderStroke(1.dp, if (isSel) TvTimePalette.Yellow else TvTimePalette.Border)
                                ) {
                                    Text(
                                        text = "@$friendUid",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
                                        color = if (isSel) TvTimePalette.OnYellow else Color.White,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                                    )
                                }
                            }
                        }

                        val cmp = comparisonSummary
                        when {
                            selectedFriendId.isEmpty() -> {
                                Text(
                                    text = "Select a friend above for a direct side-by-side watch stats comparison.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TvTimePalette.TextSecondary
                                )
                            }
                            isLoadingFriend -> {
                                Text(
                                    text = "Loading @$selectedFriendId's statistics...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TvTimePalette.TextSecondary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp)
                                )
                            }
                            friendError.isNotEmpty() -> {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = friendError,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TvTimePalette.Error,
                                        textAlign = TextAlign.Center
                                    )
                                    Button(
                                        onClick = { loadCompareFriend(selectedFriendId, true) },
                                        shape = RoundedCornerShape(50),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = TvTimePalette.Yellow,
                                            contentColor = TvTimePalette.OnYellow
                                        )
                                    ) {
                                        Text("↻ Retry", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                                    }
                                }
                            }
                            cmp != null -> {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = TvTimePalette.SurfacePill,
                                        border = BorderStroke(1.dp, TvTimePalette.Border.copy(alpha = 0.7f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "🤝 IN COMMON",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                                                color = TvTimePalette.TextSecondary
                                            )
                                            Text(
                                                text = "${cmp.sharedSeriesCount} series · ${cmp.sharedMoviesCount} movies",
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
                                                color = Color.White
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 4.dp, vertical = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "You (@$currentUserId)",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
                                            color = TvTimePalette.Yellow
                                        )
                                        Text(
                                            text = "@$selectedFriendId",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
                                            color = Color(0xFF34D399)
                                        )
                                    }

                                    StatHeadToHeadRow(
                                        label = "Total Watch Time",
                                        myValueNum = stats.totalMinutes.toLong(),
                                        friendValueNum = cmp.friendStats.totalMinutes.toLong(),
                                        myFormatted = stats.formattedTotalHours,
                                        friendFormatted = cmp.friendStats.formattedTotalHours,
                                        mySub = stats.formattedTotal,
                                        friendSub = cmp.friendStats.formattedTotal
                                    )

                                    Text(
                                        text = "📺 SERIES",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                                        color = TvTimePalette.TextSecondary,
                                        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                                    )
                                    StatHeadToHeadRow(
                                        label = "Series Watched",
                                        myValueNum = watchedShowsCount.toLong(),
                                        friendValueNum = cmp.friendWatchedShowsCount.toLong(),
                                        myFormatted = watchedShowsCount.toString(),
                                        friendFormatted = cmp.friendWatchedShowsCount.toString()
                                    )
                                    StatHeadToHeadRow(
                                        label = "Episodes Watched",
                                        myValueNum = stats.totalEpisodesWatched.toLong(),
                                        friendValueNum = cmp.friendStats.totalEpisodesWatched.toLong(),
                                        myFormatted = stats.totalEpisodesWatched.toString(),
                                        friendFormatted = cmp.friendStats.totalEpisodesWatched.toString()
                                    )
                                    StatHeadToHeadRow(
                                        label = "Series Hours",
                                        myValueNum = stats.showMinutes.toLong(),
                                        friendValueNum = cmp.friendStats.showMinutes.toLong(),
                                        myFormatted = stats.formattedSeriesHours,
                                        friendFormatted = cmp.friendStats.formattedSeriesHours
                                    )

                                    Text(
                                        text = "🎬 MOVIES",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                                        color = TvTimePalette.TextSecondary,
                                        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                                    )
                                    StatHeadToHeadRow(
                                        label = "Movies Watched",
                                        myValueNum = watchedMoviesCount.toLong(),
                                        friendValueNum = cmp.friendWatchedMoviesCount.toLong(),
                                        myFormatted = watchedMoviesCount.toString(),
                                        friendFormatted = cmp.friendWatchedMoviesCount.toString()
                                    )
                                    StatHeadToHeadRow(
                                        label = "Movie Hours",
                                        myValueNum = stats.movieMinutes.toLong(),
                                        friendValueNum = cmp.friendStats.movieMinutes.toLong(),
                                        myFormatted = stats.formattedMoviesHours,
                                        friendFormatted = cmp.friendStats.formattedMoviesHours
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsFullPageScreen(
    currentUserId: String,
    onOpenImport: () -> Unit,
    onOpenAccountSwitch: () -> Unit,
    onSyncNow: () -> Unit,
    onBack: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "settings_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        onClick = onBack,
                        shape = CircleShape,
                        color = TvTimePalette.SurfacePill,
                        border = BorderStroke(1.dp, TvTimePalette.Border),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("←", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "⚙️ Settings",
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Profile, cloud sync & library backup",
                            style = MaterialTheme.typography.bodySmall,
                            color = TvTimePalette.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                var isSyncingNow by remember { mutableStateOf(false) }
                Surface(
                    onClick = {
                        if (!isSyncingNow) {
                            isSyncingNow = true
                            onSyncNow()
                        }
                    },
                    shape = RoundedCornerShape(50),
                    color = TvTimePalette.SurfacePill,
                    border = BorderStroke(1.dp, TvTimePalette.Border)
                ) {
                    LaunchedEffect(isSyncingNow) {
                        if (isSyncingNow) {
                            delay(1500)
                            isSyncingNow = false
                        }
                    }
                    Text(
                        text = if (isSyncingNow) "↻ Syncing..." else "↻ Sync",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = TvTimePalette.Yellow,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }

        // Top Profile Card (Avatar, Name, Email & Import/Export)
        item(key = "settings_profile_card") {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = TvTimePalette.SurfaceCard,
                border = BorderStroke(1.dp, TvTimePalette.Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        listOf(TvTimePalette.Yellow, Color(0xFFF97316))
                                    )
                                )
                                .padding(2.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF18181D))
                                .clickable { onOpenAccountSwitch() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = currentUserId.take(2).uppercase(),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                                color = TvTimePalette.Yellow
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = currentUserId.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Text(
                                    text = "✎",
                                    color = TvTimePalette.TextSecondary,
                                    modifier = Modifier.clickable { onOpenAccountSwitch() }
                                )
                            }
                            Text(
                                text = "@$currentUserId",
                                style = MaterialTheme.typography.bodySmall,
                                color = TvTimePalette.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    HorizontalDivider(color = TvTimePalette.Border)

                    Surface(
                        onClick = onOpenImport,
                        shape = RoundedCornerShape(50),
                        color = TvTimePalette.SurfacePill,
                        border = BorderStroke(1.dp, TvTimePalette.Border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "⇅ Import / Export CSV & JSON",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCountCard(
    value: String,
    label: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = TvTimePalette.SurfaceCard,
        border = BorderStroke(1.dp, TvTimePalette.Border),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                color = TvTimePalette.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RecentlyWatchedFullPageScreen(
    shows: List<ShowEntity>,
    movies: List<MovieEntity>,
    onOpenShow: (ShowEntity) -> Unit,
    onUnwatchShow: (ShowEntity) -> Unit,
    onOpenMovie: (MovieEntity) -> Unit,
    onBack: () -> Unit
) {
    val watchedOrInProgressShows = remember(shows) {
        shows.filter { s ->
            val prog = TvTimeProgressCalculator.computeShowProgressFromJson(
                s.episodesJson,
                s.watchedCodesCsv,
                s.userStatus
            )
            val effectiveStatus = TvTimeProgressCalculator.deriveEffectiveShowStatus(prog, s.userStatus)
            effectiveStatus == "completed" || effectiveStatus == "in_progress"
        }
    }
    val watchedMovies = remember(movies) { movies.filter { it.watched } }
    val completedSeriesList = remember(watchedOrInProgressShows) {
        watchedOrInProgressShows.filter { s ->
            val prog = TvTimeProgressCalculator.computeShowProgressFromJson(
                s.episodesJson,
                s.watchedCodesCsv,
                s.userStatus
            )
            TvTimeProgressCalculator.deriveEffectiveShowStatus(prog, s.userStatus) == "completed"
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item(key = "recent_header") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    onClick = onBack,
                    shape = CircleShape,
                    color = TvTimePalette.SurfacePill,
                    border = BorderStroke(1.dp, TvTimePalette.Border),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("←", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "🎬 Recently Watched",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                        color = Color.White
                    )
                    Text(
                        text = "Series and movies you have watched or are currently watching.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TvTimePalette.TextSecondary
                    )
                }
            }
        }

        // 1. Recently watched Series Carousel
        item(key = "carousel_recent_series") {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = TvTimePalette.SurfaceCard,
                border = BorderStroke(1.dp, TvTimePalette.Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            text = "Recently watched",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White
                        )
                        Text(
                            text = "Series (${watchedOrInProgressShows.size})",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal),
                            color = TvTimePalette.TextSecondary
                        )
                    }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(watchedOrInProgressShows, key = { "rec_s_${it.id}" }) { show ->
                            val prog = TvTimeProgressCalculator.computeShowProgressFromJson(
                                show.episodesJson,
                                show.watchedCodesCsv,
                                show.userStatus
                            )
                            val badgeLabel = "${prog.watchedCount}/${prog.totalEpisodes} eps"

                            Column(
                                modifier = Modifier
                                    .width(108.dp)
                                    .clickable { onOpenShow(show) },
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                NetworkPosterImage(
                                    url = show.posterUrl,
                                    title = show.title,
                                    width = 108.dp,
                                    height = 156.dp,
                                    cornerRadius = 16.dp
                                )
                                Text(
                                    text = show.title,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = badgeLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                                    color = if (show.userStatus == "completed" || prog.isCompleted) Color(0xFF4ADE80) else TvTimePalette.Yellow
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Recently watched Movies Carousel
        item(key = "carousel_recent_movies") {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = TvTimePalette.SurfaceCard,
                border = BorderStroke(1.dp, TvTimePalette.Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            text = "Recently watched",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White
                        )
                        Text(
                            text = "Movies (${watchedMovies.size})",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal),
                            color = TvTimePalette.TextSecondary
                        )
                    }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(watchedMovies, key = { "rec_m_${it.id}" }) { movie ->
                            Column(
                                modifier = Modifier
                                    .width(108.dp)
                                    .clickable { onOpenMovie(movie) },
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                NetworkPosterImage(
                                    url = movie.posterUrl,
                                    title = movie.title,
                                    width = 108.dp,
                                    height = 156.dp,
                                    cornerRadius = 16.dp
                                )
                                Text(
                                    text = movie.title,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Full Scrollable Completed Series List
        item(key = "completed_series_full_header") {
            Text(
                text = "Completed Series (${completedSeriesList.size})",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = Color.White
            )
        }
        items(completedSeriesList, key = { "comp_full_${it.id}_${it.externalId}" }) { show ->
            TvShowWatchCard(
                show = show,
                onCardClick = { onOpenShow(show) },
                onEyeClick = { onUnwatchShow(show) }
            )
        }
    }
}

@Composable
private fun CategoriesFullPageScreen(
    shows: List<ShowEntity>,
    movies: List<MovieEntity>,
    onAddOrOpenResult: (SearchResultItem) -> Unit,
    onQuickWatchlistToggle: (SearchResultItem) -> Unit,
    onQuickCompletedToggle: (SearchResultItem) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val currentOnAddOrOpenResult by rememberUpdatedState(onAddOrOpenResult)
    val currentOnQuickWatchlistToggle by rememberUpdatedState(onQuickWatchlistToggle)
    val currentOnQuickCompletedToggle by rememberUpdatedState(onQuickCompletedToggle)

    var segment by rememberSaveable { mutableStateOf("movies") } // "movies" | "shows"
    var movieLang by rememberSaveable { mutableStateOf("ta") }
    var movieGenre by rememberSaveable { mutableStateOf("all") }
    var movieYear by rememberSaveable { mutableStateOf("0") }
    var showLang by rememberSaveable { mutableStateOf("all") }
    var showGenre by rememberSaveable { mutableStateOf("all") }
    var showYearFrom by rememberSaveable { mutableStateOf("0") }
    var showYearTo by rememberSaveable { mutableStateOf("0") }
    var sortKey by rememberSaveable { mutableStateOf("votes") }

    var rawResults by remember { mutableStateOf<List<SearchResultItem>>(emptyList()) }
    var currentPage by remember { mutableStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }

    val languageOptions = remember {
        listOf(
            "ta" to "Tamil",
            "ml" to "Malayalam",
            "mr" to "Marathi",
            "hi" to "Hindi",
            "te" to "Telugu",
            "kn" to "Kannada",
            "bn" to "Bengali",
            "pa" to "Punjabi",
            "gu" to "Gujarati",
            "en" to "English",
            "ko" to "Korean",
            "ja" to "Japanese",
            "es" to "Spanish",
            "all" to "All Languages"
        )
    }
    val showLanguageOptions = remember(languageOptions) {
        listOf("all" to "All Languages") + languageOptions.filter { it.first != "all" }
    }
    val movieYearOptions = remember {
        val maxY = maxOf(2026, java.time.LocalDate.now().year + 1)
        listOf("0" to "All Years") + (maxY downTo 1960).map { y -> y.toString() to y.toString() }
    }
    val showYearFromOptions = remember {
        val maxY = maxOf(2026, java.time.LocalDate.now().year + 1)
        listOf("0" to "From: Any") + (maxY downTo 1960).map { y -> y.toString() to "From: $y" }
    }
    val showYearToOptions = remember {
        val maxY = maxOf(2026, java.time.LocalDate.now().year + 1)
        listOf("0" to "To: Any") + (maxY downTo 1960).map { y -> y.toString() to "To: $y" }
    }
    val movieGenreOptions = remember {
        listOf(
            "all" to "All Genres",
            "28" to "Action",
            "12" to "Adventure",
            "16" to "Animation",
            "35" to "Comedy",
            "80" to "Crime",
            "99" to "Documentary",
            "18" to "Drama",
            "10751" to "Family",
            "14" to "Fantasy",
            "36" to "History",
            "27" to "Horror",
            "9648" to "Mystery",
            "10749" to "Romance",
            "878" to "Sci-Fi",
            "53" to "Thriller"
        )
    }
    val showGenreOptions = remember {
        listOf(
            "all" to "All Genres",
            "18" to "Drama",
            "35" to "Comedy",
            "80" to "Crime",
            "9648" to "Mystery",
            "10759" to "Action & Adventure",
            "10765" to "Sci-Fi & Fantasy",
            "16" to "Animation",
            "99" to "Documentary",
            "10751" to "Family",
            "10764" to "Reality"
        )
    }
    val sortOptions = remember {
        listOf(
            "votes" to "Most Votes",
            "popularity" to "Popular",
            "rating" to "Top Rated"
        )
    }

    LaunchedEffect(segment, movieLang, movieGenre, movieYear, showLang, showGenre, showYearFrom, showYearTo, sortKey) {
        isLoading = true
        isLoadingMore = false
        currentPage = 1
        rawResults = emptyList()
        val pageRes = LiveMediaCatalogService.discoverByCategoryPaged(
            mediaType = if (segment == "shows") "show" else "movie",
            language = if (segment == "shows") showLang else movieLang,
            year = movieYear.toIntOrNull() ?: 0,
            yearFrom = showYearFrom.toIntOrNull() ?: 0,
            yearTo = showYearTo.toIntOrNull() ?: 0,
            genre = if (segment == "shows") showGenre else movieGenre,
            sort = sortKey,
            page = 1
        )
        rawResults = pageRes.items
        hasMore = pageRes.hasMore
        isLoading = false
    }

    val untrackedResults by produceState<List<SearchResultItem>>(
        initialValue = emptyList(),
        key1 = rawResults,
        key2 = shows,
        key3 = movies
    ) {
        value = if (rawResults.isEmpty()) {
            emptyList()
        } else {
            withContext(Dispatchers.Default) {
                val movByExt = HashSet<String>(movies.size * 2 + 1)
                val movByTmdb = HashSet<Int>(movies.size * 2 + 1)
                val movByTitle = HashMap<String, MutableList<MovieEntity>>(movies.size * 2 + 1)
                for (m in movies) {
                    if (m.externalId.isNotBlank()) movByExt.add(m.externalId)
                    val ct = TvTimeProgressCalculator.cleanMovieTmdbId(m.externalId, m.tmdbId)
                    if (ct > 0) movByTmdb.add(ct)
                    val nt = TvTimeProgressCalculator.normalizeRecTitle(m.title)
                    if (nt.isNotEmpty()) movByTitle.getOrPut(nt) { mutableListOf() }.add(m)
                }
                val showByExt = HashSet<String>(shows.size * 2 + 1)
                val showByTmdb = HashSet<Int>(shows.size * 2 + 1)
                val showByMaze = HashSet<Int>(shows.size * 2 + 1)
                val showByTitle = HashMap<String, MutableList<ShowEntity>>(shows.size * 2 + 1)
                for (s in shows) {
                    if (s.externalId.isNotBlank()) showByExt.add(s.externalId)
                    if (s.tmdbId > 0) showByTmdb.add(s.tmdbId)
                    if (s.tvmazeId > 0) showByMaze.add(s.tvmazeId)
                    val nt = TvTimeProgressCalculator.normalizeRecTitle(s.title)
                    if (nt.isNotEmpty()) showByTitle.getOrPut(nt) { mutableListOf() }.add(s)
                }
                rawResults.filter { item ->
                    val isMov = item.mediaType.equals("movie", ignoreCase = true) || item.externalId.startsWith("movie_")
                    if (isMov) {
                        val cleanTmdb = TvTimeProgressCalculator.cleanMovieTmdbId(item.externalId, item.tmdbId)
                        if (item.externalId.isNotBlank() && movByExt.contains(item.externalId)) return@filter false
                        if (cleanTmdb > 0 && movByTmdb.contains(cleanTmdb)) return@filter false
                        val cands = movByTitle[TvTimeProgressCalculator.normalizeRecTitle(item.title)] ?: return@filter true
                        cands.none { m ->
                            TvTimeProgressCalculator.doesRecMatchMovie(
                                m.externalId, m.tmdbId, m.title, m.year,
                                item.externalId, cleanTmdb, item.title, item.year
                            )
                        }
                    } else {
                        if (item.externalId.isNotBlank() && showByExt.contains(item.externalId)) return@filter false
                        if (item.tmdbId > 0 && showByTmdb.contains(item.tmdbId)) return@filter false
                        if (item.tvmazeId > 0 && showByMaze.contains(item.tvmazeId)) return@filter false
                        val cands = showByTitle[TvTimeProgressCalculator.normalizeRecTitle(item.title)] ?: return@filter true
                        cands.none { s ->
                            TvTimeProgressCalculator.doesRecMatchShow(
                                s.externalId, s.tmdbId, s.tvmazeId, s.title, s.year,
                                item.externalId, item.tmdbId, item.tvmazeId, item.title, item.year
                            )
                        }
                    }
                }
            }
        }
    }

    val loadNextPage: () -> Unit = {
        if (!isLoading && !isLoadingMore && hasMore && currentPage < 50) {
            isLoadingMore = true
            val nextP = currentPage + 1
            scope.launch {
                val pageRes = LiveMediaCatalogService.discoverByCategoryPaged(
                    mediaType = if (segment == "shows") "show" else "movie",
                    language = if (segment == "shows") showLang else movieLang,
                    year = movieYear.toIntOrNull() ?: 0,
                    yearFrom = showYearFrom.toIntOrNull() ?: 0,
                    yearTo = showYearTo.toIntOrNull() ?: 0,
                    genre = if (segment == "shows") showGenre else movieGenre,
                    sort = sortKey,
                    page = nextP
                )
                val existingIds = rawResults.map { it.externalId }.toHashSet()
                val appended = pageRes.items.filter { existingIds.add(it.externalId) }
                rawResults = rawResults + appended
                currentPage = nextP
                hasMore = pageRes.hasMore && appended.isNotEmpty()
                isLoadingMore = false
            }
        }
    }

    val shouldLoadMore by remember {
        androidx.compose.runtime.derivedStateOf {
            val totalItems = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 2 && lastVisible >= totalItems - 5
        }
    }

    LaunchedEffect(shouldLoadMore, untrackedResults.size, hasMore, isLoading, isLoadingMore) {
        if ((shouldLoadMore || untrackedResults.size < 6) && hasMore && !isLoading && !isLoadingMore) {
            loadNextPage()
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "categories_header") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    onClick = onBack,
                    shape = CircleShape,
                    color = TvTimePalette.SurfacePill,
                    border = BorderStroke(1.dp, TvTimePalette.Border),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("←", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "🗂️ Categories",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                        color = Color.White
                    )
                    Text(
                        text = "Find untracked movies & series by Language, Genre & Year",
                        style = MaterialTheme.typography.bodySmall,
                        color = TvTimePalette.TextSecondary
                    )
                }
            }
        }

        item(key = "categories_segment") {
            Surface(
                shape = RoundedCornerShape(50),
                color = TvTimePalette.SurfacePill,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("movies" to "Movies", "shows" to "Series").forEach { (key, label) ->
                        val selected = segment == key
                        Surface(
                            onClick = { segment = key },
                            shape = RoundedCornerShape(50),
                            color = if (selected) TvTimePalette.Yellow else Color.Transparent,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(vertical = 10.dp)
                            ) {
                                Text(
                                    text = label,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = if (selected) TvTimePalette.OnYellow else TvTimePalette.TextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "categories_filters") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (segment == "movies") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            TvFilterDropdown(
                                prefixLabel = "",
                                selectedKey = movieLang,
                                options = languageOptions,
                                onSelect = { movieLang = it }
                            )
                        }
                        item {
                            TvFilterDropdown(
                                prefixLabel = "",
                                selectedKey = movieGenre,
                                options = movieGenreOptions,
                                onSelect = { movieGenre = it }
                            )
                        }
                        item {
                            TvFilterDropdown(
                                prefixLabel = "",
                                selectedKey = movieYear,
                                options = movieYearOptions,
                                onSelect = { movieYear = it }
                            )
                        }
                        item {
                            TvFilterDropdown(
                                prefixLabel = "",
                                selectedKey = sortKey,
                                options = sortOptions,
                                onSelect = { sortKey = it }
                            )
                        }
                    }
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            TvFilterDropdown(
                                prefixLabel = "",
                                selectedKey = showLang,
                                options = showLanguageOptions,
                                onSelect = { showLang = it }
                            )
                        }
                        item {
                            TvFilterDropdown(
                                prefixLabel = "",
                                selectedKey = showGenre,
                                options = showGenreOptions,
                                onSelect = { showGenre = it }
                            )
                        }
                        item {
                            TvFilterDropdown(
                                prefixLabel = "",
                                selectedKey = showYearFrom,
                                options = showYearFromOptions,
                                onSelect = { showYearFrom = it }
                            )
                        }
                        item {
                            TvFilterDropdown(
                                prefixLabel = "",
                                selectedKey = showYearTo,
                                options = showYearToOptions,
                                onSelect = { showYearTo = it }
                            )
                        }
                        item {
                            TvFilterDropdown(
                                prefixLabel = "",
                                selectedKey = sortKey,
                                options = sortOptions,
                                onSelect = { sortKey = it }
                            )
                        }
                    }
                }
                Text(
                    text = if (isLoading) {
                        "Searching catalog..."
                    } else {
                        "${untrackedResults.size}${if (hasMore) "+" else ""} untracked ${if (segment == "movies") "movies" else "series"} (excludes Completed & Watchlist)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TvTimePalette.TextSecondary
                )
            }
        }

        if (isLoading && untrackedResults.isEmpty()) {
            item(key = "categories_loading") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Loading untracked titles...", color = TvTimePalette.TextSecondary, fontSize = 13.sp)
                }
            }
        } else if (untrackedResults.isEmpty()) {
            item(key = "categories_empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No untracked titles left on this batch — try another year, language, or genre!",
                        color = TvTimePalette.TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            items(untrackedResults, key = { it.externalId }) { item ->
                TvSearchResultCard(
                    item = item,
                    isInWatchlist = false,
                    isCompleted = false,
                    canShowWatchlist = true,
                    onCardClick = { currentOnAddOrOpenResult(item) },
                    onWatchlistClick = { currentOnQuickWatchlistToggle(item) },
                    onCompletedClick = { currentOnQuickCompletedToggle(item) }
                )
            }
        }

        if (isLoadingMore) {
            item(key = "categories_loading_more") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Loading more untracked titles...", color = TvTimePalette.TextSecondary, fontSize = 12.sp)
                }
            }
        }
    }
}

private data class PreparedFriendMovieItem(
    val stableKey: String,
    val movie: MovieEntity,
    val myMovie: MovieEntity?
)

private data class PreparedFriendShowItem(
    val stableKey: String,
    val show: ShowEntity,
    val myShow: ShowEntity?,
    val friendEff: String,
    val watchedCount: Int,
    val totalCount: Int
)

private data class PreparedFriendLibraryState(
    val friendUserId: String = "",
    val unwatchedMovies: List<PreparedFriendMovieItem> = emptyList(),
    val unwatchedShows: List<PreparedFriendShowItem> = emptyList(),
    val isComputing: Boolean = false
)

@Composable
private fun FriendsFullPageScreen(
    currentUserId: String,
    myShows: List<ShowEntity>,
    myMovies: List<MovieEntity>,
    dao: TvTimeDao,
    onOpenCatalogItem: (SearchResultItem) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var socialState by remember { mutableStateOf(FirestoreSyncService.getCachedSocialState(currentUserId)) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<FriendUserSummary>>(emptyList()) }
    var selectedFriendId by rememberSaveable { mutableStateOf("") }
    var selectedFriendLibrary by remember { mutableStateOf<FriendLibraryData?>(null) }
    var isLoadingLibrary by remember { mutableStateOf(false) }
    var friendLoadError by remember { mutableStateOf("") }
    var librarySegment by rememberSaveable { mutableStateOf("shows") } // "shows" | "movies"
    var friendFilter by rememberSaveable { mutableStateOf("all") }
    var preparedLibrary by remember { mutableStateOf(PreparedFriendLibraryState()) }

    LaunchedEffect(selectedFriendLibrary, myMovies, myShows) {
        val lib = selectedFriendLibrary
        if (lib == null) {
            preparedLibrary = PreparedFriendLibraryState(isComputing = false)
            return@LaunchedEffect
        }
        // Only show loading placeholder when switching to a different friend's library!
        // Never set isComputing = true when myMovies/myShows updates, otherwise LazyColumn temporarily
        // drops all rows for 1 frame and resets the user's scroll position back to the top!
        if (!preparedLibrary.friendUserId.equals(lib.userId, ignoreCase = true)) {
            preparedLibrary = PreparedFriendLibraryState(friendUserId = lib.userId, isComputing = true)
        }
        val computed = withContext(Dispatchers.Default) {
            // Build O(1) indexes over myMovies & myShows off the UI thread to prevent ANR on 600+ item libraries
            val myMovByExt = HashMap<String, MovieEntity>(myMovies.size * 2 + 1)
            val myMovByTmdb = HashMap<Int, MovieEntity>(myMovies.size * 2 + 1)
            val myMovByRecTitle = HashMap<String, MutableList<MovieEntity>>(myMovies.size * 2 + 1)
            for (mm in myMovies) {
                if (mm.externalId.isNotBlank()) myMovByExt.putIfAbsent(mm.externalId, mm)
                val cleanTmdb = TvTimeProgressCalculator.cleanMovieTmdbId(mm.externalId, mm.tmdbId)
                if (cleanTmdb > 0) myMovByTmdb.putIfAbsent(cleanTmdb, mm)
                val normT = TvTimeProgressCalculator.normalizeRecTitle(mm.title)
                if (normT.isNotEmpty()) {
                    myMovByRecTitle.getOrPut(normT) { mutableListOf() }.add(mm)
                }
            }
            fun matchMyMovie(fm: MovieEntity): MovieEntity? {
                if (fm.externalId.isNotBlank()) {
                    myMovByExt[fm.externalId]?.let { return it }
                }
                val cleanFm = TvTimeProgressCalculator.cleanMovieTmdbId(fm.externalId, fm.tmdbId)
                if (cleanFm > 0) {
                    myMovByTmdb[cleanFm]?.let { return it }
                }
                val normFm = TvTimeProgressCalculator.normalizeRecTitle(fm.title)
                val candidates = myMovByRecTitle[normFm] ?: return null
                return candidates.firstOrNull { mm ->
                    TvTimeProgressCalculator.doesRecMatchMovie(
                        mm.externalId, mm.tmdbId, mm.title, mm.year,
                        fm.externalId, cleanFm, fm.title, fm.year
                    )
                }
            }

            data class MyIndexedShow(val show: ShowEntity, val eff: String, val watchedCount: Int)
            val myShowByExt = HashMap<String, MyIndexedShow>(myShows.size * 2 + 1)
            val myShowByTmdb = HashMap<Int, MyIndexedShow>(myShows.size * 2 + 1)
            val myShowByTvmaze = HashMap<Int, MyIndexedShow>(myShows.size * 2 + 1)
            val myShowByRecTitle = HashMap<String, MutableList<MyIndexedShow>>(myShows.size * 2 + 1)
            for (ms in myShows) {
                val p = TvTimeProgressCalculator.computeShowProgressFromJson(ms.episodesJson, ms.watchedCodesCsv, ms.userStatus)
                val eff = TvTimeProgressCalculator.deriveEffectiveShowStatus(p, ms.userStatus)
                val entry = MyIndexedShow(ms, eff, p.watchedCount)
                if (ms.externalId.isNotBlank()) myShowByExt.putIfAbsent(ms.externalId, entry)
                if (ms.tmdbId > 0) myShowByTmdb.putIfAbsent(ms.tmdbId, entry)
                if (ms.tvmazeId > 0) myShowByTvmaze.putIfAbsent(ms.tvmazeId, entry)
                val normT = TvTimeProgressCalculator.normalizeRecTitle(ms.title)
                if (normT.isNotEmpty()) {
                    myShowByRecTitle.getOrPut(normT) { mutableListOf() }.add(entry)
                }
            }
            fun matchMyShow(fs: ShowEntity): MyIndexedShow? {
                if (fs.externalId.isNotBlank()) {
                    myShowByExt[fs.externalId]?.let { return it }
                }
                if (fs.tmdbId > 0) {
                    myShowByTmdb[fs.tmdbId]?.let { return it }
                }
                if (fs.tvmazeId > 0) {
                    myShowByTvmaze[fs.tvmazeId]?.let { return it }
                }
                val normFs = TvTimeProgressCalculator.normalizeRecTitle(fs.title)
                val candidates = myShowByRecTitle[normFs] ?: return null
                return candidates.firstOrNull { cand ->
                    val ms = cand.show
                    TvTimeProgressCalculator.doesRecMatchShow(
                        ms.externalId, ms.tmdbId, ms.tvmazeId, ms.title, ms.year,
                        fs.externalId, fs.tmdbId, fs.tvmazeId, fs.title, fs.year
                    )
                }
            }

            // Exclude ALL movies & series already in the viewer's library (completed, in_progress, watchlist, or stopped)
            // Use stable item-identity keys (without list index) so LazyColumn preserves scroll position when items are marked!
            val seenMovieKeys = HashSet<String>(lib.movies.size * 2 + 1)
            val unwatchedMovies = ArrayList<PreparedFriendMovieItem>(lib.movies.size)
            for (fm in lib.movies) {
                val mine = matchMyMovie(fm)
                if (mine == null) {
                    val baseKey = "f_mov_${lib.userId}_${fm.externalId}_${fm.tmdbId}_${fm.title}_${fm.year}"
                    var uniqueKey = baseKey
                    var dupIdx = 1
                    while (!seenMovieKeys.add(uniqueKey)) {
                        uniqueKey = "${baseKey}_$dupIdx"
                        dupIdx++
                    }
                    unwatchedMovies.add(PreparedFriendMovieItem(stableKey = uniqueKey, movie = fm, myMovie = null))
                }
            }

            val seenShowKeys = HashSet<String>(lib.shows.size * 2 + 1)
            val unwatchedShows = ArrayList<PreparedFriendShowItem>(lib.shows.size)
            for (fs in lib.shows) {
                val mine = matchMyShow(fs)
                if (mine != null) {
                    continue
                }
                val fsProg = TvTimeProgressCalculator.computeShowProgressFromJson(
                    fs.episodesJson,
                    fs.watchedCodesCsv,
                    fs.userStatus
                )
                val fsEff = TvTimeProgressCalculator.deriveEffectiveShowStatus(fsProg, fs.userStatus)
                val baseKey = "f_sh_${lib.userId}_${fs.externalId}_${fs.tmdbId}_${fs.tvmazeId}_${fs.title}_${fs.year}"
                var uniqueKey = baseKey
                var dupIdx = 1
                while (!seenShowKeys.add(uniqueKey)) {
                    uniqueKey = "${baseKey}_$dupIdx"
                    dupIdx++
                }
                unwatchedShows.add(
                    PreparedFriendShowItem(
                        stableKey = uniqueKey,
                        show = fs,
                        myShow = null,
                        friendEff = fsEff,
                        watchedCount = fsProg.watchedCount,
                        totalCount = fsProg.totalEpisodes.coerceAtLeast(1)
                    )
                )
            }

            PreparedFriendLibraryState(
                friendUserId = lib.userId,
                unwatchedMovies = unwatchedMovies,
                unwatchedShows = unwatchedShows,
                isComputing = false
            )
        }
        preparedLibrary = computed
    }

    val refreshFriends: () -> Unit = {
        scope.launch {
            socialState = FirestoreSyncService.fetchSocialFriendsState(currentUserId)
            searchResults = FirestoreSyncService.searchUsersByUsername(currentUserId, searchQuery)
        }
    }

    LaunchedEffect(currentUserId) {
        socialState = FirestoreSyncService.fetchSocialFriendsState(currentUserId)
        searchResults = FirestoreSyncService.searchUsersByUsername(currentUserId, searchQuery)
    }

    LaunchedEffect(searchQuery) {
        delay(220)
        searchResults = FirestoreSyncService.searchUsersByUsername(currentUserId, searchQuery)
    }

    val openFriendLibrary: (String) -> Unit = { friendId ->
        selectedFriendId = friendId
        isLoadingLibrary = true
        friendLoadError = ""
        scope.launch {
            val res = FirestoreSyncService.fetchFriendLibrary(currentUserId, friendId)
            selectedFriendLibrary = res.getOrNull()
            if (res.isFailure || selectedFriendLibrary == null) {
                val errMsg = res.exceptionOrNull()?.message ?: "Could not load @$friendId's library."
                friendLoadError = errMsg
                Toast.makeText(context, errMsg, Toast.LENGTH_SHORT).show()
            }
            isLoadingLibrary = false
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "friends_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        onClick = onBack,
                        shape = CircleShape,
                        color = TvTimePalette.SurfacePill,
                        border = BorderStroke(1.dp, TvTimePalette.Border),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("←", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "👥 Friends",
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                            color = Color.White
                        )
                        Text(
                            text = "Search by username, send requests & view series and movies",
                            style = MaterialTheme.typography.bodySmall,
                            color = TvTimePalette.TextSecondary
                        )
                    }
                }
                Surface(
                    onClick = refreshFriends,
                    shape = RoundedCornerShape(50),
                    color = TvTimePalette.SurfacePill,
                    border = BorderStroke(1.dp, TvTimePalette.Border)
                ) {
                    Text(
                        text = "↻ Sync",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = TvTimePalette.Yellow,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }

        // Add Friend by Username Card
        item(key = "friends_search_card") {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = TvTimePalette.SurfaceCard,
                border = BorderStroke(1.dp, TvTimePalette.Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ADD FRIEND BY USERNAME",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                            color = TvTimePalette.TextSecondary
                        )
                        Text(
                            text = "Your username: @$currentUserId",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = TvTimePalette.Yellow
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search username (e.g. partner)...", fontSize = 13.sp) },
                            singleLine = true,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                val target = searchQuery.trim().lowercase().substringBefore("@")
                                if (target.isNotEmpty()) {
                                    scope.launch {
                                        val res = FirestoreSyncService.sendFriendRequest(currentUserId, target)
                                        res.onSuccess { updated ->
                                            socialState = updated
                                            searchQuery = ""
                                            searchResults = FirestoreSyncService.searchUsersByUsername(currentUserId, "")
                                            Toast.makeText(
                                                context,
                                                "Friend request sent to @$target!",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }.onFailure { err ->
                                            Toast.makeText(
                                                context,
                                                err.message ?: "Could not send request",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                            },
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TvTimePalette.Yellow,
                                contentColor = TvTimePalette.OnYellow
                            )
                        ) {
                            Text("+ Request", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                        }
                    }

                    searchResults.forEach { user ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = TvTimePalette.SurfacePill,
                            border = BorderStroke(1.dp, TvTimePalette.Border),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "@${user.userId}",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold),
                                        color = Color.White
                                    )
                                    Text(
                                        text = "${user.showCount} series · ${user.movieCount} movies",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TvTimePalette.TextSecondary
                                    )
                                }

                                when (user.relation) {
                                    "friend" -> {
                                        Button(
                                            onClick = { openFriendLibrary(user.userId) },
                                            shape = RoundedCornerShape(50),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFF22C55E),
                                                contentColor = Color.White
                                            )
                                        ) {
                                            Text("View Library", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    "incoming" -> {
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    FirestoreSyncService.respondToFriendRequest(
                                                        currentUserId,
                                                        user.userId,
                                                        true
                                                    ).onSuccess { socialState = it }
                                                    searchResults = FirestoreSyncService.searchUsersByUsername(currentUserId, searchQuery)
                                                }
                                            },
                                            shape = RoundedCornerShape(50),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = TvTimePalette.Yellow,
                                                contentColor = TvTimePalette.OnYellow
                                            )
                                        ) {
                                            Text("✓ Accept", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                                        }
                                    }
                                    "outgoing" -> {
                                        Text(
                                            text = "Request Sent",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = TvTimePalette.TextSecondary
                                        )
                                    }
                                    else -> {
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    FirestoreSyncService.sendFriendRequest(
                                                        currentUserId,
                                                        user.userId
                                                    ).onSuccess { socialState = it }
                                                    searchResults = FirestoreSyncService.searchUsersByUsername(currentUserId, searchQuery)
                                                }
                                            },
                                            shape = RoundedCornerShape(50),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = TvTimePalette.Yellow,
                                                contentColor = TvTimePalette.OnYellow
                                            )
                                        ) {
                                            Text("+ Add Friend", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Incoming Friend Requests
        if (socialState.incomingRequests.isNotEmpty()) {
            item(key = "friends_incoming_card") {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = TvTimePalette.SurfaceCard,
                    border = BorderStroke(1.dp, TvTimePalette.Yellow.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "INCOMING FRIEND REQUESTS (${socialState.incomingRequests.size})",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                            color = TvTimePalette.Yellow
                        )
                        socialState.incomingRequests.forEach { reqUser ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "@$reqUser",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold),
                                    color = Color.White
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                FirestoreSyncService.respondToFriendRequest(currentUserId, reqUser, true)
                                                    .onSuccess { socialState = it }
                                            }
                                        },
                                        shape = RoundedCornerShape(50),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = TvTimePalette.Yellow,
                                            contentColor = TvTimePalette.OnYellow
                                        )
                                    ) {
                                        Text("✓ Accept", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                                    }
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                FirestoreSyncService.respondToFriendRequest(currentUserId, reqUser, false)
                                                    .onSuccess { socialState = it }
                                            }
                                        }
                                    ) {
                                        Text("Decline", color = Color(0xFFF87171), fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Accepted Friends List
        item(key = "friends_accepted_card") {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = TvTimePalette.SurfaceCard,
                border = BorderStroke(1.dp, TvTimePalette.Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "MY FRIENDS (${socialState.friends.size})",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                        color = TvTimePalette.TextSecondary
                    )
                    if (socialState.friends.isEmpty()) {
                        Text(
                            text = "No friends added yet. Search by username above and send a friend request!",
                            style = MaterialTheme.typography.bodySmall,
                            color = TvTimePalette.TextSecondary
                        )
                    } else {
                        socialState.friends.forEach { friendId ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = TvTimePalette.SurfacePill,
                                border = BorderStroke(
                                    1.dp,
                                    if (selectedFriendId == friendId) TvTimePalette.Yellow else TvTimePalette.Border
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { openFriendLibrary(friendId) }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "@$friendId",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold),
                                            color = Color.White
                                        )
                                        Text(
                                            text = "✓ Friend · Tap to see series & movies",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF4ADE80)
                                        )
                                    }
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Button(
                                            onClick = { openFriendLibrary(friendId) },
                                            shape = RoundedCornerShape(50),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = TvTimePalette.Yellow,
                                                contentColor = TvTimePalette.OnYellow
                                            )
                                        ) {
                                            Text("View Library", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                                        }
                                        TextButton(
                                            onClick = {
                                                scope.launch {
                                                    FirestoreSyncService.removeFriendOrRequest(currentUserId, friendId)
                                                        .onSuccess {
                                                            socialState = it
                                                            if (selectedFriendId == friendId) {
                                                                selectedFriendId = ""
                                                                selectedFriendLibrary = null
                                                            }
                                                        }
                                                }
                                            }
                                        ) {
                                            Text("✕", color = TvTimePalette.TextSecondary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Selected Friend's Library Viewer (Virtualized LazyColumn items to prevent ANR & OpenGL texture crash)
        if (selectedFriendId.isNotEmpty()) {
            val lib = selectedFriendLibrary
            val isBusy = isLoadingLibrary || preparedLibrary.isComputing
            val unwatchedMovies = preparedLibrary.unwatchedMovies
            val unwatchedShows = preparedLibrary.unwatchedShows
            val filteredFriendMovies = if (librarySegment == "movies") {
                when (friendFilter) {
                    "completed" -> unwatchedMovies.filter { it.movie.watched }
                    "interested" -> unwatchedMovies.filter { !it.movie.watched }
                    else -> unwatchedMovies
                }
            } else emptyList()
            val filteredFriendShows = if (librarySegment != "movies") {
                if (friendFilter == "all") unwatchedShows
                else unwatchedShows.filter { it.friendEff == friendFilter }
            } else emptyList()

            item(key = "friend_library_header_card") {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = TvTimePalette.SurfaceCard,
                    border = BorderStroke(1.dp, TvTimePalette.Yellow.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "@$selectedFriendId's Library",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                                    color = Color.White
                                )
                                Text(
                                    text = when {
                                        isBusy -> "Loading library..."
                                        lib == null -> "Unable to load library"
                                        else -> "${unwatchedShows.size} unwatched series · ${unwatchedMovies.size} unwatched movies"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (!isBusy && lib == null) TvTimePalette.Error else TvTimePalette.TextSecondary
                                )
                            }
                            TextButton(
                                onClick = {
                                    selectedFriendId = ""
                                    selectedFriendLibrary = null
                                    friendLoadError = ""
                                }
                            ) {
                                Text("✕ Close", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("shows" to "Series", "movies" to "Movies").forEach { (key, label) ->
                                val selected = librarySegment == key
                                Button(
                                    onClick = { librarySegment = key },
                                    shape = RoundedCornerShape(50),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (selected) TvTimePalette.Yellow else TvTimePalette.SurfacePill,
                                        contentColor = if (selected) TvTimePalette.OnYellow else Color.White
                                    ),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(label, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }

                        if (!isBusy && lib == null) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = friendLoadError.ifBlank { "Could not load @$selectedFriendId's library." },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TvTimePalette.Error,
                                    textAlign = TextAlign.Center
                                )
                                Button(
                                    onClick = { openFriendLibrary(selectedFriendId) },
                                    shape = RoundedCornerShape(50),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = TvTimePalette.Yellow,
                                        contentColor = TvTimePalette.OnYellow
                                    )
                                ) {
                                    Text("↻ Retry", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                                }
                            }
                        } else if (!isBusy && lib != null) {
                            val filterOptions = if (librarySegment == "movies") {
                                listOf(
                                    "all" to "All (${unwatchedMovies.size})",
                                    "interested" to "Watchlist (${unwatchedMovies.count { !it.movie.watched }})",
                                    "completed" to "Watched (${unwatchedMovies.count { it.movie.watched }})"
                                )
                            } else {
                                listOf(
                                    "all" to "All (${unwatchedShows.size})",
                                    "in_progress" to "In Progress (${unwatchedShows.count { it.friendEff == "in_progress" }})",
                                    "completed" to "Completed (${unwatchedShows.count { it.friendEff == "completed" }})",
                                    "stopped" to "Stopped (${unwatchedShows.count { it.friendEff == "stopped" }})",
                                    "interested" to "Watchlist (${unwatchedShows.count { it.friendEff == "interested" }})"
                                )
                            }

                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(filterOptions, key = { it.first }) { (fKey, fLabel) ->
                                    val isSel = friendFilter == fKey
                                    Surface(
                                        onClick = { friendFilter = fKey },
                                        shape = RoundedCornerShape(50),
                                        color = if (isSel) TvTimePalette.Yellow else TvTimePalette.SurfacePill,
                                        border = BorderStroke(1.dp, if (isSel) TvTimePalette.Yellow else TvTimePalette.Border)
                                    ) {
                                        Text(
                                            text = fLabel,
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = if (isSel) TvTimePalette.OnYellow else TvTimePalette.TextSecondary,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (!isBusy && lib != null) {
                if (librarySegment == "movies") {
                    if (filteredFriendMovies.isEmpty()) {
                        item(key = "friend_movies_empty") {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = TvTimePalette.SurfaceCard,
                                border = BorderStroke(1.dp, TvTimePalette.Border),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "No unwatched movies in this category.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TvTimePalette.TextSecondary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(20.dp)
                                )
                            }
                        }
                    } else {
                        items(
                            items = filteredFriendMovies,
                            key = { row -> row.stableKey }
                        ) { row ->
                            val m = row.movie
                            val myMovie = row.myMovie
                            Surface(
                                onClick = {
                                    onOpenCatalogItem(
                                        SearchResultItem(
                                            externalId = m.externalId.ifBlank { if (m.tmdbId > 0) "movie_${m.tmdbId}" else "movie_${m.title.hashCode()}" },
                                            mediaType = "movie",
                                            tvmazeId = 0,
                                            tmdbId = m.tmdbId,
                                            title = m.title,
                                            year = m.year,
                                            networkOrDirector = m.streamingService.ifBlank { m.director },
                                            status = "Released",
                                            runtimeMins = m.runtimeMins,
                                            genresCsv = m.genresCsv,
                                            castCsv = m.castCsv,
                                            rating = m.rating,
                                            posterUrl = m.posterUrl,
                                            overview = m.overview
                                        )
                                    )
                                },
                                shape = RoundedCornerShape(18.dp),
                                color = TvTimePalette.SurfaceCard,
                                border = BorderStroke(1.dp, TvTimePalette.Border),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        NetworkPosterImage(
                                            url = m.posterUrl,
                                            title = m.title,
                                            width = 44.dp,
                                            height = 64.dp,
                                            cornerRadius = 10.dp
                                        )
                                        Column {
                                            Text(
                                                text = m.title,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                color = Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${m.year} · ${if (m.watched) "✓ Watched" else "Watchlist"}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (m.watched) Color(0xFF4ADE80) else TvTimePalette.Yellow
                                            )
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (myMovie == null) {
                                            TextButton(
                                                onClick = {
                                                    scope.launch {
                                                        dao.insertMovie(
                                                            m.copy(
                                                                id = 0,
                                                                userId = currentUserId,
                                                                watched = false,
                                                                watchedAt = ""
                                                            )
                                                        )
                                                        FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                                        Toast.makeText(context, "Added ${m.title} to Watchlist!", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            ) {
                                                Text("+ Watchlist", color = TvTimePalette.Yellow, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                            }
                                            TextButton(
                                                onClick = {
                                                    scope.launch {
                                                        dao.insertMovie(
                                                            m.copy(
                                                                id = 0,
                                                                userId = currentUserId,
                                                                watched = true,
                                                                watchedAt = java.time.LocalDate.now().toString()
                                                            )
                                                        )
                                                        FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                                        Toast.makeText(context, "Marked ${m.title} as Watched!", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            ) {
                                                Text("✓ Watched", color = Color(0xFF4ADE80), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                            }
                                        } else if (!myMovie.watched) {
                                            TextButton(
                                                onClick = {
                                                    scope.launch {
                                                        dao.updateMovie(
                                                            myMovie.copy(
                                                                watched = true,
                                                                watchedAt = java.time.LocalDate.now().toString()
                                                            )
                                                        )
                                                        FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                                        Toast.makeText(context, "Marked ${m.title} as Watched!", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            ) {
                                                Text("✓ Watched", color = Color(0xFF4ADE80), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                            }
                                        } else {
                                            Text(
                                                text = "✓ Watched",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                color = Color(0xFF4ADE80),
                                                modifier = Modifier.padding(horizontal = 8.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    if (filteredFriendShows.isEmpty()) {
                        item(key = "friend_shows_empty") {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = TvTimePalette.SurfaceCard,
                                border = BorderStroke(1.dp, TvTimePalette.Border),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "No unwatched series in this category.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TvTimePalette.TextSecondary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(20.dp)
                                )
                            }
                        }
                    } else {
                        items(
                            items = filteredFriendShows,
                            key = { row -> row.stableKey }
                        ) { row ->
                            val s = row.show
                            val myShow = row.myShow
                            val sEff = row.friendEff
                            val watchedCount = row.watchedCount
                            val totalCount = row.totalCount
                            Surface(
                                onClick = {
                                    val compMeta = TvTimeProgressCalculator.parseCompactedMeta(s.episodesJson)
                                    onOpenCatalogItem(
                                        SearchResultItem(
                                            externalId = s.externalId.ifBlank { if (s.tmdbId > 0) "tmdb_tv_${s.tmdbId}" else "show_${s.title.hashCode()}" },
                                            mediaType = "show",
                                            tvmazeId = s.tvmazeId,
                                            tmdbId = s.tmdbId,
                                            title = s.title,
                                            year = s.year,
                                            networkOrDirector = s.network,
                                            status = s.status,
                                            runtimeMins = s.runtimeMins,
                                            genresCsv = s.genresCsv,
                                            castCsv = s.castCsv,
                                            rating = s.rating,
                                            posterUrl = s.posterUrl,
                                            overview = s.overview,
                                            totalEpisodes = totalCount,
                                            countOnly = compMeta.countOnly || s.externalId.startsWith("imdb_tv_")
                                        )
                                    )
                                },
                                shape = RoundedCornerShape(18.dp),
                                color = TvTimePalette.SurfaceCard,
                                border = BorderStroke(1.dp, TvTimePalette.Border),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        NetworkPosterImage(
                                            url = s.posterUrl,
                                            title = s.title,
                                            width = 44.dp,
                                            height = 64.dp,
                                            cornerRadius = 10.dp
                                        )
                                        Column {
                                            Text(
                                                text = s.title,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                color = Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            val friendStatusLabel = when (sEff) {
                                                "completed" -> "✓ Completed ($totalCount eps)"
                                                "in_progress" -> "Watching · $watchedCount/$totalCount eps"
                                                "stopped" -> "Stopped · $watchedCount/$totalCount eps"
                                                else -> "Watchlist"
                                            }
                                            val friendStatusColor = when (sEff) {
                                                "completed" -> Color(0xFF4ADE80)
                                                "in_progress" -> TvTimePalette.Yellow
                                                "stopped" -> Color(0xFFFB7185)
                                                else -> TvTimePalette.TextSecondary
                                            }
                                            Text(
                                                text = "${s.year} · $friendStatusLabel",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = friendStatusColor
                                            )
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        val copyStatusSummary = when (sEff) {
                                            "completed" -> "Completed"
                                            "in_progress" -> "In Progress · $watchedCount/$totalCount eps"
                                            "stopped" -> "Stopped · $watchedCount/$totalCount eps"
                                            else -> "Watchlist"
                                        }
                                        if (myShow == null) {
                                            TextButton(
                                                onClick = {
                                                    scope.launch {
                                                        val sEps = withContext(Dispatchers.Default) {
                                                            TvTimeProgressCalculator.parseEpisodeList(s.episodesJson)
                                                        }
                                                        val cleanWatchlistJson = TvTimeProgressCalculator.serializeCompactedMeta(
                                                            totalEpisodes = totalCount,
                                                            completedSeasons = emptyMap(),
                                                            episodes = sEps
                                                        )
                                                        dao.insertShow(
                                                            s.copy(
                                                                id = 0,
                                                                userId = currentUserId,
                                                                userStatus = "interested",
                                                                watchedCodesCsv = "",
                                                                episodesJson = cleanWatchlistJson
                                                            )
                                                        )
                                                        FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                                        Toast.makeText(context, "Added ${s.title} to Watchlist!", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            ) {
                                                Text("+ Watchlist", color = TvTimePalette.Yellow, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                            }
                                            if (sEff != "interested") {
                                                TextButton(
                                                    onClick = {
                                                        scope.launch {
                                                            dao.insertShow(
                                                                s.copy(
                                                                    id = 0,
                                                                    userId = currentUserId,
                                                                    userStatus = sEff
                                                                )
                                                            )
                                                            FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                                            Toast.makeText(context, "Copied ${s.title} ($copyStatusSummary)!", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                ) {
                                                    Text("📋 Copy", color = Color(0xFF4ADE80), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                                }
                                            }
                                        } else if (sEff != "interested") {
                                            TextButton(
                                                onClick = {
                                                    scope.launch {
                                                        dao.updateShow(
                                                            myShow.copy(
                                                                userStatus = sEff,
                                                                watchedCodesCsv = s.watchedCodesCsv,
                                                                episodesJson = s.episodesJson,
                                                                runtimeMins = s.runtimeMins
                                                            )
                                                        )
                                                        FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                                        Toast.makeText(context, "Copied ${s.title} ($copyStatusSummary)!", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            ) {
                                                Text("📋 Copy", color = Color(0xFF4ADE80), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
