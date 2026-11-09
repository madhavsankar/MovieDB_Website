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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
    var activeTab by remember { mutableStateOf("episodes") } // "info" | "episodes"
    var episodes by remember(show.id, show.episodesJson) {
        mutableStateOf(TvTimeProgressCalculator.parseEpisodeList(show.episodesJson))
    }
    var watchedSet by remember(show.id, show.watchedCodesCsv) {
        mutableStateOf(TvTimeProgressCalculator.parseWatchedSet(show.watchedCodesCsv))
    }
    var userStatus by remember(show.id, show.userStatus) {
        val raw = show.userStatus.lowercase()
        val initial = when {
            raw == "stopped" -> "stopped"
            raw == "completed" || raw == "watched" -> "completed"
            raw == "interested" || raw == "watchlist" -> "interested"
            watchedSet.isNotEmpty() -> "in_progress"
            else -> "interested"
        }
        mutableStateOf(initial)
    }
    var isFavorite by remember(show.id, show.isFavorite) { mutableStateOf(show.isFavorite) }
    var castCsv by remember(show.id, show.castCsv) { mutableStateOf(show.castCsv) }
    var openSeasons by remember(show.id) {
        val initialSeasons = TvTimeProgressCalculator.parseEpisodeList(show.episodesJson).map { it.season }.toSet()
        mutableStateOf(if (initialSeasons.isNotEmpty()) initialSeasons else setOf(1, 2))
    }

    fun deriveStatusFromWatched(
        eps: List<EpisodeModel>,
        watched: Set<String>,
        current: String
    ): String {
        return when {
            eps.isNotEmpty() && watched.size >= eps.size -> "completed"
            watched.isNotEmpty() -> if (current == "stopped") "stopped" else "in_progress"
            else -> if (current == "stopped") "stopped" else "interested"
        }
    }

    fun persistUpdatedShow(
        nextEpisodes: List<EpisodeModel> = episodes,
        nextWatchedSet: Set<String> = watchedSet,
        nextStatus: String = userStatus,
        nextFavorite: Boolean = isFavorite,
        nextCastCsv: String = castCsv
    ) {
        val updated = show.copy(
            episodesJson = TvTimeProgressCalculator.serializeEpisodeList(nextEpisodes),
            watchedCodesCsv = TvTimeProgressCalculator.serializeWatchedSet(nextWatchedSet),
            userStatus = nextStatus,
            isFavorite = nextFavorite,
            rewatchCount = 0,
            reaction = "",
            castCsv = nextCastCsv
        )
        scope.launch {
            dao.updateShow(updated)
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
            openSeasons = openSeasons + fetchedEps.map { it.season }.toSet()
            val mergedCast = if (fetchedCast.isNotEmpty()) fetchedCast.joinToString(", ") else castCsv
            castCsv = mergedCast
            val nextWatched = if (userStatus == "completed") {
                fetchedEps.map { it.code.uppercase() }.toSet()
            } else {
                watchedSet
            }
            watchedSet = nextWatched
            persistUpdatedShow(
                nextEpisodes = fetchedEps,
                nextWatchedSet = nextWatched,
                nextCastCsv = mergedCast
            )
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
            // Show Status Selector Bar: Interested | In Progress | Watched | Stopped | Not Watched (Remove)
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
                    Text(
                        text = show.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            scope.launch {
                                dao.deleteShow(show.id)
                                FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                onDismiss()
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("✕ Not Watched", color = TvTimePalette.Error, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "interested" to "🔖 Interested",
                        "in_progress" to "▶ In Progress",
                        "completed" to "✓ Watched",
                        "stopped" to "⏹ Stopped"
                    ).forEach { (statusKey, label) ->
                        val active = userStatus == statusKey
                        Surface(
                            onClick = {
                                userStatus = statusKey
                                val nextWatched = when (statusKey) {
                                    "completed" -> episodes.map { it.code.uppercase() }.toSet()
                                    "interested" -> emptySet()
                                    else -> watchedSet
                                }
                                watchedSet = nextWatched
                                persistUpdatedShow(
                                    nextWatchedSet = nextWatched,
                                    nextStatus = statusKey
                                )
                            },
                            shape = RoundedCornerShape(50),
                            color = if (active) TvTimePalette.Yellow else TvTimePalette.SurfacePill,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (active) TvTimePalette.OnYellow else Color.White,
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
                            modifier = Modifier.padding(vertical = 10.dp)
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
                                text = "${show.year} · ${show.network} · ${show.runtimeMins}m · ${show.status}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TvTimePalette.TextSecondary
                            )
                            if (castCsv.isNotBlank()) {
                                Text(
                                    text = castCsv,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TvTimePalette.Yellow
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        isFavorite = !isFavorite
                                        persistUpdatedShow(nextFavorite = isFavorite)
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isFavorite) TvTimePalette.Yellow else TvTimePalette.SurfacePill,
                                        contentColor = if (isFavorite) TvTimePalette.OnYellow else Color.White
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(if (isFavorite) "★ Favorited" else "☆ Favorite", fontSize = 12.sp)
                                }
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

                LazyColumn(
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
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        // Season-wide eye / check button: marks all episodes in this season watched or not watched
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (allSeasonWatched) TvTimePalette.Yellow else Color(0xFF232329)
                                                )
                                                .clickable {
                                                    val nextSet = watchedSet.toMutableSet()
                                                    if (allSeasonWatched) {
                                                        seasonEps.forEach { nextSet.remove(it.code.uppercase()) }
                                                    } else {
                                                        seasonEps.forEach { nextSet.add(it.code.uppercase()) }
                                                    }
                                                    val nextStat = deriveStatusFromWatched(episodes, nextSet, userStatus)
                                                    watchedSet = nextSet
                                                    userStatus = nextStat
                                                    persistUpdatedShow(nextWatchedSet = nextSet, nextStatus = nextStat)
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (allSeasonWatched) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "Season Watched",
                                                    tint = TvTimePalette.OnYellow,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            } else {
                                                Text("👁", fontSize = 14.sp, color = TvTimePalette.TextSecondary)
                                            }
                                        }

                                        Text(
                                            text = "Season $seasonNum",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = Color.White
                                        )
                                    }

                                    Text(
                                        text = "$watchedInSeason/${seasonEps.size} watched  ${if (isSeasonOpen) "∧" else "∨"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (allSeasonWatched) TvTimePalette.Yellow else TvTimePalette.TextSecondary
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
                                    if (isWatched) nextSet.remove(epCodeUpper)
                                    else nextSet.add(epCodeUpper)
                                    val nextStat = deriveStatusFromWatched(episodes, nextSet, userStatus)
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
                                                if (isWatched) TvTimePalette.Yellow else Color(0xFF232329)
                                            )
                                            .clickable { toggleSingleEpisode() },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isWatched) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Watched",
                                                tint = TvTimePalette.OnYellow,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        } else {
                                            Text("👁", fontSize = 15.sp, color = TvTimePalette.TextSecondary)
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
    var isWatched by remember(movie.id, movie.watched) { mutableStateOf(movie.watched) }
    var isFavorite by remember(movie.id, movie.isFavorite) { mutableStateOf(movie.isFavorite) }

    fun persistMovie(nextWatched: Boolean = isWatched, nextFavorite: Boolean = isFavorite) {
        val updated = movie.copy(
            watched = nextWatched,
            isFavorite = nextFavorite,
            rewatchCount = 0,
            reaction = ""
        )
        scope.launch {
            dao.updateMovie(updated)
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
                        text = "${movie.year} · Movie · ${movie.runtimeMins}m",
                        style = MaterialTheme.typography.bodySmall,
                        color = TvTimePalette.CyanMovie
                    )
                    if (movie.castCsv.isNotBlank() || movie.director.isNotBlank()) {
                        Text(
                            text = movie.castCsv.ifBlank { movie.director },
                            style = MaterialTheme.typography.bodySmall,
                            color = TvTimePalette.TextSecondary
                        )
                    }
                    Button(
                        onClick = {
                            isFavorite = !isFavorite
                            persistMovie(nextFavorite = isFavorite)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isFavorite) TvTimePalette.Yellow else TvTimePalette.SurfacePill,
                            contentColor = if (isFavorite) TvTimePalette.OnYellow else Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(if (isFavorite) "★ Favorited" else "☆ Favorite", fontSize = 12.sp)
                    }
                }
            }

            // Movie Status Bar: Interested (Watchlist) | Watched | Not Watched (Remove)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    onClick = {
                        isWatched = false
                        persistMovie(nextWatched = false)
                    },
                    shape = RoundedCornerShape(50),
                    color = if (!isWatched) TvTimePalette.Yellow else TvTimePalette.SurfacePill,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "🔖 Interested",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (!isWatched) TvTimePalette.OnYellow else Color.White
                        )
                    }
                }

                Surface(
                    onClick = {
                        isWatched = true
                        persistMovie(nextWatched = true)
                    },
                    shape = RoundedCornerShape(50),
                    color = if (isWatched) TvTimePalette.Yellow else TvTimePalette.SurfacePill,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "✓ Watched",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isWatched) TvTimePalette.OnYellow else Color.White
                        )
                    }
                }

                Surface(
                    onClick = {
                        scope.launch {
                            dao.deleteMovie(movie.id)
                            FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                            onDismiss()
                        }
                    },
                    shape = RoundedCornerShape(50),
                    color = TvTimePalette.SurfacePill,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "✕ Not Watched",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TvTimePalette.Error
                        )
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
                    "3" to "Import a JSON backup file to merge shows, movies, and watched episodes."
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
                        "📥 Import Backup File (.json)",
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

