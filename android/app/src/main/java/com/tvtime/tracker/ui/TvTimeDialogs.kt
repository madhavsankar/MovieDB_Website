package com.tvtime.tracker.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    val initialCompacted = remember(show.id, show.episodesJson) {
        TvTimeProgressCalculator.parseCompactedMeta(show.episodesJson)
    }
    var episodes by remember(show.id, show.episodesJson) {
        mutableStateOf(initialCompacted.episodes)
    }
    var completedSeasons by remember(show.id, show.episodesJson) {
        mutableStateOf(initialCompacted.completedSeasons)
    }
    var totalEpisodesOverride by remember(show.id, show.episodesJson) {
        mutableIntStateOf(initialCompacted.totalEpisodes)
    }
    var isCountOnlyMode by remember(show.id, show.episodesJson) {
        mutableStateOf(initialCompacted.countOnly || show.externalId.startsWith("imdb_tv_"))
    }
    var isIndianShow by remember(show.id, show.episodesJson, show.network) {
        mutableStateOf(
            initialCompacted.isIndian ||
                initialCompacted.countOnly ||
                show.externalId.startsWith("imdb_tv_") ||
                LiveMediaCatalogService.isIndianBroadcasterOrKnown(show.network, show.tmdbId)
        )
    }
    var isLoadingGuide by remember(show.id) {
        mutableStateOf(initialCompacted.episodes.isEmpty())
    }
    var showOverview by remember(show.id, show.overview) {
        mutableStateOf(show.overview)
    }
    var watchedSet by remember(show.id, show.watchedCodesCsv) {
        mutableStateOf(TvTimeProgressCalculator.parseWatchedSet(show.watchedCodesCsv))
    }
    var userStatus by remember(show.id, show.userStatus, show.watchedCodesCsv) {
        val initWatched = TvTimeProgressCalculator.parseWatchedSet(show.watchedCodesCsv)
        val initProg = TvTimeProgressCalculator.computeShowProgress(
            episodes = initialCompacted.episodes,
            watchedCodes = initWatched,
            rawUserStatus = show.userStatus,
            totalEpisodesOverride = initialCompacted.totalEpisodes,
            completedSeasons = initialCompacted.completedSeasons
        )
        val initStat = if (show.id == 0L || show.userStatus == "preview") {
            "preview"
        } else {
            TvTimeProgressCalculator.deriveEffectiveShowStatus(initProg, show.userStatus)
        }
        mutableStateOf(initStat)
    }
    var castCsv by remember(show.id, show.castCsv) { mutableStateOf(show.castCsv) }
    var openSeasons by remember(show.id) {
        val initWatched = TvTimeProgressCalculator.parseWatchedSet(show.watchedCodesCsv)
        val initProg = TvTimeProgressCalculator.computeShowProgress(
            episodes = initialCompacted.episodes,
            watchedCodes = initWatched,
            rawUserStatus = show.userStatus,
            totalEpisodesOverride = initialCompacted.totalEpisodes,
            completedSeasons = initialCompacted.completedSeasons
        )
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
        val p = TvTimeProgressCalculator.computeShowProgress(
            episodes = eps,
            watchedCodes = watched,
            rawUserStatus = current,
            totalEpisodesOverride = totalEpisodesOverride,
            completedSeasons = completedSeasons
        )
        return TvTimeProgressCalculator.deriveEffectiveShowStatus(p, current)
    }

    fun persistUpdatedShow(
        nextEpisodes: List<EpisodeModel> = episodes,
        nextWatchedSet: Set<String> = watchedSet,
        nextStatus: String = userStatus,
        nextCastCsv: String = castCsv,
        nextCompletedSeasons: Map<Int, Int> = completedSeasons,
        nextTotalOverride: Int = totalEpisodesOverride,
        nextOverview: String = showOverview,
        nextRuntimeOverride: Int = 0,
        nextCountOnly: Boolean = isCountOnlyMode,
        nextIsIndian: Boolean = isIndianShow
    ) {
        val cleanStatus = if (nextStatus == "preview") "interested" else nextStatus
        val airedCount = TvTimeProgressCalculator.filterAiredEpisodes(nextEpisodes).size
        val serializedEpisodes = if (nextIsIndian || nextCompletedSeasons.isNotEmpty() || ((nextTotalOverride > 0 || nextCountOnly) && nextEpisodes.isEmpty())) {
            TvTimeProgressCalculator.serializeCompactedMeta(
                totalEpisodes = maxOf(nextTotalOverride, airedCount),
                completedSeasons = nextCompletedSeasons,
                episodes = nextEpisodes,
                countOnly = nextCountOnly && nextEpisodes.isEmpty(),
                isIndian = nextIsIndian
            )
        } else {
            TvTimeProgressCalculator.serializeEpisodeList(nextEpisodes)
        }
        val updated = show.copy(
            id = persistedShowId,
            overview = nextOverview,
            runtimeMins = nextRuntimeOverride.takeIf { it > 0 } ?: show.runtimeMins,
            episodesJson = serializedEpisodes,
            watchedCodesCsv = TvTimeProgressCalculator.serializeWatchedSet(nextWatchedSet),
            userStatus = cleanStatus,
            isFavorite = false,
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

    // Fetch full episode guide & live overview from TMDB or TVMaze so all seasons & episodes can be marked watched
    LaunchedEffect(show.id, show.tvmazeId, show.tmdbId, show.externalId) {
        if (showOverview.isBlank()) {
            val fetchedOv = LiveMediaCatalogService.fetchShowOverview(
                tvmazeId = show.tvmazeId,
                tmdbId = show.tmdbId,
                title = show.title,
                externalId = show.externalId
            )
            if (fetchedOv.isNotBlank()) {
                showOverview = fetchedOv
            }
        }
        val guide = LiveMediaCatalogService.fetchFullShowGuide(
            tvmazeId = show.tvmazeId,
            tmdbId = show.tmdbId,
            title = show.title,
            externalId = show.externalId,
            year = show.year
        )
        val fetchedEps = guide.episodes
        val fetchedCast = guide.cast
        if (guide.isIndianShow) {
            isIndianShow = true
        }
        if (guide.overviewOverride.isNotBlank() && showOverview.isBlank()) {
            showOverview = guide.overviewOverride
        }
        if (guide.countOnly && fetchedEps.isEmpty() && guide.totalEpisodesOverride > 1) {
            val prevTotal = totalEpisodesOverride
            totalEpisodesOverride = maxOf(guide.totalEpisodesOverride, prevTotal)
            episodes = emptyList()
            isCountOnlyMode = true
            isLoadingGuide = false
            if (fetchedCast.isNotEmpty()) castCsv = fetchedCast.joinToString(", ")
            if (persistedShowId != 0L && (guide.totalEpisodesOverride > prevTotal || !initialCompacted.countOnly)) {
                persistUpdatedShow(
                    nextEpisodes = emptyList(),
                    nextWatchedSet = if (userStatus == "completed") emptySet() else watchedSet,
                    nextStatus = userStatus,
                    nextCastCsv = castCsv,
                    nextCompletedSeasons = completedSeasons,
                    nextTotalOverride = totalEpisodesOverride,
                    nextOverview = showOverview,
                    nextRuntimeOverride = guide.runtimeMinsOverride,
                    nextCountOnly = true
                )
            }
        }
        val hadSeasonOne = episodes.any { it.season == 1 }
        val fetchedHasSeasonOne = fetchedEps.any { it.season == 1 }
        if (fetchedEps.isNotEmpty() && (fetchedEps.size > episodes.size || completedSeasons.isNotEmpty() || (!hadSeasonOne && fetchedHasSeasonOne))) {
            isCountOnlyMode = false
            isLoadingGuide = false
            val sortedFetched = fetchedEps.sortedWith(compareBy({ it.season }, { it.number }))
            val mergedCast = if (fetchedCast.isNotEmpty()) fetchedCast.joinToString(", ") else castCsv
            val isOngoingShow = !show.status.equals("Ended", ignoreCase = true) &&
                !show.status.equals("Canceled", ignoreCase = true)
            val nextWatched = TvTimeProgressCalculator.expandWatchedSetWithCompletedSeasons(
                episodes = sortedFetched,
                watchedCodes = watchedSet,
                completedSeasons = completedSeasons,
                rawUserStatus = userStatus,
                previousWatchedAiredCount = totalEpisodesOverride,
                isOngoingShow = isOngoingShow
            )
            val airedFetchedCount = TvTimeProgressCalculator.filterAiredEpisodes(sortedFetched).size.coerceAtLeast(1)
            val wasAlreadyCompactedCompleted = show.userStatus.equals("completed", ignoreCase = true) &&
                completedSeasons.isEmpty() &&
                episodes.size <= 1
            episodes = sortedFetched
            completedSeasons = emptyMap()
            totalEpisodesOverride = airedFetchedCount
            castCsv = mergedCast
            val nextProg = TvTimeProgressCalculator.computeShowProgress(sortedFetched, nextWatched, userStatus)
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
            // Only write back on open if the show wasn't already a compacted completed show (or if newly aired episodes moved it back to in_progress)
            if (persistedShowId != 0L && (!wasAlreadyCompactedCompleted || nextEff != "completed")) {
                persistUpdatedShow(
                    nextEpisodes = sortedFetched,
                    nextWatchedSet = nextWatched,
                    nextStatus = nextEff,
                    nextCastCsv = mergedCast,
                    nextCompletedSeasons = emptyMap(),
                    nextTotalOverride = airedFetchedCount,
                    nextOverview = showOverview,
                    nextCountOnly = false
                )
            }
        }
        isLoadingGuide = false
    }

    val prog = remember(episodes, watchedSet, userStatus, totalEpisodesOverride, completedSeasons) {
        TvTimeProgressCalculator.computeShowProgress(
            episodes = episodes,
            watchedCodes = watchedSet,
            rawUserStatus = userStatus,
            totalEpisodesOverride = totalEpisodesOverride,
            completedSeasons = completedSeasons
        )
    }
    val effectiveStatus = remember(prog, userStatus, persistedShowId) {
        if (persistedShowId == 0L && userStatus == "preview" && watchedSet.isEmpty() && completedSeasons.isEmpty()) {
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
                            "in_progress" -> "▶ In Progress (${prog.watchedCount}/${prog.totalEpisodes} watched)"
                            "stopped" -> {
                                val lastEp = prog.lastWatchedEpisode
                                if (lastEp != null) {
                                    "⏹ Stopped · Watched till ${lastEp.formattedDotCode} (${prog.watchedCount}/${prog.totalEpisodes} watched)"
                                } else {
                                    "⏹ Stopped (${prog.watchedCount}/${prog.totalEpisodes} watched)"
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
                            var nextComp = completedSeasons
                            val nextWatched = when {
                                isCompleted && episodes.size > 1 -> {
                                    val aired = TvTimeProgressCalculator.filterAiredEpisodes(episodes)
                                    val list = if (aired.size > 1) aired else episodes
                                    nextComp = emptyMap()
                                    list.dropLast(1).map { it.code.uppercase() }.toSet()
                                }
                                isCompleted && episodes.size <= 1 && totalEpisodesOverride > 1 -> {
                                    nextComp = mapOf(1 to (totalEpisodesOverride - 1))
                                    emptySet()
                                }
                                watchedSet.isEmpty() && completedSeasons.isEmpty() && episodes.isNotEmpty() ->
                                    setOf(episodes.first().code.uppercase())
                                else -> watchedSet
                            }
                            watchedSet = nextWatched
                            completedSeasons = nextComp
                            userStatus = "in_progress"
                            val nextProg = TvTimeProgressCalculator.computeShowProgress(
                                episodes = episodes,
                                watchedCodes = nextWatched,
                                rawUserStatus = "in_progress",
                                totalEpisodesOverride = totalEpisodesOverride,
                                completedSeasons = nextComp
                            )
                            openSeasons = if (nextProg.nextEpisode != null) setOf(nextProg.nextEpisode.season) else emptySet()
                            persistUpdatedShow(
                                nextWatchedSet = nextWatched,
                                nextStatus = "in_progress",
                                nextCompletedSeasons = nextComp
                            )
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
                                val airedEps = TvTimeProgressCalculator.filterAiredEpisodes(episodes)
                                val targetEps = if (airedEps.isNotEmpty()) airedEps else episodes.take(1)
                                val nextWatched = targetEps.map { it.code.uppercase() }.toSet()
                                watchedSet = nextWatched
                                completedSeasons = emptyMap()
                                totalEpisodesOverride = targetEps.size.coerceAtLeast(totalEpisodesOverride).coerceAtLeast(1)
                                userStatus = "completed"
                                openSeasons = emptySet()
                                persistUpdatedShow(
                                    nextWatchedSet = nextWatched,
                                    nextStatus = "completed",
                                    nextCompletedSeasons = emptyMap(),
                                    nextTotalOverride = totalEpisodesOverride
                                )
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
                            var nextComp = completedSeasons
                            val nextWatched = when {
                                isCompleted && episodes.size > 1 -> {
                                    val aired = TvTimeProgressCalculator.filterAiredEpisodes(episodes)
                                    val list = if (aired.size > 1) aired else episodes
                                    nextComp = emptyMap()
                                    list.dropLast(1).map { it.code.uppercase() }.toSet()
                                }
                                isCompleted && episodes.size <= 1 && totalEpisodesOverride > 1 -> {
                                    nextComp = mapOf(1 to (totalEpisodesOverride - 1))
                                    emptySet()
                                }
                                else -> watchedSet
                            }
                            val nextStat = if (isStopped) {
                                if (nextWatched.isNotEmpty() || nextComp.isNotEmpty()) "in_progress" else "interested"
                            } else {
                                "stopped"
                            }
                            watchedSet = nextWatched
                            completedSeasons = nextComp
                            userStatus = nextStat
                            persistUpdatedShow(
                                nextWatchedSet = nextWatched,
                                nextStatus = nextStat,
                                nextCompletedSeasons = nextComp
                            )
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
                val canSetWatchlist = prog.watchedCount == 0 && effectiveStatus != "completed"
                val isInWatchlist = effectiveStatus == "interested" && prog.watchedCount == 0
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
                            text = if (key == "episodes") "Episodes (${prog.watchedCount}/${prog.totalEpisodes})" else label,
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
                        text = showOverview.ifBlank { "No overview available." },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFD4D4D8)
                    )
                }
            } else {
                // Episodes Tab: Grouped by Season with Accordions + Season & Episode Watch Toggles (Unaired locked)
                val groupedBySeason = remember(episodes) {
                    episodes
                        .sortedWith(compareBy({ it.season }, { it.number }))
                        .groupBy { it.season }
                        .toSortedMap()
                }
                val unairedCodes = remember(episodes, completedSeasons) {
                    TvTimeProgressCalculator.buildUnairedCodeSet(
                        episodes = episodes,
                        hasPriorCompletedSeasons = completedSeasons.isNotEmpty()
                    )
                }
                val itemIsCompleted = effectiveStatus == "completed"
                val listState = rememberLazyListState()

                LaunchedEffect(show.id, episodes.size) {
                    if (effectiveStatus == "in_progress") {
                        val scrollProg = TvTimeProgressCalculator.computeShowProgress(episodes, watchedSet)
                        val targetSeason = scrollProg.nextEpisode?.season
                        if (targetSeason != null) {
                            val idx = groupedBySeason.keys.indexOf(targetSeason)
                            if (idx >= 0) {
                                listState.scrollToItem(idx)
                            }
                        }
                    }
                }

                val isCountOnlySeries = isCountOnlyMode && episodes.isEmpty()
                val showWatchedCountCard = isIndianShow || isCountOnlySeries
                val isPlaceholderSingleEp = episodes.size == 1 &&
                    episodes[0].title == "Pilot" &&
                    totalEpisodesOverride <= 1
                val airedGuideEpisodes = remember(episodes, unairedCodes) {
                    episodes.filter { !unairedCodes.contains(it.code.trim().uppercase()) }
                }
                val fixedTotal = if (airedGuideEpisodes.isNotEmpty()) {
                    maxOf(airedGuideEpisodes.size, prog.totalEpisodes, 1)
                } else {
                    prog.totalEpisodes.coerceAtLeast(1)
                }
                var watchedInputText by remember(prog.watchedCount, fixedTotal) {
                    mutableStateOf(prog.watchedCount.coerceAtMost(fixedTotal).toString())
                }

                fun applyWatchedEpisodeCount(nextWatchedRaw: Int) {
                    val cleanWatched = nextWatchedRaw.coerceIn(0, fixedTotal)
                    if (episodes.isNotEmpty()) {
                        val pool = if (airedGuideEpisodes.isNotEmpty()) airedGuideEpisodes else episodes
                        val chosen = pool.take(cleanWatched)
                        val nextSet = chosen.map { it.code.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
                        val nextStat = when {
                            cleanWatched >= pool.size -> "completed"
                            cleanWatched > 0 -> "stopped"
                            else -> "interested"
                        }
                        completedSeasons = emptyMap()
                        totalEpisodesOverride = pool.size.coerceAtLeast(1)
                        watchedSet = nextSet
                        userStatus = nextStat
                        openSeasons = if (nextStat == "stopped" && chosen.isNotEmpty()) {
                            setOf(chosen.last().season)
                        } else {
                            emptySet()
                        }
                        persistUpdatedShow(
                            nextEpisodes = episodes,
                            nextWatchedSet = nextSet,
                            nextStatus = nextStat,
                            nextCompletedSeasons = emptyMap(),
                            nextTotalOverride = pool.size.coerceAtLeast(1),
                            nextCountOnly = false
                        )
                    } else {
                        totalEpisodesOverride = fixedTotal
                        episodes = emptyList()
                        watchedSet = emptySet()
                        val nextComp: Map<Int, Int>
                        val nextStat: String
                        if (cleanWatched >= fixedTotal) {
                            nextComp = emptyMap()
                            nextStat = "completed"
                        } else {
                            nextComp = if (cleanWatched > 0) mapOf(1 to cleanWatched) else emptyMap()
                            nextStat = "stopped"
                        }
                        completedSeasons = nextComp
                        userStatus = nextStat
                        persistUpdatedShow(
                            nextEpisodes = emptyList(),
                            nextWatchedSet = emptySet(),
                            nextStatus = nextStat,
                            nextCompletedSeasons = nextComp,
                            nextTotalOverride = fixedTotal
                        )
                    }
                }

                if (isLoadingGuide && !showWatchedCountCard && (episodes.isEmpty() || isPlaceholderSingleEp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Loading seasons & episodes...",
                            style = MaterialTheme.typography.bodySmall,
                            color = TvTimePalette.TextSecondary
                        )
                    }
                } else {
                val haptics = LocalHapticFeedback.current
                var longPressedEpCode by remember { mutableStateOf<String?>(null) }

                fun markEpisodesUpTo(targetEp: EpisodeModel, unmarkAfter: Boolean) {
                    val validCodes = episodes.map { it.code.trim().uppercase() }.toSet()
                    val nextSet = watchedSet
                        .map { it.trim().uppercase() }
                        .filter { !unairedCodes.contains(it) && (validCodes.isEmpty() || validCodes.contains(it)) }
                        .toMutableSet()
                    val nextComp = completedSeasons.toMutableMap()
                    if (userStatus == "completed" && nextSet.isEmpty()) {
                        episodes.forEach { epItem ->
                            val c = epItem.code.trim().uppercase()
                            if (c.isNotEmpty() && !unairedCodes.contains(c)) nextSet.add(c)
                        }
                    }
                    if (nextComp.isNotEmpty()) {
                        for ((sNum, _) in nextComp.toMap()) {
                            val sList = episodes.filter { it.season == sNum }
                            if (sList.isNotEmpty()) {
                                sList.forEach { eItem ->
                                    val c = eItem.code.trim().uppercase()
                                    if (c.isNotEmpty() && !unairedCodes.contains(c)) nextSet.add(c)
                                }
                                nextComp.remove(sNum)
                            } else if (unmarkAfter && sNum > targetEp.season) {
                                nextComp.remove(sNum)
                            }
                        }
                    }
                    for (epItem in episodes) {
                        val c = epItem.code.trim().uppercase()
                        if (c.isEmpty() || unairedCodes.contains(c)) continue
                        val isAtOrBefore = epItem.season < targetEp.season ||
                            (epItem.season == targetEp.season && epItem.number <= targetEp.number)
                        if (isAtOrBefore) {
                            nextSet.add(c)
                        } else if (unmarkAfter) {
                            nextSet.remove(c)
                        }
                    }
                    val baseStatus = when {
                        userStatus == "completed" && unmarkAfter -> "in_progress"
                        userStatus == "stopped" && !unmarkAfter -> "in_progress"
                        else -> userStatus
                    }
                    completedSeasons = nextComp
                    val nextStat = deriveStatusFromWatched(episodes, nextSet, baseStatus)
                    watchedSet = nextSet
                    userStatus = nextStat
                    persistUpdatedShow(
                        nextWatchedSet = nextSet,
                        nextStatus = nextStat,
                        nextCompletedSeasons = nextComp
                    )
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (showWatchedCountCard) {
                        item(key = "watched_episode_count_card") {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
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
                                                text = "📺 Watched Episode Count",
                                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                                                color = Color.White
                                            )
                                            Text(
                                                text = if (episodes.isEmpty()) {
                                                    "Confirmed total: $fixedTotal episodes"
                                                } else {
                                                    "Enter how many episodes you watched (of $fixedTotal aired)"
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = TvTimePalette.TextSecondary
                                            )
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = TvTimePalette.SurfacePill
                                        ) {
                                            Text(
                                                text = "${prog.watchedCount.coerceAtMost(fixedTotal)} / $fixedTotal",
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black),
                                                color = TvTimePalette.Yellow,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedTextField(
                                            value = watchedInputText,
                                            onValueChange = { watchedInputText = it.filter(Char::isDigit) },
                                            label = { Text("Watched Episodes (of $fixedTotal)", fontSize = 11.sp) },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(
                                                keyboardType = KeyboardType.Number,
                                                imeAction = ImeAction.Done
                                            ),
                                            keyboardActions = KeyboardActions(
                                                onDone = {
                                                    val wVal = watchedInputText.toIntOrNull() ?: prog.watchedCount
                                                    applyWatchedEpisodeCount(wVal)
                                                }
                                            ),
                                            modifier = Modifier.weight(1f)
                                        )
                                        Button(
                                            onClick = {
                                                val wVal = watchedInputText.toIntOrNull() ?: prog.watchedCount
                                                applyWatchedEpisodeCount(wVal)
                                            },
                                            shape = RoundedCornerShape(14.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = TvTimePalette.Yellow,
                                                contentColor = TvTimePalette.OnYellow
                                            ),
                                            modifier = Modifier.height(54.dp)
                                        ) {
                                            Text("Save", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (isLoadingGuide && (episodes.isEmpty() || isPlaceholderSingleEp)) {
                        item(key = "loading_seasons_below_count") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Loading seasons & episodes...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TvTimePalette.TextSecondary
                                )
                            }
                        }
                    }

                    groupedBySeason.forEach { (seasonNum, seasonEps) ->
                        val isSeasonOpen = openSeasons.contains(seasonNum)
                        val airedSeasonEps = seasonEps.filter { !unairedCodes.contains(it.code.trim().uppercase()) }
                        val upcomingInSeason = seasonEps.size - airedSeasonEps.size
                        val isCompactedSeason = (completedSeasons[seasonNum] ?: 0) > 0
                        val watchedInSeason = if (itemIsCompleted || isCompactedSeason) {
                            airedSeasonEps.size
                        } else {
                            airedSeasonEps.count { watchedSet.contains(it.code.trim().uppercase()) }
                        }
                        val allSeasonWatched = (airedSeasonEps.isNotEmpty() && watchedInSeason == airedSeasonEps.size) ||
                            (airedSeasonEps.isEmpty() && isCompactedSeason)

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
                                        if (airedSeasonEps.isNotEmpty() || isCompactedSeason) {
                                            // Season-wide check button: toggles ONLY aired episodes in this season
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (allSeasonWatched) TvTimePalette.GreenProgress else Color(0xFF232329)
                                                    )
                                                    .clickable {
                                                        val validCodes = episodes.map { it.code.trim().uppercase() }.toSet()
                                                        val nextSet = watchedSet
                                                            .map { it.trim().uppercase() }
                                                            .filter { !unairedCodes.contains(it) && (validCodes.isEmpty() || validCodes.contains(it)) }
                                                            .toMutableSet()
                                                        if (userStatus == "completed" && nextSet.isEmpty()) {
                                                            episodes.forEach { epItem ->
                                                                val c = epItem.code.trim().uppercase()
                                                                if (c.isNotEmpty() && !unairedCodes.contains(c)) nextSet.add(c)
                                                            }
                                                        }
                                                        if (allSeasonWatched) {
                                                            val nextComp = completedSeasons.toMutableMap().apply { remove(seasonNum) }
                                                            seasonEps.forEach { nextSet.remove(it.code.trim().uppercase()) }
                                                            val baseStatus = if (userStatus == "completed") "in_progress" else userStatus
                                                            if (nextSet.isEmpty() && nextComp.isEmpty() && baseStatus != "stopped") {
                                                                val toDeleteId = persistedShowId
                                                                persistedShowId = 0L
                                                                watchedSet = emptySet()
                                                                completedSeasons = emptyMap()
                                                                userStatus = "preview"
                                                                if (toDeleteId != 0L) {
                                                                    scope.launch {
                                                                        dao.deleteShow(toDeleteId)
                                                                        FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                                                    }
                                                                }
                                                            } else {
                                                                completedSeasons = nextComp
                                                                val nextStat = deriveStatusFromWatched(episodes, nextSet, baseStatus)
                                                                watchedSet = nextSet
                                                                userStatus = nextStat
                                                                persistUpdatedShow(
                                                                    nextWatchedSet = nextSet,
                                                                    nextStatus = nextStat,
                                                                    nextCompletedSeasons = nextComp
                                                                )
                                                            }
                                                        } else {
                                                            val baseStatus = if (userStatus == "stopped") "in_progress" else userStatus
                                                            airedSeasonEps.forEach { nextSet.add(it.code.trim().uppercase()) }
                                                            val nextStat = deriveStatusFromWatched(episodes, nextSet, baseStatus)
                                                            watchedSet = nextSet
                                                            userStatus = nextStat
                                                            persistUpdatedShow(nextWatchedSet = nextSet, nextStatus = nextStat)
                                                        }
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
                                        } else {
                                            Surface(
                                                shape = RoundedCornerShape(50),
                                                color = Color(0xFF232329),
                                                border = BorderStroke(1.dp, TvTimePalette.Border)
                                            ) {
                                                Text(
                                                    text = "Upcoming",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                    color = TvTimePalette.TextSecondary,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                        }

                                        Text(
                                            text = "Season $seasonNum",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    val seasonCountText = when {
                                        airedSeasonEps.isEmpty() && !isCompactedSeason -> "$upcomingInSeason upcoming"
                                        upcomingInSeason > 0 -> "$watchedInSeason/${airedSeasonEps.size} aired · $upcomingInSeason upcoming"
                                        else -> "$watchedInSeason/${seasonEps.size} watched"
                                    }
                                    Text(
                                        text = "$seasonCountText  ${if (isSeasonOpen) "∧" else "∨"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (allSeasonWatched) TvTimePalette.GreenProgress else TvTimePalette.TextSecondary,
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        if (isSeasonOpen) {
                            if (airedSeasonEps.size >= 20) {
                                item(key = "season_hint_$seasonNum") {
                                    Text(
                                        text = "Tip: Long-press any episode's ✓ tick to mark all episodes till there.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TvTimePalette.TextSecondary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            items(seasonEps, key = { "ep_${it.code}" }) { ep ->
                                val epCodeUpper = ep.code.trim().uppercase()
                                val isUnaired = unairedCodes.contains(epCodeUpper)
                                val isWatched = !isUnaired && (itemIsCompleted || isCompactedSeason || watchedSet.contains(epCodeUpper))

                                val toggleSingleEpisode = toggleEp@{
                                    if (isUnaired) return@toggleEp
                                    val validCodes = episodes.map { it.code.trim().uppercase() }.toSet()
                                    val nextSet = watchedSet
                                        .map { it.trim().uppercase() }
                                        .filter { !unairedCodes.contains(it) && (validCodes.isEmpty() || validCodes.contains(it)) }
                                        .toMutableSet()
                                    if (userStatus == "completed" && nextSet.isEmpty()) {
                                        episodes.forEach { epItem ->
                                            val c = epItem.code.trim().uppercase()
                                            if (c.isNotEmpty() && !unairedCodes.contains(c)) nextSet.add(c)
                                        }
                                    }
                                    if (isWatched) {
                                        val nextComp = completedSeasons.toMutableMap()
                                        if (nextComp.containsKey(ep.season)) {
                                            airedSeasonEps.forEach { nextSet.add(it.code.trim().uppercase()) }
                                            nextComp.remove(ep.season)
                                        }
                                        nextSet.remove(epCodeUpper)
                                        val baseStatus = if (userStatus == "completed") "in_progress" else userStatus
                                        if (nextSet.isEmpty() && nextComp.isEmpty() && baseStatus != "stopped") {
                                            val toDeleteId = persistedShowId
                                            persistedShowId = 0L
                                            watchedSet = emptySet()
                                            completedSeasons = emptyMap()
                                            userStatus = "preview"
                                            if (toDeleteId != 0L) {
                                                scope.launch {
                                                    dao.deleteShow(toDeleteId)
                                                    FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                                }
                                            }
                                        } else {
                                            completedSeasons = nextComp
                                            val nextStat = deriveStatusFromWatched(episodes, nextSet, baseStatus)
                                            watchedSet = nextSet
                                            userStatus = nextStat
                                            persistUpdatedShow(
                                                nextWatchedSet = nextSet,
                                                nextStatus = nextStat,
                                                nextCompletedSeasons = nextComp
                                            )
                                        }
                                    } else {
                                        val baseStatus = if (userStatus == "stopped") "in_progress" else userStatus
                                        nextSet.add(epCodeUpper)
                                        val nextStat = deriveStatusFromWatched(episodes, nextSet, baseStatus)
                                        watchedSet = nextSet
                                        userStatus = nextStat
                                        persistUpdatedShow(nextWatchedSet = nextSet, nextStatus = nextStat)
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .alpha(if (isUnaired) 0.6f else 1f)
                                        .then(
                                            if (isUnaired) {
                                                Modifier
                                            } else {
                                                Modifier.combinedClickable(
                                                    onClick = { toggleSingleEpisode() },
                                                    onLongClick = {
                                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        longPressedEpCode = epCodeUpper
                                                    }
                                                )
                                            }
                                        )
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
                                            color = if (isUnaired) Color(0xFFD4D4D8) else Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${ep.airDate.ifBlank { if (isUnaired) "TBA" else "Aired" }} · ${ep.runtimeMins}m",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TvTimePalette.TextSecondary
                                        )
                                    }

                                    if (isUnaired) {
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = Color(0xFF232329),
                                            border = BorderStroke(1.dp, TvTimePalette.Border)
                                        ) {
                                            Text(
                                                text = "Upcoming",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                color = TvTimePalette.TextSecondary,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                            )
                                        }
                                    } else {
                                        val isMenuOpen = longPressedEpCode == epCodeUpper
                                        Box {
                                            Box(
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (isWatched) TvTimePalette.GreenProgress else Color(0xFF232329)
                                                    )
                                                    .combinedClickable(
                                                        onClick = { toggleSingleEpisode() },
                                                        onLongClick = {
                                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            longPressedEpCode = epCodeUpper
                                                        }
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = if (isWatched) "Watched" else "Mark Watched",
                                                    tint = if (isWatched) Color.White else TvTimePalette.TextSecondary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                            if (isMenuOpen) {
                                                val airedUpTo = episodes.count { eItem ->
                                                    val c = eItem.code.trim().uppercase()
                                                    !unairedCodes.contains(c) && (
                                                        eItem.season < ep.season ||
                                                            (eItem.season == ep.season && eItem.number <= ep.number)
                                                        )
                                                }
                                                val allUpToWatched = episodes.all { eItem ->
                                                    val c = eItem.code.trim().uppercase()
                                                    if (unairedCodes.contains(c)) {
                                                        true
                                                    } else if (eItem.season < ep.season || (eItem.season == ep.season && eItem.number <= ep.number)) {
                                                        itemIsCompleted || (completedSeasons[eItem.season] ?: 0) > 0 || watchedSet.contains(c)
                                                    } else {
                                                        true
                                                    }
                                                }
                                                val watchedAfterCount = episodes.count { eItem ->
                                                    val c = eItem.code.trim().uppercase()
                                                    !unairedCodes.contains(c) &&
                                                        (eItem.season > ep.season || (eItem.season == ep.season && eItem.number > ep.number)) &&
                                                        (itemIsCompleted || (completedSeasons[eItem.season] ?: 0) > 0 || watchedSet.contains(c))
                                                }
                                                DropdownMenu(
                                                    expanded = true,
                                                    onDismissRequest = { longPressedEpCode = null },
                                                    modifier = Modifier.background(Color(0xFF1C1C24))
                                                ) {
                                                    DropdownMenuItem(
                                                        text = {
                                                            Text(
                                                                text = if (allUpToWatched && watchedAfterCount > 0) {
                                                                    "✓ Mark watched till ${ep.formattedDotCode} ($airedUpTo eps)"
                                                                } else {
                                                                    "✓ Mark all episodes till here ($airedUpTo eps)"
                                                                },
                                                                color = TvTimePalette.Yellow,
                                                                fontWeight = FontWeight.ExtraBold,
                                                                fontSize = 13.sp
                                                            )
                                                        },
                                                        onClick = {
                                                            longPressedEpCode = null
                                                            markEpisodesUpTo(ep, unmarkAfter = allUpToWatched && watchedAfterCount > 0)
                                                        }
                                                    )
                                                    if (!allUpToWatched && watchedAfterCount > 0) {
                                                        DropdownMenuItem(
                                                            text = {
                                                                Text(
                                                                    text = "↺ Watched only till here (unmark $watchedAfterCount after)",
                                                                    color = Color.White,
                                                                    fontWeight = FontWeight.SemiBold,
                                                                    fontSize = 12.sp
                                                                )
                                                            },
                                                            onClick = {
                                                                longPressedEpCode = null
                                                                markEpisodesUpTo(ep, unmarkAfter = true)
                                                            }
                                                        )
                                                    }
                                                    DropdownMenuItem(
                                                        text = {
                                                            Text(
                                                                text = if (isWatched) "✕ Unmark only ${ep.formattedDotCode}" else "✓ Mark only ${ep.formattedDotCode}",
                                                                color = TvTimePalette.TextSecondary,
                                                                fontSize = 12.sp
                                                            )
                                                        },
                                                        onClick = {
                                                            longPressedEpCode = null
                                                            toggleSingleEpisode()
                                                        }
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
    var movieOverview by remember(movie.id, movie.overview) { mutableStateOf(movie.overview) }

    LaunchedEffect(movie.id, movie.tmdbId, movie.title) {
        if (LiveMediaCatalogService.isGenericStreamingLabel(streamingService) || castCsv.isBlank() || movieOverview.isBlank()) {
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
                if (enriched.overview.isNotBlank() && movieOverview.isBlank()) {
                    movieOverview = enriched.overview
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
                            castCsv = castCsv,
                            overview = movieOverview
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
            overview = movieOverview,
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
                text = movieOverview.ifBlank { "No overview available." },
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

