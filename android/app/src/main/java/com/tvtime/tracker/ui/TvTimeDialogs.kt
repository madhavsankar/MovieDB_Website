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
    var episodes by remember(show.episodesJson) {
        mutableStateOf(TvTimeProgressCalculator.parseEpisodeList(show.episodesJson))
    }
    var watchedSet by remember(show.watchedCodesCsv) {
        mutableStateOf(TvTimeProgressCalculator.parseWatchedSet(show.watchedCodesCsv))
    }
    var isFavorite by remember(show.isFavorite) { mutableStateOf(show.isFavorite) }
    var reaction by remember(show.reaction) { mutableStateOf(show.reaction) }
    var rewatchCount by remember(show.rewatchCount) { mutableStateOf(show.rewatchCount) }
    var openSeasons by remember { mutableStateOf(setOf(1, 2)) }

    // Fetch full episode guide from TVMaze if only stub episodes exist
    LaunchedEffect(show.id, show.tvmazeId) {
        if (show.tvmazeId > 0 && episodes.size <= 1) {
            val (fetchedEps, fetchedCast) = LiveMediaCatalogService.fetchFullShowEpisodesAndCast(show.tvmazeId)
            if (fetchedEps.isNotEmpty()) {
                episodes = fetchedEps
                val updatedShow = show.copy(
                    episodesJson = TvTimeProgressCalculator.serializeEpisodeList(fetchedEps),
                    castCsv = if (fetchedCast.isNotEmpty()) fetchedCast.joinToString(", ") else show.castCsv
                )
                dao.updateShow(updatedShow)
                FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
            }
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
                .fillMaxHeight(0.86f)
        ) {
            // Top Tabs: Info | Episodes (Screenshot 4)
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
                            text = label,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (selected) Color.White else TvTimePalette.TextSecondary,
                            modifier = Modifier.padding(vertical = 12.dp)
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
                            if (show.castCsv.isNotBlank()) {
                                Text(
                                    text = show.castCsv,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TvTimePalette.Yellow
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        isFavorite = !isFavorite
                                        scope.launch {
                                            dao.updateShow(show.copy(isFavorite = isFavorite))
                                            FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isFavorite) TvTimePalette.Yellow else TvTimePalette.SurfacePill,
                                        contentColor = if (isFavorite) TvTimePalette.OnYellow else Color.White
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(if (isFavorite) "★ Favorited" else "☆ Favorite", fontSize = 12.sp)
                                }

                                Button(
                                    onClick = {
                                        rewatchCount += 1
                                        scope.launch {
                                            dao.updateShow(show.copy(rewatchCount = rewatchCount))
                                            FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = TvTimePalette.SurfacePill,
                                        contentColor = Color.White
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text("↻ Rewatch ($rewatchCount)", fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    Text(
                        text = show.overview.ifBlank { "No overview available." },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFD4D4D8)
                    )

                    Text(
                        text = "REACTION BADGE",
                        style = MaterialTheme.typography.labelSmall,
                        color = TvTimePalette.TextSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("😍", "🔥", "🤯", "😂", "😢", "😴").forEach { emoji ->
                            Surface(
                                onClick = {
                                    reaction = emoji
                                    scope.launch {
                                        dao.updateShow(show.copy(reaction = emoji))
                                        FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                    }
                                },
                                shape = CircleShape,
                                color = if (reaction == emoji) TvTimePalette.Yellow else TvTimePalette.SurfacePill,
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(text = emoji, fontSize = 16.sp)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            scope.launch {
                                dao.deleteShow(show.id)
                                FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                onDismiss()
                            }
                        }
                    ) {
                        Text("Remove from Watch List", color = TvTimePalette.Error)
                    }
                }
            } else {
                // Episodes Tab: Grouped by Season with Accordions (Screenshot 4)
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
                        val allSeasonWatched = seasonEps.isNotEmpty() && seasonEps.all { watchedSet.contains(it.code.uppercase()) }

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
                                        // Season-wide eye / check button
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
                                                    watchedSet = nextSet
                                                    val csv = TvTimeProgressCalculator.serializeWatchedSet(nextSet)
                                                    scope.launch {
                                                        dao.updateShow(show.copy(watchedCodesCsv = csv))
                                                        FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                                    }
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
                                        text = "${seasonEps.size} episodes  ${if (isSeasonOpen) "∧" else "∨"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TvTimePalette.TextSecondary
                                    )
                                }
                            }
                        }

                        if (isSeasonOpen) {
                            items(seasonEps, key = { "ep_${it.code}" }) { ep ->
                                val isWatched = watchedSet.contains(ep.code.uppercase())
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val nextSet = watchedSet.toMutableSet()
                                            if (isWatched) nextSet.remove(ep.code.uppercase())
                                            else nextSet.add(ep.code.uppercase())
                                            watchedSet = nextSet
                                            val csv = TvTimeProgressCalculator.serializeWatchedSet(nextSet)
                                            scope.launch {
                                                dao.updateShow(show.copy(watchedCodesCsv = csv))
                                                FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                            }
                                        }
                                        .padding(vertical = 6.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    NetworkPosterImage(
                                        url = ep.imageUrl.ifBlank { show.posterUrl },
                                        title = ep.title,
                                        width = 92.dp,
                                        height = 54.dp,
                                        cornerRadius = 10.dp
                                    )

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${ep.number}. ${ep.title}",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = ep.airDate.ifBlank { "Aired" },
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
                                            ),
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
fun FollowingBottomSheet(
    actors: List<ActorEntity>,
    currentUserId: String,
    onSwitchUser: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf("actors") } // "users" | "actors" (Screenshot 6)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF121216),
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.78f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Following",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            // Segmented Pill: Users | Actors
            Surface(
                shape = RoundedCornerShape(50),
                color = TvTimePalette.SurfacePill,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(4.dp)) {
                    listOf("users" to "Users", "actors" to "Actors").forEach { (key, label) ->
                        val active = selectedTab == key
                        Surface(
                            onClick = { selectedTab = key },
                            shape = RoundedCornerShape(50),
                            color = if (active) TvTimePalette.Yellow else Color.Transparent,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (active) TvTimePalette.OnYellow else TvTimePalette.TextSecondary
                                )
                            }
                        }
                    }
                }
            }

            if (selectedTab == "users") {
                val partnerId = "partner"
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = TvTimePalette.SurfaceCard,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF27272F)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = partnerId.take(2).uppercase(),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TvTimePalette.Yellow
                                )
                            }
                            Column {
                                Text(
                                    text = "@$partnerId",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White
                                )
                                Text(
                                    text = "2-User Shared Household · Tap Switch to view",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TvTimePalette.Yellow
                                )
                            }
                        }
                        Button(
                            onClick = {
                                onSwitchUser(partnerId)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TvTimePalette.Yellow,
                                contentColor = TvTimePalette.OnYellow
                            )
                        ) {
                            Text("Switch", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(actors, key = { it.id }) { actor ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                NetworkPosterImage(
                                    url = actor.imageUrl,
                                    title = actor.name,
                                    width = 48.dp,
                                    height = 48.dp,
                                    cornerRadius = 24.dp
                                )
                                Column {
                                    Text(
                                        text = actor.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color.White
                                    )
                                    Text(
                                        text = actor.role.ifBlank { "Acting" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TvTimePalette.TextSecondary
                                    )
                                }
                            }

                            Surface(
                                onClick = {
                                    val url = actor.imdbUrl.ifBlank {
                                        "https://www.imdb.com/find/?q=${Uri.encode(actor.name)}"
                                    }
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                },
                                shape = RoundedCornerShape(6.dp),
                                color = TvTimePalette.Yellow.copy(alpha = 0.18f),
                                border = BorderStroke(1.dp, TvTimePalette.Yellow.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = "IMDb",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TvTimePalette.Yellow,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
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

