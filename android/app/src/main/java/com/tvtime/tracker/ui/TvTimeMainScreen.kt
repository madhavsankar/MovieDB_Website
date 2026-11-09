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

    // Home Tab State
    var homeTopSegment by remember { mutableStateOf("shows") } // "shows" | "movies" | "upcoming"
    var mediaSubFilter by remember { mutableStateOf("in_progress") } // "in_progress" | "interested" | "completed" | "stopped"
    var is3DShelfMode by remember { mutableStateOf(false) }
    var havenStartedCollapsed by remember { mutableStateOf(false) }

    // Search Tab State
    var searchQuery by remember { mutableStateOf("") }
    var searchSort by remember { mutableStateOf("top") } // "top" | "newest" | "oldest"
    var searchResults by remember { mutableStateOf<List<SearchResultItem>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    // Modals & Bottom Sheets
    var selectedShowForDetail by remember { mutableStateOf<ShowEntity?>(null) }
    var selectedMovieForDetail by remember { mutableStateOf<MovieEntity?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showAccountSwitcherDialog by remember(currentUserId) {
        mutableStateOf(!FirestoreSyncService.isUserAllowed(currentUserId))
    }

    LaunchedEffect(currentUserId) {
        if (FirestoreSyncService.isUserAllowed(currentUserId)) {
            FirestoreSyncService.pullFromFirestoreIfNeeded(dao, currentUserId)
        }
    }

    val shows by dao.observeShowsForUser(currentUserId).collectAsState(initial = emptyList())
    val movies by dao.observeMoviesForUser(currentUserId).collectAsState(initial = emptyList())

    // Initial Search Results load
    LaunchedEffect(searchQuery) {
        isSearching = true
        if (searchQuery.isNotBlank()) delay(250)
        searchResults = LiveMediaCatalogService.searchMulti(searchQuery.ifBlank { "Game of Thrones" })
            .filter { it.mediaType != "actor" }
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
                        topSegment = homeTopSegment,
                        onSelectTopSegment = { seg ->
                            homeTopSegment = seg
                            if (seg == "movies" && (mediaSubFilter == "in_progress" || mediaSubFilter == "stopped")) {
                                mediaSubFilter = "interested"
                            } else if (seg == "shows" && mediaSubFilter == "all") {
                                mediaSubFilter = "in_progress"
                            }
                        },
                        mediaFilter = mediaSubFilter,
                        onSelectMediaFilter = { mediaSubFilter = it },
                        is3DShelfMode = is3DShelfMode,
                        onToggle3DShelf = { is3DShelfMode = !is3DShelfMode },
                        havenStartedCollapsed = havenStartedCollapsed,
                        onToggleHavenStarted = { havenStartedCollapsed = !havenStartedCollapsed },
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
                    SearchTabScreen(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        sortKey = searchSort,
                        onSortChange = { searchSort = it },
                        results = searchResults,
                        isSearching = isSearching,
                        existingExternalIds = (shows.map { it.externalId } + movies.map { it.externalId }).toSet(),
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
                        }
                    )
                }

                "profile" -> {
                    ProfileTabScreen(
                        currentUserId = currentUserId,
                        shows = shows,
                        movies = movies,
                        onOpenShow = { selectedShowForDetail = it },
                        onOpenMovie = { selectedMovieForDetail = it },
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
private fun HomeTabScreen(
    shows: List<ShowEntity>,
    movies: List<MovieEntity>,
    topSegment: String,
    onSelectTopSegment: (String) -> Unit,
    mediaFilter: String,
    onSelectMediaFilter: (String) -> Unit,
    is3DShelfMode: Boolean,
    onToggle3DShelf: () -> Unit,
    havenStartedCollapsed: Boolean,
    onToggleHavenStarted: () -> Unit,
    onOpenShow: (ShowEntity) -> Unit,
    onOpenMovie: (MovieEntity) -> Unit,
    onMarkNextEpisode: (ShowEntity) -> Unit,
    onToggleMovieWatched: (MovieEntity) -> Unit
) {
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

        // 2. Sub-filter Row
        item(key = "subfilter_row") {
            if (topSegment == "shows") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = TvTimePalette.SurfacePill,
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            listOf(
                                "in_progress" to "In progress",
                                "interested" to "Watchlist",
                                "completed" to "Completed",
                                "stopped" to "Stopped"
                            ).forEach { (key, label) ->
                                val active = mediaFilter == key
                                Surface(
                                    onClick = { onSelectMediaFilter(key) },
                                    shape = RoundedCornerShape(50),
                                    color = if (active) TvTimePalette.Yellow else Color.Transparent
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (active) TvTimePalette.OnYellow else TvTimePalette.TextSecondary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

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
            } else if (topSegment == "movies") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "All Movies",
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
                                    "interested" to "Watchlist",
                                    "completed" to "Watched"
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
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }

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

        // 3. Optional 3D Watch List Shelf View
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
                                        modifier = Modifier
                                            .width(78.dp)
                                            .clickable { onOpenMovie(m) }
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
                val prog = TvTimeProgressCalculator.computeShowProgress(eps, w)
                val effectiveStatus = TvTimeProgressCalculator.deriveEffectiveShowStatus(prog, s.userStatus)
                s to effectiveStatus
            }
            val startedInProgress = partitioned.filter { it.second == "in_progress" }.map { it.first }
            val notStarted = partitioned.filter { it.second == "interested" }.map { it.first }
            val completed = partitioned.filter { it.second == "completed" }.map { it.first }
            val stopped = partitioned.filter { it.second == "stopped" }.map { it.first }

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
                "interested" -> {
                    items(notStarted, key = { it.id }) { show ->
                        TvShowWatchCard(
                            show = show,
                            onCardClick = { onOpenShow(show) },
                            onEyeClick = { onMarkNextEpisode(show) }
                        )
                    }
                }
                "stopped" -> {
                    items(stopped, key = { it.id }) { show ->
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
                }
            }
        } else if (topSegment == "movies") {
            val filteredMovies = when (mediaFilter) {
                "completed" -> movies.filter { it.watched }
                "interested" -> movies.filter { !it.watched }
                else -> movies
            }
            items(filteredMovies, key = { it.id }) { movie ->
                TvMovieWatchCard(
                    movie = movie,
                    onCardClick = { onOpenMovie(movie) },
                    onToggleWatched = { onToggleMovieWatched(movie) }
                )
            }
        } else {
            // Upcoming Segment
            val activeShows = shows.filter { it.userStatus != "stopped" }
            items(activeShows, key = { "up_${it.id}" }) { show ->
                val eps = TvTimeProgressCalculator.parseEpisodeList(show.episodesJson)
                val w = TvTimeProgressCalculator.parseWatchedSet(show.watchedCodesCsv)
                val prog = TvTimeProgressCalculator.computeShowProgress(eps, w)
                val nextEp = prog.nextEpisode ?: eps.lastOrNull()

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
                                            text = nextEp?.airDate?.ifBlank { "Upcoming" } ?: "Upcoming",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TvTimePalette.TextSecondary,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "TV Show · ${show.network}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TvTimePalette.Yellow
                                )
                                Text(
                                    text = "${nextEp?.formattedDotCode ?: "S01·E01"} — ${nextEp?.title ?: "Next Episode"}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = TvTimePalette.TextSecondary
                                )
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

        // 2. Results Count + Sort Bar: Top | Newest | Oldest
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
    onOpenShow: (ShowEntity) -> Unit,
    onOpenMovie: (MovieEntity) -> Unit,
    onOpenImport: () -> Unit,
    onOpenAccountSwitch: () -> Unit,
    onSyncNow: () -> Unit
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

    val partitionedShows = remember(shows) {
        shows.map { s ->
            val eps = TvTimeProgressCalculator.parseEpisodeList(s.episodesJson)
            val w = TvTimeProgressCalculator.parseWatchedSet(s.watchedCodesCsv)
            val prog = TvTimeProgressCalculator.computeShowProgress(eps, w)
            val effectiveStatus = TvTimeProgressCalculator.deriveEffectiveShowStatus(prog, s.userStatus)
            s to effectiveStatus
        }
    }
    val interestedShows = remember(partitionedShows) {
        partitionedShows.filter { it.second == "interested" }.map { it.first }
    }
    val watchedOrInProgressShows = remember(partitionedShows) {
        partitionedShows.filter { it.second == "completed" || it.second == "in_progress" }.map { it.first }
    }
    val interestedMovies = remember(movies) { movies.filter { !it.watched } }
    val watchedMovies = remember(movies) { movies.filter { it.watched } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // 1. Top Profile Header
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
                    Surface(
                        onClick = onOpenImport,
                        shape = RoundedCornerShape(50),
                        color = TvTimePalette.SurfacePill
                    ) {
                        Text(
                            text = "📤 Export",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }

                    Surface(
                        onClick = onSyncNow,
                        shape = RoundedCornerShape(50),
                        color = TvTimePalette.SurfacePill
                    ) {
                        Text(
                            text = "↻ Sync",
                            style = MaterialTheme.typography.labelMedium,
                            color = TvTimePalette.Yellow,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }

        // 2. WATCH STATS Card (Total Watch Hours, Series Watch Hours, Movie Watch Hours, Number of Series & Movies)
        item(key = "total_watch_time_card") {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = TvTimePalette.SurfaceCard,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "WATCH STATS",
                            style = MaterialTheme.typography.labelSmall,
                            color = TvTimePalette.TextSecondary
                        )
                        Text(
                            text = "${stats.totalSeriesCount} Series · ${stats.totalMoviesCount} Movies",
                            style = MaterialTheme.typography.labelMedium,
                            color = TvTimePalette.Yellow
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stats.formattedTotalHours,
                            style = MaterialTheme.typography.headlineLarge,
                            color = TvTimePalette.Yellow
                        )
                        Text(
                            text = "Total Watch Hours (${stats.formattedTotal})",
                            style = MaterialTheme.typography.bodySmall,
                            color = TvTimePalette.TextSecondary
                        )
                    }

                    HorizontalDivider(color = TvTimePalette.Border)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stats.formattedSeriesHours,
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White
                            )
                            Text(
                                text = "Series Watch Hours",
                                style = MaterialTheme.typography.labelSmall,
                                color = TvTimePalette.Yellow
                            )
                            Text(
                                text = "${stats.totalSeriesCount} Series (${watchedOrInProgressShows.size} active/done · ${interestedShows.size} watchlist) · ${stats.totalEpisodesWatched} eps",
                                style = MaterialTheme.typography.bodySmall,
                                color = TvTimePalette.TextSecondary
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stats.formattedMoviesHours,
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White
                            )
                            Text(
                                text = "Movie Watch Hours",
                                style = MaterialTheme.typography.labelSmall,
                                color = TvTimePalette.Yellow
                            )
                            Text(
                                text = "${stats.totalMoviesCount} Movies (${watchedMovies.size} watched · ${interestedMovies.size} watchlist)",
                                style = MaterialTheme.typography.bodySmall,
                                color = TvTimePalette.TextSecondary
                            )
                        }
                    }
                }
            }
        }

        // 3. Series Watchlist (Interested / Yet to be watched)
        item(key = "carousel_series_watchlist") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            text = "Series",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White
                        )
                        Text(
                            text = "Watchlist (${interestedShows.size})",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal),
                            color = TvTimePalette.TextSecondary
                        )
                    }
                    Text(
                        text = "Watchlist",
                        style = MaterialTheme.typography.labelMedium,
                        color = TvTimePalette.Yellow
                    )
                }

                if (interestedShows.isEmpty()) {
                    Text(
                        text = "No series in your watchlist yet. Add a series to your Watchlist to see it here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TvTimePalette.TextSecondary
                    )
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(interestedShows, key = { "wl_s_${it.id}" }) { show ->
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
                                    text = "Watchlist",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TvTimePalette.Yellow
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Movie Watchlist
        item(key = "carousel_movie_watchlist") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            text = "Movie",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White
                        )
                        Text(
                            text = "Watchlist (${interestedMovies.size})",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal),
                            color = TvTimePalette.TextSecondary
                        )
                    }
                    Text(
                        text = "Watchlist",
                        style = MaterialTheme.typography.labelMedium,
                        color = TvTimePalette.Yellow
                    )
                }

                if (interestedMovies.isEmpty()) {
                    Text(
                        text = "No movies in your watchlist yet. Add a movie to your Watchlist to see it here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TvTimePalette.TextSecondary
                    )
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(interestedMovies, key = { "wl_m_${it.id}" }) { movie ->
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
                                    text = "Watchlist",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TvTimePalette.Yellow
                                )
                            }
                        }
                    }
                }
            }
        }

        // 5. Watched & In Progress Series Carousel
        item(key = "carousel_recent_series") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            text = "Watched & In Progress",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White
                        )
                        Text(
                            text = "Series (${watchedOrInProgressShows.size})",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal),
                            color = TvTimePalette.TextSecondary
                        )
                    }
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

        // 6. Watched Movies Carousel
        item(key = "carousel_recent_movies") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            text = "Watched",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White
                        )
                        Text(
                            text = "Movies (${watchedMovies.size})",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal),
                            color = TvTimePalette.TextSecondary
                        )
                    }
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
