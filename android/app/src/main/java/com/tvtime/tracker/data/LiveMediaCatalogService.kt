package com.tvtime.tracker.data

import com.tvtime.tracker.BuildConfig
import com.tvtime.tracker.domain.EpisodeModel
import com.tvtime.tracker.domain.SearchPageResult
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

    private val INDIAN_LANG_CODES = setOf("hi", "ml", "ta", "te", "kn", "bn", "mr", "pa", "gu", "ur")

    private fun stripEditionSuffix(raw: String): String =
        raw.replace(
            Regex(
                "\\s*[\\(\\[:\\-]\\s*(extended|unrated|director'?s cut|special edition|bonus|deluxe|collector'?s|remastered|imax|theatrical|ultimate|anniversary).*$",
                RegexOption.IGNORE_CASE
            ),
            ""
        ).trim()

    suspend fun discoverByUsProviders(selectedPlatforms: Set<String>, page: Int = 1): List<SearchResultItem> =
        discoverByUsProvidersPaged(selectedPlatforms, page).items

    suspend fun discoverByUsProvidersPaged(
        selectedPlatforms: Set<String>,
        page: Int = 1
    ): SearchPageResult = withContext(Dispatchers.IO) {
        val safePage = page.coerceIn(1, 25)
        if (selectedPlatforms.isEmpty()) return@withContext searchMultiPaged("Game of Thrones", safePage)
        val tmdbToken = BuildConfig.TMDB_READ_TOKEN.trim()
        val tmdbKey = BuildConfig.TMDB_API_KEY.trim()
        val providerIds = selectedPlatforms.flatMap { US_PLATFORM_PROVIDER_IDS[it] ?: emptyList() }.distinct()
        val providerPipe = providerIds.joinToString("|")
        val platformLabel = selectedPlatforms.joinToString(" / ")
        val results = mutableListOf<SearchResultItem>()
        var hasMore = false

        if (providerPipe.isNotEmpty() && (tmdbToken.isNotEmpty() || tmdbKey.isNotEmpty())) {
            val encPipe = URLEncoder.encode(providerPipe, "UTF-8")
            fun fetchDiscover(mediaType: String) {
                try {
                    val base = "https://api.themoviedb.org/3/discover/$mediaType?watch_region=US&with_watch_monetization_types=flatrate&with_watch_providers=$encPipe&sort_by=popularity.desc&language=en-US&page=$safePage"
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
                        val json = JSONObject(text)
                        val totalPages = json.optInt("total_pages", 1)
                        if (totalPages > safePage && safePage < 25) hasMore = true
                        val arr = json.optJSONArray("results") ?: JSONArray()
                        for (i in 0 until minOf(15, arr.length())) {
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
        SearchPageResult(items = results, hasMore = hasMore)
    }

    suspend fun discoverByCategoryPaged(
        mediaType: String = "movie",
        language: String = "",
        year: Int = 0,
        yearFrom: Int = 0,
        yearTo: Int = 0,
        genre: String = "",
        sort: String = "votes",
        page: Int = 1
    ): SearchPageResult = withContext(Dispatchers.IO) {
        val safePage = page.coerceIn(1, 50)
        val isShow = mediaType.equals("show", ignoreCase = true) || mediaType.equals("tv", ignoreCase = true)
        val tmdbToken = BuildConfig.TMDB_READ_TOKEN.trim()
        val tmdbKey = BuildConfig.TMDB_API_KEY.trim()
        val results = mutableListOf<SearchResultItem>()
        var hasMore = false

        if (tmdbToken.isNotEmpty() || tmdbKey.isNotEmpty()) {
            try {
                val sortParam = when (sort.lowercase()) {
                    "popularity" -> "popularity.desc"
                    "rating" -> "vote_average.desc"
                    "newest" -> if (isShow) "first_air_date.desc" else "primary_release_date.desc"
                    else -> "vote_count.desc"
                }
                val params = mutableListOf(
                    "sort_by=${URLEncoder.encode(sortParam, "UTF-8")}",
                    "include_adult=false",
                    "language=en-US",
                    "page=$safePage"
                )
                if (sort.equals("rating", ignoreCase = true)) {
                    params.add("vote_count.gte=5")
                }
                val cleanLang = language.trim().lowercase()
                val cleanGenre = genre.trim()
                if (isShow) {
                    var y1 = yearFrom
                    var y2 = yearTo
                    if (y1 > 1900 && y2 > 1900 && y1 > y2) {
                        val tmp = y1
                        y1 = y2
                        y2 = tmp
                    }
                    if (y1 > 1900) params.add("first_air_date.gte=$y1-01-01")
                    if (y2 > 1900) params.add("first_air_date.lte=$y2-12-31")
                    if (cleanGenre.isNotEmpty() && cleanGenre != "all") {
                        params.add("with_genres=${URLEncoder.encode(cleanGenre, "UTF-8")}")
                    }
                    if (cleanLang.isNotEmpty() && cleanLang != "all") {
                        params.add("with_original_language=${URLEncoder.encode(cleanLang, "UTF-8")}")
                    }
                } else {
                    if (year > 1900) params.add("primary_release_year=$year")
                    if (cleanLang.isNotEmpty() && cleanLang != "all") {
                        params.add("with_original_language=${URLEncoder.encode(cleanLang, "UTF-8")}")
                    }
                }

                val endpoint = if (isShow) "tv" else "movie"
                val base = "https://api.themoviedb.org/3/discover/$endpoint?${params.joinToString("&")}"
                val url = if (tmdbToken.isNotEmpty()) base else "$base&api_key=$tmdbKey"
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                    if (tmdbToken.isNotEmpty()) setRequestProperty("Authorization", "Bearer $tmdbToken")
                    connectTimeout = 6000
                    readTimeout = 6000
                }
                if (conn.responseCode in 200..299) {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val totalPages = json.optInt("total_pages", 1).coerceAtLeast(1)
                    val arr = json.optJSONArray("results") ?: JSONArray()
                    val seenIds = mutableSetOf<Int>()
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val id = item.optInt("id", 0)
                        if (id <= 0 || !seenIds.add(id)) continue
                        val posterPath = item.optString("poster_path", "")
                        val poster = if (posterPath.isNotBlank() && posterPath != "null") "https://image.tmdb.org/t/p/w500$posterPath" else ""
                        val overview = stripHtml(item.optString("overview", ""))
                        if (isShow) {
                            val title = item.optString("name", item.optString("original_name", "")).trim()
                            if (title.isEmpty()) continue
                            val itemYear = item.optString("first_air_date", "").take(4).toIntOrNull()
                                ?: yearFrom.takeIf { it > 1900 }
                                ?: 2024
                            val rating = (item.optDouble("vote_average", 7.8).takeIf { !it.isNaN() && it > 0 } ?: 7.8)
                            results.add(
                                SearchResultItem(
                                    externalId = "tmdb_tv_$id",
                                    tvmazeId = 0,
                                    tmdbId = id,
                                    mediaType = "show",
                                    title = title,
                                    year = itemYear,
                                    networkOrDirector = "Streaming",
                                    status = "Running",
                                    runtimeMins = 45,
                                    genresCsv = "TV Series",
                                    rating = Math.round(rating * 10.0) / 10.0,
                                    posterUrl = poster,
                                    overview = overview
                                )
                            )
                        } else {
                            val title = item.optString("title", item.optString("original_title", "")).trim()
                            if (title.isEmpty()) continue
                            val itemYear = item.optString("release_date", "").take(4).toIntOrNull()
                                ?: year.takeIf { it > 1900 }
                                ?: 2024
                            val rating = (item.optDouble("vote_average", 7.5).takeIf { !it.isNaN() && it > 0 } ?: 7.5)
                            results.add(
                                SearchResultItem(
                                    externalId = "movie_$id",
                                    tmdbId = id,
                                    mediaType = "movie",
                                    title = title,
                                    year = itemYear,
                                    networkOrDirector = "Theaters / Streaming",
                                    status = "Released",
                                    runtimeMins = 125,
                                    genresCsv = "Movie",
                                    rating = Math.round(rating * 10.0) / 10.0,
                                    posterUrl = poster,
                                    overview = overview
                                )
                            )
                        }
                    }
                    hasMore = results.isNotEmpty() && safePage < minOf(50, totalPages)
                }
            } catch (_: Exception) {}
        }
        SearchPageResult(items = results, hasMore = hasMore)
    }

    suspend fun fetchRecommendations(
        shows: List<ShowEntity>,
        movies: List<MovieEntity>,
        page: Int = 1
    ): List<SearchResultItem> = withContext(Dispatchers.IO) {
        val safePage = page.coerceIn(1, 15)
        val indianBucket = mutableListOf<SearchResultItem>()
        val globalBucket = mutableListOf<SearchResultItem>()
        val seenIds = mutableSetOf<String>()
        val seenTitleYears = mutableSetOf<String>()

        fun addCandidate(item: SearchResultItem, isIndian: Boolean) {
            if (item.title.isBlank() || item.posterUrl.isBlank()) return
            val isMovieItem = item.mediaType.equals("movie", ignoreCase = true) || item.externalId.startsWith("movie_")
            val alreadyInLibrary = if (isMovieItem) {
                val cleanTmdb = TvTimeProgressCalculator.cleanMovieTmdbId(item.externalId, item.tmdbId)
                movies.any { m ->
                    TvTimeProgressCalculator.doesRecMatchMovie(
                        m.externalId, m.tmdbId, m.title, m.year,
                        item.externalId, cleanTmdb, item.title, item.year
                    )
                }
            } else {
                shows.any { s ->
                    TvTimeProgressCalculator.doesRecMatchShow(
                        s.externalId, s.tmdbId, s.tvmazeId, s.title, s.year,
                        item.externalId, item.tmdbId, item.tvmazeId, item.title, item.year
                    )
                }
            }
            if (alreadyInLibrary) return

            val idKey = if (item.tmdbId > 0) "${item.mediaType}_${item.tmdbId}" else item.externalId
            val baseKey = "${item.mediaType}_${TvTimeProgressCalculator.normalizeRecTitle(stripEditionSuffix(item.title))}"
            val yearKeys = if (item.year > 0) {
                listOf("${baseKey}_${item.year}", "${baseKey}_${item.year - 1}", "${baseKey}_${item.year + 1}")
            } else {
                listOf(baseKey)
            }
            if (seenIds.contains(idKey) || yearKeys.any { seenTitleYears.contains(it) }) return
            seenIds.add(idKey)
            seenTitleYears.add("${baseKey}_${item.year}")
            if (isIndian) indianBucket.add(item) else globalBucket.add(item)
        }

        val tmdbToken = BuildConfig.TMDB_READ_TOKEN.trim()
        val tmdbKey = BuildConfig.TMDB_API_KEY.trim()
        var indianSeedCount = 0
        var resolvedSeedCount = 0

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

            fun pickMatchingTmdbHit(resultsArr: JSONArray?, targetTitle: String, targetYear: Int, dateField: String): JSONObject? {
                if (resultsArr == null) return null
                val wantNorm = TvTimeProgressCalculator.normalizeRecTitle(targetTitle)
                if (wantNorm.isEmpty()) return null
                val matches = mutableListOf<JSONObject>()
                for (i in 0 until resultsArr.length()) {
                    val obj = resultsArr.optJSONObject(i) ?: continue
                    val pNorm = TvTimeProgressCalculator.normalizeRecTitle(
                        obj.optString("title", obj.optString("name", ""))
                    )
                    val oNorm = TvTimeProgressCalculator.normalizeRecTitle(
                        obj.optString("original_title", obj.optString("original_name", ""))
                    )
                    if (pNorm == wantNorm || (oNorm.isNotEmpty() && oNorm == wantNorm)) {
                        matches.add(obj)
                    }
                }
                if (matches.isEmpty()) return null
                if (targetYear > 1900) {
                    val strictHit = matches.firstOrNull { hit ->
                        val yr = hit.optString(dateField, "").take(4).toIntOrNull() ?: 0
                        yr > 1900 && kotlin.math.abs(yr - targetYear) <= 1
                    }
                    if (strictHit != null) return strictHit
                    if (dateField == "first_air_date") {
                        val looseTvHit = matches.firstOrNull { hit ->
                            val yr = hit.optString(dateField, "").take(4).toIntOrNull() ?: 0
                            yr > 1900 && kotlin.math.abs(yr - targetYear) <= 2
                        }
                        if (looseTvHit != null) return looseTvHit
                    }
                    return null
                }
                return matches.first()
            }

            fun isRelevantSeedRec(item: JSONObject, seedGenres: Set<Int>, seedLang: String): Boolean {
                val voteCount = item.optInt("vote_count", 0)
                val voteAvg = item.optDouble("vote_average", 0.0)
                if (voteCount < 15 || voteAvg.isNaN() || voteAvg < 5.5) return false
                val gArr = item.optJSONArray("genre_ids")
                val itemGenres = mutableSetOf<Int>()
                if (gArr != null) {
                    for (gi in 0 until gArr.length()) {
                        val gid = gArr.optInt(gi, 0)
                        if (gid > 0) itemGenres.add(gid)
                    }
                }
                val sharedGenres = if (seedGenres.isNotEmpty()) itemGenres.count { seedGenres.contains(it) } else 1
                if (seedGenres.isNotEmpty() && sharedGenres < 1) return false

                val itemLang = item.optString("original_language", "").trim().lowercase()
                if (seedLang.isNotEmpty()) {
                    if (INDIAN_LANG_CODES.contains(seedLang)) {
                        if (!INDIAN_LANG_CODES.contains(itemLang)) {
                            if (!(sharedGenres >= 2 && voteCount >= 1500 && voteAvg >= 7.4)) return false
                        }
                    } else if (itemLang.isNotEmpty() && itemLang != seedLang) {
                        if (!(sharedGenres >= 2 && voteCount >= 300 && voteAvg >= 6.8)) return false
                    }
                }
                return true
            }

            val langLabels = mapOf(
                "hi" to "Hindi", "ml" to "Malayalam", "ta" to "Tamil", "te" to "Telugu",
                "kn" to "Kannada", "bn" to "Bengali", "mr" to "Marathi", "pa" to "Punjabi",
                "gu" to "Gujarati", "ur" to "Urdu"
            )
            val genreLabels = mapOf(
                28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy",
                80 to "Crime", 99 to "Documentary", 18 to "Drama", 10751 to "Family",
                14 to "Fantasy", 36 to "History", 27 to "Horror", 9648 to "Mystery",
                10749 to "Romance", 878 to "Sci-Fi", 53 to "Thriller",
                10759 to "Action & Adventure", 10765 to "Sci-Fi & Fantasy"
            )

            // Sample randomly across watched library items ONLY (never unwatched watchlist entries)
            val watchedShows = shows.filter { s ->
                val prog = TvTimeProgressCalculator.computeShowProgressFromJson(
                    s.episodesJson,
                    s.watchedCodesCsv,
                    s.userStatus
                )
                prog.watchedCount > 0 ||
                    s.userStatus.equals("completed", ignoreCase = true) ||
                    s.userStatus.equals("in_progress", ignoreCase = true)
            }
            val seedShows = watchedShows.shuffled().take(5)
            val watchedMovies = movies.filter { it.watched }
            val seedMovies = watchedMovies.shuffled().take(8)
            val regionalCounts = mutableMapOf<String, Int>()

            for (s in seedShows) {
                var tid = s.tmdbId
                var seedLang = ""
                if (tid <= 0 && s.title.isNotBlank()) {
                    val enc = URLEncoder.encode(s.title, "UTF-8")
                    val yrParam = if (s.year > 1900) "&first_air_date_year=${s.year}" else ""
                    val matchedHit = pickMatchingTmdbHit(
                        getTmdbJson("/search/tv?query=$enc$yrParam&language=en-US&page=1")?.optJSONArray("results"),
                        s.title,
                        s.year,
                        "first_air_date"
                    ) ?: if (yrParam.isNotEmpty()) {
                        pickMatchingTmdbHit(
                            getTmdbJson("/search/tv?query=$enc&language=en-US&page=1")?.optJSONArray("results"),
                            s.title,
                            s.year,
                            "first_air_date"
                        )
                    } else null
                    tid = matchedHit?.optInt("id", 0) ?: 0
                    seedLang = matchedHit?.optString("original_language", "")?.lowercase().orEmpty()
                }
                if (tid > 0) {
                    seenIds.add("show_$tid")
                    val detail = getTmdbJson("/tv/$tid?append_to_response=recommendations&language=en-US")
                    if (detail != null) {
                        if (seedLang.isEmpty()) {
                            seedLang = detail.optString("original_language", "").lowercase()
                        }
                        val seedGenres = mutableSetOf<Int>()
                        val detailGenres = detail.optJSONArray("genres")
                        if (detailGenres != null) {
                            for (gi in 0 until detailGenres.length()) {
                                val gid = detailGenres.optJSONObject(gi)?.optInt("id", 0) ?: 0
                                if (gid > 0) seedGenres.add(gid)
                            }
                        }
                        resolvedSeedCount++
                        val seedIsIndian = INDIAN_LANG_CODES.contains(seedLang)
                        if (seedIsIndian) {
                            indianSeedCount++
                            regionalCounts[seedLang] = (regionalCounts[seedLang] ?: 0) + 1
                        }
                        var recArr = detail.optJSONObject("recommendations")?.optJSONArray("results") ?: JSONArray()
                        if (safePage > 1) {
                            val pageArr = getTmdbJson("/tv/$tid/recommendations?language=en-US&page=$safePage")?.optJSONArray("results")
                            if (pageArr != null && pageArr.length() > 0) recArr = pageArr
                        }
                        var validAdded = 0
                        for (i in 0 until recArr.length()) {
                            val item = recArr.optJSONObject(i) ?: continue
                            if (!isRelevantSeedRec(item, seedGenres, seedLang)) continue
                            val id = item.optInt("id", 0)
                            val posterPath = item.optString("poster_path", "")
                            if (id <= 0 || posterPath.isBlank() || posterPath == "null") continue
                            val itemLang = item.optString("original_language", "").lowercase()
                            val title = item.optString("name", item.optString("original_name", ""))
                            val year = item.optString("first_air_date", "").take(4).toIntOrNull() ?: 2024
                            val rating = (item.optDouble("vote_average", 7.9).takeIf { !it.isNaN() && it > 0 } ?: 7.9)
                            addCandidate(
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
                                ),
                                isIndian = seedIsIndian || INDIAN_LANG_CODES.contains(itemLang)
                            )
                            validAdded++
                            if (validAdded >= 8) break
                        }
                        if (validAdded < 3 && seedLang.isNotEmpty() && seedGenres.isNotEmpty()) {
                            val primaryGenre = seedGenres.first()
                            val fbLabel = listOfNotNull(langLabels[seedLang], genreLabels[primaryGenre], "Series")
                                .filter { it.isNotBlank() }
                                .joinToString(" ")
                            val fbArr = getTmdbJson(
                                "/discover/tv?with_original_language=$seedLang&with_genres=$primaryGenre&sort_by=popularity.desc&vote_count.gte=20&vote_average.gte=6.2&include_adult=false&language=en-US&page=$safePage"
                            )?.optJSONArray("results") ?: JSONArray()
                            for (i in 0 until minOf(fbArr.length(), 6)) {
                                val item = fbArr.optJSONObject(i) ?: continue
                                val id = item.optInt("id", 0)
                                val posterPath = item.optString("poster_path", "")
                                if (id <= 0 || posterPath.isBlank() || posterPath == "null") continue
                                val title = item.optString("name", item.optString("original_name", ""))
                                val year = item.optString("first_air_date", "").take(4).toIntOrNull() ?: 2024
                                val rating = (item.optDouble("vote_average", 7.8).takeIf { !it.isNaN() && it > 0 } ?: 7.8)
                                addCandidate(
                                    SearchResultItem(
                                        externalId = "tmdb_tv_$id",
                                        tvmazeId = 0,
                                        tmdbId = id,
                                        mediaType = "show",
                                        title = title,
                                        year = year,
                                        networkOrDirector = "Popular $fbLabel",
                                        status = "Running",
                                        runtimeMins = 45,
                                        genresCsv = "TV Series",
                                        rating = Math.round(rating * 10.0) / 10.0,
                                        posterUrl = "https://image.tmdb.org/t/p/w500$posterPath",
                                        overview = stripHtml(item.optString("overview", ""))
                                    ),
                                    isIndian = seedIsIndian
                                )
                            }
                        }
                    }
                }
            }

            for (m in seedMovies) {
                var mid = TvTimeProgressCalculator.cleanMovieTmdbId(m.externalId, m.tmdbId)
                var seedLang = ""
                if (mid <= 0 && m.title.isNotBlank()) {
                    val enc = URLEncoder.encode(m.title, "UTF-8")
                    val yearQuery = if (m.year > 1900) "&year=${m.year}" else ""
                    val matchedHit = pickMatchingTmdbHit(
                        getTmdbJson("/search/movie?query=$enc$yearQuery&language=en-US&page=1")?.optJSONArray("results"),
                        m.title,
                        m.year,
                        "release_date"
                    ) ?: if (yearQuery.isNotEmpty()) {
                        pickMatchingTmdbHit(
                            getTmdbJson("/search/movie?query=$enc&language=en-US&page=1")?.optJSONArray("results"),
                            m.title,
                            m.year,
                            "release_date"
                        )
                    } else null
                    mid = matchedHit?.optInt("id", 0) ?: 0
                    seedLang = matchedHit?.optString("original_language", "")?.lowercase().orEmpty()
                }
                if (mid > 0) {
                    seenIds.add("movie_$mid")
                    val detail = getTmdbJson("/movie/$mid?append_to_response=recommendations&language=en-US")
                    if (detail != null) {
                        if (seedLang.isEmpty()) {
                            seedLang = detail.optString("original_language", "").lowercase()
                        }
                        val seedGenres = mutableSetOf<Int>()
                        val detailGenres = detail.optJSONArray("genres")
                        if (detailGenres != null) {
                            for (gi in 0 until detailGenres.length()) {
                                val gid = detailGenres.optJSONObject(gi)?.optInt("id", 0) ?: 0
                                if (gid > 0) seedGenres.add(gid)
                            }
                        }
                        resolvedSeedCount++
                        val seedIsIndian = INDIAN_LANG_CODES.contains(seedLang)
                        if (seedIsIndian) {
                            indianSeedCount++
                            regionalCounts[seedLang] = (regionalCounts[seedLang] ?: 0) + 1
                        }
                        var recArr = detail.optJSONObject("recommendations")?.optJSONArray("results") ?: JSONArray()
                        if (safePage > 1) {
                            val pageArr = getTmdbJson("/movie/$mid/recommendations?language=en-US&page=$safePage")?.optJSONArray("results")
                            if (pageArr != null && pageArr.length() > 0) recArr = pageArr
                        }
                        var validAdded = 0
                        for (i in 0 until recArr.length()) {
                            val item = recArr.optJSONObject(i) ?: continue
                            if (!isRelevantSeedRec(item, seedGenres, seedLang)) continue
                            val id = item.optInt("id", 0)
                            val posterPath = item.optString("poster_path", "")
                            if (id <= 0 || posterPath.isBlank() || posterPath == "null") continue
                            val itemLang = item.optString("original_language", "").lowercase()
                            val title = item.optString("title", item.optString("original_title", ""))
                            val year = item.optString("release_date", "").take(4).toIntOrNull() ?: 2024
                            val rating = (item.optDouble("vote_average", 7.8).takeIf { !it.isNaN() && it > 0 } ?: 7.8)
                            addCandidate(
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
                                ),
                                isIndian = seedIsIndian || INDIAN_LANG_CODES.contains(itemLang)
                            )
                            validAdded++
                            if (validAdded >= 8) break
                        }
                        if (validAdded < 3 && seedLang.isNotEmpty() && seedGenres.isNotEmpty()) {
                            val primaryGenre = seedGenres.first()
                            val fbLabel = listOfNotNull(langLabels[seedLang], genreLabels[primaryGenre], "Cinema")
                                .filter { it.isNotBlank() }
                                .joinToString(" ")
                            val fbArr = getTmdbJson(
                                "/discover/movie?with_original_language=$seedLang&with_genres=$primaryGenre&sort_by=popularity.desc&vote_count.gte=30&vote_average.gte=6.2&include_adult=false&language=en-US&page=$safePage"
                            )?.optJSONArray("results") ?: JSONArray()
                            for (i in 0 until minOf(fbArr.length(), 6)) {
                                val item = fbArr.optJSONObject(i) ?: continue
                                val id = item.optInt("id", 0)
                                val posterPath = item.optString("poster_path", "")
                                if (id <= 0 || posterPath.isBlank() || posterPath == "null") continue
                                val title = item.optString("title", item.optString("original_title", ""))
                                val year = item.optString("release_date", "").take(4).toIntOrNull() ?: 2024
                                val rating = (item.optDouble("vote_average", 7.6).takeIf { !it.isNaN() && it > 0 } ?: 7.6)
                                addCandidate(
                                    SearchResultItem(
                                        externalId = "movie_$id",
                                        tmdbId = id,
                                        mediaType = "movie",
                                        title = title,
                                        year = year,
                                        networkOrDirector = "Popular $fbLabel",
                                        status = "Released",
                                        runtimeMins = 120,
                                        genresCsv = "Movie",
                                        rating = Math.round(rating * 10.0) / 10.0,
                                        posterUrl = "https://image.tmdb.org/t/p/w500$posterPath",
                                        overview = stripHtml(item.optString("overview", ""))
                                    ),
                                    isIndian = seedIsIndian
                                )
                            }
                        }
                    }
                }
            }

            // Indian / Hindi discovery proportional to the user's watched library language mix
            val indianRatio = if (resolvedSeedCount > 0) {
                indianSeedCount.toDouble() / resolvedSeedCount.toDouble()
            } else 0.0

            if (indianSeedCount > 0) {
                val discPage = ((safePage - 1) % 8) + 1
                val sortMode = if (safePage % 2 == 1) "popularity.desc" else "vote_count.desc"
                val hiMovies = getTmdbJson(
                    "/discover/movie?with_original_language=hi&sort_by=$sortMode&vote_count.gte=25&page=$discPage&language=en-US"
                )?.optJSONArray("results") ?: JSONArray()
                for (i in 0 until minOf(hiMovies.length(), 16)) {
                    val item = hiMovies.optJSONObject(i) ?: continue
                    val id = item.optInt("id", 0)
                    val posterPath = item.optString("poster_path", "")
                    if (id <= 0 || posterPath.isBlank() || posterPath == "null") continue
                    val title = item.optString("title", item.optString("original_title", ""))
                    val year = item.optString("release_date", "").take(4).toIntOrNull() ?: 2024
                    val rating = (item.optDouble("vote_average", 7.6).takeIf { !it.isNaN() && it > 0 } ?: 7.6)
                    addCandidate(
                        SearchResultItem(
                            externalId = "movie_$id",
                            tmdbId = id,
                            mediaType = "movie",
                            title = title,
                            year = year,
                            networkOrDirector = "Popular Hindi Film",
                            status = "Released",
                            runtimeMins = 135,
                            genresCsv = "Hindi Movie",
                            rating = Math.round(rating * 10.0) / 10.0,
                            posterUrl = "https://image.tmdb.org/t/p/w500$posterPath",
                            overview = stripHtml(item.optString("overview", ""))
                        ),
                        isIndian = true
                    )
                }

                val hiShows = getTmdbJson(
                    "/discover/tv?with_original_language=hi&sort_by=popularity.desc&vote_count.gte=10&page=$discPage&language=en-US"
                )?.optJSONArray("results") ?: JSONArray()
                for (i in 0 until minOf(hiShows.length(), 10)) {
                    val item = hiShows.optJSONObject(i) ?: continue
                    val id = item.optInt("id", 0)
                    val posterPath = item.optString("poster_path", "")
                    if (id <= 0 || posterPath.isBlank() || posterPath == "null") continue
                    val title = item.optString("name", item.optString("original_name", ""))
                    val year = item.optString("first_air_date", "").take(4).toIntOrNull() ?: 2024
                    val rating = (item.optDouble("vote_average", 7.8).takeIf { !it.isNaN() && it > 0 } ?: 7.8)
                    addCandidate(
                        SearchResultItem(
                            externalId = "tmdb_tv_$id",
                            tvmazeId = 0,
                            tmdbId = id,
                            mediaType = "show",
                            title = title,
                            year = year,
                            networkOrDirector = "Popular Indian Series",
                            status = "Running",
                            runtimeMins = 45,
                            genresCsv = "Hindi Series",
                            rating = Math.round(rating * 10.0) / 10.0,
                            posterUrl = "https://image.tmdb.org/t/p/w500$posterPath",
                            overview = stripHtml(item.optString("overview", ""))
                        ),
                        isIndian = true
                    )
                }

                val topRegional = regionalCounts.entries
                    .filter { it.key != "hi" }
                    .maxByOrNull { it.value }
                    ?.key
                if (!topRegional.isNullOrEmpty()) {
                    val regMovies = getTmdbJson(
                        "/discover/movie?with_original_language=$topRegional&sort_by=popularity.desc&vote_count.gte=20&page=$discPage&language=en-US"
                    )?.optJSONArray("results") ?: JSONArray()
                    for (i in 0 until minOf(regMovies.length(), 8)) {
                        val item = regMovies.optJSONObject(i) ?: continue
                        val id = item.optInt("id", 0)
                        val posterPath = item.optString("poster_path", "")
                        if (id <= 0 || posterPath.isBlank() || posterPath == "null") continue
                        val title = item.optString("title", item.optString("original_title", ""))
                        val year = item.optString("release_date", "").take(4).toIntOrNull() ?: 2024
                        val rating = (item.optDouble("vote_average", 7.7).takeIf { !it.isNaN() && it > 0 } ?: 7.7)
                        addCandidate(
                            SearchResultItem(
                                externalId = "movie_$id",
                                tmdbId = id,
                                mediaType = "movie",
                                title = title,
                                year = year,
                                networkOrDirector = "Acclaimed Indian Cinema",
                                status = "Released",
                                runtimeMins = 140,
                                genresCsv = "Movie",
                                rating = Math.round(rating * 10.0) / 10.0,
                                posterUrl = "https://image.tmdb.org/t/p/w500$posterPath",
                                overview = stripHtml(item.optString("overview", ""))
                            ),
                            isIndian = true
                        )
                    }
                }
            }

            // Supplement with global trending TV & movies
            val trendTv = getTmdbJson("/trending/tv/week?language=en-US&page=$safePage")?.optJSONArray("results") ?: JSONArray()
            for (i in 0 until minOf(trendTv.length(), 8)) {
                val item = trendTv.optJSONObject(i) ?: continue
                val id = item.optInt("id", 0)
                val posterPath = item.optString("poster_path", "")
                if (id <= 0 || posterPath.isBlank() || posterPath == "null") continue
                val itemLang = item.optString("original_language", "").lowercase()
                val title = item.optString("name", item.optString("original_name", ""))
                val year = item.optString("first_air_date", "").take(4).toIntOrNull() ?: 2024
                val rating = (item.optDouble("vote_average", 8.0).takeIf { !it.isNaN() && it > 0 } ?: 8.0)
                addCandidate(
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
                    ),
                    isIndian = INDIAN_LANG_CODES.contains(itemLang)
                )
            }
            val trendMov = getTmdbJson("/trending/movie/week?language=en-US&page=$safePage")?.optJSONArray("results") ?: JSONArray()
            for (i in 0 until minOf(trendMov.length(), 8)) {
                val item = trendMov.optJSONObject(i) ?: continue
                val id = item.optInt("id", 0)
                val posterPath = item.optString("poster_path", "")
                if (id <= 0 || posterPath.isBlank() || posterPath == "null") continue
                val itemLang = item.optString("original_language", "").lowercase()
                val title = item.optString("title", item.optString("original_title", ""))
                val year = item.optString("release_date", "").take(4).toIntOrNull() ?: 2024
                val rating = (item.optDouble("vote_average", 7.8).takeIf { !it.isNaN() && it > 0 } ?: 7.8)
                addCandidate(
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
                    ),
                    isIndian = INDIAN_LANG_CODES.contains(itemLang)
                )
            }

            // Interleave Indian & Global buckets according to user's seed ratio
            val targetIndianRatio = if (indianSeedCount > 0) indianRatio.coerceIn(0.30, 0.75) else 0.0
            if (targetIndianRatio > 0.0 && indianBucket.isNotEmpty()) {
                val merged = mutableListOf<SearchResultItem>()
                var indIdx = 0
                var glbIdx = 0
                val indBatch = if (targetIndianRatio >= 0.55) 2 else 1
                val glbBatch = if (targetIndianRatio >= 0.70) 1 else if (targetIndianRatio >= 0.45) 1 else 2
                while (indIdx < indianBucket.size || glbIdx < globalBucket.size) {
                    repeat(indBatch) {
                        if (indIdx < indianBucket.size) merged.add(indianBucket[indIdx++])
                    }
                    repeat(glbBatch) {
                        if (glbIdx < globalBucket.size) merged.add(globalBucket[glbIdx++])
                    }
                }
                return@withContext merged
            }
        }

        val combined = mutableListOf<SearchResultItem>()
        combined.addAll(indianBucket)
        combined.addAll(globalBucket)
        combined
    }

    private fun stripHtml(raw: String?): String =
        (raw ?: "").replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()

    suspend fun searchMulti(query: String, page: Int = 1): List<SearchResultItem> =
        searchMultiPaged(query, page).items

    suspend fun searchMultiPaged(query: String, page: Int = 1): SearchPageResult = withContext(Dispatchers.IO) {
        val safePage = page.coerceIn(1, 25)
        val q = query.trim().ifEmpty { "Game of Thrones" }
        val encoded = URLEncoder.encode(q, "UTF-8")
        val results = mutableListOf<SearchResultItem>()
        val seenMovieTmdbIds = mutableSetOf<Int>()
        val seenShowTmdbIds = mutableSetOf<Int>()
        val seenMovieTitleYears = mutableListOf<Pair<String, Int>>()
        val seenShowTitleYears = mutableListOf<Pair<String, Int>>()
        var hasMore = false
        var tmdbMovieCount = 0

        fun normBaseKey(raw: String): String =
            TvTimeProgressCalculator.normalizeTitleKey(stripEditionSuffix(raw))

        fun canAddMovie(tmdbId: Int, title: String, year: Int): Boolean {
            if (tmdbId > 0 && !seenMovieTmdbIds.add(tmdbId)) return false
            val tKey = normBaseKey(title)
            if (tKey.isEmpty()) return false
            val skel = TvTimeProgressCalculator.translitSkeleton(title)
            val dupByTitleYear = seenMovieTitleYears.any { (k, y) ->
                val sameTitle = k == tKey || (skel.length >= 8 && TvTimeProgressCalculator.translitSkeleton(k) == skel)
                sameTitle && (year <= 0 || y <= 0 || kotlin.math.abs(y - year) <= 1)
            }
            if (dupByTitleYear) return false
            seenMovieTitleYears.add(tKey to year)
            return true
        }

        fun canAddShow(tmdbId: Int, title: String, year: Int): Boolean {
            if (tmdbId > 0 && !seenShowTmdbIds.add(tmdbId)) return false
            val tKey = normBaseKey(title)
            if (tKey.isEmpty()) return false
            val dupByTitleYear = seenShowTitleYears.any { (k, y) ->
                k == tKey && (year <= 0 || y <= 0 || kotlin.math.abs(y - year) <= 1)
            }
            if (dupByTitleYear) return false
            seenShowTitleYears.add(tKey to year)
            return true
        }

        // 0. TMDB v3 Multi Search (Movies, TV Shows & People)
        val tmdbToken = BuildConfig.TMDB_READ_TOKEN.trim()
        val tmdbKey = BuildConfig.TMDB_API_KEY.trim()
        if (tmdbToken.isNotEmpty() || tmdbKey.isNotEmpty()) {
            try {
                fun fetchTmdbJson(path: String): JSONObject? {
                    val sep = if (path.contains("?")) "&" else "?"
                    val url = if (tmdbToken.isNotEmpty()) {
                        "https://api.themoviedb.org/3$path"
                    } else {
                        "https://api.themoviedb.org/3$path${sep}api_key=$tmdbKey"
                    }
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Accept", "application/json")
                        if (tmdbToken.isNotEmpty()) {
                            setRequestProperty("Authorization", "Bearer $tmdbToken")
                        }
                        connectTimeout = 5000
                        readTimeout = 5000
                    }
                    if (conn.responseCode !in 200..299) return null
                    return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                }

                fun normTitleKey(raw: String): String =
                    TvTimeProgressCalculator.normalizeTitleKey(raw)

                val normQ = normTitleKey(q)
                val combinedItems = mutableListOf<JSONObject>()
                val personItems = mutableListOf<JSONObject>()
                if (safePage == 1 && Regex("\\band\\b", RegexOption.IGNORE_CASE).containsMatchIn(q)) {
                    val ampEncoded = URLEncoder.encode(q.replace(Regex("\\band\\b", RegexOption.IGNORE_CASE), "&"), "UTF-8")
                    val ampRoot = fetchTmdbJson("/search/tv?query=$ampEncoded&include_adult=false&language=en-US&page=1")
                    val ampArr = ampRoot?.optJSONArray("results") ?: JSONArray()
                    for (i in 0 until minOf(10, ampArr.length())) {
                        val tvObj = ampArr.optJSONObject(i) ?: continue
                        combinedItems.add(JSONObject(tvObj.toString()).put("media_type", "tv"))
                    }
                }
                val multiRoot = fetchTmdbJson("/search/multi?query=$encoded&include_adult=false&language=en-US&page=$safePage")
                val multiTotalPages = multiRoot?.optInt("total_pages", 1) ?: 1
                if (multiTotalPages > safePage && safePage < 25) hasMore = true
                val multiArr = multiRoot?.optJSONArray("results") ?: JSONArray()
                for (i in 0 until minOf(20, multiArr.length())) {
                    val item = multiArr.optJSONObject(i) ?: continue
                    if (item.optString("media_type", "").equals("person", ignoreCase = true)) {
                        personItems.add(item)
                    } else {
                        combinedItems.add(item)
                    }
                }

                // On page > 1, also check page 1 person matches so actor filmographies paginate smoothly
                if (safePage > 1 && personItems.isEmpty()) {
                    val pRoot = fetchTmdbJson("/search/person?query=$encoded&include_adult=false&language=en-US&page=1")
                    val pArr = pRoot?.optJSONArray("results") ?: JSONArray()
                    for (i in 0 until minOf(3, pArr.length())) {
                        pArr.optJSONObject(i)?.let { personItems.add(it) }
                    }
                }

                // Expand actor matches into BOTH movies and TV series starring that actor (30 per page)
                val actorCreditResults = mutableListOf<SearchResultItem>()
                val matchedActors = personItems.filter { p ->
                    val pid = p.optInt("id", 0)
                    val pName = normTitleKey(p.optString("name", ""))
                    pid > 0 && pName.isNotEmpty() && (
                        pName == normQ ||
                            pName.contains(normQ) ||
                            normQ.contains(pName) ||
                            (safePage == 1 && personItems.indexOf(p) == 0)
                        )
                }.sortedByDescending { p ->
                    val pName = normTitleKey(p.optString("name", ""))
                    val exactBonus = if (pName == normQ) 200.0 else if (pName.startsWith(normQ)) 80.0 else 0.0
                    p.optDouble("popularity", 0.0) + exactBonus
                }.take(2)

                fun scoreActorCredit(c: JSONObject): Double {
                    val rawType = c.optString("media_type", if (c.has("first_air_date") && !c.has("title")) "tv" else "movie").lowercase()
                    val isTv = rawType == "tv"
                    val votes = c.optDouble("vote_count", 0.0)
                    val pop = c.optDouble("popularity", 0.0)
                    val epCount = c.optInt("episode_count", 0)
                    val starringBonus = if (isTv && epCount >= 4) 25.0 else 0.0
                    val voteBoost = minOf(300.0, votes / if (isTv) 6.0 else 18.0)
                    val base = if (votes >= if (isTv) 10.0 else 25.0) {
                        pop + voteBoost + starringBonus
                    } else {
                        pop * 0.2
                    }
                    return if (isTv && epCount == 1 && votes < 5000.0) base * 0.35 else base
                }

                val actorPageSize = 30
                val actorOffset = (safePage - 1) * actorPageSize
                for (actorObj in matchedActors) {
                    val actorId = actorObj.optInt("id", 0)
                    val actorName = actorObj.optString("name", "").trim()
                    val creditsJson = fetchTmdbJson("/person/$actorId/combined_credits?language=en-US") ?: continue
                    val castArr = creditsJson.optJSONArray("cast") ?: JSONArray()
                    val candidateCredits = mutableListOf<JSONObject>()
                    for (ci in 0 until castArr.length()) {
                        val c = castArr.optJSONObject(ci) ?: continue
                        val cid = c.optInt("id", 0)
                        val rawType = c.optString("media_type", if (c.has("first_air_date") && !c.has("title")) "tv" else "movie").lowercase()
                        if (cid <= 0 || (rawType != "movie" && rawType != "tv")) continue
                        val cTitle = if (rawType == "tv") {
                            c.optString("name", c.optString("original_name", "")).trim()
                        } else {
                            c.optString("title", c.optString("original_title", "")).trim()
                        }
                        if (cTitle.isEmpty()) continue

                        val genreIds = c.optJSONArray("genre_ids")
                        val gSet = mutableSetOf<Int>()
                        if (genreIds != null) {
                            for (gi in 0 until genreIds.length()) {
                                gSet.add(genreIds.optInt(gi, 0))
                            }
                        }
                        val charLower = c.optString("character", "").trim().lowercase()
                        val epCount = c.optInt("episode_count", 0)
                        val votes = c.optInt("vote_count", 0)
                        val isSelf = charLower == "self" || charLower.startsWith("self ") ||
                            charLower.startsWith("self-") || charLower.contains("himself") ||
                            charLower.contains("herself") || charLower.contains("archive") ||
                            charLower.contains("guest")

                        if (rawType == "tv") {
                            if ((gSet.contains(10767) || gSet.contains(10763)) && epCount < 15) continue
                            if ((gSet.contains(99) || gSet.contains(10764)) && (isSelf || charLower.isEmpty()) && !(epCount >= 10 && votes >= 200)) {
                                continue
                            }
                            if (isSelf && epCount <= 3) continue
                        } else {
                            if (gSet.contains(99) && isSelf && votes < 200) continue
                        }
                        candidateCredits.add(c)
                    }
                    candidateCredits.sortByDescending { c -> scoreActorCredit(c) }
                    if (candidateCredits.size > actorOffset + actorPageSize && safePage < 25) {
                        hasMore = true
                    }
                    for (c in candidateCredits.drop(actorOffset).take(actorPageSize)) {
                        val cid = c.optInt("id", 0)
                        val rawType = c.optString("media_type", if (c.has("first_air_date") && !c.has("title")) "tv" else "movie").lowercase()
                        val posterPath = c.optString("poster_path", "")
                        val poster = if (posterPath.isNotBlank() && posterPath != "null") "https://image.tmdb.org/t/p/w500$posterPath" else ""
                        val rawOverview = stripHtml(c.optString("overview", ""))
                        val overview = if (actorName.isNotEmpty() && rawOverview.isNotEmpty()) {
                            "Starring $actorName · $rawOverview"
                        } else if (actorName.isNotEmpty()) {
                            "Starring $actorName"
                        } else {
                            rawOverview
                        }
                        if (rawType == "tv") {
                            val title = c.optString("name", c.optString("original_name", "Untitled Show"))
                            val year = c.optString("first_air_date", "").take(4).toIntOrNull() ?: 2024
                            if (!canAddShow(cid, title, year)) continue
                            val rating = (c.optDouble("vote_average", 8.0).takeIf { !it.isNaN() && it > 0 } ?: 8.0)
                            actorCreditResults.add(
                                SearchResultItem(
                                    externalId = "tmdb_tv_$cid",
                                    tvmazeId = 0,
                                    tmdbId = cid,
                                    mediaType = "show",
                                    title = title,
                                    year = year,
                                    networkOrDirector = "Streaming",
                                    status = "Running",
                                    runtimeMins = 45,
                                    genresCsv = "TV Series",
                                    castCsv = actorName,
                                    rating = Math.round(rating * 10.0) / 10.0,
                                    posterUrl = poster,
                                    overview = overview
                                )
                            )
                        } else {
                            val title = c.optString("title", c.optString("original_title", "Untitled Movie"))
                            val year = c.optString("release_date", "").take(4).toIntOrNull() ?: 2024
                            if (!canAddMovie(cid, title, year)) continue
                            tmdbMovieCount++
                            val rating = (c.optDouble("vote_average", 7.8).takeIf { !it.isNaN() && it > 0 } ?: 7.8)
                            actorCreditResults.add(
                                SearchResultItem(
                                    externalId = "movie_$cid",
                                    tmdbId = cid,
                                    mediaType = "movie",
                                    title = title,
                                    year = year,
                                    networkOrDirector = if (actorName.isNotEmpty()) "Starring $actorName" else "TMDB Feature Film",
                                    status = "Released",
                                    runtimeMins = 125,
                                    genresCsv = "Movie",
                                    castCsv = actorName,
                                    rating = Math.round(rating * 10.0) / 10.0,
                                    posterUrl = poster,
                                    overview = overview
                                )
                            )
                        }
                    }
                }

                val topMultiIsPerson = (multiArr.length() > 0 &&
                    multiArr.optJSONObject(0)?.optString("media_type", "")?.equals("person", ignoreCase = true) == true) ||
                    matchedActors.any { normTitleKey(it.optString("name", "")) == normQ }
                if (topMultiIsPerson && actorCreditResults.isNotEmpty()) {
                    results.addAll(actorCreditResults)
                }

                combinedItems.sortByDescending { item ->
                    val rawTitle = item.optString("name", item.optString("title", ""))
                    val tKey = normTitleKey(rawTitle)
                    val startsBonus = if (normQ.isNotEmpty() && (tKey == normQ || tKey.startsWith("$normQ "))) 80.0 else 0.0
                    item.optDouble("popularity", 0.0) + minOf(200.0, item.optDouble("vote_count", 0.0) / 10.0) + startsBonus
                }

                for (item in combinedItems) {
                    val id = item.optInt("id", 0)
                    if (id <= 0) continue
                    val mType = item.optString("media_type", "").lowercase()
                    if (mType == "movie") {
                        val title = item.optString("title", item.optString("original_title", "Untitled Movie"))
                        val year = item.optString("release_date", "").take(4).toIntOrNull() ?: 2024
                        if (!canAddMovie(id, title, year)) continue
                        tmdbMovieCount++
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
                        val year = item.optString("first_air_date", "").take(4).toIntOrNull() ?: 2024
                        if (!canAddShow(id, title, year)) continue
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
                if (!topMultiIsPerson && actorCreditResults.isNotEmpty()) {
                    results.addAll(actorCreditResults)
                }
            } catch (_: Exception) {}
        }

        // 1. TVMaze Shows Search (Page 1 only)
        if (safePage == 1) {
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
                        val premiered = show.optString("premiered", "")
                        val year = premiered.take(4).toIntOrNull() ?: 2024
                        // Attach tvmazeId only if title AND year (±1) match
                        val existingIdx = results.indexOfFirst {
                            it.mediaType == "show" &&
                                normBaseKey(it.title) == normBaseKey(showTitle) &&
                                kotlin.math.abs(it.year - year) <= 1
                        }
                        if (existingIdx >= 0) {
                            val existing = results[existingIdx]
                            if (existing.tvmazeId == 0) {
                                results[existingIdx] = existing.copy(
                                    externalId = if (existing.tmdbId > 0) existing.externalId else "tvmaze_$id",
                                    tvmazeId = id
                                )
                            }
                            continue
                        }
                        if (!canAddShow(0, showTitle, year)) continue
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
                                runtimeMins = (show.optInt("averageRuntime", show.optInt("runtime", 45)).takeIf { it > 0 } ?: 45).coerceAtLeast(5),
                                genresCsv = genres.ifBlank { "TV Show" },
                                rating = rating,
                                posterUrl = poster,
                                overview = stripHtml(show.optString("summary", ""))
                            )
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. IMDb + iTunes Fallback on page 1 when TMDB returned < 5 movies OR < 3 TV shows (e.g. Chandanamazha, Karuthamuthu, Parasparam)
        val tvShowCountBeforeImdb = results.count { it.mediaType == "show" }
        if (safePage == 1 && (tmdbMovieCount < 5 || tvShowCountBeforeImdb < 3)) {
            var tvShowCountNow = tvShowCountBeforeImdb
            try {
                val firstChar = q.firstOrNull()?.lowercaseChar()?.takeIf { it in 'a'..'z' || it in '0'..'9' } ?: 'x'
                val conn = (URL("https://v3.sg.media-imdb.com/suggestion/$firstChar/$encoded.json").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                if (conn.responseCode in 200..299) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val root = JSONObject(text)
                    val arr = root.optJSONArray("d") ?: JSONArray()

                    // Resolve top IMDb tvSeries tt* hit via TMDB /find when TMDB + TVMaze had 0 TV matches
                    if (tvShowCountNow == 0 && (tmdbToken.isNotEmpty() || tmdbKey.isNotEmpty())) {
                        for (i in 0 until minOf(2, arr.length())) {
                            val imObj = arr.optJSONObject(i) ?: continue
                            val ttId = imObj.optString("id", "")
                            val qid = imObj.optString("qid", "").lowercase()
                            if (!ttId.startsWith("tt") || (qid != "tvseries" && qid != "tvminiseries")) continue
                            try {
                                val findBase = "https://api.themoviedb.org/3/find/$ttId?external_source=imdb_id&language=en-US"
                                val findUrl = if (tmdbToken.isNotEmpty()) findBase else "$findBase&api_key=$tmdbKey"
                                val fConn = (URL(findUrl).openConnection() as HttpURLConnection).apply {
                                    requestMethod = "GET"
                                    setRequestProperty("Accept", "application/json")
                                    if (tmdbToken.isNotEmpty()) setRequestProperty("Authorization", "Bearer $tmdbToken")
                                    connectTimeout = 4000
                                    readTimeout = 4000
                                }
                                if (fConn.responseCode in 200..299) {
                                    val fRoot = JSONObject(fConn.inputStream.bufferedReader().use { it.readText() })
                                    val tvArr = fRoot.optJSONArray("tv_results") ?: JSONArray()
                                    for (ti in 0 until tvArr.length()) {
                                        val tv = tvArr.optJSONObject(ti) ?: continue
                                        val tid = tv.optInt("id", 0)
                                        val tTitle = tv.optString("name", tv.optString("original_name", imObj.optString("l", "")))
                                        val tYear = tv.optString("first_air_date", "").take(4).toIntOrNull() ?: imObj.optInt("y", 2024)
                                        if (tid <= 0 || !canAddShow(tid, tTitle, tYear)) continue
                                        tvShowCountNow++
                                        val pPath = tv.optString("poster_path", "")
                                        val poster = if (pPath.isNotBlank() && pPath != "null") {
                                            "https://image.tmdb.org/t/p/w500$pPath"
                                        } else {
                                            imObj.optJSONObject("i")?.optString("imageUrl", "") ?: ""
                                        }
                                        val rating = (tv.optDouble("vote_average", 8.0).takeIf { !it.isNaN() && it > 0 } ?: 8.0)
                                        results.add(
                                            SearchResultItem(
                                                externalId = "tmdb_tv_$tid",
                                                tvmazeId = 0,
                                                tmdbId = tid,
                                                mediaType = "show",
                                                title = tTitle,
                                                year = tYear,
                                                networkOrDirector = "Streaming",
                                                status = "Running",
                                                runtimeMins = 45,
                                                genresCsv = "TV Series",
                                                rating = Math.round(rating * 10.0) / 10.0,
                                                posterUrl = poster,
                                                overview = stripHtml(tv.optString("overview", ""))
                                            )
                                        )
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    // Resolve top IMDb movie tt* hit to canonical TMDB ID when TMDB direct search missed a transliterated spelling
                    if (tmdbMovieCount == 0 && (tmdbToken.isNotEmpty() || tmdbKey.isNotEmpty())) {
                        for (i in 0 until minOf(2, arr.length())) {
                            val imObj = arr.optJSONObject(i) ?: continue
                            val ttId = imObj.optString("id", "")
                            val qid = imObj.optString("qid", "").lowercase()
                            if (!ttId.startsWith("tt") || (qid != "movie" && qid != "feature" && qid != "tvmovie")) continue
                            try {
                                val findBase = "https://api.themoviedb.org/3/find/$ttId?external_source=imdb_id&language=en-US"
                                val findUrl = if (tmdbToken.isNotEmpty()) findBase else "$findBase&api_key=$tmdbKey"
                                val fConn = (URL(findUrl).openConnection() as HttpURLConnection).apply {
                                    requestMethod = "GET"
                                    setRequestProperty("Accept", "application/json")
                                    if (tmdbToken.isNotEmpty()) setRequestProperty("Authorization", "Bearer $tmdbToken")
                                    connectTimeout = 4000
                                    readTimeout = 4000
                                }
                                if (fConn.responseCode in 200..299) {
                                    val fRoot = JSONObject(fConn.inputStream.bufferedReader().use { it.readText() })
                                    val movArr = fRoot.optJSONArray("movie_results") ?: JSONArray()
                                    for (mi in 0 until movArr.length()) {
                                        val tm = movArr.optJSONObject(mi) ?: continue
                                        val mid = tm.optInt("id", 0)
                                        val mTitle = tm.optString("title", tm.optString("original_title", imObj.optString("l", "")))
                                        val mYear = tm.optString("release_date", "").take(4).toIntOrNull() ?: imObj.optInt("y", 2024)
                                        if (mid <= 0 || !canAddMovie(mid, mTitle, mYear)) continue
                                        tmdbMovieCount++
                                        val pPath = tm.optString("poster_path", "")
                                        val poster = if (pPath.isNotBlank() && pPath != "null") {
                                            "https://image.tmdb.org/t/p/w500$pPath"
                                        } else {
                                            imObj.optJSONObject("i")?.optString("imageUrl", "") ?: ""
                                        }
                                        val rating = (tm.optDouble("vote_average", 7.8).takeIf { !it.isNaN() && it > 0 } ?: 7.8)
                                        results.add(
                                            SearchResultItem(
                                                externalId = "movie_$mid",
                                                tmdbId = mid,
                                                mediaType = "movie",
                                                title = mTitle,
                                                year = mYear,
                                                networkOrDirector = "TMDB Feature Film",
                                                status = "Released",
                                                runtimeMins = 125,
                                                genresCsv = "Movie",
                                                rating = Math.round(rating * 10.0) / 10.0,
                                                posterUrl = poster,
                                                overview = stripHtml(tm.optString("overview", ""))
                                            )
                                        )
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    for (i in 0 until arr.length()) {
                        val m = arr.optJSONObject(i) ?: continue
                        val id = m.optString("id", "")
                        val title = m.optString("l", "")
                        val qid = m.optString("qid", "").lowercase()
                        if (id.isBlank() || title.isBlank()) continue
                        if (tvShowCountNow < 3 && (qid == "tvseries" || qid == "tvminiseries")) {
                            val year = m.optInt("y", 2024)
                            if (!canAddShow(0, title, year)) continue
                            val wiki = lookupWikipediaShowMeta(title = title, year = year, imdbId = id)
                            if (!wiki.isIndianShow || wiki.totalEpisodesOverride < 5) continue
                            tvShowCountNow++
                            val stars = m.optString("s", "")
                            val poster = (m.optJSONObject("i")?.optString("imageUrl", "") ?: "").ifBlank { wiki.posterOverride }
                            results.add(
                                SearchResultItem(
                                    externalId = "imdb_tv_$id",
                                    tvmazeId = 0,
                                    tmdbId = 0,
                                    mediaType = "show",
                                    title = title,
                                    year = year,
                                    networkOrDirector = wiki.networkOverride.ifBlank { "Television" },
                                    status = wiki.statusOverride.ifBlank { "Ended" },
                                    runtimeMins = wiki.runtimeMinsOverride.takeIf { it > 0 } ?: 22,
                                    genresCsv = "TV Series",
                                    castCsv = stars,
                                    rating = 8.0,
                                    posterUrl = poster,
                                    overview = wiki.overviewOverride.ifBlank { if (stars.isNotBlank()) "TV Series · Starring $stars" else "TV Series" },
                                    totalEpisodes = wiki.totalEpisodesOverride,
                                    countOnly = true
                                )
                            )
                        } else if (tmdbMovieCount < 5 && (qid == "movie" || qid == "feature" || qid == "tvmovie")) {
                            val year = m.optInt("y", 2024)
                            if (!canAddMovie(0, title, year)) continue
                            val stars = m.optString("s", "")
                            val poster = m.optJSONObject("i")?.optString("imageUrl", "") ?: ""
                            results.add(
                                SearchResultItem(
                                    externalId = "movie_$id",
                                    tmdbId = 0,
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

            if (tmdbMovieCount < 5) {
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
                        if (!canAddMovie(0, title, year)) continue
                        val runtimeMins = (m.optLong("trackTimeMillis", 7200000L) / 60000L).toInt().coerceAtLeast(45)
                        val poster = m.optString("artworkUrl100", "").replace("100x100bb.jpg", "600x900bb.jpg")
                        results.add(
                            SearchResultItem(
                                externalId = "movie_itunes_$trackId",
                                tmdbId = 0,
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
            }
        }

        SearchPageResult(items = results, hasMore = hasMore)
    }

    data class ShowGuideEnrichment(
        val episodes: List<EpisodeModel>,
        val cast: List<String>,
        val totalEpisodesOverride: Int = 0,
        val runtimeMinsOverride: Int = 0,
        val overviewOverride: String = "",
        val posterOverride: String = "",
        val networkOverride: String = "",
        val statusOverride: String = "",
        val isIndianShow: Boolean = false,
        val countOnly: Boolean = false
    )

    private val WIKI_NUM_EPS_REGEX = Regex("num_episodes\\s*=\\s*(?:\\{\\{[^}|]*\\|)?\\s*([0-9][0-9,]*)", RegexOption.IGNORE_CASE)
    private val WIKI_RUNTIME_REGEX = Regex("runtime\\s*=\\s*[^0-9\\n]{0,25}([0-9]{2,3})(?:\\s*[-\\u2013\\u2014]\\s*([0-9]{2,3}))?", RegexOption.IGNORE_CASE)
    private val WIKI_COUNTRY_REGEX = Regex("\\|\\s*country\\s*=\\s*([^\\n|]+)", RegexOption.IGNORE_CASE)
    private val WIKI_NETWORK_BLOCK_REGEX = Regex("\\|\\s*network\\s*=\\s*([\\s\\S]{1,260}?)(?=\\n\\s*\\||\\n\\}\\})", RegexOption.IGNORE_CASE)
    private val WIKI_LINK_REGEX = Regex("\\[\\[([^\\]|]+)(?:\\|([^\\]]+))?\\]\\]")
    private val WIKI_REF_TAG_REGEX = Regex("<ref[\\s\\S]*?(?:</ref>|/>)", RegexOption.IGNORE_CASE)
    private val WIKI_TABLE_REGEX = Regex("\\{\\|[\\s\\S]*?\\|\\}")
    private val WIKI_DATE_DMY_REGEX = Regex("\\b([0-9]{1,2})\\s+([A-Za-z]+)\\s+([12][0-9]{3})\\b")
    private val WIKI_DATE_MDY_REGEX = Regex("\\b([A-Za-z]+)\\s+([0-9]{1,2}),?\\s+([12][0-9]{3})\\b")
    private val PURE_DIGITS_REGEX = Regex("^([0-9]{2,5})$")
    private val INDIAN_SERIES_META_REGEX = Regex(
        "\\b(india|indian|malayalam|tamil|telugu|hindi|kannada|bengali|marathi|gujarati|punjabi|odia|assamese|urdu)\\b",
        RegexOption.IGNORE_CASE
    )
    private val INDIAN_LANG_SET = setOf("hi", "ml", "ta", "te", "kn", "bn", "mr", "pa", "gu", "ur", "or", "as")
    private val knownNonIndianTmdbShows = java.util.Collections.synchronizedSet(HashSet<Int>())
    private val knownIndianTmdbShows = java.util.Collections.synchronizedSet(HashSet<Int>())

    private fun isIndianSeriesMetadata(text: String): Boolean =
        text.isNotBlank() && INDIAN_SERIES_META_REGEX.containsMatchIn(text)

    private fun hasIndianWikiCategories(cats: JSONArray?): Boolean {
        if (cats == null) return false
        for (i in 0 until cats.length()) {
            if (isIndianSeriesMetadata(cats.optJSONObject(i)?.optString("title", "").orEmpty())) return true
        }
        return false
    }

    private fun parseWikiDateEpochDays(raw: String): Long {
        val clean = raw.replace("[[", " ").replace("]]", " ").replace(Regex("<[^>]*>"), " ").trim()
        val months = mapOf(
            "january" to 1, "february" to 2, "march" to 3, "april" to 4, "may" to 5, "june" to 6,
            "july" to 7, "august" to 8, "september" to 9, "october" to 10, "november" to 11, "december" to 12
        )
        WIKI_DATE_DMY_REGEX.find(clean)?.let { m ->
            val d = m.groupValues[1].toIntOrNull() ?: return@let
            val mo = months[m.groupValues[2].lowercase()] ?: return@let
            val y = m.groupValues[3].toIntOrNull() ?: return@let
            return runCatching { java.time.LocalDate.of(y, mo, d).toEpochDay() }.getOrDefault(0L)
        }
        WIKI_DATE_MDY_REGEX.find(clean)?.let { m ->
            val mo = months[m.groupValues[1].lowercase()] ?: return@let
            val d = m.groupValues[2].toIntOrNull() ?: return@let
            val y = m.groupValues[3].toIntOrNull() ?: return@let
            return runCatching { java.time.LocalDate.of(y, mo, d).toEpochDay() }.getOrDefault(0L)
        }
        return 0L
    }

    private fun parseSeasonTableEpisodes(wt: String, queryTitle: String, pageTitle: String): Int {
        if (wt.isBlank()) return 0
        val nonAlnum = Regex("[^a-z0-9]+")
        val queryWords = queryTitle.lowercase().replace(nonAlnum, " ").trim().split(Regex("\\s+")).filter { it.length >= 3 }
        val pageWords = pageTitle.lowercase().replace(nonAlnum, " ").trim().split(Regex("\\s+")).toSet()
        val sponsorTokens = queryWords.filter { !pageWords.contains(it) && it != "series" && it != "the" }
        val todayEpoch = java.time.LocalDate.now().toEpochDay()

        for (tbMatch in WIKI_TABLE_REGEX.findAll(wt)) {
            val cleanTb = tbMatch.value.replace(WIKI_REF_TAG_REGEX, "")
            val rows = cleanTb.split("\n|-")
            val header = rows.firstOrNull().orEmpty()
            if (!header.contains("season", ignoreCase = true)) continue
            val hasEpCol = Regex("\\bepisodes?\\b", RegexOption.IGNORE_CASE).containsMatchIn(header)
            val hasAiredCols = Regex("aired|original\\s+release", RegexOption.IGNORE_CASE).containsMatchIn(header)
            if (!hasEpCol && !hasAiredCols) continue

            var headerEpIdx = -1
            if (hasEpCol) {
                val hCells = header.split(Regex("!!|\\n!")).map { it.trimStart('!', '|', ' ').trim() }.filter { it.isNotEmpty() }
                headerEpIdx = hCells.indexOfFirst { Regex("\\bepisodes?\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            }

            var totalAll = 0
            var totalSponsor = 0
            for (ri in 1 until rows.size) {
                val row = rows[ri]
                val cells = row.split(Regex("\\|\\||\\n\\|")).map { it.trim() }.filter { it.isNotEmpty() }
                if (cells.size < 2) continue
                var rowEps = 0
                if (headerEpIdx in cells.indices) {
                    rowEps = cells[headerEpIdx].replace(",", "").trim().toIntOrNull()?.takeIf { it in 1..5000 } ?: 0
                }
                if (rowEps == 0 && hasAiredCols) {
                    val dates = cells.mapNotNull { parseWikiDateEpochDays(it).takeIf { d -> d > 0L } }
                    if (dates.size >= 2 && dates[1] > dates[0]) {
                        val startD = dates[0]
                        val endD = minOf(dates[1], todayEpoch)
                        if (endD > startD) {
                            val rl = row.lowercase()
                            val perWeek = when {
                                Regex("mon[^a-z0-9]{1,5}sat").containsMatchIn(rl) -> 6
                                Regex("mon[^a-z0-9]{1,5}fri").containsMatchIn(rl) -> 5
                                rl.contains("daily") -> 7
                                else -> 2
                            }
                            rowEps = Math.round(((endD - startD) / 7.0) * perWeek).toInt().coerceAtLeast(1)
                        }
                    }
                }
                if (rowEps > 0) {
                    totalAll += rowEps
                    if (sponsorTokens.isNotEmpty() && sponsorTokens.all { row.lowercase().contains(it) }) {
                        totalSponsor += rowEps
                    }
                }
            }
            if (totalSponsor >= 5) return totalSponsor
            if (totalAll >= 5) return totalAll
        }
        return 0
    }

    suspend fun lookupWikipediaShowMeta(
        title: String,
        year: Int = 0,
        imdbId: String = ""
    ): ShowGuideEnrichment = withContext(Dispatchers.IO) {
        val cleanTitle = title.trim()
        val cleanImdb = imdbId.trim().lowercase().takeIf { it.startsWith("tt") && it.drop(2).all(Char::isDigit) }.orEmpty()
        var totalEps = 0
        var runtime = 0
        var network = ""
        var overview = ""
        var poster = ""
        var status = ""
        var isIndian = false

        fun openWiki(url: String): JSONObject? {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "MovieDBTracker/1.0 (https://moviedb-website.madhavsankar.workers.dev)")
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (conn.responseCode !in 200..299) return null
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        }

        fun parseInfobox(wt: String) {
            if (wt.isBlank()) return
            if (isIndianSeriesMetadata(wt)) isIndian = true
            WIKI_NUM_EPS_REGEX.find(wt)?.groupValues?.getOrNull(1)?.replace(",", "")?.toIntOrNull()?.let { n ->
                if (n in 2..25000) totalEps = n
            }
            WIKI_RUNTIME_REGEX.find(wt)?.let { m ->
                val r1 = m.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
                val r2 = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
                if (r1 in 10..180) {
                    runtime = if (r2 in r1..240) Math.round((r1 + r2) / 2.0).toInt() else r1
                }
            }
            WIKI_COUNTRY_REGEX.find(wt)?.let { mc ->
                if (isIndianSeriesMetadata(mc.groupValues.getOrNull(1).orEmpty())) isIndian = true
            }
            WIKI_NETWORK_BLOCK_REGEX.find(wt)?.let { mb ->
                if (network.isEmpty()) {
                    val block = mb.groupValues.getOrNull(1).orEmpty()
                    val lm = WIKI_LINK_REGEX.find(block)
                    val rawNetStr = if (lm != null) {
                        lm.groupValues.getOrNull(2).orEmpty().ifBlank { lm.groupValues.getOrNull(1).orEmpty() }.trim()
                    } else {
                        block.replace(Regex("\\{\\{[^|}]*\\|?"), "").replace("}}", "").lineSequence().firstOrNull().orEmpty().trimStart('*', ' ').trim()
                    }
                    val cleanNetStr = rawNetStr.replace(Regex("\\s*\\(TV channel\\)\\s*", RegexOption.IGNORE_CASE), "").trim()
                    if (cleanNetStr.isNotEmpty() && cleanNetStr.length <= 45) {
                        network = cleanNetStr
                    }
                }
            }
            if (wt.contains("present", ignoreCase = true) && wt.contains("last_aired", ignoreCase = true)) {
                status = "Running"
            } else if (wt.contains("last_aired", ignoreCase = true)) {
                status = "Ended"
            }
        }

        // 1. Direct Wikipedia Infobox lookup
        var matchedInfoboxPageId = 0
        var matchedInfoboxTitle = ""
        if (cleanTitle.isNotEmpty()) {
            try {
                val cands = mutableListOf(cleanTitle, "$cleanTitle (TV series)")
                if (year > 1950) cands.add("$cleanTitle ($year TV series)")
                if (cleanTitle.contains(":")) {
                    val afterColon = cleanTitle.substringAfter(":").trim()
                    if (afterColon.length >= 4) {
                        cands.add(afterColon)
                        cands.add("$afterColon (TV series)")
                    }
                }
                val encTitles = URLEncoder.encode(cands.joinToString("|"), "UTF-8")
                val root = openWiki(
                    "https://en.wikipedia.org/w/api.php?action=query&titles=$encTitles&prop=revisions|extracts|pageimages|categories&cllimit=30&exintro=1&explaintext=1&rvprop=content&rvsection=0&piprop=thumbnail&pithumbsize=500&format=json&redirects=1&origin=*"
                )
                val pagesObj = root?.optJSONObject("query")?.optJSONObject("pages")
                if (pagesObj != null) {
                    val pageList = mutableListOf<JSONObject>()
                    val keys = pagesObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if ((k.toIntOrNull() ?: -1) > 0) {
                            pagesObj.optJSONObject(k)?.let { pageList.add(it) }
                        }
                    }
                    pageList.sortByDescending { if (it.optString("title", "").contains("TV series", ignoreCase = true)) 1 else 0 }
                    for (page in pageList) {
                        val rev = page.optJSONArray("revisions")?.optJSONObject(0)
                        val wt = rev?.optJSONObject("slots")?.optJSONObject("main")?.optString("*", "")
                            ?: rev?.optString("*", "")
                            ?: ""
                        if (!wt.contains("Infobox television", ignoreCase = true) && !wt.contains("num_episodes", ignoreCase = true)) {
                            continue
                        }
                        parseInfobox(wt)
                        if (hasIndianWikiCategories(page.optJSONArray("categories"))) isIndian = true
                        val extStr = page.optString("extract", "").trim()
                        if (isIndianSeriesMetadata(extStr) || isIndianSeriesMetadata(page.optString("title", ""))) {
                            isIndian = true
                        }
                        if (overview.isEmpty()) overview = extStr.take(500)
                        if (poster.isEmpty()) poster = page.optJSONObject("thumbnail")?.optString("source", "").orEmpty()
                        matchedInfoboxPageId = page.optInt("pageid", 0)
                        matchedInfoboxTitle = page.optString("title", "")
                        if (totalEps > 0) break
                    }
                }
            } catch (_: Exception) {}
        }

        // 1b. Parse full Wikipedia Season Table generically when Indian Infobox lacked `num_episodes`
        if (totalEps == 0 && isIndian && matchedInfoboxPageId > 0) {
            try {
                val fRoot = openWiki("https://en.wikipedia.org/w/api.php?action=query&pageids=$matchedInfoboxPageId&prop=revisions&rvprop=content&format=json&origin=*")
                val fPage = fRoot?.optJSONObject("query")?.optJSONObject("pages")?.optJSONObject(matchedInfoboxPageId.toString())
                val fRev = fPage?.optJSONArray("revisions")?.optJSONObject(0)
                val fullWt = fRev?.optJSONObject("slots")?.optJSONObject("main")?.optString("*", "")
                    ?: fRev?.optString("*", "")
                    ?: ""
                val tableEps = parseSeasonTableEpisodes(fullWt, cleanTitle, matchedInfoboxTitle)
                if (tableEps >= 15) {
                    totalEps = tableEps
                    if (status.isEmpty()) status = "Ended"
                }
            } catch (_: Exception) {}
        }

        // 2. Wikipedia Broadcaster Program List / Search Fallback
        if (totalEps == 0 && cleanTitle.isNotEmpty()) {
            try {
                val sQuery = URLEncoder.encode("\"$cleanTitle\" episodes", "UTF-8")
                val sRoot = openWiki("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$sQuery&srlimit=6&format=json&origin=*")
                val sArr = sRoot?.optJSONObject("query")?.optJSONArray("search") ?: JSONArray()
                val hitTitles = (0 until minOf(6, sArr.length()))
                    .mapNotNull { sArr.optJSONObject(it)?.optString("title", "")?.takeIf(String::isNotBlank) }
                    .sortedByDescending { if (it.startsWith("List of ", ignoreCase = true)) 1 else 0 }
                if (hitTitles.isNotEmpty()) {
                    val pRoot = openWiki(
                        "https://en.wikipedia.org/w/api.php?action=query&titles=${URLEncoder.encode(hitTitles.take(4).joinToString("|"), "UTF-8")}&prop=revisions|categories&cllimit=30&rvprop=content&format=json&redirects=1&origin=*"
                    )
                    val pagesObj = pRoot?.optJSONObject("query")?.optJSONObject("pages")
                    val titleRowRe = Regex("(?:'+'|\\[\\[(?:[^\\]|]*\\|)?)" + Regex.escape(cleanTitle) + "(?:'+'|\\]\\])", RegexOption.IGNORE_CASE)
                    if (pagesObj != null) {
                        val keys = pagesObj.keys()
                        while (keys.hasNext() && totalEps == 0) {
                            val page = pagesObj.optJSONObject(keys.next()) ?: continue
                            val rev = page.optJSONArray("revisions")?.optJSONObject(0)
                            val wt = rev?.optJSONObject("slots")?.optJSONObject("main")?.optString("*", "")
                                ?: rev?.optString("*", "")
                                ?: ""
                            if (wt.isBlank()) continue
                            val pTitle = page.optString("title", "")
                            val pageHasIndCat = hasIndianWikiCategories(page.optJSONArray("categories"))
                            parseInfobox(wt)
                            if (totalEps > 0) {
                                if (pageHasIndCat || isIndianSeriesMetadata(pTitle)) isIndian = true
                                break
                            }
                            val rows = wt.split("\n|-")
                            for (row in rows) {
                                if (!titleRowRe.containsMatchIn(row)) continue
                                val cells = row.replace(WIKI_REF_TAG_REGEX, "").split("\n|").map { it.trim() }
                                for (ci in cells.indices.reversed()) {
                                    val candNum = PURE_DIGITS_REGEX.find(cells[ci].replace(",", ""))?.groupValues?.getOrNull(1)?.toIntOrNull()
                                    if (candNum != null && candNum in 15..25000 && candNum != year) {
                                        totalEps = candNum
                                        if (status.isEmpty()) status = "Ended"
                                        if (pTitle.contains("broadcast by ", ignoreCase = true) && network.isEmpty()) {
                                            network = pTitle.substringAfter("broadcast by ", "").trim()
                                        }
                                        if (pageHasIndCat || isIndianSeriesMetadata(pTitle) || isIndianSeriesMetadata(row) || isIndianSeriesMetadata(wt.take(1500))) {
                                            isIndian = true
                                        }
                                        break
                                    }
                                }
                                if (totalEps > 0) break
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Wikidata SPARQL Fallback by IMDb ID (P345 -> P1113, P495 country of origin)
        if (totalEps == 0 && cleanImdb.isNotEmpty()) {
            try {
                val sparql = "SELECT ?episodes ?duration ?country WHERE { ?item wdt:P345 \"$cleanImdb\" . OPTIONAL { ?item wdt:P1113 ?episodes . } OPTIONAL { ?item wdt:P2047 ?duration . } OPTIONAL { ?item wdt:P495 ?country . } } LIMIT 1"
                val wdRoot = openWiki("https://query.wikidata.org/sparql?format=json&query=${URLEncoder.encode(sparql, "UTF-8")}")
                val b = wdRoot?.optJSONObject("results")?.optJSONArray("bindings")?.optJSONObject(0)
                val epVal = b?.optJSONObject("episodes")?.optString("value", "")?.toIntOrNull() ?: 0
                if (epVal in 2..25000) totalEps = epVal
                val durVal = b?.optJSONObject("duration")?.optString("value", "")?.toIntOrNull() ?: 0
                if (durVal in 10..180 && runtime == 0) runtime = durVal
                if (b?.optJSONObject("country")?.optString("value", "")?.endsWith("/Q668") == true) {
                    isIndian = true
                }
            } catch (_: Exception) {}
        }

        ShowGuideEnrichment(
            episodes = emptyList(),
            cast = emptyList(),
            totalEpisodesOverride = totalEps,
            runtimeMinsOverride = runtime,
            overviewOverride = overview,
            posterOverride = poster,
            networkOverride = network,
            statusOverride = status,
            isIndianShow = isIndian
        )
    }

    suspend fun fetchFullShowGuide(
        tvmazeId: Int,
        tmdbId: Int = 0,
        title: String = "",
        externalId: String = "",
        year: Int = 0
    ): ShowGuideEnrichment = withContext(Dispatchers.IO) {
        val isImdbTv = externalId.startsWith("imdb_tv_tt", ignoreCase = true)
        val imdbId = if (isImdbTv) externalId.removePrefix("imdb_tv_") else ""
        val resolvedTmdbId = when {
            tmdbId > 0 -> tmdbId
            externalId.startsWith("tmdb_tv_") -> externalId.removePrefix("tmdb_tv_").toIntOrNull() ?: 0
            else -> 0
        }
        val (eps, cast) = if (isImdbTv) {
            emptyList<EpisodeModel>() to emptyList<String>()
        } else {
            fetchFullShowEpisodesAndCast(tvmazeId = tvmazeId, tmdbId = tmdbId, title = title, externalId = externalId)
        }
        val populatedSeasons = eps.map { it.season }.toSet().size
        val hasDetailedSeasons = populatedSeasons >= 2
        val explicitlyNonIndian = eps.isNotEmpty() && resolvedTmdbId > 0 && knownNonIndianTmdbShows.contains(resolvedTmdbId)
        val explicitlyIndian = resolvedTmdbId > 0 && knownIndianTmdbShows.contains(resolvedTmdbId)
        val lacksDetailedGuide = !hasDetailedSeasons && (eps.size <= 1 || (explicitlyIndian && eps.size <= 35))
        if (lacksDetailedGuide && !explicitlyNonIndian && (isImdbTv || title.isNotBlank())) {
            val wiki = lookupWikipediaShowMeta(title = title, year = year, imdbId = imdbId)
            val meetsSerialThreshold = if (eps.size <= 1) {
                wiki.totalEpisodesOverride >= 15
            } else {
                wiki.totalEpisodesOverride >= 50 && wiki.totalEpisodesOverride >= eps.size * 4
            }
            if ((explicitlyIndian || wiki.isIndianShow) && meetsSerialThreshold) {
                return@withContext wiki.copy(
                    episodes = emptyList(),
                    cast = cast,
                    runtimeMinsOverride = wiki.runtimeMinsOverride.takeIf { it > 0 } ?: 22,
                    countOnly = true
                )
            }
        }
        ShowGuideEnrichment(
            episodes = eps,
            cast = cast,
            totalEpisodesOverride = eps.size,
            countOnly = false
        )
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
                    val origLang = tvJson.optString("original_language", "").trim().lowercase()
                    val originArr = tvJson.optJSONArray("origin_country") ?: JSONArray()
                    val originSet = (0 until originArr.length()).mapNotNull { originArr.optString(it, "").uppercase().takeIf(String::isNotEmpty) }.toSet()
                    val rawNetName = tvJson.optJSONArray("networks")?.optJSONObject(0)?.optString("name", "").orEmpty()
                    val isIndianTmdb = originSet.contains("IN") || INDIAN_LANG_SET.contains(origLang)
                    if (isIndianTmdb) {
                        knownIndianTmdbShows.add(resolvedTmdbId)
                    } else if ((originSet.isNotEmpty() && !originSet.contains("IO")) || origLang.isNotEmpty()) {
                        if (!originSet.contains("IO")) knownNonIndianTmdbShows.add(resolvedTmdbId)
                    }
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
                    val seenCodes = mutableSetOf<String>()
                    fun appendEpisodesArray(sNum: Int, epsArr: JSONArray) {
                        for (j in 0 until epsArr.length()) {
                            val ep = epsArr.optJSONObject(j) ?: continue
                            val eNum = ep.optInt("episode_number", j + 1).coerceAtLeast(1)
                            val code = EpisodeModel.formatCode(sNum, eNum)
                            if (!seenCodes.add(code.uppercase())) continue
                            val stillPath = ep.optString("still_path", "")
                            val thumb = if (stillPath.isNotBlank() && stillPath != "null") {
                                "https://image.tmdb.org/t/p/w300$stillPath"
                            } else ""
                            episodes.add(
                                EpisodeModel(
                                    code = code,
                                    season = sNum,
                                    number = eNum,
                                    title = ep.optString("name", "Episode $eNum"),
                                    airDate = ep.optString("air_date", ""),
                                    runtimeMins = (ep.optInt("runtime", defaultRuntime).takeIf { it > 0 } ?: defaultRuntime).coerceAtLeast(5),
                                    imageUrl = thumb,
                                    overview = stripHtml(ep.optString("overview", ""))
                                )
                            )
                        }
                    }

                    val sortedSeasons = seasonNums.distinct().sorted()
                    val missingSeasons = mutableListOf<Int>()
                    for (chunk in sortedSeasons.chunked(20)) {
                        var batchJson: JSONObject? = null
                        try {
                            val appendSeasons = chunk.joinToString(",") { "season/$it" }
                            val bConn = openTmdb("/tv/$resolvedTmdbId?append_to_response=$appendSeasons&language=en-US")
                            if (bConn.responseCode in 200..299) {
                                batchJson = JSONObject(bConn.inputStream.bufferedReader().use { it.readText() })
                            }
                        } catch (_: Exception) {}

                        for (sNum in chunk) {
                            val sJson = batchJson?.optJSONObject("season/$sNum")
                            val epsArr = sJson?.optJSONArray("episodes")
                            if (epsArr != null) {
                                appendEpisodesArray(sNum, epsArr)
                            } else {
                                missingSeasons.add(sNum)
                            }
                        }
                    }

                    for (sNum in missingSeasons.take(20)) {
                        try {
                            val sConn = openTmdb("/tv/$resolvedTmdbId/season/$sNum?language=en-US")
                            if (sConn.responseCode in 200..299) {
                                val sJson = JSONObject(sConn.inputStream.bufferedReader().use { it.readText() })
                                val epsArr = sJson.optJSONArray("episodes") ?: JSONArray()
                                appendEpisodesArray(sNum, epsArr)
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
                                    runtimeMins = (nextToAir.optInt("runtime", defaultRuntime).takeIf { it > 0 } ?: defaultRuntime).coerceAtLeast(5),
                                    imageUrl = thumb,
                                    overview = stripHtml(nextToAir.optString("overview", ""))
                                )
                            )
                        }
                    }
                    if (episodes.isNotEmpty()) {
                        return@withContext episodes.sortedWith(compareBy({ it.season }, { it.number })) to castNames
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
                        runtimeMins = (e.optInt("runtime", 45).takeIf { it > 0 } ?: 45).coerceAtLeast(5),
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
        val genresCsv: String,
        val overview: String = ""
    )

    suspend fun fetchShowOverview(
        tvmazeId: Int,
        tmdbId: Int = 0,
        title: String = "",
        externalId: String = ""
    ): String = withContext(Dispatchers.IO) {
        val tmdbToken = BuildConfig.TMDB_READ_TOKEN.trim()
        val tmdbKey = BuildConfig.TMDB_API_KEY.trim()
        val resolvedTmdbId = when {
            tmdbId > 0 -> tmdbId
            externalId.startsWith("tmdb_tv_") -> externalId.removePrefix("tmdb_tv_").toIntOrNull() ?: 0
            externalId.startsWith("tmdb_show_") -> externalId.removePrefix("tmdb_show_").toIntOrNull() ?: 0
            else -> 0
        }
        if (resolvedTmdbId > 0 && (tmdbToken.isNotEmpty() || tmdbKey.isNotEmpty())) {
            try {
                val base = "https://api.themoviedb.org/3/tv/$resolvedTmdbId?language=en-US"
                val url = if (tmdbToken.isNotEmpty()) base else "$base&api_key=$tmdbKey"
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                    if (tmdbToken.isNotEmpty()) setRequestProperty("Authorization", "Bearer $tmdbToken")
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                if (conn.responseCode in 200..299) {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val ov = stripHtml(json.optString("overview", ""))
                    if (ov.isNotBlank()) return@withContext ov
                }
            } catch (_: Exception) {}
        }
        val resolvedTvmazeId = when {
            tvmazeId > 0 -> tvmazeId
            externalId.startsWith("tvmaze_") -> externalId.removePrefix("tvmaze_").toIntOrNull() ?: 0
            else -> 0
        }
        if (resolvedTvmazeId > 0) {
            try {
                val conn = (URL("https://api.tvmaze.com/shows/$resolvedTvmazeId").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                if (conn.responseCode in 200..299) {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val ov = stripHtml(json.optString("summary", ""))
                    if (ov.isNotBlank()) return@withContext ov
                }
            } catch (_: Exception) {}
        }
        ""
    }

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

        var resolvedId = TvTimeProgressCalculator.cleanMovieTmdbId(externalId, tmdbId)
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
                val overview = stripHtml(movJson.optString("overview", ""))

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
                    genresCsv = genresList.joinToString(", "),
                    overview = overview
                )
            }
        } catch (_: Exception) {}
        null
    }

    fun buildStarterShows(userId: String): List<ShowEntity> = emptyList()

    fun buildStarterMovies(userId: String): List<MovieEntity> = emptyList()

    fun buildStarterActors(userId: String): List<ActorEntity> = emptyList()
}

