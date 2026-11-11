package com.tvtime.tracker.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvtime.tracker.data.ActorEntity
import com.tvtime.tracker.data.FirestoreSyncService
import com.tvtime.tracker.data.LiveMediaCatalogService
import com.tvtime.tracker.data.MovieEntity
import com.tvtime.tracker.data.ShowEntity
import com.tvtime.tracker.data.TvTimeDao
import com.tvtime.tracker.domain.EpisodeModel
import com.tvtime.tracker.domain.TvTimeProgressCalculator
import com.tvtime.tracker.ui.theme.TvTimePalette
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShowDetailBottomSheet(
    show: ShowEntity,
    dao: TvTimeDao,
    currentUserId: String,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var persistedShowId by remember(show.id) { mutableLongStateOf(show.id) }
    var activeTab by remember(show.id) {
        mutableStateOf(if (show.id == 0L || show.userStatus == "preview") "info" else "episodes")
    } // "info" | "episodes"
    var episodes by remember(show.id, show.episodesJson) {
        mutableStateOf(TvTimeProgressCalculator.parseEpisodeList(show.episodesJson))
    }
    var watchedSet by remember(show.id, show.watchedCodesCsv) {
        mutableStateOf(TvTimeProgressCalculator.parseWatchedSet(show.watchedCodesCsv))
    }
    var userStatus by remember(show.id, show.userStatus, show.watchedCodesCsv) {
        val initEps = TvTimeProgressCalculator.parseEpisodeList(show.episodesJson)
        val initWatched = TvTimeProgressCalculator.parseWatchedSet(show.watchedCodesCsv)
        val initStat = if (show.id == 0L || show.userStatus == "preview") {
            "preview"
        } else {
            TvTimeProgressCalculator.deriveEffectiveShowStatus(initEps, initWatched, show.userStatus)
        }
        mutableStateOf(initStat)
    }
    var isFavorite by remember(show.id, show.isFavorite) { mutableStateOf(show.isFavorite) }
    var castCsv by remember(show.id, show.castCsv) { mutableStateOf(show.castCsv) }
    var openSeasons by remember(show.id) {
        val initEps = TvTimeProgressCalculator.parseEpisodeList(show.episodesJson)
        val initWatched = TvTimeProgressCalculator.parseWatchedSet(show.watchedCodesCsv)
        val initProg = TvTimeProgressCalculator.computeShowProgress(initEps, initWatched)
        val initEff = TvTimeProgressCalculator.deriveEffectiveShowStatus(initProg, show.userStatus)
        val initialOpen = if (initEff == "in_progress" && initProg.nextEpisode != null) {
            setOf(initProg.nextEpisode.season)
        } else {
            emptySet()
        }
        mutableStateOf(initialOpen)
    }

    fun deriveStatusFromWatched(
        eps: List<EpisodeModel>,
        watched: Set<String>,
        current: String
    ): String {
        return TvTimeProgressCalculator.deriveEffectiveShowStatus(eps, watched, current)
    }

    fun persistUpdatedShow(
        nextEpisodes: List<EpisodeModel> = episodes,
        nextWatchedSet: Set<String> = watchedSet,
        nextStatus: String = userStatus,
        nextFavorite: Boolean = isFavorite,
        nextCastCsv: String = castCsv
    ) {
        val cleanStatus = if (nextStatus == "preview") "interested" else nextStatus
        val updated = show.copy(
            id = persistedShowId,
            episodesJson = TvTimeProgressCalculator.serializeEpisodeList(nextEpisodes),
            watchedCodesCsv = TvTimeProgressCalculator.serializeWatchedSet(nextWatchedSet),
            userStatus = cleanStatus,
            isFavorite = nextFavorite,
            rewatchCount = 0,
            reaction = "",
            castCsv = nextCastCsv
        )
        scope.launch {
            if (persistedShowId == 0L) {
                persistedShowId = dao.insertShow(updated.copy(id = 0L))
            } else {
                dao.updateShow(updated)
            }
            FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
        }
    }

    // Fetch full episode guide from TMDB or TVMaze so all seasons & episodes can be marked watched
    LaunchedEffect(show.id, show.tvmazeId, show.tmdbId, show.externalId) {
        val (fetchedEps, fetchedCast) = LiveMediaCatalogService.fetchFullShowEpisodesAndCast(
            tvmazeId = show.tvmazeId,
            tmdbId = show.tmdbId,
            title = show.title,
            externalId = show.externalId
        )
        if (fetchedEps.isNotEmpty() && fetchedEps.size > episodes.size) {
            episodes = fetchedEps
            val mergedCast = if (fetchedCast.isNotEmpty()) fetchedCast.joinToString(", ") else castCsv
            castCsv = mergedCast
            val nextWatched = if (show.userStatus.equals("completed", ignoreCase = true)) {
                fetchedEps.map { it.code.uppercase() }.toSet()
            } else {
                watchedSet
            }
            val nextProg = TvTimeProgressCalculator.computeShowProgress(fetchedEps, nextWatched)
            val nextEff = if (persistedShowId == 0L && userStatus == "preview") {
                "preview"
            } else {
                TvTimeProgressCalculator.deriveEffectiveShowStatus(nextProg, userStatus)
            }
            watchedSet = nextWatched
            userStatus = nextEff
            openSeasons = if (nextEff == "in_progress" && nextProg.nextEpisode != null) {
                setOf(nextProg.nextEpisode.season)
            } else {
                emptySet()
            }
            if (persistedShowId != 0L) {
                persistUpdatedShow(
                    nextEpisodes = fetchedEps,
                    nextWatchedSet = nextWatched,
                    nextStatus = nextEff,
                    nextCastCsv = mergedCast
                )
            }
        }
    }

    val prog = remember(episodes, watchedSet) {
        TvTimeProgressCalculator.computeShowProgress(episodes, watchedSet)
    }
    val effectiveStatus = remember(prog, userStatus, persistedShowId) {
        if (persistedShowId == 0L && userStatus == "preview" && watchedSet.isEmpty()) {
            "preview"
        } else {
            TvTimeProgressCalculator.deriveEffectiveShowStatus(prog, userStatus)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF121216),
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
        ) {
            // Show Status Header: Status Indicator + Settable Completed/Stopped + Separate Watchlist Button
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = show.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val statusBadgeText = when (effectiveStatus) {
                            "completed" -> "✓ Completed"
                            "in_progress" -> "▶ In Progress (${watchedSet.size}/${episodes.size} watched)"
                            "stopped" -> {
                                val lastEp = prog.lastWatchedEpisode
                                if (lastEp != null) {
                                    "⏹ Stopped · Watched till ${lastEp.formattedDotCode} (${watchedSet.size}/${episodes.size} watched)"
                                } else {
                                    "⏹ Stopped (0/${episodes.size} watched)"
                                }
                            }
                            "preview" -> "Not in library yet"
                            else -> "Watchlist"
                        }
                        val statusBadgeColor = when (effectiveStatus) {
                            "completed" -> Color(0xFF4ADE80)
                            "in_progress" -> TvTimePalette.Yellow
                            "stopped" -> TvTimePalette.Error
                            "preview" -> TvTimePalette.TextSecondary
                            else -> TvTimePalette.Yellow
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            if (effectiveStatus == "interested") {
                                WatchlistBookmarkIcon(tint = TvTimePalette.Yellow, size = 13.dp, filled = true)
                            }
                            Text(
                                text = statusBadgeText,
                                style = MaterialTheme.typography.labelSmall,
                                color = statusBadgeColor
                            )
                        }
                    }
                    if (persistedShowId != 0L) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    dao.deleteShow(persistedShowId)
                                    FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                    onDismiss()
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("✕ Not Watched", color = TvTimePalette.Error, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Settable Watch Statuses: In Progress | Completed | Stopped
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isInProgress = effectiveStatus == "in_progress"
                    val isCompleted = effectiveStatus == "completed"
                    val isStopped = effectiveStatus == "stopped"

                    Surface(
                        onClick = {
                            val nextWatched = when {
                                watchedSet.isEmpty() && episodes.isNotEmpty() -> setOf(episodes.first().code.uppercase())
                                isCompleted && episodes.size > 1 -> episodes.dropLast(1).map { it.code.uppercase() }.toSet()
                                else -> watchedSet
                            }
                            watchedSet = nextWatched
                            userStatus = "in_progress"
                            val nextProg = TvTimeProgressCalculator.computeShowProgress(episodes, nextWatched)
                            openSeasons = if (nextProg.nextEpisode != null) setOf(nextProg.nextEpisode.season) else emptySet()
                            persistUpdatedShow(nextWatchedSet = nextWatched, nextStatus = "in_progress")
                        },
                        shape = RoundedCornerShape(50),
                        color = if (isInProgress) TvTimePalette.Yellow else TvTimePalette.SurfacePill,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 9.dp, horizontal = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "▶ In Progress",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isInProgress) TvTimePalette.OnYellow else Color.White,
                                maxLines = 1
                            )
                        }
                    }

                    Surface(
                        onClick = {
                            if (isCompleted) {
                                scope.launch {
                                    if (persistedShowId != 0L) {
                                        dao.deleteShow(persistedShowId)
                                        FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                    }
                                    onDismiss()
                                }
                            } else {
                                val nextWatched = episodes.map { it.code.uppercase() }.toSet()
                                watchedSet = nextWatched
                                userStatus = "completed"
                                openSeasons = emptySet()
                                persistUpdatedShow(nextWatchedSet = nextWatched, nextStatus = "completed")
                            }
                        },
                        shape = RoundedCornerShape(50),
                        color = if (isCompleted) TvTimePalette.Yellow else TvTimePalette.SurfacePill,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 9.dp, horizontal = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "✓ Completed",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isCompleted) TvTimePalette.OnYellow else Color.White,
                                maxLines = 1
                            )
                        }
                    }

                    Surface(
                        onClick = {
                            val nextWatched = if (isCompleted && episodes.size > 1) {
                                episodes.dropLast(1).map { it.code.uppercase() }.toSet()
                            } else {
                                watchedSet
                            }
                            val nextStat = if (isStopped) {
                                if (nextWatched.isNotEmpty()) "in_progress" else "interested"
                            } else {
                                "stopped"
                            }
                            watchedSet = nextWatched
                            userStatus = nextStat
                            persistUpdatedShow(nextWatchedSet = nextWatched, nextStatus = nextStat)
                        },
                        shape = RoundedCornerShape(50),
                        color = if (isStopped) TvTimePalette.Yellow else TvTimePalette.SurfacePill,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 9.dp, horizontal = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isStopped) "⏹ Stopped ✓" else "⏹ Stopped",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isStopped) TvTimePalette.OnYellow else Color.White,
                                maxLines = 1
                            )
                        }
                    }
                }

                // Separate Watchlist Button (Hidden once any episode is watched or show is completed)
                val canSetWatchlist = watchedSet.isEmpty() && effectiveStatus != "completed"
                val isInWatchlist = effectiveStatus == "interested" && watchedSet.isEmpty()
                if (canSetWatchlist) {
                    Surface(
                        onClick = {
                            if (isInWatchlist && persistedShowId != 0L) {
                                scope.launch {
                                    dao.deleteShow(persistedShowId)
                                    FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                    onDismiss()
                                }
                            } else {
                                userStatus = "interested"
                                persistUpdatedShow(nextWatchedSet = emptySet(), nextStatus = "interested")
                            }
                        },
                        shape = RoundedCornerShape(50),
                        color = if (isInWatchlist) TvTimePalette.Yellow else TvTimePalette.SurfacePill,
                        border = BorderStroke(
                            1.dp,
                            if (isInWatchlist) TvTimePalette.Yellow else Color.White.copy(alpha = 0.08f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            val contentTint = if (isInWatchlist) TvTimePalette.OnYellow else TvTimePalette.Yellow
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                WatchlistBookmarkIcon(tint = contentTint, size = 14.dp, filled = true)
                                Text(
                                    text = if (isInWatchlist) "In Watchlist" else "Add to Watchlist",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isInWatchlist) TvTimePalette.OnYellow else Color.White,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            // Top Tabs: Info | Episodes
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                listOf("info" to "Info", "episodes" to "Episodes").forEach { (key, label) ->
                    val selected = activeTab == key
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { activeTab = key },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (key == "episodes") "Episodes (${watchedSet.size}/${episodes.size})" else label,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (selected) Color.White else TvTimePalette.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.5.dp)
                                .background(if (selected) TvTimePalette.Yellow else Color.Transparent)
                        )
                    }
                }
            }

            if (activeTab == "info") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        NetworkPosterImage(
                            url = show.posterUrl,
                            title = show.title,
                            width = 92.dp,
                            height = 136.dp
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = show.title,
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.White
                            )
                            Text(
                                text = "${show.year} · TV Series · ${show.runtimeMins}m · ${show.status}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TvTimePalette.TextSecondary
                            )
                            Text(
                                text = "Streaming: ${show.network.ifBlank { "Streaming" }}",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = TvTimePalette.Yellow
                            )
                            if (castCsv.isNotBlank()) {
                                Text(
                                    text = castCsv,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TvTimePalette.TextSecondary
                                )
                            }
                        }
                    }

                    Text(
                        text = show.overview.ifBlank { "No overview available." },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFD4D4D8)
                    )
                }
            } else {
                // Episodes Tab: Grouped by Season with Accordions + Season & Episode Watch Toggles
                val groupedBySeason = remember(episodes) {
                    episodes.groupBy { it.season }.toSortedMap()
                }
                val listState = rememberLazyListState()

                LaunchedEffect(show.id, episodes.size) {
                    if (effectiveStatus == "in_progress") {
                        val prog = TvTimeProgressCalculator.computeShowProgress(episodes, watchedSet)
                        val targetSeason = prog.nextEpisode?.season
                        if (targetSeason != null) {
                            val idx = groupedBySeason.keys.indexOf(targetSeason)
                            if (idx >= 0) {
                                listState.scrollToItem(idx)
                            }
                        }
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    groupedBySeason.forEach { (seasonNum, seasonEps) ->
                        val isSeasonOpen = openSeasons.contains(seasonNum)
                        val watchedInSeason = seasonEps.count { watchedSet.contains(it.code.uppercase()) }
                        val allSeasonWatched = seasonEps.isNotEmpty() && watchedInSeason == seasonEps.size

                        item(key = "season_header_$seasonNum") {
                            Surface(
                                onClick = {
                                    openSeasons = if (isSeasonOpen) openSeasons - seasonNum else openSeasons + seasonNum
                                },
                                shape = RoundedCornerShape(18.dp),
                                color = TvTimePalette.SurfaceCard,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        // Season-wide check button: turns green when all episodes in this season are watched
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (allSeasonWatched) TvTimePalette.GreenProgress else Color(0xFF232329)
                                                )
                                                .clickable {
                                                    val nextSet = watchedSet.toMutableSet()
                                                    val baseStatus = if (!allSeasonWatched && userStatus == "stopped") "in_progress" else userStatus
                                                    if (allSeasonWatched) {
                                                        seasonEps.forEach { nextSet.remove(it.code.uppercase()) }
                                                    } else {
                                                        seasonEps.forEach { nextSet.add(it.code.uppercase()) }
                                                    }
                                                    val nextStat = deriveStatusFromWatched(episodes, nextSet, baseStatus)
                                                    watchedSet = nextSet
                                                    userStatus = nextStat
                                                    persistUpdatedShow(nextWatchedSet = nextSet, nextStatus = nextStat)
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = if (allSeasonWatched) "Season Watched" else "Mark Season Watched",
                                                tint = if (allSeasonWatched) Color.White else TvTimePalette.TextSecondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        Text(
                                            text = "Season $seasonNum",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Text(
                                        text = "$watchedInSeason/${seasonEps.size} watched  ${if (isSeasonOpen) "∧" else "∨"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (allSeasonWatched) TvTimePalette.GreenProgress else TvTimePalette.TextSecondary,
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        if (isSeasonOpen) {
                            items(seasonEps, key = { "ep_${it.code}" }) { ep ->
                                val epCodeUpper = ep.code.uppercase()
                                val isWatched = watchedSet.contains(epCodeUpper)

                                val toggleSingleEpisode = {
                                    val nextSet = watchedSet.toMutableSet()
                                    val baseStatus = if (!isWatched && userStatus == "stopped") "in_progress" else userStatus
                                    if (isWatched) nextSet.remove(epCodeUpper)
                                    else nextSet.add(epCodeUpper)
                                    val nextStat = deriveStatusFromWatched(episodes, nextSet, baseStatus)
                                    watchedSet = nextSet
                                    userStatus = nextStat
                                    persistUpdatedShow(nextWatchedSet = nextSet, nextStatus = nextStat)
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { toggleSingleEpisode() }
                                        .padding(vertical = 6.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    NetworkPosterImage(
                                        url = ep.imageUrl.ifBlank { show.posterUrl },
                                        title = ep.title,
                                        width = 84.dp,
                                        height = 50.dp,
                                        cornerRadius = 10.dp
                                    )

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${ep.formattedDotCode} · ${ep.title}",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${ep.airDate.ifBlank { "Aired" }} · ${ep.runtimeMins}m",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TvTimePalette.TextSecondary
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isWatched) TvTimePalette.GreenProgress else Color(0xFF232329)
                                            )
                                            .clickable { toggleSingleEpisode() },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = if (isWatched) "Watched" else "Mark Watched",
                                            tint = if (isWatched) Color.White else TvTimePalette.TextSecondary,
                                            modifier = Modifier.size(20.dp)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieDetailBottomSheet(
    movie: MovieEntity,
    dao: TvTimeDao,
    currentUserId: String,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var persistedMovieId by remember(movie.id) { mutableLongStateOf(movie.id) }
    var isWatched by remember(movie.id, movie.watched) { mutableStateOf(movie.watched) }
    var streamingService by remember(movie.id, movie.streamingService) { mutableStateOf(movie.streamingService) }
    var runtimeMins by remember(movie.id, movie.runtimeMins) { mutableIntStateOf(movie.runtimeMins) }
    var director by remember(movie.id, movie.director) { mutableStateOf(movie.director) }
    var castCsv by remember(movie.id, movie.castCsv) { mutableStateOf(movie.castCsv) }

    LaunchedEffect(movie.id, movie.tmdbId, movie.title) {
        if (LiveMediaCatalogService.isGenericStreamingLabel(streamingService) || castCsv.isBlank()) {
            val enriched = LiveMediaCatalogService.fetchMovieDetails(
                tmdbId = movie.tmdbId,
                title = movie.title,
                externalId = movie.externalId
            )
            if (enriched != null) {
                var changed = false
                if (!LiveMediaCatalogService.isGenericStreamingLabel(enriched.streamingService)) {
                    streamingService = enriched.streamingService
                    changed = true
                }
                if (enriched.runtimeMins > 0 && enriched.runtimeMins != runtimeMins) {
                    runtimeMins = enriched.runtimeMins
                    changed = true
                }
                if (enriched.director.isNotBlank() && director.isBlank()) {
                    director = enriched.director
                    changed = true
                }
                if (enriched.castCsv.isNotBlank() && castCsv.isBlank()) {
                    castCsv = enriched.castCsv
                    changed = true
                }
                if (changed && persistedMovieId != 0L) {
                    dao.updateMovie(
                        movie.copy(
                            id = persistedMovieId,
                            watched = isWatched,
                            streamingService = streamingService,
                            runtimeMins = runtimeMins,
                            director = director,
                            castCsv = castCsv
                        )
                    )
                    FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                }
            }
        }
    }

    fun persistMovie(nextWatched: Boolean = isWatched) {
        val todayIso = java.time.LocalDate.now().toString()
        val updated = movie.copy(
            id = persistedMovieId,
            watched = nextWatched,
            watchedAt = if (nextWatched) (movie.watchedAt.ifBlank { todayIso }) else "",
            streamingService = streamingService,
            runtimeMins = runtimeMins,
            director = director,
            castCsv = castCsv,
            rewatchCount = 0,
            reaction = ""
        )
        scope.launch {
            if (persistedMovieId == 0L) {
                persistedMovieId = dao.insertMovie(updated.copy(id = 0L))
            } else {
                dao.updateMovie(updated)
            }
            FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF121216),
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                NetworkPosterImage(
                    url = movie.posterUrl,
                    title = movie.title,
                    width = 92.dp,
                    height = 136.dp
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = movie.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White
                    )
                    Text(
                        text = "${movie.year} · Movie · ${runtimeMins}m",
                        style = MaterialTheme.typography.bodySmall,
                        color = TvTimePalette.CyanMovie
                    )
                    val cleanStreaming = streamingService.takeIf {
                        !LiveMediaCatalogService.isGenericStreamingLabel(it)
                    } ?: streamingService.ifBlank { "Streaming" }
                    Text(
                        text = "Streaming: $cleanStreaming",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = TvTimePalette.Yellow
                    )
                    val cleanMovieDirector = director.takeIf {
                        it.isNotBlank() &&
                            !LiveMediaCatalogService.isGenericStreamingLabel(it) &&
                            !it.equals(streamingService, ignoreCase = true)
                    }.orEmpty()
                    if (cleanMovieDirector.isNotBlank()) {
                        Text(
                            text = "Director: $cleanMovieDirector",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White
                        )
                    }
                    if (castCsv.isNotBlank()) {
                        Text(
                            text = castCsv,
                            style = MaterialTheme.typography.bodySmall,
                            color = TvTimePalette.TextSecondary
                        )
                    }
                }
            }

            // Movie Status Card: Header + 2-Column Watchlist / Watched Buttons
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = TvTimePalette.SurfaceCard,
                border = BorderStroke(1.dp, TvTimePalette.Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            if (!isWatched && persistedMovieId != 0L) {
                                WatchlistBookmarkIcon(tint = TvTimePalette.Yellow, size = 13.dp, filled = true)
                            }
                            Text(
                                text = when {
                                    isWatched -> "✓ Watched"
                                    persistedMovieId == 0L -> "Not in library yet"
                                    else -> "Watchlist"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                                color = when {
                                    isWatched -> Color(0xFF4ADE80)
                                    persistedMovieId == 0L -> TvTimePalette.TextSecondary
                                    else -> TvTimePalette.Yellow
                                }
                            )
                        }
                        if (persistedMovieId != 0L) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        dao.deleteMovie(persistedMovieId)
                                        FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                        onDismiss()
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "✕ Not Watched (Remove)",
                                    color = TvTimePalette.Error,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!isWatched) {
                            val isInWatchlist = !isWatched && persistedMovieId != 0L
                            Surface(
                                onClick = {
                                    if (isInWatchlist) {
                                        scope.launch {
                                            dao.deleteMovie(persistedMovieId)
                                            FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                            onDismiss()
                                        }
                                    } else {
                                        isWatched = false
                                        persistMovie(nextWatched = false)
                                    }
                                },
                                shape = RoundedCornerShape(50),
                                color = if (isInWatchlist) TvTimePalette.Yellow else TvTimePalette.SurfacePill,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        WatchlistBookmarkIcon(
                                            tint = if (isInWatchlist) TvTimePalette.OnYellow else TvTimePalette.Yellow,
                                            size = 14.dp,
                                            filled = true
                                        )
                                        Text(
                                            text = if (isInWatchlist) "In Watchlist" else "Add to Watchlist",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isInWatchlist) TvTimePalette.OnYellow else Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        Surface(
                            onClick = {
                                if (isWatched && persistedMovieId != 0L) {
                                    scope.launch {
                                        dao.deleteMovie(persistedMovieId)
                                        FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                        onDismiss()
                                    }
                                } else {
                                    isWatched = true
                                    persistMovie(nextWatched = true)
                                }
                            },
                            shape = RoundedCornerShape(50),
                            color = if (isWatched) TvTimePalette.Yellow else TvTimePalette.SurfacePill,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "✓ Watched",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isWatched) TvTimePalette.OnYellow else Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = movie.overview.ifBlank { "No overview available." },
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFD4D4D8)
            )

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
fun TvTimeImportHistoryDialog(
    onDismiss: () -> Unit,
    onExportJson: () -> Unit,
    onExportCsv: () -> Unit,
    onChooseImportFile: () -> Unit
) {
    // Export & Import TV Time History Dialog
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF18181D),
        shape = RoundedCornerShape(28.dp),
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF27272F)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("⇅", color = TvTimePalette.Yellow, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Export & Import TV Time Library",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Export a complete backup of your shows, movies, episode progress, and followed actors as JSON or CSV — or restore a previous TV Time backup file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TvTimePalette.TextSecondary,
                    textAlign = TextAlign.Center
                )
                listOf(
                    "1" to "Export JSON (.json) for full lossless backup and cross-device restore.",
                    "2" to "Export CSV (.csv) to open your watch history in Excel or Google Sheets.",
                    "3" to "Import a CSV or JSON backup file to merge shows, movies, and watched episodes."
                ).forEach { (num, stepText) ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF27272F)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(num, color = TvTimePalette.Yellow, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            text = stepText,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFE4E4E7)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onExportJson,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TvTimePalette.Yellow,
                        contentColor = TvTimePalette.OnYellow
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "📤 Export Library Backup (.json)",
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                Button(
                    onClick = onExportCsv,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TvTimePalette.SurfacePill,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "📊 Export Spreadsheet (.csv)",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                Button(
                    onClick = onChooseImportFile,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TvTimePalette.SurfacePill,
                        contentColor = TvTimePalette.Yellow
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "📥 Import Backup File (.csv or .json)",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    )
}

