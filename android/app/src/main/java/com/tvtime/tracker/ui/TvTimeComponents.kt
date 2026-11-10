package com.tvtime.tracker.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvtime.tracker.data.MovieEntity
import com.tvtime.tracker.data.ShowEntity
import com.tvtime.tracker.domain.SearchResultItem
import com.tvtime.tracker.domain.TvTimeProgressCalculator
import com.tvtime.tracker.ui.theme.TvTimePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object RemoteBitmapCache {
    private val cache = LruCache<String, ImageBitmap>(80)

    fun get(url: String): ImageBitmap? = cache.get(url)

    fun put(url: String, bmp: ImageBitmap) {
        cache.put(url, bmp)
    }
}

@Composable
fun NetworkPosterImage(
    url: String,
    title: String,
    width: Dp = 64.dp,
    height: Dp = 96.dp,
    cornerRadius: Dp = 12.dp
) {
    var bitmap by remember(url) { mutableStateOf( if (url.isNotBlank()) RemoteBitmapCache.get(url) else null ) }

    LaunchedEffect(url) {
        if (url.isNotBlank() && bitmap == null) {
            withContext(Dispatchers.IO) {
                try {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 4500
                        readTimeout = 4500
                    }
                    if (conn.responseCode in 200..299) {
                        val bytes = conn.inputStream.use { it.readBytes() }
                        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                        if (decoded != null) {
                            RemoteBitmapCache.put(url, decoded)
                            bitmap = decoded
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    val shape = RoundedCornerShape(cornerRadius)
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width, height)
                .clip(shape)
                .background(Color(0xFF1E1E24))
        )
    } else {
        Box(
            modifier = Modifier
                .size(width, height)
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF272732), Color(0xFF14141A))
                    )
                )
                .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
                .padding(6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title.take(18),
                style = MaterialTheme.typography.labelSmall,
                color = TvTimePalette.Yellow,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun TvYellowEyeButton(
    onClick: () -> Unit,
    isChecked: Boolean = false,
    isPlus: Boolean = false,
    size: Dp = 44.dp
) {
    if (isChecked) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(TvTimePalette.GreenProgress)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Watched",
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(Color(0xFF232329))
                .border(1.5.dp, Color(0xFF3F3F46), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (isPlus) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add",
                    tint = TvTimePalette.Yellow,
                    modifier = Modifier.size(22.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Mark watched",
                    tint = TvTimePalette.TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun TvShowWatchCard(
    show: ShowEntity,
    onCardClick: () -> Unit,
    onEyeClick: () -> Unit
) {
    val episodes = remember(show.episodesJson) {
        TvTimeProgressCalculator.parseEpisodeList(show.episodesJson)
    }
    val watchedSet = remember(show.watchedCodesCsv) {
        TvTimeProgressCalculator.parseWatchedSet(show.watchedCodesCsv)
    }
    val progress = remember(episodes, watchedSet) {
        TvTimeProgressCalculator.computeShowProgress(episodes, watchedSet)
    }
    val statusKey = remember(show.userStatus, progress.isCompleted, progress.hasStarted) {
        TvTimeProgressCalculator.deriveEffectiveShowStatus(progress, show.userStatus)
    }

    Surface(
        onClick = onCardClick,
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
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = show.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF27272F)
                    ) {
                        Text(
                            text = show.year.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = TvTimePalette.TextSecondary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                when (statusKey) {
                    "completed" -> {
                        Text(
                            text = "TV Show · Watched",
                            style = MaterialTheme.typography.labelSmall,
                            color = TvTimePalette.Yellow,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Completed ·",
                                style = MaterialTheme.typography.bodySmall,
                                color = TvTimePalette.TextSecondary,
                                maxLines = 1
                            )
                            Text(
                                text = "All ${progress.totalEpisodes} episodes watched",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = TvTimePalette.Yellow,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    "stopped" -> {
                        Text(
                            text = "TV Show · Stopped Watching",
                            style = MaterialTheme.typography.labelSmall,
                            color = TvTimePalette.Error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${progress.watchedCount}/${progress.totalEpisodes} episodes watched",
                            style = MaterialTheme.typography.bodySmall,
                            color = TvTimePalette.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    else -> {
                        if (statusKey == "interested" && !progress.hasStarted) {
                            Text(
                                text = "TV Show · Watchlist",
                                style = MaterialTheme.typography.labelSmall,
                                color = TvTimePalette.Yellow,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .width(110.dp)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color(0xFF27272F))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(progress.progressFraction.coerceAtLeast(0.08f))
                                        .clip(RoundedCornerShape(50))
                                        .background(TvTimePalette.GreenProgress)
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "${progress.leftCount} ep${if (progress.leftCount == 1) "" else "s"} left",
                                style = MaterialTheme.typography.bodySmall,
                                color = TvTimePalette.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = progress.nextEpisode?.formattedDotCode ?: "S01·E01",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.ExtraBold),
                                color = TvTimePalette.Yellow,
                                maxLines = 1
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(text = "★", fontSize = 12.sp, color = TvTimePalette.Yellow)
                        Text(
                            text = String.format("%.1f", show.rating),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            maxLines = 1
                        )
                    }
                    Text(
                        text = "📋 Episodes (${progress.watchedCount}/${progress.totalEpisodes})",
                        style = MaterialTheme.typography.labelSmall,
                        color = TvTimePalette.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            TvYellowEyeButton(
                onClick = onEyeClick,
                isChecked = statusKey == "completed"
            )
        }
    }
}

@Composable
fun TvMovieWatchCard(
    movie: MovieEntity,
    onCardClick: () -> Unit,
    onToggleWatched: () -> Unit
) {
    Surface(
        onClick = onCardClick,
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
            NetworkPosterImage(url = movie.posterUrl, title = movie.title)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = movie.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF27272F)
                    ) {
                        Text(
                            text = movie.year.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = TvTimePalette.TextSecondary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Text(
                    text = "Movie · ${movie.runtimeMins}m · ${if (movie.watched) "Watched" else "Watchlist"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (movie.watched) TvTimePalette.Yellow else TvTimePalette.CyanMovie,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = movie.castCsv.ifBlank { movie.director.ifBlank { movie.streamingService } },
                    style = MaterialTheme.typography.bodySmall,
                    color = TvTimePalette.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(text = "★", fontSize = 12.sp, color = TvTimePalette.Yellow)
                    Text(
                        text = String.format("%.1f", movie.rating),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White
                    )
                }
            }

            TvYellowEyeButton(
                onClick = onToggleWatched,
                isChecked = movie.watched
            )
        }
    }
}

@Composable
fun TvSearchResultCard(
    item: SearchResultItem,
    isInWatchlist: Boolean,
    isCompleted: Boolean,
    onCardClick: () -> Unit,
    onWatchlistClick: () -> Unit,
    onCompletedClick: () -> Unit
) {
    Surface(
        onClick = onCardClick,
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
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NetworkPosterImage(url = item.posterUrl, title = item.title)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF27272F)
                    ) {
                        Text(
                            text = item.year.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = TvTimePalette.TextSecondary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                val badgeColor = if (item.mediaType == "movie") TvTimePalette.CyanMovie else TvTimePalette.Yellow
                val badgeLabel = if (item.mediaType == "movie") "Movie" else "TV Show"

                Text(
                    text = badgeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeColor
                )

                Text(
                    text = item.overview.ifBlank { item.networkOrDirector },
                    style = MaterialTheme.typography.bodySmall,
                    color = TvTimePalette.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(text = "★", fontSize = 12.sp, color = TvTimePalette.Yellow)
                    Text(
                        text = String.format("%.1f", item.rating),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // + Watchlist quick-action button
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (isInWatchlist) TvTimePalette.Yellow else Color(0xFF232329))
                        .border(
                            width = 1.5.dp,
                            color = if (isInWatchlist) TvTimePalette.Yellow else Color(0xFF3F3F46),
                            shape = CircleShape
                        )
                        .clickable(onClick = onWatchlistClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = if (isInWatchlist) "In Watchlist" else "Add to Watchlist",
                        tint = if (isInWatchlist) Color(0xFF09090B) else TvTimePalette.Yellow,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // ✓ Completed quick-action button
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (isCompleted) TvTimePalette.GreenProgress else Color(0xFF232329))
                        .border(
                            width = 1.5.dp,
                            color = if (isCompleted) TvTimePalette.GreenProgress else Color(0xFF3F3F46),
                            shape = CircleShape
                        )
                        .clickable(onClick = onCompletedClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = if (isCompleted) "Completed" else "Mark Completed",
                        tint = if (isCompleted) Color.White else TvTimePalette.TextSecondary,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
    }
}

