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
                        } else if (mType == "person") {
                            val name = item.optString("name", "")
                            if (name.isBlank()) continue
                            val profilePath = item.optString("profile_path", "")
                            val img = if (profilePath.isNotBlank() && profilePath != "null") "https://image.tmdb.org/t/p/w300$profilePath" else ""
                            results.add(
                                SearchResultItem(
                                    externalId = "actor_tmdb_$id",
                                    tmdbId = id,
                                    mediaType = "actor",
                                    title = name,
                                    year = 1985,
                                    networkOrDirector = item.optString("known_for_department", "Acting"),
                                    posterUrl = img,
                                    imdbUrl = "https://www.imdb.com/find/?q=${URLEncoder.encode(name, "UTF-8")}",
                                    overview = "Actor"
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

        // 3. TVMaze Actors Search
        try {
            val conn = (URL("https://api.tvmaze.com/search/people?q=$encoded").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (conn.responseCode in 200..299) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONArray(text)
                for (i in 0 until minOf(4, arr.length())) {
                    val p = arr.optJSONObject(i)?.optJSONObject("person") ?: continue
                    val id = p.optInt("id", 0)
                    val name = p.optString("name", "")
                    if (id <= 0 || name.isBlank()) continue
                    val imgObj = p.optJSONObject("image")
                    val poster = (imgObj?.optString("medium") ?: imgObj?.optString("original") ?: "")
                        .replace("http://", "https://")
                    results.add(
                        SearchResultItem(
                            externalId = "actor_$id",
                            mediaType = "actor",
                            title = name,
                            year = p.optString("birthday", "1985").take(4).toIntOrNull() ?: 1985,
                            networkOrDirector = "Acting",
                            posterUrl = poster,
                            overview = "Actor · Follow for upcoming release alerts",
                            imdbUrl = "https://www.imdb.com/find/?q=${URLEncoder.encode(name, "UTF-8")}"
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        results
    }

    /**
     * Fetches the full episode guide & cast list for a TVMaze show ID (`/shows/{id}?embed[]=episodes&embed[]=cast`).
     */
    suspend fun fetchFullShowEpisodesAndCast(tvmazeId: Int): Pair<List<EpisodeModel>, List<String>> = withContext(Dispatchers.IO) {
        if (tvmazeId <= 0) return@withContext emptyList<EpisodeModel>() to emptyList<String>()
        try {
            val url = "https://api.tvmaze.com/shows/$tvmazeId?embed[]=episodes&embed[]=cast"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
            }
            if (conn.responseCode in 200..299) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(text)
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
                return@withContext episodes to castNames
            }
        } catch (_: Exception) {}
        emptyList<EpisodeModel>() to emptyList<String>()
    }

    fun buildStarterShows(userId: String): List<ShowEntity> {
        val severanceEps = listOf(
            EpisodeModel("S01E01", 1, 1, "Good News About Hell", "2022-02-18", 57),
            EpisodeModel("S01E02", 1, 2, "Half Loop", "2022-02-18", 47),
            EpisodeModel("S01E03", 1, 3, "In Perpetuity", "2022-02-25", 52),
            EpisodeModel("S01E04", 1, 4, "The You You Are", "2022-03-04", 46),
            EpisodeModel("S01E05", 1, 5, "The Grim Barbarity of Optics and Design", "2022-03-11", 43),
            EpisodeModel("S01E06", 1, 6, "Hide and Seek", "2022-03-18", 47),
            EpisodeModel("S01E07", 1, 7, "Defiant Jazz", "2022-03-25", 49),
            EpisodeModel("S01E08", 1, 8, "What's for Dinner?", "2022-04-01", 48),
            EpisodeModel("S01E09", 1, 9, "The We We Are", "2022-04-08", 40),
            EpisodeModel("S02E01", 2, 1, "Hello, Ms. Cobel", "2025-01-17", 49),
            EpisodeModel("S02E02", 2, 2, "Goodbye, Mrs. Selvig", "2025-01-24", 47),
            EpisodeModel("S02E03", 2, 3, "Who Is Alive?", "2025-01-31", 53),
            EpisodeModel("S02E04", 2, 4, "Woe's Hollow", "2025-02-07", 51),
            EpisodeModel("S02E05", 2, 5, "Trojan's Horse", "2025-02-14", 48),
            EpisodeModel("S02E06", 2, 6, "Attila", "2025-02-21", 50)
        )
        val bearEps = listOf(
            EpisodeModel("S01E01", 1, 1, "System", "2022-06-23", 29),
            EpisodeModel("S01E02", 1, 2, "Hands", "2022-06-23", 30),
            EpisodeModel("S01E03", 1, 3, "Brigade", "2022-06-23", 25),
            EpisodeModel("S01E04", 1, 4, "Dogs", "2022-06-23", 29),
            EpisodeModel("S01E05", 1, 5, "Sheridan", "2022-06-23", 30),
            EpisodeModel("S01E06", 1, 6, "Ceres", "2022-06-23", 35),
            EpisodeModel("S01E07", 1, 7, "Review", "2022-06-23", 20),
            EpisodeModel("S01E08", 1, 8, "Braciole", "2022-06-23", 47)
        )
        val siloEps = listOf(
            EpisodeModel("S01E01", 1, 1, "Freedom Day", "2023-05-05", 59),
            EpisodeModel("S01E02", 1, 2, "Holston's Pick", "2023-05-05", 48),
            EpisodeModel("S01E03", 1, 3, "Machines", "2023-05-12", 62),
            EpisodeModel("S01E04", 1, 4, "Truth", "2023-05-19", 46),
            EpisodeModel("S01E05", 1, 5, "The Janitor's Boy", "2026-11-12", 51)
        )

        return listOf(
            ShowEntity(
                userId = userId,
                externalId = "tvmaze_44933",
                tvmazeId = 44933,
                tmdbId = 95396,
                title = "Severance",
                year = 2022,
                network = "Apple TV+",
                status = "Running",
                runtimeMins = 50,
                genresCsv = "Drama, Sci-Fi, Mystery",
                castCsv = "Adam Scott, Britt Lower, John Turturro, Patricia Arquette",
                rating = 8.7,
                posterUrl = "https://static.tvmaze.com/uploads/images/medium_portrait/548/1371406.jpg",
                overview = "Mark leads a team of office workers whose memories have been surgically divided between their work and personal lives.",
                userStatus = "watching",
                userRating = 5,
                reaction = "😍",
                isFavorite = true,
                watchedCodesCsv = "S01E01,S01E02,S01E03,S01E04,S01E05,S01E06,S01E07,S01E08,S01E09,S02E01,S02E02,S02E03",
                episodesJson = TvTimeProgressCalculator.serializeEpisodeList(severanceEps)
            ),
            ShowEntity(
                userId = userId,
                externalId = "tvmaze_54198",
                tvmazeId = 54198,
                tmdbId = 136315,
                title = "The Bear",
                year = 2022,
                network = "FX / Hulu",
                status = "Running",
                runtimeMins = 34,
                genresCsv = "Drama, Comedy",
                castCsv = "Jeremy Allen White, Ayo Edebiri, Ebon Moss-Bachrach",
                rating = 8.6,
                posterUrl = "https://static.tvmaze.com/uploads/images/medium_portrait/629/1574642.jpg",
                overview = "A young chef from the fine dining world comes home to Chicago to run his family sandwich shop.",
                userStatus = "watching",
                userRating = 5,
                reaction = "🔥",
                isFavorite = true,
                watchedCodesCsv = "S01E01,S01E02,S01E03,S01E04",
                episodesJson = TvTimeProgressCalculator.serializeEpisodeList(bearEps)
            ),
            ShowEntity(
                userId = userId,
                externalId = "tvmaze_38052",
                tvmazeId = 38052,
                tmdbId = 125988,
                title = "Silo",
                year = 2023,
                network = "Apple TV+",
                status = "Running",
                runtimeMins = 52,
                genresCsv = "Drama, Sci-Fi, Thriller",
                castCsv = "Rebecca Ferguson, Common, Tim Robbins",
                rating = 8.1,
                posterUrl = "https://static.tvmaze.com/uploads/images/medium_portrait/631/1577677.jpg",
                overview = "In a ruined and toxic future, thousands live in a giant silo deep underground.",
                userStatus = "watching",
                userRating = 4,
                reaction = "🤯",
                isFavorite = false,
                watchedCodesCsv = "",
                episodesJson = TvTimeProgressCalculator.serializeEpisodeList(siloEps)
            )
        )
    }

    fun buildStarterMovies(userId: String): List<MovieEntity> = listOf(
        MovieEntity(
            userId = userId,
            externalId = "movie_693134",
            tmdbId = 693134,
            title = "Dune: Part Two",
            year = 2024,
            runtimeMins = 166,
            genresCsv = "Sci-Fi, Adventure",
            castCsv = "Timothée Chalamet, Zendaya, Rebecca Ferguson",
            director = "Denis Villeneuve",
            streamingService = "Max",
            rating = 8.5,
            posterUrl = "https://m.media-amazon.com/images/M/MV5BNTc0YmQxMjEtODI5MC00NjFiLTlkMWUtOGQ5NjFmYWUyZGJhXkEyXkFqcGc@._V1_.jpg",
            overview = "Paul Atreides unites with Chani and the Fremen while on a warpath of revenge.",
            watched = true,
            watchedAt = "2026-09-12",
            userRating = 5,
            reaction = "😍",
            rewatchCount = 1,
            isFavorite = true
        ),
        MovieEntity(
            userId = userId,
            externalId = "movie_872585",
            tmdbId = 872585,
            title = "Oppenheimer",
            year = 2023,
            runtimeMins = 180,
            genresCsv = "Biography, Drama, History",
            castCsv = "Cillian Murphy, Emily Blunt, Matt Damon, Robert Downey Jr.",
            director = "Christopher Nolan",
            streamingService = "Peacock / Prime Video",
            rating = 8.9,
            posterUrl = "https://m.media-amazon.com/images/M/MV5BN2JkMDc5MGQtZjg3YS00NmFiLWIyZmQtZTJmNTM5MjVmYTQ4XkEyXkFqcGc@._V1_.jpg",
            overview = "The story of J. Robert Oppenheimer's role in the development of the atomic bomb.",
            watched = true,
            watchedAt = "2026-08-04",
            userRating = 5,
            reaction = "🤯",
            isFavorite = true
        ),
        MovieEntity(
            userId = userId,
            externalId = "movie_1022789",
            tmdbId = 1022789,
            title = "Conclave",
            year = 2024,
            runtimeMins = 120,
            genresCsv = "Drama, Thriller",
            castCsv = "Ralph Fiennes, Stanley Tucci, John Lithgow",
            director = "Edward Berger",
            streamingService = "Peacock",
            rating = 7.6,
            posterUrl = "https://m.media-amazon.com/images/M/MV5BYjgxMDI5NmMtNTU3OS00ZDQxLTgxZmEtNzY1ZTBmMDY4NDRkXkEyXkFqcGc@._V1_.jpg",
            overview = "Cardinal Lawrence is tasked with managing the covert election of a new Pope.",
            watched = false
        )
    )

    fun buildStarterActors(userId: String): List<ActorEntity> = listOf(
        ActorEntity(
            userId = userId,
            externalId = "actor_rebecca_ferguson",
            name = "Rebecca Ferguson",
            role = "Acting",
            imdbUrl = "https://www.imdb.com/find/?q=Rebecca+Ferguson",
            knownFor = "Silo, Dune: Part Two"
        ),
        ActorEntity(
            userId = userId,
            externalId = "actor_adam_scott",
            name = "Adam Scott",
            role = "Acting",
            imdbUrl = "https://www.imdb.com/find/?q=Adam+Scott",
            knownFor = "Severance, Parks and Recreation"
        ),
        ActorEntity(
            userId = userId,
            externalId = "actor_rami_malek",
            name = "Rami Malek",
            role = "Acting",
            imdbUrl = "https://www.imdb.com/find/?q=Rami+Malek",
            knownFor = "Mr. Robot, Oppenheimer"
        ),
        ActorEntity(
            userId = userId,
            externalId = "actor_bob_odenkirk",
            name = "Bob Odenkirk",
            role = "Acting",
            imdbUrl = "https://www.imdb.com/find/?q=Bob+Odenkirk",
            knownFor = "Better Call Saul, The Bear"
        )
    )
}

