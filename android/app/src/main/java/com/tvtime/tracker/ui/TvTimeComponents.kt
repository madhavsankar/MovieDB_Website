package com.tvtime.tracker.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object RemoteBitmapCache {
    // Byte-bounded (max 24 MB or 1/8th JVM heap) & synchronized LruCache preventing OutOfMemoryError crashes
    private val maxCacheKb: Int = run {
        val heapKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
        (heapKb / 8).coerceIn(8 * 1024, 24 * 1024)
    }

    private val cache = object : LruCache<String, ImageBitmap>(maxCacheKb) {
        override fun sizeOf(key: String, value: ImageBitmap): Int {
            // RGB_565 uses 2 bytes/pixel; estimate conservatively in KB
            return ((value.width * value.height * 2) / 1024).coerceAtLeast(1)
        }
    }

    val downloadSemaphore = Semaphore(6)

    @Synchronized
    fun get(key: String): ImageBitmap? = cache.get(key)

    @Synchronized
    fun put(key: String, bmp: ImageBitmap) {
        cache.put(key, bmp)
    }

    @Synchronized
    fun evictAll() {
        cache.evictAll()
    }

    fun optimizePosterUrl(rawUrl: String, widthDp: Float): String {
        val trimmed = rawUrl.trim()
        if (!trimmed.contains("image.tmdb.org/t/p/")) return trimmed
        val targetBucket = when {
            widthDp <= 115f -> "w185"
            widthDp <= 220f -> "w342"
            else -> "w500"
        }
        return trimmed
            .replace("/t/p/original/", "/t/p/$targetBucket/")
            .replace("/t/p/w780/", "/t/p/$targetBucket/")
            .replace("/t/p/w500/", "/t/p/$targetBucket/")
    }

    fun decodeSampledBitmap(bytes: ByteArray, reqWidthPx: Int, reqHeightPx: Int): ImageBitmap? {
        if (bytes.isEmpty()) return null
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
        val srcW = boundsOpts.outWidth
        val srcH = boundsOpts.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        var sampleSize = 1
        val targetW = reqWidthPx.coerceAtLeast(96)
        val targetH = reqHeightPx.coerceAtLeast(96)
        while ((srcW / (sampleSize * 2)) >= targetW && (srcH / (sampleSize * 2)) >= targetH) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)?.asImageBitmap()
        } catch (_: OutOfMemoryError) {
            evictAll()
            val fallbackOpts = BitmapFactory.Options().apply {
                inSampleSize = (sampleSize * 2).coerceAtLeast(2)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, fallbackOpts)?.asImageBitmap() }.getOrNull()
        }
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
    val density = LocalDensity.current
    val effectiveUrl = remember(url, width) {
        if (url.isNotBlank()) RemoteBitmapCache.optimizePosterUrl(url, width.value) else ""
    }
    val reqWidthPx = remember(width, density) { with(density) { width.roundToPx() }.coerceAtLeast(64) }
    val reqHeightPx = remember(height, density) { with(density) { height.roundToPx() }.coerceAtLeast(64) }
    var bitmap by remember(effectiveUrl) {
        mutableStateOf(if (effectiveUrl.isNotBlank()) RemoteBitmapCache.get(effectiveUrl) else null)
    }

    LaunchedEffect(effectiveUrl, reqWidthPx, reqHeightPx) {
        if (effectiveUrl.isNotBlank() && bitmap == null) {
            val cached = RemoteBitmapCache.get(effectiveUrl)
            if (cached != null) {
                bitmap = cached
                return@LaunchedEffect
            }
            val loaded = withContext(Dispatchers.IO) {
                RemoteBitmapCache.downloadSemaphore.withPermit {
                    RemoteBitmapCache.get(effectiveUrl)?.let { return@withPermit it }
                    try {
                        val conn = (URL(effectiveUrl).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 4500
                            readTimeout = 4500
                        }
                        if (conn.responseCode in 200..299) {
                            val bytes = conn.inputStream.use { it.readBytes() }
                            val decoded = RemoteBitmapCache.decodeSampledBitmap(bytes, reqWidthPx, reqHeightPx)
                            if (decoded != null) {
                                RemoteBitmapCache.put(effectiveUrl, decoded)
                            }
                            decoded
                        } else {
                            null
                        }
                    } catch (_: Throwable) {
                        null
                    }
                }
            }
            if (loaded != null) {
                bitmap = loaded
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
fun WatchlistBookmarkIcon(
    tint: Color = TvTimePalette.Yellow,
    size: Dp = 18.dp,
    filled: Boolean = true
) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.22f, h * 0.12f)
            lineTo(w * 0.78f, h * 0.12f)
            lineTo(w * 0.78f, h * 0.88f)
            lineTo(w * 0.50f, h * 0.68f)
            lineTo(w * 0.22f, h * 0.88f)
            close()
        }
        if (filled) {
            drawPath(path = path, color = tint)
        } else {
            drawPath(
                path = path,
                color = tint,
                style = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
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
    val progress = remember(show.episodesJson, show.watchedCodesCsv, show.userStatus) {
        TvTimeProgressCalculator.computeShowProgressFromJson(
            show.episodesJson,
            show.watchedCodesCsv,
            show.userStatus
        )
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
                            text = "TV Show",
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
                            text = "TV Show · Stopped",
                            style = MaterialTheme.typography.labelSmall,
                            color = TvTimePalette.Error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val stoppedSubtitle = if (progress.lastWatchedEpisode != null) {
                            "Watched till ${progress.lastWatchedEpisode.formattedDotCode} (${progress.watchedCount}/${progress.totalEpisodes} watched)"
                        } else {
                            "Stopped watching (${progress.watchedCount}/${progress.totalEpisodes} watched)"
                        }
                        Text(
                            text = stoppedSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = TvTimePalette.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    else -> {
                        if (statusKey == "interested" && !progress.hasStarted) {
                            Text(
                                text = "TV Show",
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
                    text = "Movie · ${movie.runtimeMins}m",
                    style = MaterialTheme.typography.labelSmall,
                    color = TvTimePalette.CyanMovie,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val cleanDirector = movie.director.takeIf {
                    it.isNotBlank() &&
                        !it.equals(movie.streamingService, ignoreCase = true) &&
                        !it.contains("Streaming", ignoreCase = true) &&
                        !it.contains("TMDB", ignoreCase = true)
                }.orEmpty()
                val movieSubtitle = movie.castCsv.ifBlank {
                    cleanDirector.ifBlank { movie.overview }
                }
                if (movieSubtitle.isNotBlank()) {
                    Text(
                        text = movieSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = TvTimePalette.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

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
    canShowWatchlist: Boolean = true,
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

                if (item.overview.isNotBlank()) {
                    Text(
                        text = item.overview,
                        style = MaterialTheme.typography.bodySmall,
                        color = TvTimePalette.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

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
                if (canShowWatchlist) {
                    // + Watchlist quick-action button (hidden for watched/started items)
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (isInWatchlist) Color(0x2EFACC15) else Color(0xFF232329))
                            .border(
                                width = 1.5.dp,
                                color = if (isInWatchlist) TvTimePalette.Yellow else Color(0xFF3F3F46),
                                shape = CircleShape
                            )
                            .clickable(onClick = onWatchlistClick),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isInWatchlist) {
                            WatchlistBookmarkIcon(
                                tint = TvTimePalette.Yellow,
                                size = 19.dp,
                                filled = true
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add to Watchlist",
                                tint = TvTimePalette.Yellow,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
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

