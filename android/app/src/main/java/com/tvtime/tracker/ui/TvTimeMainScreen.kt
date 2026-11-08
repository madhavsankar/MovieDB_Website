package com.tvtime.tracker.ui

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

@Composable
fun TvTimeMainScreen(
    dao: TvTimeDao,
    initialUserId: String,
    onSwitchUser: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentUserId by remember { mutableStateOf(initialUserId) }
    var activeBottomTab by remember { mutableStateOf("home") } // "home" | "search" | "profile"

    // Home Tab State (Screenshots 3, 7, 8, iPad 2)
    var homeTopSegment by remember { mutableStateOf("shows") } // "shows" | "movies" | "upcoming"
    var mediaSubFilter by remember { mutableStateOf("in_progress") } // "all" | "in_progress" | "completed"
    var upcomingSubFilter by remember { mutableStateOf("watchlist") } // "watchlist" | "actors"
    var is3DShelfMode by remember { mutableStateOf(false) }
    var havenStartedCollapsed by remember { mutableStateOf(false) }

    // Search Tab State (Screenshot 1)
    var searchQuery by remember { mutableStateOf("") }
    var searchSort by remember { mutableStateOf("top") } // "top" | "newest" | "oldest"
    var searchResults by remember { mutableStateOf<List<SearchResultItem>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    // Modals & Bottom Sheets
    var selectedShowForDetail by remember { mutableStateOf<ShowEntity?>(null) }
    var showFollowingSheet by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showAccountSwitcherDialog by remember { mutableStateOf(false) }

    val shows by dao.observeShowsForUser(currentUserId).collectAsState(initial = emptyList())
    val movies by dao.observeMoviesForUser(currentUserId).collectAsState(initial = emptyList())
    val actors by dao.observeActorsForUser(currentUserId).collectAsState(initial = emptyList())

    // Initial Search Results load
    LaunchedEffect(searchQuery) {
        isSearching = true
        if (searchQuery.isNotBlank()) delay(250)
        searchResults = LiveMediaCatalogService.searchMulti(searchQuery.ifBlank { "Game of Thrones" })
        isSearching = false
    }

    Scaffold(
        containerColor = TvTimePalette.Background,
        bottomBar = {
            NavigationBar(
                containerColor = TvTimePalette.Background,
                contentColor = Color.White,
                tonalElevation = 0.dp
            ) {
                val navItems = listOf(
                    "home" to Icons.Default.Home,
                    "search" to Icons.Default.Search,
                    "profile" to Icons.Default.Person
                )
                navItems.forEach { (key, icon) ->
                    val selected = activeBottomTab == key
                    NavigationBarItem(
                        selected = selected,
                        onClick = { activeBottomTab = key },
                        icon = {
                            Icon(
                                imageVector = icon,
                                contentDescription = key,
                                tint = if (selected) TvTimePalette.Yellow else TvTimePalette.TextSecondary,
                                modifier = Modifier.size(26.dp)
                            )
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
        ) {
            when (activeBottomTab) {
                "home" -> {
                    HomeTabScreen(
                        shows = shows,
                        movies = movies,
                        actors = actors,
                        topSegment = homeTopSegment,
                        onSelectTopSegment = { homeTopSegment = it },
                        mediaFilter = mediaSubFilter,
                        onSelectMediaFilter = { mediaSubFilter = it },
                        upcomingFilter = upcomingSubFilter,
                        onSelectUpcomingFilter = { upcomingSubFilter = it },
                        is3DShelfMode = is3DShelfMode,
                        onToggle3DShelf = { is3DShelfMode = !is3DShelfMode },
                        havenStartedCollapsed = havenStartedCollapsed,
                        onToggleHavenStarted = { havenStartedCollapsed = !havenStartedCollapsed },
                        onOpenShow = { selectedShowForDetail = it },
                        onMarkNextEpisode = { show ->
                            val eps = TvTimeProgressCalculator.parseEpisodeList(show.episodesJson)
                            val watched = TvTimeProgressCalculator.parseWatchedSet(show.watchedCodesCsv).toMutableSet()
                            val prog = TvTimeProgressCalculator.computeShowProgress(eps, watched)
                            prog.nextEpisode?.let { nextEp ->
                                watched.add(nextEp.code.uppercase())
                                val updatedCsv = TvTimeProgressCalculator.serializeWatchedSet(watched)
                                scope.launch {
                                    dao.updateShow(show.copy(watchedCodesCsv = updatedCsv))
                                    FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                }
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
                    SearchTabScreen(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        sortKey = searchSort,
                        onSortChange = { searchSort = it },
                        results = searchResults,
                        isSearching = isSearching,
                        existingExternalIds = (shows.map { it.externalId } + movies.map { it.externalId } + actors.map { it.externalId }).toSet(),
                        onAddOrOpenResult = { item ->
                            scope.launch {
                                when (item.mediaType) {
                                    "actor" -> {
                                        if (actors.none { it.externalId == item.externalId }) {
                                            dao.insertActor(
                                                ActorEntity(
                                                    userId = currentUserId,
                                                    externalId = item.externalId,
                                                    name = item.title,
                                                    role = "Acting",
                                                    imageUrl = item.posterUrl,
                                                    imdbUrl = item.imdbUrl
                                                )
                                            )
                                            FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                            Toast.makeText(context, "Following ${item.title}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    "movie" -> {
                                        if (movies.none { it.externalId == item.externalId }) {
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
                                                    rating = item.rating,
                                                    posterUrl = item.posterUrl,
                                                    overview = item.overview,
                                                    watched = false
                                                )
                                            )
                                            FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                            Toast.makeText(context, "Added ${item.title} to Movies Watch List", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    else -> {
                                        val existing = shows.firstOrNull { it.externalId == item.externalId }
                                        if (existing != null) {
                                            selectedShowForDetail = existing
                                        } else {
                                            val (fetchedEps, fetchedCast) = LiveMediaCatalogService.fetchFullShowEpisodesAndCast(item.tvmazeId)
                                            val finalEps = if (fetchedEps.isNotEmpty()) fetchedEps else listOf(
                                                EpisodeModel("S01E01", 1, 1, "Pilot", "${item.year}-01-01", item.runtimeMins)
                                            )
                                            val newShow = ShowEntity(
                                                userId = currentUserId,
                                                externalId = item.externalId,
                                                tvmazeId = item.tvmazeId,
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
                                                episodesJson = TvTimeProgressCalculator.serializeEpisodeList(finalEps)
                                            )
                                            val newId = dao.insertShow(newShow)
                                            FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                            selectedShowForDetail = newShow.copy(id = newId)
                                        }
                                    }
                                }
                            }
                        }
                    )
                }

                "profile" -> {
                    ProfileTabScreen(
                        currentUserId = currentUserId,
                        shows = shows,
                        movies = movies,
                        actors = actors,
                        onOpenShow = { selectedShowForDetail = it },
                        onOpenFollowing = { showFollowingSheet = true },
                        onOpenImport = { showImportDialog = true },
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
                        }
                    )
                }
            }
        }
    }

    // Show Detail Bottom Sheet (Screenshot 4)
    selectedShowForDetail?.let { activeShow ->
        val latestFromList = shows.firstOrNull { it.id == activeShow.id } ?: activeShow
        ShowDetailBottomSheet(
            show = latestFromList,
            dao = dao,
            currentUserId = currentUserId,
            onDismiss = { selectedShowForDetail = null }
        )
    }

    // Following Bottom Sheet (Screenshot 6)
    if (showFollowingSheet) {
        FollowingBottomSheet(
            actors = actors,
            currentUserId = currentUserId,
            onSwitchUser = { nextUser ->
                val clean = FirestoreSyncService.sanitizeUserId(nextUser)
                currentUserId = clean
                onSwitchUser(clean)
                scope.launch {
                    FirestoreSyncService.pullFromFirestoreIfNeeded(dao, clean)
                }
            },
            onDismiss = { showFollowingSheet = false }
        )
    }

    // TV Time History Import Dialog (Screenshot 5)
    if (showImportDialog) {
        TvTimeImportHistoryDialog(
            onDismiss = { showImportDialog = false },
            onConfirmSampleImport = {
                showImportDialog = false
                scope.launch {
                    FirestoreSyncService.pullFromFirestoreIfNeeded(dao, currentUserId)
                    FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                    Toast.makeText(context, "TV Time history synced!", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // 2-User Account & Google Sign-In Switcher Dialog
    if (showAccountSwitcherDialog) {
        var manualInput by remember(currentUserId) { mutableStateOf(currentUserId) }
        val googleLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                val account = task.getResult(ApiException::class.java)
                val email = account?.email?.trim()?.lowercase() ?: ""
                if (email.isNotBlank() && FirestoreSyncService.isUserAllowed(email)) {
                    val clean = FirestoreSyncService.sanitizeUserId(email)
                    currentUserId = clean
                    onSwitchUser(clean)
                    showAccountSwitcherDialog = false
                    scope.launch {
                        FirestoreSyncService.pullFromFirestoreIfNeeded(dao, clean)
                    }
                }
            } catch (_: ApiException) {}
        }

        AlertDialog(
            onDismissRequest = { showAccountSwitcherDialog = false },
            containerColor = Color(0xFF18181D),
            title = { Text("Switch Profile (2-User Sync)", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Switch between your 2 household profiles or sign in with Google to sync with Cloud Firestore:",
                        style = MaterialTheme.typography.bodySmall,
                        color = TvTimePalette.TextSecondary
                    )
                    OutlinedTextField(
                        value = manualInput,
                        onValueChange = { manualInput = it },
                        singleLine = true,
                        label = { Text("Profile Username") }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { manualInput = "madhavsankar" },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("User 1", color = TvTimePalette.Yellow, fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = { manualInput = "partner" },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("User 2", color = TvTimePalette.Yellow, fontSize = 12.sp)
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                                .requestEmail()
                            val webClientId = BuildConfig.GOOGLE_CLIENT_ID.trim()
                            if (webClientId.isNotEmpty()) {
                                gsoBuilder.requestIdToken(webClientId)
                            }
                            val client = GoogleSignIn.getClient(context, gsoBuilder.build())
                            googleLauncher.launch(client.signInIntent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🔐 Sign in with Google", color = Color.White)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = FirestoreSyncService.sanitizeUserId(manualInput)
                        currentUserId = clean
                        onSwitchUser(clean)
                        showAccountSwitcherDialog = false
                        scope.launch {
                            FirestoreSyncService.pullFromFirestoreIfNeeded(dao, clean)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TvTimePalette.Yellow,
                        contentColor = TvTimePalette.OnYellow
                    )
                ) {
                    Text("Switch & Sync", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAccountSwitcherDialog = false }) {
                    Text("Cancel", color = Color.White)
                }
            }
        )
    }
}


@Composable
private fun HomeTabScreen(
    shows: List<ShowEntity>,
    movies: List<MovieEntity>,
    actors: List<ActorEntity>,
    topSegment: String,
    onSelectTopSegment: (String) -> Unit,
    mediaFilter: String,
    onSelectMediaFilter: (String) -> Unit,
    upcomingFilter: String,
    onSelectUpcomingFilter: (String) -> Unit,
    is3DShelfMode: Boolean,
    onToggle3DShelf: () -> Unit,
    havenStartedCollapsed: Boolean,
    onToggleHavenStarted: () -> Unit,
    onOpenShow: (ShowEntity) -> Unit,
    onMarkNextEpisode: (ShowEntity) -> Unit,
    onToggleMovieWatched: (MovieEntity) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Top Segmented Pill Bar: Shows | Movies | Upcoming (Screenshots 3 & 7)
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

        // 2. Sub-filter Row
        item(key = "subfilter_row") {
            if (topSegment != "upcoming") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Watch List",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (mediaFilter == "all") TvTimePalette.Yellow else TvTimePalette.TextSecondary,
                        modifier = Modifier
                            .clickable { onSelectMediaFilter("all") }
                            .padding(horizontal = 4.dp, vertical = 6.dp)
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = TvTimePalette.SurfacePill
                        ) {
                            Row(modifier = Modifier.padding(4.dp)) {
                                listOf(
                                    "in_progress" to "In progress",
                                    "completed" to "Completed"
                                ).forEach { (key, label) ->
                                    val active = mediaFilter == key
                                    Surface(
                                        onClick = { onSelectMediaFilter(key) },
                                        shape = RoundedCornerShape(50),
                                        color = if (active) TvTimePalette.Yellow else Color.Transparent
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (active) TvTimePalette.OnYellow else TvTimePalette.TextSecondary,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // 3D Shelf View Toggle Button (iPad Screenshot 2 & Screenshot 8)
                        Surface(
                            onClick = onToggle3DShelf,
                            shape = CircleShape,
                            color = if (is3DShelfMode) TvTimePalette.Yellow else TvTimePalette.SurfacePill,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "⊞",
                                    fontSize = 15.sp,
                                    color = if (is3DShelfMode) TvTimePalette.OnYellow else Color.White
                                )
                            }
                        }
                    }
                }
            } else {
                // Upcoming Sub-Filter: Upcoming | [Watchlist | Actors] (Screenshot 7)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Upcoming",
                        style = MaterialTheme.typography.titleMedium,
                        color = TvTimePalette.TextSecondary,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = TvTimePalette.SurfacePill
                    ) {
                        Row(modifier = Modifier.padding(4.dp)) {
                            listOf(
                                "watchlist" to "Watchlist",
                                "actors" to "Actors"
                            ).forEach { (key, label) ->
                                val active = upcomingFilter == key
                                Surface(
                                    onClick = { onSelectUpcomingFilter(key) },
                                    shape = RoundedCornerShape(50),
                                    color = if (active) TvTimePalette.Yellow else Color.Transparent
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (active) TvTimePalette.OnYellow else TvTimePalette.TextSecondary,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Optional 3D Watch List Shelf View (Screenshot 8)
        if (is3DShelfMode && topSegment != "upcoming") {
            item(key = "3d_shelf_room") {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xFF0E0A1A),
                    border = BorderStroke(1.dp, Color(0xFF3B1D5E)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (topSegment == "shows") "Shows" else "Movies",
                                style = MaterialTheme.typography.titleLarge,
                                color = Color(0xFF34D399)
                            )
                            Text(
                                text = "Watch List 3D",
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color(0xFFD946EF)
                            )
                        }

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (topSegment == "shows") {
                                items(shows, key = { "shelf_s_${it.id}" }) { s ->
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .width(78.dp)
                                            .clickable { onOpenShow(s) }
                                    ) {
                                        NetworkPosterImage(
                                            url = s.posterUrl,
                                            title = s.title,
                                            width = 78.dp,
                                            height = 112.dp,
                                            cornerRadius = 8.dp
                                        )
                                        Text(
                                            text = s.title,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }
                            } else {
                                items(movies, key = { "shelf_m_${it.id}" }) { m ->
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.width(78.dp)
                                    ) {
                                        NetworkPosterImage(
                                            url = m.posterUrl,
                                            title = m.title,
                                            width = 78.dp,
                                            height = 112.dp,
                                            cornerRadius = 8.dp
                                        )
                                        Text(
                                            text = m.title,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFFA16207))
                        )
                    }
                }
            }
        }

        // 4. Main Content List
        if (topSegment == "shows") {
            val partitioned = shows.map { s ->
                val eps = TvTimeProgressCalculator.parseEpisodeList(s.episodesJson)
                val w = TvTimeProgressCalculator.parseWatchedSet(s.watchedCodesCsv)
                s to TvTimeProgressCalculator.computeShowProgress(eps, w)
            }
            val startedInProgress = partitioned.filter { !it.second.isCompleted && it.second.hasStarted }.map { it.first }
            val notStarted = partitioned.filter { !it.second.isCompleted && !it.second.hasStarted }.map { it.first }
            val completed = partitioned.filter { it.second.isCompleted }.map { it.first }

            when (mediaFilter) {
                "completed" -> {
                    items(completed, key = { it.id }) { show ->
                        TvShowWatchCard(
                            show = show,
                            onCardClick = { onOpenShow(show) },
                            onEyeClick = { onMarkNextEpisode(show) }
                        )
                    }
                }
                "all" -> {
                    items(shows, key = { it.id }) { show ->
                        TvShowWatchCard(
                            show = show,
                            onCardClick = { onOpenShow(show) },
                            onEyeClick = { onMarkNextEpisode(show) }
                        )
                    }
                }
                else -> {
                    items(startedInProgress, key = { it.id }) { show ->
                        TvShowWatchCard(
                            show = show,
                            onCardClick = { onOpenShow(show) },
                            onEyeClick = { onMarkNextEpisode(show) }
                        )
                    }
                    if (notStarted.isNotEmpty()) {
                        item(key = "havent_started_divider") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggleHavenStarted() }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                HorizontalDivider(
                                    modifier = Modifier.weight(1f),
                                    color = TvTimePalette.Border
                                )
                                Text(
                                    text = "Haven't started yet ${if (havenStartedCollapsed) "∨" else "∧"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TvTimePalette.TextSecondary
                                )
                                HorizontalDivider(
                                    modifier = Modifier.weight(1f),
                                    color = TvTimePalette.Border
                                )
                            }
                        }
                        if (!havenStartedCollapsed) {
                            items(notStarted, key = { "ns_${it.id}" }) { show ->
                                TvShowWatchCard(
                                    show = show,
                                    onCardClick = { onOpenShow(show) },
                                    onEyeClick = { onMarkNextEpisode(show) }
                                )
                            }
                        }
                    }
                }
            }
        } else if (topSegment == "movies") {
            val filteredMovies = when (mediaFilter) {
                "completed" -> movies.filter { it.watched }
                "in_progress" -> movies.filter { !it.watched }
                else -> movies
            }
            items(filteredMovies, key = { it.id }) { movie ->
                TvMovieWatchCard(
                    movie = movie,
                    onCardClick = { onToggleMovieWatched(movie) },
                    onToggleWatched = { onToggleMovieWatched(movie) }
                )
            }
        } else {
            // Upcoming Segment (Screenshot 7)
            items(shows, key = { "up_${it.id}" }) { show ->
                val eps = TvTimeProgressCalculator.parseEpisodeList(show.episodesJson)
                val w = TvTimeProgressCalculator.parseWatchedSet(show.watchedCodesCsv)
                val prog = TvTimeProgressCalculator.computeShowProgress(eps, w)
                val nextEp = prog.nextEpisode ?: eps.lastOrNull()
                val leadActor = show.castCsv.split(",").firstOrNull()?.trim()?.ifBlank { "Lead Cast" } ?: "Lead Cast"

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        HorizontalDivider(modifier = Modifier.weight(1f), color = TvTimePalette.Border)
                        Text(
                            text = if (show.id % 2L == 0L) "5 days" else "1 week",
                            style = MaterialTheme.typography.bodySmall,
                            color = TvTimePalette.TextSecondary
                        )
                        HorizontalDivider(modifier = Modifier.weight(1f), color = TvTimePalette.Border)
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
                                        modifier = Modifier.weight(1f)
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = Color(0xFF27272F)
                                    ) {
                                        Text(
                                            text = nextEp?.airDate?.ifBlank { "Thursday 30th" } ?: "Thursday 30th",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TvTimePalette.TextSecondary,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "TV Show",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TvTimePalette.Yellow
                                )
                                Text(
                                    text = nextEp?.formattedDotCode ?: "S01·E01",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = TvTimePalette.TextSecondary
                                )
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = Color(0xFF23232B)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(18.dp)
                                                .clip(CircleShape)
                                                .background(TvTimePalette.Yellow),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = leadActor.take(1),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = TvTimePalette.OnYellow
                                            )
                                        }
                                        Text(
                                            text = leadActor,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White
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
}


@Composable
private fun SearchTabScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    sortKey: String,
    onSortChange: (String) -> Unit,
    results: List<SearchResultItem>,
    isSearching: Boolean,
    existingExternalIds: Set<String>,
    onAddOrOpenResult: (SearchResultItem) -> Unit
) {
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
        // 1. Pill Search Bar (Screenshot 1)
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
                                "Search TV shows, movies, or actors...",
                                color = TvTimePalette.TextSecondary,
                                fontSize = 14.sp
                            )
                        },
                        singleLine = true,
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

        // 2. Results Count + Sort Bar: Top | Newest | Oldest (Screenshot 1)
        item(key = "search_sort_row") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (isSearching) "Searching..." else "${sortedResults.size} results",
                    style = MaterialTheme.typography.bodySmall,
                    color = TvTimePalette.TextSecondary
                )

                Surface(
                    shape = RoundedCornerShape(50),
                    color = TvTimePalette.SurfacePill
                ) {
                    Row(modifier = Modifier.padding(4.dp)) {
                        listOf(
                            "top" to "Top",
                            "newest" to "Newest",
                            "oldest" to "Oldest"
                        ).forEach { (key, label) ->
                            val active = sortKey == key
                            Surface(
                                onClick = { onSortChange(key) },
                                shape = RoundedCornerShape(50),
                                color = if (active) TvTimePalette.Yellow else Color.Transparent
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (active) TvTimePalette.OnYellow else TvTimePalette.TextSecondary,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Result Cards
        items(sortedResults, key = { it.externalId }) { item ->
            val isAdded = existingExternalIds.contains(item.externalId)
            TvSearchResultCard(
                item = item,
                isAdded = isAdded,
                onCardClick = { onAddOrOpenResult(item) },
                onActionClick = { onAddOrOpenResult(item) }
            )
        }
    }
}

@Composable
private fun ProfileTabScreen(
    currentUserId: String,
    shows: List<ShowEntity>,
    movies: List<MovieEntity>,
    actors: List<ActorEntity>,
    onOpenShow: (ShowEntity) -> Unit,
    onOpenFollowing: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenAccountSwitch: () -> Unit,
    onSyncNow: () -> Unit
) {
    val stats = remember(shows, movies) {
        var epCount = 0
        var showMins = 0
        for (s in shows) {
            val watchedCount = TvTimeProgressCalculator.parseWatchedSet(s.watchedCodesCsv).size
            val mult = 1 + s.rewatchCount.coerceAtLeast(0)
            epCount += watchedCount * mult
            showMins += watchedCount * s.runtimeMins.coerceAtLeast(15) * mult
        }
        var movieCount = 0
        var movieMins = 0
        for (m in movies) {
            if (m.watched) {
                val mult = 1 + m.rewatchCount.coerceAtLeast(0)
                movieCount += mult
                movieMins += m.runtimeMins.coerceAtLeast(45) * mult
            }
        }
        WatchStatsSummary(
            totalMinutes = showMins + movieMins,
            showMinutes = showMins,
            movieMinutes = movieMins,
            totalEpisodesWatched = epCount,
            totalMoviesWatched = movieCount
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // 1. Top Profile Header (Screenshot 2)
        item(key = "profile_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(modifier = Modifier.clickable { onOpenAccountSwitch() }) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        listOf(TvTimePalette.Yellow, Color(0xFFF97316))
                                    )
                                )
                                .padding(2.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF18181D)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = currentUserId.take(2).uppercase(),
                                style = MaterialTheme.typography.titleLarge,
                                color = TvTimePalette.Yellow
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(TvTimePalette.Yellow)
                                .align(Alignment.BottomEnd),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("📷", fontSize = 10.sp)
                        }
                    }

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = currentUserId.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.White
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
                            color = TvTimePalette.TextSecondary
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Import Original TV Time History Button
                    Surface(
                        onClick = onOpenImport,
                        shape = CircleShape,
                        color = TvTimePalette.SurfacePill,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("📥", fontSize = 16.sp)
                        }
                    }

                    // Yellow Person+ Button (Screenshot 2)
                    Surface(
                        onClick = onOpenFollowing,
                        shape = CircleShape,
                        color = TvTimePalette.Yellow,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "👤+",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = TvTimePalette.OnYellow
                            )
                        }
                    }
                }
            }
        }

        // 2. Followers / Following + Cloud Sync Row (Screenshot 2)
        item(key = "followers_row") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                Column(modifier = Modifier.clickable { onOpenFollowing() }) {
                    Text(
                        text = "2",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Text(
                        text = "Followers",
                        style = MaterialTheme.typography.bodySmall,
                        color = TvTimePalette.TextSecondary
                    )
                }

                Column(modifier = Modifier.clickable { onOpenFollowing() }) {
                    Text(
                        text = "${actors.size + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Text(
                        text = "Following",
                        style = MaterialTheme.typography.bodySmall,
                        color = TvTimePalette.TextSecondary
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Surface(
                    onClick = onSyncNow,
                    shape = RoundedCornerShape(50),
                    color = TvTimePalette.SurfacePill
                ) {
                    Text(
                        text = "↻ Sync Cloud",
                        style = MaterialTheme.typography.labelMedium,
                        color = TvTimePalette.Yellow,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }

        // 3. TOTAL WATCH TIME Hero Card (Screenshot 2)
        item(key = "total_watch_time_card") {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = TvTimePalette.SurfaceCard,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "TOTAL WATCH TIME",
                        style = MaterialTheme.typography.labelSmall,
                        color = TvTimePalette.TextSecondary
                    )

                    Text(
                        text = stats.formattedTotal,
                        style = MaterialTheme.typography.headlineLarge,
                        color = TvTimePalette.Yellow
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = stats.formattedSeries,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                            Text(
                                text = "Series · ${stats.totalEpisodesWatched} eps",
                                style = MaterialTheme.typography.bodySmall,
                                color = TvTimePalette.TextSecondary
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = stats.formattedMovies,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                            Text(
                                text = "Movies · ${stats.totalMoviesWatched}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TvTimePalette.TextSecondary
                            )
                        }
                    }
                }
            }
        }

        // 4. Favorite Shows Carousel (iPad Screenshot 3)
        val favShows = shows.filter { it.isFavorite }.ifEmpty { shows }
        if (favShows.isNotEmpty()) {
            item(key = "carousel_favorites") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(
                                text = "Favorite",
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White
                            )
                            Text(
                                text = "Shows",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal),
                                color = TvTimePalette.TextSecondary
                            )
                        }
                        Text(
                            text = "See all",
                            style = MaterialTheme.typography.labelMedium,
                            color = TvTimePalette.Yellow
                        )
                    }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(favShows, key = { "fav_${it.id}" }) { show ->
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
                            }
                        }
                    }
                }
            }
        }

        // 5. Recently Watched Series Carousel (Screenshot 2)
        item(key = "carousel_recent_series") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            text = "Recently",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White
                        )
                        Text(
                            text = "Watched Series",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal),
                            color = TvTimePalette.TextSecondary
                        )
                    }
                    Text(
                        text = "See all",
                        style = MaterialTheme.typography.labelMedium,
                        color = TvTimePalette.Yellow
                    )
                }

                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(shows, key = { "rec_s_${it.id}" }) { show ->
                        val watched = TvTimeProgressCalculator.parseWatchedSet(show.watchedCodesCsv)
                        val latestCode = watched.lastOrNull() ?: "S01E01"
                        val formattedDot = latestCode.replace(Regex("^S(\\d+)E(\\d+)$"), "S$1·E$2")

                        Column(
                            modifier = Modifier
                                .width(108.dp)
                                .clickable { onOpenShow(show) },
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box {
                                NetworkPosterImage(
                                    url = show.posterUrl,
                                    title = show.title,
                                    width = 108.dp,
                                    height = 156.dp,
                                    cornerRadius = 16.dp
                                )
                                if (show.reaction.isNotBlank()) {
                                    Box(
                                        modifier = Modifier
                                            .padding(6.dp)
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xD9121216))
                                            .align(Alignment.TopEnd),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(show.reaction, fontSize = 12.sp)
                                    }
                                }
                            }
                            Text(
                                text = show.title,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = formattedDot,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                                color = TvTimePalette.Yellow
                            )
                        }
                    }
                }
            }
        }

        // 6. Recently Watched Movies Carousel (Screenshot 2)
        item(key = "carousel_recent_movies") {
            val watchedMovies = movies.filter { it.watched }.ifEmpty { movies }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            text = "Recently",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White
                        )
                        Text(
                            text = "Watched Movies",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal),
                            color = TvTimePalette.TextSecondary
                        )
                    }
                    Text(
                        text = "See all",
                        style = MaterialTheme.typography.labelMedium,
                        color = TvTimePalette.Yellow
                    )
                }

                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(watchedMovies, key = { "rec_m_${it.id}" }) { movie ->
                        Column(
                            modifier = Modifier.width(108.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box {
                                NetworkPosterImage(
                                    url = movie.posterUrl,
                                    title = movie.title,
                                    width = 108.dp,
                                    height = 156.dp,
                                    cornerRadius = 16.dp
                                )
                                if (movie.reaction.isNotBlank()) {
                                    Box(
                                        modifier = Modifier
                                            .padding(6.dp)
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xD9121216))
                                            .align(Alignment.TopEnd),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(movie.reaction, fontSize = 12.sp)
                                    }
                                }
                            }
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
}
