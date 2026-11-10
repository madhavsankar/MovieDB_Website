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
import kotlin.math.roundToInt
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.tvtime.tracker.BuildConfig
import com.tvtime.tracker.data.ActorEntity
import com.tvtime.tracker.data.FirestoreSyncService
import com.tvtime.tracker.data.LiveMediaCatalogService
import com.tvtime.tracker.data.MovieEntity
import com.tvtime.tracker.data.ShowEntity
import com.tvtime.tracker.data.TvTimeDao
import com.tvtime.tracker.domain.EpisodeModel
import com.tvtime.tracker.domain.SearchResultItem
import com.tvtime.tracker.domain.TvTimeProgressCalculator
import com.tvtime.tracker.domain.WatchStatsSummary
import com.tvtime.tracker.ui.theme.TvTimePalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    val shows by dao.observeShowsForUser(currentUserId).collectAsState(initial = emptyList())
    val movies by dao.observeMoviesForUser(currentUserId).collectAsState(initial = emptyList())
    val actors by dao.observeActorsForUser(currentUserId).collectAsState(initial = emptyList())
    val decidedIds = remember(actors) {
        actors.filter { it.role == "decided_rec" }.map { it.externalId }.toSet()
    }

    var refreshTrigger by remember { mutableIntStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }
    var pullOffsetPx by remember { mutableFloatStateOf(0f) }

    // When switching to Upcoming, refresh episode lists for running/stub series so newly announced future air dates appear
    LaunchedEffect(homeTopSegment, shows.size, refreshTrigger) {
        if (homeTopSegment == "upcoming") {
            val candidates = shows.filter {
                it.userStatus != "stopped" && !it.status.equals("Ended", ignoreCase = true)
            }
            var updatedAny = false
            for (s in candidates) {
                val currentEps = TvTimeProgressCalculator.parseEpisodeList(s.episodesJson)
                val hasUpcoming = TvTimeProgressCalculator.findNextUpcomingEpisode(currentEps) != null
                if (!hasUpcoming || currentEps.size <= 1) {
                    val (fetchedEps, fetchedCast) = LiveMediaCatalogService.fetchFullShowEpisodesAndCast(
                        tvmazeId = s.tvmazeId,
                        tmdbId = s.tmdbId,
                        title = s.title,
                        externalId = s.externalId
                    )
                    if (fetchedEps.size > currentEps.size ||
                        TvTimeProgressCalculator.findNextUpcomingEpisode(fetchedEps) != null
                    ) {
                        dao.updateShow(
                            s.copy(
                                episodesJson = TvTimeProgressCalculator.serializeEpisodeList(fetchedEps),
                                castCsv = if (s.castCsv.isBlank() && fetchedCast.isNotEmpty()) {
                                    fetchedCast.joinToString(", ")
                                } else s.castCsv
                            )
                        )
                        updatedAny = true
                    }
                }
            }
            if (updatedAny && FirestoreSyncService.isUserAllowed(currentUserId)) {
                FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
            }
        }
    }

    val triggerPullRefresh: () -> Unit = {
        if (!isRefreshing) {
            isRefreshing = true
            scope.launch {
                FirestoreSyncService.pullFromFirestoreIfNeeded(dao, currentUserId)
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
        if (searchQuery.isNotBlank()) delay(250)
        searchResults = if (searchQuery.isBlank() && selectedUsPlatforms.isNotEmpty()) {
            LiveMediaCatalogService.discoverByUsProviders(selectedUsPlatforms)
        } else {
            val raw = LiveMediaCatalogService.searchMulti(searchQuery.ifBlank { "Game of Thrones" })
                .filter { it.mediaType != "actor" }
            if (selectedUsPlatforms.isNotEmpty()) {
                raw.filter { LiveMediaCatalogService.matchesUsPlatforms(it.networkOrDirector, selectedUsPlatforms) }
            } else {
                raw
            }
        }
        isSearching = false
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
                    verticalAlignment = Alignment.CenterVertically
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
                                if (mediaSubFilter != "in_progress" && mediaSubFilter != "interested" && mediaSubFilter != "completed" && mediaSubFilter != "stopped") {
                                    mediaSubFilter = "in_progress"
                                }
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
                                var eps = TvTimeProgressCalculator.parseEpisodeList(show.episodesJson)
                                var castCsv = show.castCsv
                                if (eps.size <= 1) {
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
                                val watched = TvTimeProgressCalculator.parseWatchedSet(show.watchedCodesCsv)
                                val prog = TvTimeProgressCalculator.computeShowProgress(eps, watched)
                                val isAlreadyCompleted = show.userStatus == "completed" || prog.isCompleted
                                val updatedShow = if (isAlreadyCompleted) {
                                    show.copy(
                                        watchedCodesCsv = "",
                                        userStatus = "interested",
                                        episodesJson = TvTimeProgressCalculator.serializeEpisodeList(eps),
                                        castCsv = castCsv
                                    )
                                } else {
                                    val allCodes = eps.map { it.code.uppercase() }.toSet()
                                    show.copy(
                                        watchedCodesCsv = TvTimeProgressCalculator.serializeWatchedSet(allCodes),
                                        userStatus = "completed",
                                        episodesJson = TvTimeProgressCalculator.serializeEpisodeList(eps),
                                        castCsv = castCsv
                                    )
                                }
                                dao.updateShow(updatedShow)
                                FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                            }
                        },
                        onToggleMovieWatched = { movie ->
                            scope.launch {
                                dao.updateMovie(movie.copy(watched = !movie.watched))
                                FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                            }
                        }
                    )
                }

                "search" -> {
                    val watchlistExternalIds = remember(shows, movies) {
                        val showWatchlist = shows.filter { s ->
                            val eps = TvTimeProgressCalculator.parseEpisodeList(s.episodesJson)
                            val watched = TvTimeProgressCalculator.parseWatchedSet(s.watchedCodesCsv)
                            val prog = TvTimeProgressCalculator.computeShowProgress(eps, watched)
                            TvTimeProgressCalculator.deriveEffectiveShowStatus(prog, s.userStatus) == "interested"
                        }.map { it.externalId }
                        val movieWatchlist = movies.filter { !it.watched }.map { it.externalId }
                        (showWatchlist + movieWatchlist).toSet()
                    }
                    val completedExternalIds = remember(shows, movies) {
                        val showCompleted = shows.filter { s ->
                            val eps = TvTimeProgressCalculator.parseEpisodeList(s.episodesJson)
                            val watched = TvTimeProgressCalculator.parseWatchedSet(s.watchedCodesCsv)
                            val prog = TvTimeProgressCalculator.computeShowProgress(eps, watched)
                            TvTimeProgressCalculator.deriveEffectiveShowStatus(prog, s.userStatus) == "completed"
                        }.map { it.externalId }
                        val movieCompleted = movies.filter { it.watched }.map { it.externalId }
                        (showCompleted + movieCompleted).toSet()
                    }

                    SearchTabScreen(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        sortKey = searchSort,
                        onSortChange = { searchSort = it },
                        selectedUsPlatforms = selectedUsPlatforms,
                        onToggleUsPlatform = toggleUsPlatform,
                        results = searchResults,
                        isSearching = isSearching,
                        watchlistExternalIds = watchlistExternalIds,
                        completedExternalIds = completedExternalIds,
                        onAddOrOpenResult = { item ->
                            scope.launch {
                                when (item.mediaType) {
                                    "movie" -> {
                                        val existingMovie = movies.firstOrNull { it.externalId == item.externalId }
                                        if (existingMovie != null) {
                                            selectedMovieForDetail = existingMovie
                                        } else {
                                            val newMovie = MovieEntity(
                                                userId = currentUserId,
                                                externalId = item.externalId,
                                                tmdbId = item.tmdbId,
                                                title = item.title,
                                                year = item.year,
                                                runtimeMins = item.runtimeMins,
                                                genresCsv = item.genresCsv,
                                                director = item.networkOrDirector,
                                                streamingService = item.networkOrDirector,
                                                rating = item.rating,
                                                posterUrl = item.posterUrl,
                                                overview = item.overview,
                                                watched = false
                                            )
                                            val newId = dao.insertMovie(newMovie)
                                            FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                            selectedMovieForDetail = newMovie.copy(id = newId)
                                        }
                                    }
                                    else -> {
                                        val existing = shows.firstOrNull { it.externalId == item.externalId }
                                        if (existing != null) {
                                            selectedShowForDetail = existing
                                        } else {
                                            val (fetchedEps, fetchedCast) = LiveMediaCatalogService.fetchFullShowEpisodesAndCast(
                                                tvmazeId = item.tvmazeId,
                                                tmdbId = item.tmdbId,
                                                title = item.title,
                                                externalId = item.externalId
                                            )
                                            val finalEps = if (fetchedEps.isNotEmpty()) fetchedEps else listOf(
                                                EpisodeModel("S01E01", 1, 1, "Pilot", "${item.year}-01-01", item.runtimeMins)
                                            )
                                            val newShow = ShowEntity(
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
                                                castCsv = fetchedCast.joinToString(", "),
                                                rating = item.rating,
                                                posterUrl = item.posterUrl,
                                                overview = item.overview,
                                                userStatus = "interested",
                                                episodesJson = TvTimeProgressCalculator.serializeEpisodeList(finalEps)
                                            )
                                            val newId = dao.insertShow(newShow)
                                            FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                            selectedShowForDetail = newShow.copy(id = newId)
                                        }
                                    }
                                }
                            }
                        },
                        onQuickWatchlistToggle = { item ->
                            scope.launch {
                                when (item.mediaType) {
                                    "movie" -> {
                                        val existingMovie = movies.firstOrNull { it.externalId == item.externalId }
                                        if (existingMovie != null) {
                                            if (!existingMovie.watched) {
                                                dao.deleteMovie(existingMovie.id)
                                            } else {
                                                dao.updateMovie(existingMovie.copy(watched = false, watchedAt = ""))
                                            }
                                        } else {
                                            val newMovie = MovieEntity(
                                                userId = currentUserId,
                                                externalId = item.externalId,
                                                tmdbId = item.tmdbId,
                                                title = item.title,
                                                year = item.year,
                                                runtimeMins = item.runtimeMins,
                                                genresCsv = item.genresCsv,
                                                director = item.networkOrDirector,
                                                streamingService = item.networkOrDirector,
                                                rating = item.rating,
                                                posterUrl = item.posterUrl,
                                                overview = item.overview,
                                                watched = false
                                            )
                                            dao.insertMovie(newMovie)
                                        }
                                        FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                    }
                                    else -> {
                                        val existing = shows.firstOrNull { it.externalId == item.externalId }
                                        if (existing != null) {
                                            val eps = TvTimeProgressCalculator.parseEpisodeList(existing.episodesJson)
                                            val watched = TvTimeProgressCalculator.parseWatchedSet(existing.watchedCodesCsv)
                                            val prog = TvTimeProgressCalculator.computeShowProgress(eps, watched)
                                            val eff = TvTimeProgressCalculator.deriveEffectiveShowStatus(prog, existing.userStatus)
                                            if (eff == "interested") {
                                                dao.deleteShow(existing.id)
                                            } else {
                                                dao.updateShow(existing.copy(userStatus = "interested", watchedCodesCsv = ""))
                                            }
                                        } else {
                                            val (fetchedEps, fetchedCast) = LiveMediaCatalogService.fetchFullShowEpisodesAndCast(
                                                tvmazeId = item.tvmazeId,
                                                tmdbId = item.tmdbId,
                                                title = item.title,
                                                externalId = item.externalId
                                            )
                                            val finalEps = if (fetchedEps.isNotEmpty()) fetchedEps else listOf(
                                                EpisodeModel("S01E01", 1, 1, "Pilot", "${item.year}-01-01", item.runtimeMins)
                                            )
                                            val newShow = ShowEntity(
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
                                                castCsv = fetchedCast.joinToString(", "),
                                                rating = item.rating,
                                                posterUrl = item.posterUrl,
                                                overview = item.overview,
                                                userStatus = "interested",
                                                episodesJson = TvTimeProgressCalculator.serializeEpisodeList(finalEps)
                                            )
                                            dao.insertShow(newShow)
                                        }
                                        FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                    }
                                }
                            }
                        },
                        onQuickCompletedToggle = { item ->
                            scope.launch {
                                when (item.mediaType) {
                                    "movie" -> {
                                        val existingMovie = movies.firstOrNull { it.externalId == item.externalId }
                                        val todayIso = java.time.LocalDate.now().toString()
                                        if (existingMovie != null) {
                                            val nextWatched = !existingMovie.watched
                                            dao.updateMovie(
                                                existingMovie.copy(
                                                    watched = nextWatched,
                                                    watchedAt = if (nextWatched) todayIso else ""
                                                )
                                            )
                                        } else {
                                            val newMovie = MovieEntity(
                                                userId = currentUserId,
                                                externalId = item.externalId,
                                                tmdbId = item.tmdbId,
                                                title = item.title,
                                                year = item.year,
                                                runtimeMins = item.runtimeMins,
                                                genresCsv = item.genresCsv,
                                                director = item.networkOrDirector,
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
                                    }
                                    else -> {
                                        val existing = shows.firstOrNull { it.externalId == item.externalId }
                                        if (existing != null) {
                                            val eps = TvTimeProgressCalculator.parseEpisodeList(existing.episodesJson)
                                            val watched = TvTimeProgressCalculator.parseWatchedSet(existing.watchedCodesCsv)
                                            val prog = TvTimeProgressCalculator.computeShowProgress(eps, watched)
                                            val eff = TvTimeProgressCalculator.deriveEffectiveShowStatus(prog, existing.userStatus)
                                            if (eff == "completed") {
                                                dao.updateShow(existing.copy(userStatus = "interested", watchedCodesCsv = ""))
                                            } else {
                                                val allCodes = eps.map { it.code }.toSet()
                                                dao.updateShow(
                                                    existing.copy(
                                                        userStatus = "completed",
                                                        watchedCodesCsv = TvTimeProgressCalculator.serializeWatchedSet(allCodes)
                                                    )
                                                )
                                            }
                                        } else {
                                            val (fetchedEps, fetchedCast) = LiveMediaCatalogService.fetchFullShowEpisodesAndCast(
                                                tvmazeId = item.tvmazeId,
                                                tmdbId = item.tmdbId,
                                                title = item.title,
                                                externalId = item.externalId
                                            )
                                            val finalEps = if (fetchedEps.isNotEmpty()) fetchedEps else listOf(
                                                EpisodeModel("S01E01", 1, 1, "Pilot", "${item.year}-01-01", item.runtimeMins)
                                            )
                                            val allCodes = finalEps.map { it.code }.toSet()
                                            val newShow = ShowEntity(
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
                                                castCsv = fetchedCast.joinToString(", "),
                                                rating = item.rating,
                                                posterUrl = item.posterUrl,
                                                overview = item.overview,
                                                userStatus = "completed",
                                                watchedCodesCsv = TvTimeProgressCalculator.serializeWatchedSet(allCodes),
                                                episodesJson = TvTimeProgressCalculator.serializeEpisodeList(finalEps)
                                            )
                                            dao.insertShow(newShow)
                                        }
                                        FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                    }
                                }
                            }
                        }
                    )
                }

                "swipe" -> {
                    SwipeRecommendationsTabScreen(
                        shows = shows,
                        movies = movies,
                        decidedIds = decidedIds,
                        swipeFilter = swipeMediaFilter,
                        onSelectSwipeFilter = { swipeMediaFilter = it },
                        onSwipeDecision = { item, action ->
                            scope.launch {
                                // 1. Record decision so we never suggest this again
                                if (!decidedIds.contains(item.externalId)) {
                                    dao.insertActor(
                                        ActorEntity(
                                            userId = currentUserId,
                                            externalId = item.externalId,
                                            name = item.title.lowercase(),
                                            role = "decided_rec"
                                        )
                                    )
                                }

                                // 2. If watchlist or completed, add to DB
                                if (action == "watchlist" || action == "completed") {
                                    val isCompleted = action == "completed"
                                    if (item.mediaType == "movie") {
                                        val existingMovie = movies.firstOrNull { it.externalId == item.externalId }
                                        if (existingMovie == null) {
                                            dao.insertMovie(
                                                MovieEntity(
                                                    userId = currentUserId,
                                                    externalId = item.externalId,
                                                    tmdbId = item.tmdbId,
                                                    title = item.title,
                                                    year = item.year,
                                                    runtimeMins = item.runtimeMins,
                                                    genresCsv = item.genresCsv,
                                                    director = item.networkOrDirector,
                                                    streamingService = item.networkOrDirector,
                                                    rating = item.rating,
                                                    posterUrl = item.posterUrl,
                                                    overview = item.overview,
                                                    watched = isCompleted
                                                )
                                            )
                                        }
                                    } else {
                                        val existingShow = shows.firstOrNull { it.externalId == item.externalId }
                                        if (existingShow == null) {
                                            val (fetchedEps, fetchedCast) = LiveMediaCatalogService.fetchFullShowEpisodesAndCast(
                                                tvmazeId = item.tvmazeId,
                                                tmdbId = item.tmdbId,
                                                title = item.title,
                                                externalId = item.externalId
                                            )
                                            val finalEps = if (fetchedEps.isNotEmpty()) fetchedEps else listOf(
                                                EpisodeModel("S01E01", 1, 1, "Pilot", "${item.year}-01-01", item.runtimeMins)
                                            )
                                            val watchedCsv = if (isCompleted) {
                                                TvTimeProgressCalculator.serializeWatchedSet(
                                                    finalEps.map { it.code.uppercase() }.toSet()
                                                )
                                            } else ""
                                            dao.insertShow(
                                                ShowEntity(
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
                                                    castCsv = fetchedCast.joinToString(", "),
                                                    rating = item.rating,
                                                    posterUrl = item.posterUrl,
                                                    overview = item.overview,
                                                    userStatus = if (isCompleted) "completed" else "interested",
                                                    watchedCodesCsv = watchedCsv,
                                                    episodesJson = TvTimeProgressCalculator.serializeEpisodeList(finalEps)
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
                        shows = shows,
                        movies = movies,
                        onBack = navigateBack
                    )
                }

                "recent" -> {
                    RecentlyWatchedFullPageScreen(
                        shows = shows,
                        movies = movies,
                        onOpenShow = { selectedShowForDetail = it },
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
                                FirestoreSyncService.pullFromFirestoreIfNeeded(dao, currentUserId)
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
            }

            // Pull-to-Refresh Top Floating Pill Indicator
            if (pullOffsetPx > 20f || isRefreshing) {
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
                            pullOffsetPx >= 130f -> "↻ Release to refresh"
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
        val latestFromList = shows.firstOrNull { it.id == activeShow.id } ?: activeShow
        ShowDetailBottomSheet(
            show = latestFromList,
            dao = dao,
            currentUserId = currentUserId,
            onDismiss = { selectedShowForDetail = null }
        )
    }

    // Movie Detail Bottom Sheet
    selectedMovieForDetail?.let { activeMovie ->
        val latestMovie = movies.firstOrNull { it.id == activeMovie.id } ?: activeMovie
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
            onClick = { expanded = true },
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
                onClick = { expanded = true },
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
                        onClick = { onTogglePlatform(platform) }
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
            val partitioned = platformFilteredShows.map { s ->
                val eps = TvTimeProgressCalculator.parseEpisodeList(s.episodesJson)
                val w = TvTimeProgressCalculator.parseWatchedSet(s.watchedCodesCsv)
                val prog = TvTimeProgressCalculator.computeShowProgress(eps, w)
                val effectiveStatus = TvTimeProgressCalculator.deriveEffectiveShowStatus(prog, s.userStatus)
                s to effectiveStatus
            }
            val startedInProgress = partitioned.filter { it.second == "in_progress" }.map { it.first }
            val notStarted = partitioned.filter { it.second == "interested" }.map { it.first }
            val completed = partitioned.filter { it.second == "completed" }.map { it.first }
            val stopped = partitioned.filter { it.second == "stopped" }.map { it.first }

            val activeShowsList = when (mediaFilter) {
                "completed" -> completed
                "interested" -> notStarted
                "stopped" -> stopped
                else -> startedInProgress
            }

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
                platformFilteredMovies.filter { it.watched }
            } else {
                platformFilteredMovies.filter { !it.watched }
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
            val upcomingEntries = platformFilteredShows
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
                                        text = "TV Show · ${show.network}",
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
    watchlistExternalIds: Set<String>,
    completedExternalIds: Set<String>,
    onAddOrOpenResult: (SearchResultItem) -> Unit,
    onQuickWatchlistToggle: (SearchResultItem) -> Unit,
    onQuickCompletedToggle: (SearchResultItem) -> Unit
) {
    val focusManager = LocalFocusManager.current
    val sortedResults = remember(results, sortKey) {
        when (sortKey) {
            "newest" -> results.sortedByDescending { it.year }
            "oldest" -> results.sortedBy { it.year }
            else -> results
        }
    }

    LazyColumn(
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
                    text = if (isSearching) "Searching..." else "${sortedResults.size} results",
                    style = MaterialTheme.typography.bodySmall,
                    color = TvTimePalette.TextSecondary
                )
            }
        }

        // 3. Result Cards
        items(sortedResults, key = { it.externalId }) { item ->
            val isInWatchlist = watchlistExternalIds.contains(item.externalId)
            val isCompleted = completedExternalIds.contains(item.externalId)
            TvSearchResultCard(
                item = item,
                isInWatchlist = isInWatchlist,
                isCompleted = isCompleted,
                onCardClick = { onAddOrOpenResult(item) },
                onWatchlistClick = { onQuickWatchlistToggle(item) },
                onCompletedClick = { onQuickCompletedToggle(item) }
            )
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
    onSwipeDecision: (SearchResultItem, String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var rawDeck by remember { mutableStateOf<List<SearchResultItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var currentPage by remember { mutableStateOf(1) }
    var localDismissedIds by remember { mutableStateOf(emptySet<String>()) }

    val existingIds = remember(shows, movies) {
        (shows.map { it.externalId } + movies.map { it.externalId }).toSet()
    }
    val existingTitles = remember(shows, movies) {
        (shows.map { it.title.lowercase().trim() } + movies.map { it.title.lowercase().trim() }).toSet()
    }

    val loadMore: (Int) -> Unit = { pageToFetch ->
        if (!isLoading) {
            isLoading = true
            scope.launch {
                val fetched = LiveMediaCatalogService.fetchRecommendations(
                    shows = shows,
                    movies = movies,
                    page = pageToFetch
                )
                val merged = (rawDeck + fetched).distinctBy { it.externalId }
                rawDeck = merged
                isLoading = false
            }
        }
    }

    LaunchedEffect(shows.size, movies.size) {
        if (rawDeck.isEmpty()) {
            loadMore(1)
        }
    }

    val filteredDeck = remember(rawDeck, existingIds, existingTitles, decidedIds, localDismissedIds, swipeFilter) {
        rawDeck.filter { item ->
            !existingIds.contains(item.externalId) &&
                !existingTitles.contains(item.title.lowercase().trim()) &&
                !decidedIds.contains(item.externalId) &&
                !localDismissedIds.contains(item.externalId) &&
                (swipeFilter == "all" || item.mediaType == swipeFilter)
        }
    }

    LaunchedEffect(filteredDeck.size) {
        if (filteredDeck.size <= 3 && !isLoading && rawDeck.isNotEmpty() && currentPage < 5) {
            val nextPage = currentPage + 1
            currentPage = nextPage
            loadMore(nextPage)
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
                                loadMore(next)
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
                                if (topItem.status.startsWith("Because")) {
                                    Text(
                                        text = "✨ ${topItem.status}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                                        color = Color(0xFF4ADE80),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
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

@Composable
private fun StatisticsFullPageScreen(
    shows: List<ShowEntity>,
    movies: List<MovieEntity>,
    onBack: () -> Unit
) {
    val stats = remember(shows, movies) {
        var epCount = 0
        var showMins = 0
        for (s in shows) {
            val watchedCount = TvTimeProgressCalculator.parseWatchedSet(s.watchedCodesCsv).size
            epCount += watchedCount
            showMins += watchedCount * s.runtimeMins.coerceAtLeast(15)
        }
        var movieCount = 0
        var movieMins = 0
        for (m in movies) {
            if (m.watched) {
                movieCount += 1
                movieMins += m.runtimeMins.coerceAtLeast(45)
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

    val completedShowsCount = remember(shows) {
        shows.count { s ->
            val eps = TvTimeProgressCalculator.parseEpisodeList(s.episodesJson)
            val w = TvTimeProgressCalculator.parseWatchedSet(s.watchedCodesCsv)
            val prog = TvTimeProgressCalculator.computeShowProgress(eps, w)
            TvTimeProgressCalculator.deriveEffectiveShowStatus(prog, s.userStatus) == "completed"
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
                    value = completedShowsCount.toString(),
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
                Surface(
                    onClick = onSyncNow,
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
    onOpenMovie: (MovieEntity) -> Unit,
    onBack: () -> Unit
) {
    val watchedOrInProgressShows = remember(shows) {
        shows.filter { s ->
            val eps = TvTimeProgressCalculator.parseEpisodeList(s.episodesJson)
            val w = TvTimeProgressCalculator.parseWatchedSet(s.watchedCodesCsv)
            val prog = TvTimeProgressCalculator.computeShowProgress(eps, w)
            val effectiveStatus = TvTimeProgressCalculator.deriveEffectiveShowStatus(prog, s.userStatus)
            effectiveStatus == "completed" || effectiveStatus == "in_progress"
        }
    }
    val watchedMovies = remember(movies) { movies.filter { it.watched } }

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
                            val watched = TvTimeProgressCalculator.parseWatchedSet(show.watchedCodesCsv)
                            val eps = TvTimeProgressCalculator.parseEpisodeList(show.episodesJson)
                            val prog = TvTimeProgressCalculator.computeShowProgress(eps, watched)
                            val badgeLabel = if (show.userStatus == "completed" || prog.isCompleted) {
                                "Watched (${watched.size}/${eps.size})"
                            } else {
                                "In Progress (${watched.size}/${eps.size})"
                            }

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
                                Text(
                                    text = "Watched",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF4ADE80)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
