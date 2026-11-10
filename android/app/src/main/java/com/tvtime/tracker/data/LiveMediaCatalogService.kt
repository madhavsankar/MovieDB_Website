package com.tvtime.tracker.data

import com.tvtime.tracker.BuildConfig
import com.tvtime.tracker.domain.EpisodeModel
import com.tvtime.tracker.domain.SearchResultItem
import com.tvtime.tracker.domain.TvTimeProgressCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Live Catalog Service for TV Time: Show & Movie Tracker:
 *  - TMDB API v3 (`https://api.themoviedb.org/3/search/multi`) — Official Movies, TV Shows & Actors when `TMDB_READ_TOKEN` or `TMDB_API_KEY` is configured
 *  - TVMaze Public API (`https://api.tvmaze.com`) — Live Shows, Seasons, Episodes, Air Dates & Actors
 *  - IMDb Suggestion API & Apple iTunes Search API — Fallback Movie Search
 */
object LiveMediaCatalogService {

    val US_PLATFORMS: List<String> = listOf(
        "Netflix",
        "Prime Video",
        "Hulu",
        "Max",
        "Disney+",
        "Apple TV+",
        "Paramount+",
        "Peacock"
    )

    private val US_PLATFORM_PROVIDER_IDS: Map<String, List<Int>> = mapOf(
        "Netflix" to listOf(8, 1796),
        "Prime Video" to listOf(9, 119, 2100),
        "Hulu" to listOf(15),
        "Max" to listOf(1899, 384),
        "Disney+" to listOf(337),
        "Apple TV+" to listOf(350, 2),
        "Paramount+" to listOf(531, 1853, 2303),
        "Peacock" to listOf(386, 387)
    )

    fun matchesUsPlatforms(rawPlatformText: String, selectedPlatforms: Set<String>): Boolean {
        if (selectedPlatforms.isEmpty()) return true
        val s = rawPlatformText.lowercase()
        for (plat in selectedPlatforms) {
            when (plat.lowercase()) {
                "netflix" -> if (s.contains("netflix")) return true
                "prime video" -> if (s.contains("prime") || s.contains("amazon")) return true
                "hulu" -> if (s.contains("hulu") || s.contains("fx")) return true
                "max" -> if (s.contains("max") || s.contains("hbo")) return true
                "disney+" -> if (s.contains("disney")) return true
                "apple tv+" -> if (s.contains("apple")) return true
                "paramount+" -> if (s.contains("paramount") || s.contains("cbs") || s.contains("showtime")) return true
                "peacock" -> if (s.contains("peacock") || s.contains("nbc")) return true
            }
        }
        return false
    }

    suspend fun discoverByUsProviders(selectedPlatforms: Set<String>): List<SearchResultItem> = withContext(Dispatchers.IO) {
        if (selectedPlatforms.isEmpty()) return@withContext searchMulti("Game of Thrones")
        val tmdbToken = BuildConfig.TMDB_READ_TOKEN.trim()
        val tmdbKey = BuildConfig.TMDB_API_KEY.trim()
        val providerIds = selectedPlatforms.flatMap { US_PLATFORM_PROVIDER_IDS[it] ?: emptyList() }.distinct()
        val providerPipe = providerIds.joinToString("|")
        val platformLabel = selectedPlatforms.joinToString(" / ")
        val results = mutableListOf<SearchResultItem>()

        if (providerPipe.isNotEmpty() && (tmdbToken.isNotEmpty() || tmdbKey.isNotEmpty())) {
            val encPipe = URLEncoder.encode(providerPipe, "UTF-8")
            fun fetchDiscover(mediaType: String) {
                try {
                    val base = "https://api.themoviedb.org/3/discover/$mediaType?watch_region=US&with_watch_monetization_types=flatrate&with_watch_providers=$encPipe&sort_by=popularity.desc&language=en-US&page=1"
                    val url = if (tmdbToken.isNotEmpty()) base else "$base&api_key=$tmdbKey"
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Accept", "application/json")
                        if (tmdbToken.isNotEmpty()) {
                            setRequestProperty("Authorization", "Bearer $tmdbToken")
                        }
                        connectTimeout = 5000
                        readTimeout = 5000
                    }
                    if (conn.responseCode in 200..299) {
                        val text = conn.inputStream.bufferedReader().use { it.readText() }
                        val arr = JSONObject(text).optJSONArray("results") ?: JSONArray()
                        for (i in 0 until minOf(12, arr.length())) {
                            val item = arr.optJSONObject(i) ?: continue
                            val id = item.optInt("id", 0)
                            if (id <= 0) continue
                            val posterPath = item.optString("poster_path", "")
                            val poster = if (posterPath.isNotBlank() && posterPath != "null") "https://image.tmdb.org/t/p/w500$posterPath" else ""
                            if (mediaType == "tv") {
                                val title = item.optString("name", item.optString("original_name", "Untitled Show"))
                                val year = item.optString("first_air_date", "").take(4).toIntOrNull() ?: 2024
                                val rating = (item.optDouble("vote_average", 8.0).takeIf { !it.isNaN() && it > 0 } ?: 8.0)
                                results.add(
                                    SearchResultItem(
                                        externalId = "tmdb_tv_$id",
                                        tmdbId = id,
                                        mediaType = "show",
                                        title = title,
                                        year = year,
                                        networkOrDirector = platformLabel,
                                        status = "Running",
                                        runtimeMins = 45,
                                        genresCsv = "TV Series",
                                        rating = Math.round(rating * 10.0) / 10.0,
                                        posterUrl = poster,
                                        overview = stripHtml(item.optString("overview", ""))
                                    )
                                )
                            } else {
                                val title = item.optString("title", item.optString("original_title", "Untitled Movie"))
                                val year = item.optString("release_date", "").take(4).toIntOrNull() ?: 2024
                                val rating = (item.optDouble("vote_average", 7.8).takeIf { !it.isNaN() && it > 0 } ?: 7.8)
                                results.add(
                                    SearchResultItem(
                                        externalId = "movie_$id",
                                        tmdbId = id,
                                        mediaType = "movie",
                                        title = title,
                                        year = year,
                                        networkOrDirector = platformLabel,
                                        status = "Released",
                                        runtimeMins = 120,
                                        genresCsv = "Movie",
                                        rating = Math.round(rating * 10.0) / 10.0,
                                        posterUrl = poster,
                                        overview = stripHtml(item.optString("overview", ""))
                                    )
                                )
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            fetchDiscover("tv")
            fetchDiscover("movie")
        }
        results
    }

    suspend fun fetchRecommendations(
        shows: List<ShowEntity>,
        movies: List<MovieEntity>,
        page: Int = 1
    ): List<SearchResultItem> = withContext(Dispatchers.IO) {
        val safePage = page.coerceIn(1, 10)
        val results = mutableListOf<SearchResultItem>()
        val seenKeys = mutableSetOf<String>()

        fun addUnique(item: SearchResultItem) {
            if (item.title.isBlank() || item.posterUrl.isBlank()) return
            val normKey = "${item.mediaType}_${item.title.trim().lowercase()}"
            if (seenKeys.contains(item.externalId) || seenKeys.contains(normKey)) return
            seenKeys.add(item.externalId)
            seenKeys.add(normKey)
            results.add(item)
        }

        val tmdbToken = BuildConfig.TMDB_READ_TOKEN.trim()
        val tmdbKey = BuildConfig.TMDB_API_KEY.trim()
        if (tmdbToken.isNotEmpty() || tmdbKey.isNotEmpty()) {
            fun getTmdbJson(pathWithQuery: String): JSONObject? {
                return try {
                    val sep = if (pathWithQuery.contains("?")) "&" else "?"
                    val url = if (tmdbToken.isNotEmpty()) {
                        "https://api.themoviedb.org/3$pathWithQuery"
                    } else {
                        "https://api.themoviedb.org/3$pathWithQuery${sep}api_key=$tmdbKey"
                    }
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        if (tmdbToken.isNotEmpty()) setRequestProperty("Authorization", "Bearer $tmdbToken")
                        setRequestProperty("Accept", "application/json")
                        connectTimeout = 5000
                        readTimeout = 5000
                    }
                    if (conn.responseCode in 200..299) {
                        JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    } else null
                } catch (_: Exception) {
                    null
                }
            }

            val watchedShows = shows.filter { it.watchedCodesCsv.isNotBlank() || it.userStatus == "completed" }
            val seedShows = (watchedShows.ifEmpty { shows }).take(4)
            val watchedMovies = movies.filter { it.watched }
            val seedMovies = (watchedMovies.ifEmpty { movies }).take(4)

            for (s in seedShows) {
                var tid = s.tmdbId
                if (tid <= 0 && s.title.isNotBlank()) {
                    val enc = URLEncoder.encode(s.title, "UTF-8")
                    tid = getTmdbJson("/search/tv?query=$enc&language=en-US&page=1")
                        ?.optJSONArray("results")
                        ?.optJSONObject(0)
                        ?.optInt("id", 0) ?: 0
                }
                if (tid > 0) {
                    val arr = getTmdbJson("/tv/$tid/recommendations?language=en-US&page=$safePage")
                        ?.optJSONArray("results") ?: JSONArray()
                    for (i in 0 until minOf(arr.length(), 8)) {
                        val item = arr.optJSONObject(i) ?: continue
                        val id = item.optInt("id", 0)
                        if (id <= 0) continue
                        val posterPath = item.optString("poster_path", "")
                        if (posterPath.isBlank() || posterPath == "null") continue
                        val title = item.optString("name", item.optString("original_name", ""))
                        val year = item.optString("first_air_date", "").take(4).toIntOrNull() ?: 2024
                        val rating = (item.optDouble("vote_average", 7.9).takeIf { !it.isNaN() && it > 0 } ?: 7.9)
                        addUnique(
                            SearchResultItem(
                                externalId = "tmdb_tv_$id",
                                tvmazeId = 0,
                                tmdbId = id,
                                mediaType = "show",
                                title = title,
                                year = year,
                                networkOrDirector = "Because you watched ${s.title}",
                                status = "Running",
                                runtimeMins = 45,
                                genresCsv = "TV Series",
                                rating = Math.round(rating * 10.0) / 10.0,
                                posterUrl = "https://image.tmdb.org/t/p/w500$posterPath",
                                overview = stripHtml(item.optString("overview", ""))
                            )
                        )
                    }
                }
            }

            for (m in seedMovies) {
                var mid = m.tmdbId
                if (mid <= 0 && m.title.isNotBlank()) {
                    val enc = URLEncoder.encode(m.title, "UTF-8")
                    mid = getTmdbJson("/search/movie?query=$enc&language=en-US&page=1")
                        ?.optJSONArray("results")
                        ?.optJSONObject(0)
                        ?.optInt("id", 0) ?: 0
                }
                if (mid > 0) {
                    val arr = getTmdbJson("/movie/$mid/recommendations?language=en-US&page=$safePage")
                        ?.optJSONArray("results") ?: JSONArray()
                    for (i in 0 until minOf(arr.length(), 8)) {
                        val item = arr.optJSONObject(i) ?: continue
                        val id = item.optInt("id", 0)
                        if (id <= 0) continue
                        val posterPath = item.optString("poster_path", "")
                        if (posterPath.isBlank() || posterPath == "null") continue
                        val title = item.optString("title", item.optString("original_title", ""))
                        val year = item.optString("release_date", "").take(4).toIntOrNull() ?: 2024
                        val rating = (item.optDouble("vote_average", 7.8).takeIf { !it.isNaN() && it > 0 } ?: 7.8)
                        addUnique(
                            SearchResultItem(
                                externalId = "movie_$id",
                                tmdbId = id,
                                mediaType = "movie",
                                title = title,
                                year = year,
                                networkOrDirector = "Because you watched ${m.title}",
                                status = "Released",
                                runtimeMins = 120,
                                genresCsv = "Movie",
                                rating = Math.round(rating * 10.0) / 10.0,
                                posterUrl = "https://image.tmdb.org/t/p/w500$posterPath",
                                overview = stripHtml(item.optString("overview", ""))
                            )
                        )
                    }
                }
            }

            // Supplement with trending TV & movies
            val trendTv = getTmdbJson("/trending/tv/week?language=en-US&page=$safePage")?.optJSONArray("results") ?: JSONArray()
            for (i in 0 until minOf(trendTv.length(), 8)) {
                val item = trendTv.optJSONObject(i) ?: continue
                val id = item.optInt("id", 0)
                val posterPath = item.optString("poster_path", "")
                if (id <= 0 || posterPath.isBlank() || posterPath == "null") continue
                val title = item.optString("name", item.optString("original_name", ""))
                val year = item.optString("first_air_date", "").take(4).toIntOrNull() ?: 2024
                val rating = (item.optDouble("vote_average", 8.0).takeIf { !it.isNaN() && it > 0 } ?: 8.0)
                addUnique(
                    SearchResultItem(
                        externalId = "tmdb_tv_$id",
                        tvmazeId = 0,
                        tmdbId = id,
                        mediaType = "show",
                        title = title,
                        year = year,
                        networkOrDirector = "Popular & Acclaimed Series",
                        status = "Running",
                        runtimeMins = 45,
                        genresCsv = "TV Series",
                        rating = Math.round(rating * 10.0) / 10.0,
                        posterUrl = "https://image.tmdb.org/t/p/w500$posterPath",
                        overview = stripHtml(item.optString("overview", ""))
                    )
                )
            }
            val trendMov = getTmdbJson("/trending/movie/week?language=en-US&page=$safePage")?.optJSONArray("results") ?: JSONArray()
            for (i in 0 until minOf(trendMov.length(), 8)) {
                val item = trendMov.optJSONObject(i) ?: continue
                val id = item.optInt("id", 0)
                val posterPath = item.optString("poster_path", "")
                if (id <= 0 || posterPath.isBlank() || posterPath == "null") continue
                val title = item.optString("title", item.optString("original_title", ""))
                val year = item.optString("release_date", "").take(4).toIntOrNull() ?: 2024
                val rating = (item.optDouble("vote_average", 7.8).takeIf { !it.isNaN() && it > 0 } ?: 7.8)
                addUnique(
                    SearchResultItem(
                        externalId = "movie_$id",
                        tmdbId = id,
                        mediaType = "movie",
                        title = title,
                        year = year,
                        networkOrDirector = "Popular & Acclaimed Movie",
                        status = "Released",
                        runtimeMins = 120,
                        genresCsv = "Movie",
                        rating = Math.round(rating * 10.0) / 10.0,
                        posterUrl = "https://image.tmdb.org/t/p/w500$posterPath",
                        overview = stripHtml(item.optString("overview", ""))
                    )
                )
            }
        }
        results
    }

    private fun stripHtml(raw: String?): String =
        (raw ?: "").replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()

    suspend fun searchMulti(query: String): List<SearchResultItem> = withContext(Dispatchers.IO) {
        val q = query.trim().ifEmpty { "Game of Thrones" }
        val encoded = URLEncoder.encode(q, "UTF-8")
        val results = mutableListOf<SearchResultItem>()
        val seenShows = mutableSetOf<String>()
        val seenMovies = mutableSetOf<String>()

        // 0. TMDB v3 Multi Search (Movies, TV Shows & People)
        val tmdbToken = BuildConfig.TMDB_READ_TOKEN.trim()
        val tmdbKey = BuildConfig.TMDB_API_KEY.trim()
        if (tmdbToken.isNotEmpty() || tmdbKey.isNotEmpty()) {
            try {
                val tmdbUrl = if (tmdbToken.isNotEmpty()) {
                    "https://api.themoviedb.org/3/search/multi?query=$encoded&include_adult=false&language=en-US&page=1"
                } else {
                    "https://api.themoviedb.org/3/search/multi?query=$encoded&include_adult=false&language=en-US&page=1&api_key=$tmdbKey"
                }
                val conn = (URL(tmdbUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                    if (tmdbToken.isNotEmpty()) {
                        setRequestProperty("Authorization", "Bearer $tmdbToken")
                    }
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                if (conn.responseCode in 200..299) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val arr = JSONObject(text).optJSONArray("results") ?: JSONArray()
                    for (i in 0 until minOf(16, arr.length())) {
                        val item = arr.optJSONObject(i) ?: continue
                        val id = item.optInt("id", 0)
                        if (id <= 0) continue
                        val mType = item.optString("media_type", "").lowercase()
                        if (mType == "movie") {
                            val title = item.optString("title", item.optString("original_title", "Untitled Movie"))
                            val year = item.optString("release_date", "").take(4).toIntOrNull() ?: 2024
                            val key = "${title.lowercase()}_$year"
                            if (!seenMovies.add(key)) continue
                            val posterPath = item.optString("poster_path", "")
                            val poster = if (posterPath.isNotBlank() && posterPath != "null") "https://image.tmdb.org/t/p/w500$posterPath" else ""
                            val rating = (item.optDouble("vote_average", 7.8).takeIf { !it.isNaN() && it > 0 } ?: 7.8)
                            results.add(
                                SearchResultItem(
                                    externalId = "movie_$id",
                                    tmdbId = id,
                                    mediaType = "movie",
                                    title = title,
                                    year = year,
                                    networkOrDirector = "TMDB Feature Film",
                                    status = "Released",
                                    runtimeMins = 125,
                                    genresCsv = "Movie",
                                    rating = Math.round(rating * 10.0) / 10.0,
                                    posterUrl = poster,
                                    overview = stripHtml(item.optString("overview", ""))
                                )
                            )
                        } else if (mType == "tv") {
                            val title = item.optString("name", item.optString("original_name", "Untitled Show"))
                            if (!seenShows.add(title.lowercase())) continue
                            val year = item.optString("first_air_date", "").take(4).toIntOrNull() ?: 2024
                            val posterPath = item.optString("poster_path", "")
                            val poster = if (posterPath.isNotBlank() && posterPath != "null") "https://image.tmdb.org/t/p/w500$posterPath" else ""
                            val rating = (item.optDouble("vote_average", 8.0).takeIf { !it.isNaN() && it > 0 } ?: 8.0)
                            results.add(
                                SearchResultItem(
                                    externalId = "tmdb_tv_$id",
                                    tvmazeId = 0,
                                    tmdbId = id,
                                    mediaType = "show",
                                    title = title,
                                    year = year,
                                    networkOrDirector = "Streaming",
                                    status = "Running",
                                    runtimeMins = 45,
                                    genresCsv = "TV Series",
                                    rating = Math.round(rating * 10.0) / 10.0,
                                    posterUrl = poster,
                                    overview = stripHtml(item.optString("overview", ""))
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 1. TVMaze Shows Search
        try {
            val conn = (URL("https://api.tvmaze.com/search/shows?q=$encoded").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (conn.responseCode in 200..299) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONArray(text)
                for (i in 0 until minOf(12, arr.length())) {
                    val show = arr.optJSONObject(i)?.optJSONObject("show") ?: continue
                    val id = show.optInt("id", 0)
                    if (id <= 0) continue
                    val showTitle = show.optString("name", "Untitled Show")
                    // Attach tvmazeId if already added via TMDB
                    val existingIdx = results.indexOfFirst { it.mediaType == "show" && it.title.equals(showTitle, ignoreCase = true) }
                    if (existingIdx >= 0) {
                        val existing = results[existingIdx]
                        if (existing.tvmazeId == 0) {
                            results[existingIdx] = existing.copy(externalId = "tvmaze_$id", tvmazeId = id)
                        }
                        continue
                    }
                    seenShows.add(showTitle.lowercase())
                    val premiered = show.optString("premiered", "")
                    val year = premiered.take(4).toIntOrNull() ?: 2024
                    val network = show.optJSONObject("network")?.optString("name")
                        ?: show.optJSONObject("webChannel")?.optString("name")
                        ?: "Streaming"
                    val imgObj = show.optJSONObject("image")
                    val poster = (imgObj?.optString("medium") ?: imgObj?.optString("original") ?: "")
                        .replace("http://", "https://")
                    val genresArr = show.optJSONArray("genres") ?: JSONArray()
                    val genres = (0 until genresArr.length()).mapNotNull { genresArr.optString(it) }.joinToString(", ")
                    val rating = show.optJSONObject("rating")?.optDouble("average", 7.9)?.takeIf { !it.isNaN() } ?: 7.9

                    results.add(
                        SearchResultItem(
                            externalId = "tvmaze_$id",
                            tvmazeId = id,
                            mediaType = "show",
                            title = showTitle,
                            year = year,
                            networkOrDirector = network,
                            status = show.optString("status", "Running"),
                            runtimeMins = show.optInt("averageRuntime", show.optInt("runtime", 45)).coerceAtLeast(15),
                            genresCsv = genres.ifBlank { "TV Show" },
                            rating = rating,
                            posterUrl = poster,
                            overview = stripHtml(show.optString("summary", ""))
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        // 2. IMDb + iTunes Movies Search
        try {
            val conn = (URL("https://v3.sg.media-imdb.com/suggestion/x/$encoded.json").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (conn.responseCode in 200..299) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(text)
                val arr = root.optJSONArray("d") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    val id = m.optString("id", "")
                    val title = m.optString("l", "")
                    val qid = m.optString("qid", "").lowercase()
                    if (id.isBlank() || title.isBlank()) continue
                    if (qid == "movie" || qid == "feature" || qid == "tvmovie") {
                        val year = m.optInt("y", 2024)
                        val key = "${title.lowercase()}_$year"
                        if (!seenMovies.add(key)) continue
                        val stars = m.optString("s", "")
                        val poster = m.optJSONObject("i")?.optString("imageUrl", "") ?: ""
                        results.add(
                            SearchResultItem(
                                externalId = "movie_$id",
                                tmdbId = id.filter { it.isDigit() }.toIntOrNull() ?: 0,
                                mediaType = "movie",
                                title = title,
                                year = year,
                                networkOrDirector = stars.split(",").firstOrNull()?.trim() ?: "Feature Film",
                                status = "Released",
                                runtimeMins = 125,
                                genresCsv = "Movie",
                                castCsv = stars,
                                rating = 8.0,
                                posterUrl = poster,
                                overview = if (stars.isNotBlank()) "Starring $stars" else "Feature Film"
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        try {
            val conn = (URL("https://itunes.apple.com/search?term=$encoded&country=US&entity=movie&limit=8").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (conn.responseCode in 200..299) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(text)
                val arr = root.optJSONArray("results") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    val trackId = m.optInt("trackId", 0)
                    if (trackId <= 0) continue
                    val title = m.optString("trackName", "Untitled Movie")
                    val rel = m.optString("releaseDate", "")
                    val year = rel.take(4).toIntOrNull() ?: 2024
                    val key = "${title.lowercase()}_$year"
                    if (!seenMovies.add(key)) continue
                    val runtimeMins = (m.optLong("trackTimeMillis", 7200000L) / 60000L).toInt().coerceAtLeast(45)
                    val poster = m.optString("artworkUrl100", "").replace("100x100bb.jpg", "600x900bb.jpg")
                    results.add(
                        SearchResultItem(
                            externalId = "movie_$trackId",
                            tmdbId = trackId,
                            mediaType = "movie",
                            title = title,
                            year = year,
                            networkOrDirector = m.optString("artistName", "Feature Film"),
                            status = "Released",
                            runtimeMins = runtimeMins,
                            genresCsv = m.optString("primaryGenreName", "Movie"),
                            rating = 7.7,
                            posterUrl = poster,
                            overview = stripHtml(m.optString("longDescription", m.optString("shortDescription", "")))
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        results
    }

    /**
     * Fetches the full episode guide & cast list for a TV show via:
     *  1. TMDB v3 (`/3/tv/{id}` + `/3/tv/{id}/season/{n}`) when `tmdbId > 0`
     *  2. TVMaze by ID (`/shows/{id}?embed[]=episodes&embed[]=cast`) when `tvmazeId > 0`
     *  3. TVMaze single-search by title (`/singlesearch/shows?q={title}&embed[]=episodes&embed[]=cast`) fallback
     */
    suspend fun fetchFullShowEpisodesAndCast(
        tvmazeId: Int,
        tmdbId: Int = 0,
        title: String = "",
        externalId: String = ""
    ): Pair<List<EpisodeModel>, List<String>> = withContext(Dispatchers.IO) {
        val resolvedTmdbId = when {
            tmdbId > 0 -> tmdbId
            externalId.startsWith("tmdb_tv_") -> externalId.removePrefix("tmdb_tv_").toIntOrNull() ?: 0
            else -> 0
        }
        val resolvedTvmazeId = when {
            tvmazeId > 0 -> tvmazeId
            externalId.startsWith("tvmaze_") -> externalId.removePrefix("tvmaze_").toIntOrNull() ?: 0
            else -> 0
        }

        // 1. Try TMDB API v3 if tmdbId is available
        val tmdbToken = BuildConfig.TMDB_READ_TOKEN.trim()
        val tmdbKey = BuildConfig.TMDB_API_KEY.trim()
        if (resolvedTmdbId > 0 && (tmdbToken.isNotEmpty() || tmdbKey.isNotEmpty())) {
            try {
                fun buildTmdbUrl(path: String): String {
                    val sep = if (path.contains("?")) "&" else "?"
                    return if (tmdbToken.isNotEmpty()) {
                        "https://api.themoviedb.org/3$path"
                    } else {
                        "https://api.themoviedb.org/3$path${sep}api_key=$tmdbKey"
                    }
                }
                fun openTmdb(path: String): HttpURLConnection =
                    (URL(buildTmdbUrl(path)).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Accept", "application/json")
                        if (tmdbToken.isNotEmpty()) {
                            setRequestProperty("Authorization", "Bearer $tmdbToken")
                        }
                        connectTimeout = 6000
                        readTimeout = 6000
                    }

                val tvConn = openTmdb("/tv/$resolvedTmdbId?append_to_response=credits&language=en-US")
                if (tvConn.responseCode in 200..299) {
                    val tvJson = JSONObject(tvConn.inputStream.bufferedReader().use { it.readText() })
                    val defaultRuntime = tvJson.optJSONArray("episode_run_time")?.optInt(0, 45)?.takeIf { it > 0 } ?: 45
                    val castNames = mutableListOf<String>()
                    val castArr = tvJson.optJSONObject("credits")?.optJSONArray("cast") ?: JSONArray()
                    for (i in 0 until minOf(8, castArr.length())) {
                        val name = castArr.optJSONObject(i)?.optString("name", "") ?: ""
                        if (name.isNotBlank()) castNames.add(name)
                    }

                    val seasonsArr = tvJson.optJSONArray("seasons") ?: JSONArray()
                    val seasonNums = mutableListOf<Int>()
                    for (i in 0 until seasonsArr.length()) {
                        val sNum = seasonsArr.optJSONObject(i)?.optInt("season_number", 0) ?: 0
                        if (sNum >= 1) seasonNums.add(sNum)
                    }

                    val episodes = mutableListOf<EpisodeModel>()
                    for (sNum in seasonNums.takeLast(12)) {
                        try {
                            val sConn = openTmdb("/tv/$resolvedTmdbId/season/$sNum?language=en-US")
                            if (sConn.responseCode in 200..299) {
                                val sJson = JSONObject(sConn.inputStream.bufferedReader().use { it.readText() })
                                val epsArr = sJson.optJSONArray("episodes") ?: JSONArray()
                                for (j in 0 until epsArr.length()) {
                                    val ep = epsArr.optJSONObject(j) ?: continue
                                    val eNum = ep.optInt("episode_number", j + 1).coerceAtLeast(1)
                                    val stillPath = ep.optString("still_path", "")
                                    val thumb = if (stillPath.isNotBlank() && stillPath != "null") {
                                        "https://image.tmdb.org/t/p/w300$stillPath"
                                    } else ""
                                    episodes.add(
                                        EpisodeModel(
                                            code = EpisodeModel.formatCode(sNum, eNum),
                                            season = sNum,
                                            number = eNum,
                                            title = ep.optString("name", "Episode $eNum"),
                                            airDate = ep.optString("air_date", ""),
                                            runtimeMins = ep.optInt("runtime", defaultRuntime).coerceAtLeast(15),
                                            imageUrl = thumb,
                                            overview = stripHtml(ep.optString("overview", ""))
                                        )
                                    )
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    val nextToAir = tvJson.optJSONObject("next_episode_to_air")
                    if (nextToAir != null) {
                        val nextS = nextToAir.optInt("season_number", 1).coerceAtLeast(1)
                        val nextE = nextToAir.optInt("episode_number", 1).coerceAtLeast(1)
                        val nextCode = EpisodeModel.formatCode(nextS, nextE)
                        if (episodes.none { it.code.equals(nextCode, ignoreCase = true) }) {
                            val stillPath = nextToAir.optString("still_path", "")
                            val thumb = if (stillPath.isNotBlank() && stillPath != "null") {
                                "https://image.tmdb.org/t/p/w300$stillPath"
                            } else ""
                            episodes.add(
                                EpisodeModel(
                                    code = nextCode,
                                    season = nextS,
                                    number = nextE,
                                    title = nextToAir.optString("name", "Episode $nextE"),
                                    airDate = nextToAir.optString("air_date", ""),
                                    runtimeMins = nextToAir.optInt("runtime", defaultRuntime).coerceAtLeast(15),
                                    imageUrl = thumb,
                                    overview = stripHtml(nextToAir.optString("overview", ""))
                                )
                            )
                        }
                    }
                    if (episodes.isNotEmpty()) {
                        return@withContext episodes to castNames
                    }
                }
            } catch (_: Exception) {}
        }

        // Helper to parse TVMaze show JSON with embedded episodes & cast
        fun parseTvMazeRoot(root: JSONObject): Pair<List<EpisodeModel>, List<String>> {
            val embedded = root.optJSONObject("_embedded") ?: JSONObject()
            val epsArr = embedded.optJSONArray("episodes") ?: JSONArray()
            val episodes = mutableListOf<EpisodeModel>()
            for (i in 0 until epsArr.length()) {
                val e = epsArr.optJSONObject(i) ?: continue
                val sNum = e.optInt("season", 1).coerceAtLeast(1)
                val eNum = e.optInt("number", i + 1).coerceAtLeast(1)
                val imgObj = e.optJSONObject("image")
                val thumb = (imgObj?.optString("medium") ?: imgObj?.optString("original") ?: "")
                    .replace("http://", "https://")
                episodes.add(
                    EpisodeModel(
                        code = EpisodeModel.formatCode(sNum, eNum),
                        season = sNum,
                        number = eNum,
                        title = e.optString("name", "Episode $eNum"),
                        airDate = e.optString("airdate", ""),
                        runtimeMins = e.optInt("runtime", 45).coerceAtLeast(15),
                        imageUrl = thumb,
                        overview = stripHtml(e.optString("summary", ""))
                    )
                )
            }
            val castArr = embedded.optJSONArray("cast") ?: JSONArray()
            val castNames = mutableListOf<String>()
            for (i in 0 until minOf(8, castArr.length())) {
                val name = castArr.optJSONObject(i)?.optJSONObject("person")?.optString("name", "") ?: ""
                if (name.isNotBlank()) castNames.add(name)
            }
            return episodes to castNames
        }

        // 2. Try TVMaze by ID
        if (resolvedTvmazeId > 0) {
            try {
                val url = "https://api.tvmaze.com/shows/$resolvedTvmazeId?embed[]=episodes&embed[]=cast"
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 6000
                    readTimeout = 6000
                }
                if (conn.responseCode in 200..299) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val parsed = parseTvMazeRoot(JSONObject(text))
                    if (parsed.first.isNotEmpty()) return@withContext parsed
                }
            } catch (_: Exception) {}
        }

        // 3. Fallback: TVMaze single-search by title
        if (title.isNotBlank()) {
            try {
                val encTitle = URLEncoder.encode(title.trim(), "UTF-8")
                val url = "https://api.tvmaze.com/singlesearch/shows?q=$encTitle&embed[]=episodes&embed[]=cast"
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 6000
                    readTimeout = 6000
                }
                if (conn.responseCode in 200..299) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val parsed = parseTvMazeRoot(JSONObject(text))
                    if (parsed.first.isNotEmpty()) return@withContext parsed
                }
            } catch (_: Exception) {}
        }

        emptyList<EpisodeModel>() to emptyList<String>()
    }

    data class MovieDetailsEnrichment(
        val streamingService: String,
        val runtimeMins: Int,
        val director: String,
        val castCsv: String,
        val genresCsv: String
    )

    fun isGenericStreamingLabel(label: String): Boolean {
        val s = label.trim().lowercase()
        return s.isEmpty() ||
            s == "streaming" ||
            s == "theaters / streaming" ||
            s == "digital / streaming" ||
            s == "tmdb feature film" ||
            s == "popular & acclaimed movie" ||
            s == "popular & acclaimed series" ||
            s == "feature film" ||
            s.startsWith("because ") ||
            s.startsWith("recommended ")
    }

    private fun normalizeUsProvidersFromTmdbJson(usObj: JSONObject?, fallback: String = ""): String {
        val names = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        fun pushClean(raw: String?) {
            val s = (raw ?: "").trim()
            if (s.isEmpty()) return
            val lower = s.lowercase()
            val canonical = when {
                lower.contains("netflix") -> "Netflix"
                lower.contains("prime video") || lower.contains("amazon") -> "Prime Video"
                lower.contains("hulu") || lower == "fx" -> "Hulu"
                lower == "max" || lower.contains("hbo") -> "Max"
                lower.contains("disney") -> "Disney+"
                lower.contains("apple tv") -> "Apple TV+"
                lower.contains("paramount") || lower == "cbs" || lower.contains("showtime") -> "Paramount+"
                lower.contains("peacock") || lower == "nbc" -> "Peacock"
                else -> s
            }
            if (seen.add(canonical.lowercase())) {
                names.add(canonical)
            }
        }
        if (usObj != null) {
            listOf("flatrate", "free", "ads").forEach { key ->
                val arr = usObj.optJSONArray(key) ?: JSONArray()
                for (i in 0 until arr.length()) {
                    pushClean(arr.optJSONObject(i)?.optString("provider_name"))
                }
            }
            if (names.isEmpty()) {
                listOf("rent", "buy").forEach { key ->
                    val arr = usObj.optJSONArray(key) ?: JSONArray()
                    for (i in 0 until minOf(3, arr.length())) {
                        pushClean(arr.optJSONObject(i)?.optString("provider_name"))
                    }
                }
            }
        }
        if (fallback.isNotBlank() && !isGenericStreamingLabel(fallback)) {
            fallback.split(",", "/").forEach { pushClean(it) }
        }
        return names.joinToString(" / ").ifBlank { fallback.takeIf { !isGenericStreamingLabel(it) } ?: "Streaming" }
    }

    suspend fun fetchMovieDetails(
        tmdbId: Int,
        title: String = "",
        externalId: String = ""
    ): MovieDetailsEnrichment? = withContext(Dispatchers.IO) {
        val tmdbToken = BuildConfig.TMDB_READ_TOKEN.trim()
        val tmdbKey = BuildConfig.TMDB_API_KEY.trim()
        if (tmdbToken.isEmpty() && tmdbKey.isEmpty()) return@withContext null

        fun buildTmdbUrl(path: String): String {
            val sep = if (path.contains("?")) "&" else "?"
            return if (tmdbToken.isNotEmpty()) {
                "https://api.themoviedb.org/3$path"
            } else {
                "https://api.themoviedb.org/3$path${sep}api_key=$tmdbKey"
            }
        }
        fun openTmdb(path: String): HttpURLConnection =
            (URL(buildTmdbUrl(path)).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                if (tmdbToken.isNotEmpty()) {
                    setRequestProperty("Authorization", "Bearer $tmdbToken")
                }
                connectTimeout = 6000
                readTimeout = 6000
            }

        var resolvedId = when {
            tmdbId > 0 -> tmdbId
            externalId.startsWith("movie_") -> externalId.removePrefix("movie_").toIntOrNull() ?: 0
            else -> 0
        }
        if (resolvedId <= 0 && title.isNotBlank()) {
            try {
                val enc = URLEncoder.encode(title.trim(), "UTF-8")
                val searchConn = openTmdb("/search/movie?query=$enc&language=en-US&page=1")
                if (searchConn.responseCode in 200..299) {
                    val sJson = JSONObject(searchConn.inputStream.bufferedReader().use { it.readText() })
                    resolvedId = sJson.optJSONArray("results")?.optJSONObject(0)?.optInt("id", 0) ?: 0
                }
            } catch (_: Exception) {}
        }
        if (resolvedId <= 0) return@withContext null

        try {
            val movConn = openTmdb("/movie/$resolvedId?append_to_response=credits,watch/providers&language=en-US")
            if (movConn.responseCode in 200..299) {
                val movJson = JSONObject(movConn.inputStream.bufferedReader().use { it.readText() })
                val usObj = movJson.optJSONObject("watch/providers")?.optJSONObject("results")?.optJSONObject("US")
                val streamingService = normalizeUsProvidersFromTmdbJson(usObj, "")
                val runtimeMins = movJson.optInt("runtime", 120).takeIf { it > 0 } ?: 120

                val castNames = mutableListOf<String>()
                val castArr = movJson.optJSONObject("credits")?.optJSONArray("cast") ?: JSONArray()
                for (i in 0 until minOf(8, castArr.length())) {
                    val name = castArr.optJSONObject(i)?.optString("name", "") ?: ""
                    if (name.isNotBlank()) castNames.add(name)
                }

                var directorName = ""
                val crewArr = movJson.optJSONObject("credits")?.optJSONArray("crew") ?: JSONArray()
                for (i in 0 until crewArr.length()) {
                    val c = crewArr.optJSONObject(i) ?: continue
                    if (c.optString("job").equals("Director", ignoreCase = true)) {
                        directorName = c.optString("name", "")
                        if (directorName.isNotBlank()) break
                    }
                }

                val genresList = mutableListOf<String>()
                val genresArr = movJson.optJSONArray("genres") ?: JSONArray()
                for (i in 0 until genresArr.length()) {
                    val gName = genresArr.optJSONObject(i)?.optString("name", "") ?: ""
                    if (gName.isNotBlank()) genresList.add(gName)
                }

                return@withContext MovieDetailsEnrichment(
                    streamingService = streamingService,
                    runtimeMins = runtimeMins,
                    director = directorName,
                    castCsv = castNames.joinToString(", "),
                    genresCsv = genresList.joinToString(", ")
                )
            }
        } catch (_: Exception) {}
        null
    }

    fun buildStarterShows(userId: String): List<ShowEntity> = emptyList()

    fun buildStarterMovies(userId: String): List<MovieEntity> = emptyList()

    fun buildStarterActors(userId: String): List<ActorEntity> = emptyList()
}

