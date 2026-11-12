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

data class SearchPageResult(
    val items: List<SearchResultItem>,
    val hasMore: Boolean = false
)

data class CompactedShowMeta(
    val totalEpisodes: Int = 0,
    val completedSeasons: Map<Int, Int> = emptyMap(),
    val episodes: List<EpisodeModel> = emptyList()
)

object TvTimeProgressCalculator {

    private val EP_CODE_REGEX = Regex("^S(\\d+)E(\\d+)$", RegexOption.IGNORE_CASE)

    fun normalizeTitleKey(raw: String): String {
        return raw.lowercase()
            .replace("'", "")
            .replace("\u2019", "")
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    fun cleanMovieTmdbId(externalId: String, rawTmdbId: Int): Int {
        val ext = externalId.trim()
        if (ext.startsWith("movie_tt", ignoreCase = true) || ext.startsWith("movie_itunes_", ignoreCase = true)) {
            return 0
        }
        if (rawTmdbId > 0) return rawTmdbId
        val match = Regex("^(?:tmdb_)?movie_(\\d+)$", RegexOption.IGNORE_CASE).find(ext)
        return match?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 } ?: 0
    }

    fun canonicalizeDecidedId(raw: String, mediaType: String = "", tmdbId: Int = 0): String {
        val isMov = mediaType.equals("movie", ignoreCase = true) || raw.startsWith("movie_", ignoreCase = true) || raw.startsWith("tmdb_movie_", ignoreCase = true)
        val cleanMovTmdb = if (isMov) cleanMovieTmdbId(raw, tmdbId) else 0
        if (isMov && cleanMovTmdb > 0) return "movie_$cleanMovTmdb"
        if (!isMov && tmdbId > 0) return "tmdb_tv_$tmdbId"
        val s = raw.trim()
        if (s.isEmpty() || s.startsWith("title_", ignoreCase = true)) return ""
        return when {
            s.startsWith("tmdb_movie_", ignoreCase = true) -> "movie_" + s.substring(11)
            s.startsWith("tmdb_show_", ignoreCase = true) -> "tmdb_tv_" + s.substring(10)
            else -> s
        }
    }

    fun doesMovieMatch(
        aExternalId: String,
        aTmdbId: Int,
        aTitle: String,
        aYear: Int,
        bExternalId: String,
        bTmdbId: Int,
        bTitle: String,
        bYear: Int
    ): Boolean {
        if (aExternalId.isNotBlank() && aExternalId == bExternalId) return true
        val cleanA = cleanMovieTmdbId(aExternalId, aTmdbId)
        val cleanB = cleanMovieTmdbId(bExternalId, bTmdbId)
        if (cleanA > 0 && cleanB > 0) {
            return cleanA == cleanB
        }
        val normA = normalizeTitleKey(aTitle)
        val normB = normalizeTitleKey(bTitle)
        if (normA.isEmpty() || normA != normB) return false
        if (aYear > 1900 && bYear > 1900 && kotlin.math.abs(aYear - bYear) > 1) return false
        return true
    }

    fun doesShowMatch(
        aExternalId: String,
        aTmdbId: Int,
        aTvmazeId: Int,
        aTitle: String,
        aYear: Int,
        bExternalId: String,
        bTmdbId: Int,
        bTvmazeId: Int,
        bTitle: String,
        bYear: Int
    ): Boolean {
        if (aExternalId.isNotBlank() && aExternalId == bExternalId) return true
        if (aTmdbId > 0 && bTmdbId > 0) return aTmdbId == bTmdbId
        if (aTvmazeId > 0 && bTvmazeId > 0) return aTvmazeId == bTvmazeId
        val normA = normalizeTitleKey(aTitle)
        val normB = normalizeTitleKey(bTitle)
        if (normA.isEmpty() || normA != normB) return false
        if (aYear > 1900 && bYear > 1900 && kotlin.math.abs(aYear - bYear) > 1) return false
        return true
    }

    fun parseCompactedMeta(episodesJson: String): CompactedShowMeta {
        val trimmed = episodesJson.trim()
        if (trimmed.isEmpty()) return CompactedShowMeta()
        return try {
            if (trimmed.startsWith("{")) {
                val root = JSONObject(trimmed)
                val total = root.optInt("totalEpisodes", 0).coerceAtLeast(0)
                val compObj = root.optJSONObject("completedSeasons")
                val compMap = LinkedHashMap<Int, Int>()
                if (compObj != null) {
                    val keys = compObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val sNum = k.toIntOrNull() ?: 0
                        val cnt = compObj.optInt(k, 0)
                        if (sNum > 0 && cnt > 0) compMap[sNum] = cnt
                    }
                }
                val arr = root.optJSONArray("episodes") ?: JSONArray()
                val eps = mutableListOf<EpisodeModel>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    eps.add(EpisodeModel.fromJson(obj))
                }
                CompactedShowMeta(
                    totalEpisodes = maxOf(total, compMap.values.sum() + eps.size),
                    completedSeasons = compMap,
                    episodes = eps
                )
            } else {
                val arr = JSONArray(trimmed)
                val eps = mutableListOf<EpisodeModel>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    eps.add(EpisodeModel.fromJson(obj))
                }
                CompactedShowMeta(totalEpisodes = eps.size, completedSeasons = emptyMap(), episodes = eps)
            }
        } catch (_: Exception) {
            CompactedShowMeta()
        }
    }

    fun serializeCompactedMeta(
        totalEpisodes: Int,
        completedSeasons: Map<Int, Int>,
        episodes: List<EpisodeModel>
    ): String {
        val cleanComp = completedSeasons.filter { it.key > 0 && it.value > 0 }
        val cleanTotal = maxOf(totalEpisodes, cleanComp.values.sum() + episodes.size)
        if (cleanComp.isEmpty() && (cleanTotal <= 0 || cleanTotal == episodes.size)) {
            return serializeEpisodeList(episodes)
        }
        val arr = JSONArray()
        episodes.forEach { arr.put(it.toJson()) }
        return JSONObject().apply {
            put("totalEpisodes", cleanTotal)
            if (cleanComp.isNotEmpty()) {
                val csObj = JSONObject()
                cleanComp.toSortedMap().forEach { (sNum, cnt) -> csObj.put(sNum.toString(), cnt) }
                put("completedSeasons", csObj)
            }
            put("episodes", arr)
        }.toString()
    }

    fun expandWatchedSetWithCompletedSeasons(
        episodes: List<EpisodeModel>,
        watchedCodes: Set<String>,
        completedSeasons: Map<Int, Int>,
        rawUserStatus: String = ""
    ): Set<String> {
        val base = watchedCodes.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toMutableSet()
        if (rawUserStatus.trim().equals("completed", ignoreCase = true) && episodes.isNotEmpty()) {
            episodes.forEach { base.add(it.code.trim().uppercase()) }
            return base
        }
        if (completedSeasons.isNotEmpty()) {
            for (ep in episodes) {
                if (completedSeasons.containsKey(ep.season)) {
                    base.add(ep.code.trim().uppercase())
                }
            }
            for ((sNum, cnt) in completedSeasons) {
                if (episodes.none { it.season == sNum }) {
                    for (eNum in 1..cnt) {
                        base.add(EpisodeModel.formatCode(sNum, eNum))
                    }
                }
            }
        }
        return base
    }

    fun parseEpisodeList(episodesJson: String): List<EpisodeModel> {
        return parseCompactedMeta(episodesJson).episodes
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

    private fun parseSeasonFromCode(code: String): Int {
        val m = EP_CODE_REGEX.find(code.trim()) ?: return 0
        return m.groupValues[1].toIntOrNull() ?: 0
    }

    private fun parseEpisodeFromCode(code: String): EpisodeModel? {
        val m = EP_CODE_REGEX.find(code.trim()) ?: return null
        val s = m.groupValues[1].toIntOrNull() ?: 1
        val n = m.groupValues[2].toIntOrNull() ?: 1
        return EpisodeModel(
            code = EpisodeModel.formatCode(s, n),
            season = s,
            number = n,
            title = "Episode $n"
        )
    }

    fun computeShowProgressFromJson(
        episodesJson: String,
        watchedCodesCsv: String,
        rawUserStatus: String = ""
    ): ShowProgressSummary {
        val meta = parseCompactedMeta(episodesJson)
        val watched = parseWatchedSet(watchedCodesCsv)
        return computeShowProgress(
            episodes = meta.episodes,
            watchedCodes = watched,
            rawUserStatus = rawUserStatus,
            totalEpisodesOverride = meta.totalEpisodes,
            completedSeasons = meta.completedSeasons
        )
    }

    fun computeShowProgress(
        episodes: List<EpisodeModel>,
        watchedCodes: Set<String>,
        rawUserStatus: String = "",
        totalEpisodesOverride: Int = 0,
        completedSeasons: Map<Int, Int> = emptyMap()
    ): ShowProgressSummary {
        val normWatched = watchedCodes.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
        val compTotal = completedSeasons.filter { it.key > 0 && it.value > 0 }.values.sum()
        val isRawCompletedEmpty = episodes.isEmpty() && compTotal == 0 &&
            rawUserStatus.trim().equals("completed", ignoreCase = true)

        if (isRawCompletedEmpty) {
            val total = maxOf(totalEpisodesOverride, normWatched.size, 1)
            return ShowProgressSummary(
                totalEpisodes = total,
                watchedCount = total,
                leftCount = 0,
                nextEpisode = null,
                progressFraction = 1f,
                isCompleted = true,
                hasStarted = true,
                lastWatchedEpisode = normWatched.maxOrNull()?.let { parseEpisodeFromCode(it) }
            )
        }

        val epsOutsideComp = episodes.filter { !completedSeasons.containsKey(it.season) }
        val watchedOutsideComp = if (epsOutsideComp.isNotEmpty()) {
            val validActiveCodes = epsOutsideComp.map { it.code.trim().uppercase() }.toSet()
            val inActive = normWatched.count { validActiveCodes.contains(it) }
            val extraSeasons = normWatched.count { code ->
                val sNum = parseSeasonFromCode(code)
                sNum > 0 && !completedSeasons.containsKey(sNum) && epsOutsideComp.none { it.season == sNum }
            }
            inActive + extraSeasons
        } else {
            normWatched.count { code ->
                val sNum = parseSeasonFromCode(code)
                sNum <= 0 || !completedSeasons.containsKey(sNum)
            }
        }

        val watchedCount = compTotal + watchedOutsideComp
        val total = when {
            totalEpisodesOverride > 0 -> maxOf(totalEpisodesOverride, compTotal + epsOutsideComp.size, watchedCount)
            episodes.isNotEmpty() || compTotal > 0 -> maxOf(compTotal + epsOutsideComp.size, episodes.size, watchedCount)
            else -> maxOf(10, watchedCount)
        }
        val leftCount = (total - watchedCount).coerceAtLeast(0)
        val nextEp = epsOutsideComp.firstOrNull { !normWatched.contains(it.code.trim().uppercase()) }
            ?: if (leftCount > 0) {
                val maxCompS = completedSeasons.keys.maxOrNull() ?: 0
                val nextSeason = if (maxCompS > 0 && epsOutsideComp.isEmpty()) maxCompS + 1 else 1
                val nextNum = if (maxCompS > 0 && epsOutsideComp.isEmpty()) 1 else (watchedOutsideComp + 1)
                EpisodeModel(
                    code = EpisodeModel.formatCode(nextSeason, nextNum),
                    season = nextSeason,
                    number = nextNum,
                    title = "Episode $nextNum"
                )
            } else null

        val lastWatchedEp = epsOutsideComp.lastOrNull { normWatched.contains(it.code.trim().uppercase()) }
            ?: normWatched.maxOrNull()?.let { parseEpisodeFromCode(it) }
            ?: completedSeasons.keys.maxOrNull()?.let { maxS ->
                val cnt = completedSeasons[maxS] ?: 1
                EpisodeModel(
                    code = EpisodeModel.formatCode(maxS, cnt),
                    season = maxS,
                    number = cnt,
                    title = "Episode $cnt"
                )
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
        val progress = computeShowProgress(episodes, watchedCodes, rawUserStatus)
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

