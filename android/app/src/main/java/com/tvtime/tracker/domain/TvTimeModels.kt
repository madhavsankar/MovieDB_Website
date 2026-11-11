package com.tvtime.tracker.domain

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class EpisodeModel(
    val code: String,
    val season: Int,
    val number: Int,
    val title: String,
    val airDate: String = "",
    val runtimeMins: Int = 45,
    val imageUrl: String = "",
    val overview: String = ""
) {
    val formattedDotCode: String
        get() = "S${season.toString().padStart(2, '0')}·E${number.toString().padStart(2, '0')}"

    fun toJson(): JSONObject = JSONObject().apply {
        put("code", code)
        put("season", season)
        put("number", number)
        put("title", title)
        put("airDate", airDate)
        put("runtimeMins", runtimeMins)
        put("imageUrl", imageUrl)
        put("overview", overview)
    }

    companion object {
        fun formatCode(season: Int, number: Int): String =
            "S${season.coerceAtLeast(1).toString().padStart(2, '0')}E${number.coerceAtLeast(1).toString().padStart(2, '0')}"

        fun fromJson(obj: JSONObject): EpisodeModel {
            val s = obj.optInt("season", 1).coerceAtLeast(1)
            val n = obj.optInt("number", 1).coerceAtLeast(1)
            val c = obj.optString("code", "").ifBlank { formatCode(s, n) }
            return EpisodeModel(
                code = c,
                season = s,
                number = n,
                title = obj.optString("title", "Episode $n"),
                airDate = obj.optString("airDate", ""),
                runtimeMins = obj.optInt("runtimeMins", 45).coerceAtLeast(1),
                imageUrl = obj.optString("imageUrl", ""),
                overview = obj.optString("overview", "")
            )
        }
    }
}

data class ShowProgressSummary(
    val totalEpisodes: Int,
    val watchedCount: Int,
    val leftCount: Int,
    val nextEpisode: EpisodeModel?,
    val progressFraction: Float,
    val isCompleted: Boolean,
    val hasStarted: Boolean,
    val lastWatchedEpisode: EpisodeModel? = null
)

data class WatchStatsSummary(
    val totalMinutes: Int,
    val showMinutes: Int,
    val movieMinutes: Int,
    val totalEpisodesWatched: Int,
    val totalMoviesWatched: Int,
    val totalSeriesCount: Int = 0,
    val totalMoviesCount: Int = 0
) {
    val totalDays: Int get() = totalMinutes / 1440
    val totalHoursRemainder: Int get() = (totalMinutes % 1440) / 60
    val showDays: Int get() = showMinutes / 1440
    val showHoursRemainder: Int get() = (showMinutes % 1440) / 60
    val movieDays: Int get() = movieMinutes / 1440
    val movieHoursRemainder: Int get() = (movieMinutes % 1440) / 60

    val totalHoursDecimal: Double get() = Math.round((totalMinutes / 60.0) * 10.0) / 10.0
    val seriesHoursDecimal: Double get() = Math.round((showMinutes / 60.0) * 10.0) / 10.0
    val movieHoursDecimal: Double get() = Math.round((movieMinutes / 60.0) * 10.0) / 10.0

    val formattedTotalHours: String get() = "${totalHoursDecimal} hrs"
    val formattedSeriesHours: String get() = "${seriesHoursDecimal} hrs"
    val formattedMoviesHours: String get() = "${movieHoursDecimal} hrs"

    val formattedTotal: String get() = "${totalDays}d ${totalHoursRemainder}h"
    val formattedSeries: String get() = "${showDays}d ${showHoursRemainder}h"
    val formattedMovies: String get() = "${movieDays}d ${movieHoursRemainder}h"
}

data class SearchResultItem(
    val externalId: String,
    val tvmazeId: Int = 0,
    val tmdbId: Int = 0,
    val mediaType: String, // "show" | "movie" | "actor"
    val title: String,
    val year: Int = 2024,
    val networkOrDirector: String = "",
    val status: String = "Running",
    val runtimeMins: Int = 45,
    val genresCsv: String = "",
    val castCsv: String = "",
    val rating: Double = 7.8,
    val posterUrl: String = "",
    val overview: String = "",
    val imdbUrl: String = ""
)

object TvTimeProgressCalculator {

    fun parseEpisodeList(episodesJson: String): List<EpisodeModel> {
        if (episodesJson.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(episodesJson)
            val list = mutableListOf<EpisodeModel>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(EpisodeModel.fromJson(obj))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun serializeEpisodeList(episodes: List<EpisodeModel>): String {
        val arr = JSONArray()
        episodes.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    fun parseWatchedSet(watchedCodesCsv: String): Set<String> {
        return watchedCodesCsv.split(",")
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    fun serializeWatchedSet(codes: Iterable<String>): String {
        return codes.map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .joinToString(",")
    }

    fun computeShowProgress(
        episodes: List<EpisodeModel>,
        watchedCodes: Set<String>
    ): ShowProgressSummary {
        val total = if (episodes.isNotEmpty()) episodes.size else 10
        val watchedCount = watchedCodes.size
        val leftCount = (total - watchedCount).coerceAtLeast(0)
        val nextEp = episodes.firstOrNull { !watchedCodes.contains(it.code.uppercase()) }
            ?: if (leftCount > 0) {
                val nextNum = watchedCount + 1
                EpisodeModel(
                    code = EpisodeModel.formatCode(1, nextNum),
                    season = 1,
                    number = nextNum,
                    title = "Episode $nextNum"
                )
            } else null

        val lastWatchedEp = episodes.lastOrNull { watchedCodes.contains(it.code.uppercase()) }
            ?: watchedCodes.maxOrNull()?.let { code ->
                val match = Regex("^S(\\d+)E(\\d+)$", RegexOption.IGNORE_CASE).find(code)
                if (match != null) {
                    val s = match.groupValues[1].toIntOrNull() ?: 1
                    val n = match.groupValues[2].toIntOrNull() ?: 1
                    EpisodeModel(
                        code = code.uppercase(),
                        season = s,
                        number = n,
                        title = "Episode $n"
                    )
                } else null
            }

        val fraction = if (total > 0) {
            (watchedCount.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        } else 0f

        return ShowProgressSummary(
            totalEpisodes = total,
            watchedCount = watchedCount,
            leftCount = leftCount,
            nextEpisode = nextEp,
            progressFraction = fraction,
            isCompleted = total > 0 && leftCount == 0,
            hasStarted = watchedCount > 0,
            lastWatchedEpisode = lastWatchedEp
        )
    }

    fun deriveEffectiveShowStatus(
        progress: ShowProgressSummary,
        rawUserStatus: String
    ): String {
        val raw = rawUserStatus.trim().lowercase()
        return when {
            progress.isCompleted -> "completed"
            progress.hasStarted -> if (raw == "stopped") "stopped" else "in_progress"
            else -> if (raw == "stopped") "stopped" else "interested"
        }
    }

    fun deriveEffectiveShowStatus(
        episodes: List<EpisodeModel>,
        watchedCodes: Set<String>,
        rawUserStatus: String
    ): String {
        val progress = computeShowProgress(episodes, watchedCodes)
        return deriveEffectiveShowStatus(progress, rawUserStatus)
    }

    fun formatRelativeCountdownBucket(daysUntil: Long): String {
        return when {
            daysUntil <= 0L -> "Today"
            daysUntil == 1L -> "Tomorrow"
            daysUntil in 2L..6L -> "In $daysUntil days"
            daysUntil in 7L..13L -> "Next week"
            daysUntil in 14L..29L -> "In ${daysUntil / 7L} weeks"
            daysUntil in 30L..59L -> "In 1 month"
            else -> "In ${daysUntil / 30L} months"
        }
    }

    fun findNextUpcomingEpisode(
        episodes: List<EpisodeModel>,
        todayIso: String = LocalDate.now().toString()
    ): Pair<EpisodeModel, Long>? {
        val today = try {
            LocalDate.parse(todayIso.trim().take(10))
        } catch (_: Exception) {
            LocalDate.now()
        }

        val futureCandidates = mutableListOf<Pair<EpisodeModel, Long>>()
        for (ep in episodes) {
            val rawDate = ep.airDate.trim().take(10)
            if (rawDate.length < 10) continue
            val epDate = try {
                LocalDate.parse(rawDate)
            } catch (_: Exception) {
                null
            } ?: continue
            val daysUntil = ChronoUnit.DAYS.between(today, epDate)
            if (daysUntil >= 0L) {
                futureCandidates.add(ep.copy(airDate = rawDate) to daysUntil)
            }
        }

        return futureCandidates.minWithOrNull(
            compareBy<Pair<EpisodeModel, Long>> { it.second }
                .thenBy { it.first.season }
                .thenBy { it.first.number }
        )
    }
}

