package com.tvtime.tracker.data

import com.tvtime.tracker.BuildConfig
import com.tvtime.tracker.domain.EpisodeModel
import com.tvtime.tracker.domain.TvTimeProgressCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * Multi-User Cloud Firestore NoSQL Sync Service (`tvtime_state/user_state_{userId}`).
 *
 * Shares the same free Firebase Cloud Firestore instance as PackTrack (`packtrack_state`)
 * and PlacesTracker (`places_tracker`), storing per-user watch history in `tvtime_state`.
 */
object FirestoreSyncService {

    const val DEFAULT_USER_ID = "default"

    private val firebaseProjectId: String
        get() = BuildConfig.FIREBASE_PROJECT_ID.trim()

    fun sanitizeUserId(raw: String?): String {
        val trimmed = (raw ?: "").trim().lowercase()
        if (trimmed.isEmpty()) return DEFAULT_USER_ID
        val withoutDomain = trimmed.substringBefore("@").removeSuffix("_gmail.com")
        val cleaned = withoutDomain.replace(Regex("[^a-z0-9_.-]"), "_").take(64)
        return cleaned.ifEmpty { DEFAULT_USER_ID }
    }

    fun displayUserLabel(userId: String): String = sanitizeUserId(userId)

    fun isUserAllowed(raw: String?): Boolean {
        val cleaned = sanitizeUserId(raw)
        if (cleaned.isBlank() || cleaned == "default") return false
        val allowedRaw = BuildConfig.ALLOWED_EMAILS.trim()
        if (allowedRaw.isEmpty()) return true
        val allowedSet = allowedRaw.split(",")
            .flatMap { entry ->
                val c = entry.trim().lowercase()
                if (c.isEmpty()) emptyList()
                else listOf(c, c.substringBefore("@"), sanitizeUserId(c))
            }
            .toSet()
        val rawLower = (raw ?: "").trim().lowercase()
        return allowedSet.contains(rawLower) || allowedSet.contains(cleaned)
    }

    private fun docUrlForUser(userId: String): String? {
        val projectId = firebaseProjectId
        val clean = sanitizeUserId(userId)
        if (projectId.isBlank() || clean == "default") return null
        return "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents/tvtime_state/user_state_$clean"
    }

    suspend fun ensureUserSeeded(dao: TvTimeDao, userId: String) = withContext(Dispatchers.IO) {
        // Default state for a new or unsigned-in user is intentionally empty (no starter shows/movies).
    }

    suspend fun pushLocalStateToFirestore(
        dao: TvTimeDao,
        userId: String = DEFAULT_USER_ID
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val cleanUser = sanitizeUserId(userId)
            val docUrl = docUrlForUser(cleanUser) ?: return@withContext false
            val shows = dao.getShowsForUser(cleanUser)
            val movies = dao.getMoviesForUser(cleanUser)
            val actors = dao.getActorsForUser(cleanUser)

            val showsArr = JSONArray()
            for (s in shows) {
                val watchedArr = JSONArray(TvTimeProgressCalculator.parseWatchedSet(s.watchedCodesCsv).toList())
                val epsArr = try { JSONArray(s.episodesJson) } catch (_: Exception) { JSONArray() }
                showsArr.put(
                    JSONObject().apply {
                        put("id", s.externalId)
                        put("tvmazeId", s.tvmazeId)
                        put("tmdbId", s.tmdbId)
                        put("title", s.title)
                        put("year", s.year)
                        put("network", s.network)
                        put("status", s.status)
                        put("runtimeMins", s.runtimeMins)
                        put("genres", JSONArray(s.genresCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }))
                        put("cast", JSONArray(s.castCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }))
                        put("rating", s.rating)
                        put("posterUrl", s.posterUrl)
                        put("overview", s.overview)
                        put("userStatus", s.userStatus)
                        put("userRating", s.userRating)
                        put("reaction", s.reaction)
                        put("isFavorite", s.isFavorite)
                        put("rewatchCount", s.rewatchCount)
                        put("watchedEpisodes", watchedArr)
                        put("episodes", epsArr)
                        put("lastWatchedAt", s.lastWatchedAt)
                    }
                )
            }

            val moviesArr = JSONArray()
            for (m in movies) {
                moviesArr.put(
                    JSONObject().apply {
                        put("id", m.externalId)
                        put("tmdbId", m.tmdbId)
                        put("title", m.title)
                        put("year", m.year)
                        put("runtimeMins", m.runtimeMins)
                        put("genres", JSONArray(m.genresCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }))
                        put("cast", JSONArray(m.castCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }))
                        put("director", m.director)
                        put("streamingService", m.streamingService)
                        put("rating", m.rating)
                        put("posterUrl", m.posterUrl)
                        put("overview", m.overview)
                        put("watched", m.watched)
                        put("watchedAt", m.watchedAt)
                        put("userRating", m.userRating)
                        put("reaction", m.reaction)
                        put("rewatchCount", m.rewatchCount)
                        put("isFavorite", m.isFavorite)
                        put("notes", m.notes)
                    }
                )
            }

            val actorsArr = JSONArray()
            for (a in actors) {
                actorsArr.put(
                    JSONObject().apply {
                        put("id", a.externalId)
                        put("name", a.name)
                        put("role", a.role)
                        put("imageUrl", a.imageUrl)
                        put("imdbUrl", a.imdbUrl)
                        put("knownFor", a.knownFor)
                    }
                )
            }

            val decidedIdsArr = JSONArray()
            for (a in actors) {
                if (a.role == "decided_rec") {
                    decidedIdsArr.put(a.externalId)
                    if (a.name.isNotBlank()) decidedIdsArr.put("title_${a.name.lowercase()}")
                }
            }

            val payloadObj = JSONObject().apply {
                put("userId", cleanUser)
                put("email", displayUserLabel(cleanUser))
                put("shows", showsArr)
                put("movies", moviesArr)
                put("actors", actorsArr)
                put("decidedIds", decidedIdsArr)
                put("followingUsers", JSONArray(listOf("partner")))
                put("updatedAt", Instant.now().toString())
            }

            val firestoreBody = JSONObject().apply {
                put(
                    "fields",
                    JSONObject().apply {
                        put("userId", JSONObject().put("stringValue", cleanUser))
                        put("email", JSONObject().put("stringValue", displayUserLabel(cleanUser)))
                        put("payload", JSONObject().put("stringValue", payloadObj.toString()))
                        put("showCount", JSONObject().put("integerValue", shows.size.toString()))
                        put("movieCount", JSONObject().put("integerValue", movies.size.toString()))
                        put("actorCount", JSONObject().put("integerValue", actors.size.toString()))
                        put("updatedAt", JSONObject().put("stringValue", Instant.now().toString()))
                    }
                )
            }

            val conn = (URL(docUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "PATCH"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
            }
            conn.outputStream.use { it.write(firestoreBody.toString().toByteArray(Charsets.UTF_8)) }
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        }
    }

    suspend fun pullFromFirestoreIfNeeded(
        dao: TvTimeDao,
        userId: String = DEFAULT_USER_ID
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanUser = sanitizeUserId(userId)
        val docUrl = docUrlForUser(cleanUser)
        if (docUrl == null) {
            ensureUserSeeded(dao, cleanUser)
            return@withContext false
        }
        try {
            val conn = (URL(docUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (conn.responseCode !in 200..299) {
                ensureUserSeeded(dao, cleanUser)
                return@withContext false
            }

            val docText = conn.inputStream.bufferedReader().use { it.readText() }
            val docJson = JSONObject(docText)
            val payloadStr = docJson.optJSONObject("fields")
                ?.optJSONObject("payload")
                ?.optString("stringValue", "") ?: ""
            if (payloadStr.isBlank()) {
                ensureUserSeeded(dao, cleanUser)
                return@withContext false
            }

            val payload = JSONObject(payloadStr)
            val showsArr = payload.optJSONArray("shows")
            val moviesArr = payload.optJSONArray("movies")
            val actorsArr = payload.optJSONArray("actors")

            if (showsArr != null) {
                dao.deleteAllShowsForUser(cleanUser)
                for (i in 0 until showsArr.length()) {
                    val s = showsArr.optJSONObject(i) ?: continue
                    val watchedArr = s.optJSONArray("watchedEpisodes") ?: JSONArray()
                    val watchedCodes = (0 until watchedArr.length()).mapNotNull { watchedArr.optString(it) }
                    val genresArr = s.optJSONArray("genres") ?: JSONArray()
                    val genresCsv = (0 until genresArr.length()).mapNotNull { genresArr.optString(it) }.joinToString(", ")
                    val castArr = s.optJSONArray("cast") ?: JSONArray()
                    val castCsv = (0 until castArr.length()).mapNotNull { castArr.optString(it) }.joinToString(", ")
                    val epsArr = s.optJSONArray("episodes") ?: JSONArray()
                    dao.insertShow(
                        ShowEntity(
                            userId = cleanUser,
                            externalId = s.optString("id", "show_$i"),
                            tvmazeId = s.optInt("tvmazeId", 0),
                            tmdbId = s.optInt("tmdbId", 0),
                            title = s.optString("title", "Show"),
                            year = s.optInt("year", 2024),
                            network = s.optString("network", "Streaming"),
                            status = s.optString("status", "Running"),
                            runtimeMins = s.optInt("runtimeMins", 45),
                            genresCsv = genresCsv.ifBlank { "Drama" },
                            castCsv = castCsv,
                            rating = s.optDouble("rating", 8.0),
                            posterUrl = s.optString("posterUrl", ""),
                            overview = s.optString("overview", ""),
                            userStatus = s.optString("userStatus", "watching"),
                            userRating = s.optInt("userRating", 0),
                            reaction = s.optString("reaction", ""),
                            isFavorite = s.optBoolean("isFavorite", false),
                            rewatchCount = s.optInt("rewatchCount", 0),
                            watchedCodesCsv = TvTimeProgressCalculator.serializeWatchedSet(watchedCodes),
                            episodesJson = epsArr.toString(),
                            lastWatchedAt = s.optString("lastWatchedAt", "")
                        )
                    )
                }
            }

            if (moviesArr != null) {
                dao.deleteAllMoviesForUser(cleanUser)
                for (i in 0 until moviesArr.length()) {
                    val m = moviesArr.optJSONObject(i) ?: continue
                    val genresArr = m.optJSONArray("genres") ?: JSONArray()
                    val genresCsv = (0 until genresArr.length()).mapNotNull { genresArr.optString(it) }.joinToString(", ")
                    val castArr = m.optJSONArray("cast") ?: JSONArray()
                    val castCsv = (0 until castArr.length()).mapNotNull { castArr.optString(it) }.joinToString(", ")
                    dao.insertMovie(
                        MovieEntity(
                            userId = cleanUser,
                            externalId = m.optString("id", "movie_$i"),
                            tmdbId = m.optInt("tmdbId", 0),
                            title = m.optString("title", "Movie"),
                            year = m.optInt("year", 2024),
                            runtimeMins = m.optInt("runtimeMins", 120),
                            genresCsv = genresCsv.ifBlank { "Movie" },
                            castCsv = castCsv,
                            director = m.optString("director", ""),
                            streamingService = m.optString("streamingService", "Streaming"),
                            rating = m.optDouble("rating", 8.0),
                            posterUrl = m.optString("posterUrl", ""),
                            overview = m.optString("overview", ""),
                            watched = m.optBoolean("watched", false),
                            watchedAt = m.optString("watchedAt", ""),
                            userRating = m.optInt("userRating", 0),
                            reaction = m.optString("reaction", ""),
                            rewatchCount = m.optInt("rewatchCount", 0),
                            isFavorite = m.optBoolean("isFavorite", false),
                            notes = m.optString("notes", "")
                        )
                    )
                }
            }

            if (actorsArr != null) {
                dao.deleteAllActorsForUser(cleanUser)
                val insertedDecided = mutableSetOf<String>()
                for (i in 0 until actorsArr.length()) {
                    val a = actorsArr.optJSONObject(i) ?: continue
                    val extId = a.optString("id", "actor_$i")
                    val role = a.optString("role", "Acting")
                    if (role == "decided_rec") insertedDecided.add(extId)
                    dao.insertActor(
                        ActorEntity(
                            userId = cleanUser,
                            externalId = extId,
                            name = a.optString("name", "Actor"),
                            role = role,
                            imageUrl = a.optString("imageUrl", ""),
                            imdbUrl = a.optString("imdbUrl", ""),
                            knownFor = a.optString("knownFor", "")
                        )
                    )
                }
                val decidedArr = payload.optJSONArray("decidedIds")
                if (decidedArr != null) {
                    for (j in 0 until decidedArr.length()) {
                        val did = decidedArr.optString(j, "").trim()
                        if (did.isNotEmpty() && !insertedDecided.contains(did)) {
                            insertedDecided.add(did)
                            dao.insertActor(
                                ActorEntity(
                                    userId = cleanUser,
                                    externalId = did,
                                    name = did.removePrefix("title_"),
                                    role = "decided_rec"
                                )
                            )
                        }
                    }
                }
            }

            ensureUserSeeded(dao, cleanUser)
            true
        } catch (_: Exception) {
            ensureUserSeeded(dao, cleanUser)
            false
        }
    }

    suspend fun exportUserStateToJson(dao: TvTimeDao, userId: String): String = withContext(Dispatchers.IO) {
        val cleanUser = sanitizeUserId(userId)
        val shows = dao.getShowsForUser(cleanUser)
        val movies = dao.getMoviesForUser(cleanUser)
        val actors = dao.getActorsForUser(cleanUser)

        val showsArr = JSONArray()
        for (s in shows) {
            val watchedArr = JSONArray(TvTimeProgressCalculator.parseWatchedSet(s.watchedCodesCsv).toList())
            val epsArr = try { JSONArray(s.episodesJson) } catch (_: Exception) { JSONArray() }
            showsArr.put(
                JSONObject().apply {
                    put("id", s.externalId)
                    put("tvmazeId", s.tvmazeId)
                    put("tmdbId", s.tmdbId)
                    put("title", s.title)
                    put("year", s.year)
                    put("network", s.network)
                    put("status", s.status)
                    put("runtimeMins", s.runtimeMins)
                    put("genres", JSONArray(s.genresCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }))
                    put("cast", JSONArray(s.castCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }))
                    put("rating", s.rating)
                    put("posterUrl", s.posterUrl)
                    put("overview", s.overview)
                    put("userStatus", s.userStatus)
                    put("userRating", s.userRating)
                    put("reaction", s.reaction)
                    put("isFavorite", s.isFavorite)
                    put("rewatchCount", s.rewatchCount)
                    put("watchedEpisodes", watchedArr)
                    put("episodes", epsArr)
                    put("lastWatchedAt", s.lastWatchedAt)
                }
            )
        }

        val moviesArr = JSONArray()
        for (m in movies) {
            moviesArr.put(
                JSONObject().apply {
                    put("id", m.externalId)
                    put("tmdbId", m.tmdbId)
                    put("title", m.title)
                    put("year", m.year)
                    put("runtimeMins", m.runtimeMins)
                    put("genres", JSONArray(m.genresCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }))
                    put("cast", JSONArray(m.castCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }))
                    put("director", m.director)
                    put("streamingService", m.streamingService)
                    put("rating", m.rating)
                    put("posterUrl", m.posterUrl)
                    put("overview", m.overview)
                    put("watched", m.watched)
                    put("watchedAt", m.watchedAt)
                    put("userRating", m.userRating)
                    put("reaction", m.reaction)
                    put("rewatchCount", m.rewatchCount)
                    put("isFavorite", m.isFavorite)
                    put("notes", m.notes)
                }
            )
        }

        val actorsArr = JSONArray()
        for (a in actors) {
            actorsArr.put(
                JSONObject().apply {
                    put("id", a.externalId)
                    put("name", a.name)
                    put("role", a.role)
                    put("imageUrl", a.imageUrl)
                    put("imdbUrl", a.imdbUrl)
                    put("knownFor", a.knownFor)
                }
            )
        }

        JSONObject().apply {
            put("userId", cleanUser)
            put("exportedAt", Instant.now().toString())
            put("shows", showsArr)
            put("movies", moviesArr)
            put("actors", actorsArr)
        }.toString(2)
    }

    suspend fun exportUserStateToCsv(dao: TvTimeDao, userId: String): String = withContext(Dispatchers.IO) {
        val cleanUser = sanitizeUserId(userId)
        val shows = dao.getShowsForUser(cleanUser)
        val movies = dao.getMoviesForUser(cleanUser)

        fun csvEsc(raw: String): String = "\"" + raw.replace("\"", "\"\"") + "\""

        val sb = StringBuilder()
        sb.appendLine("Type,Title,Year,Status,WatchedProgress,Rating,Reaction,Favorite,RewatchCount,NetworkOrDirector")
        for (s in shows) {
            val watchedCount = TvTimeProgressCalculator.parseWatchedSet(s.watchedCodesCsv).size
            sb.appendLine(
                listOf(
                    "Show",
                    csvEsc(s.title),
                    s.year.toString(),
                    csvEsc(s.userStatus),
                    csvEsc("$watchedCount eps (${s.watchedCodesCsv})"),
                    s.rating.toString(),
                    csvEsc(s.reaction),
                    s.isFavorite.toString(),
                    s.rewatchCount.toString(),
                    csvEsc(s.network)
                ).joinToString(",")
            )
        }
        for (m in movies) {
            sb.appendLine(
                listOf(
                    "Movie",
                    csvEsc(m.title),
                    m.year.toString(),
                    csvEsc(if (m.watched) "completed" else "watchlist"),
                    csvEsc(if (m.watched) "Watched" else "Unwatched"),
                    m.rating.toString(),
                    csvEsc(m.reaction),
                    m.isFavorite.toString(),
                    m.rewatchCount.toString(),
                    csvEsc(m.director.ifBlank { m.streamingService })
                ).joinToString(",")
            )
        }
        sb.toString()
    }

    suspend fun importUserStateFromJson(dao: TvTimeDao, userId: String, rawText: String): Int = withContext(Dispatchers.IO) {
        val cleanUser = sanitizeUserId(userId)
        val root = JSONObject(rawText)
        var importedCount = 0

        val showsArr = root.optJSONArray("shows")
        if (showsArr != null) {
            dao.deleteAllShowsForUser(cleanUser)
            for (i in 0 until showsArr.length()) {
                val s = showsArr.optJSONObject(i) ?: continue
                val watchedArr = s.optJSONArray("watchedEpisodes") ?: JSONArray()
                val watchedCodes = (0 until watchedArr.length()).mapNotNull { watchedArr.optString(it) }.toSet()
                val epsArr = s.optJSONArray("episodes") ?: JSONArray()
                val genresArr = s.optJSONArray("genres") ?: JSONArray()
                val genresCsv = (0 until genresArr.length()).mapNotNull { genresArr.optString(it) }.joinToString(", ")
                val castArr = s.optJSONArray("cast") ?: JSONArray()
                val castCsv = (0 until castArr.length()).mapNotNull { castArr.optString(it) }.joinToString(", ")
                dao.insertShow(
                    ShowEntity(
                        userId = cleanUser,
                        externalId = s.optString("id", "show_$i"),
                        tvmazeId = s.optInt("tvmazeId", 0),
                        tmdbId = s.optInt("tmdbId", 0),
                        title = s.optString("title", "Show"),
                        year = s.optInt("year", 2024),
                        network = s.optString("network", "Streaming"),
                        status = s.optString("status", "Running"),
                        runtimeMins = s.optInt("runtimeMins", 45),
                        genresCsv = genresCsv.ifBlank { "Drama" },
                        castCsv = castCsv,
                        rating = s.optDouble("rating", 8.0),
                        posterUrl = s.optString("posterUrl", ""),
                        overview = s.optString("overview", ""),
                        userStatus = s.optString("userStatus", "watching"),
                        userRating = s.optInt("userRating", 0),
                        reaction = s.optString("reaction", ""),
                        isFavorite = s.optBoolean("isFavorite", false),
                        rewatchCount = s.optInt("rewatchCount", 0),
                        watchedCodesCsv = TvTimeProgressCalculator.serializeWatchedSet(watchedCodes),
                        episodesJson = epsArr.toString(),
                        lastWatchedAt = s.optString("lastWatchedAt", "")
                    )
                )
                importedCount++
            }
        }

        val moviesArr = root.optJSONArray("movies")
        if (moviesArr != null) {
            dao.deleteAllMoviesForUser(cleanUser)
            for (i in 0 until moviesArr.length()) {
                val m = moviesArr.optJSONObject(i) ?: continue
                val genresArr = m.optJSONArray("genres") ?: JSONArray()
                val genresCsv = (0 until genresArr.length()).mapNotNull { genresArr.optString(it) }.joinToString(", ")
                val castArr = m.optJSONArray("cast") ?: JSONArray()
                val castCsv = (0 until castArr.length()).mapNotNull { castArr.optString(it) }.joinToString(", ")
                dao.insertMovie(
                    MovieEntity(
                        userId = cleanUser,
                        externalId = m.optString("id", "movie_$i"),
                        tmdbId = m.optInt("tmdbId", 0),
                        title = m.optString("title", "Movie"),
                        year = m.optInt("year", 2024),
                        runtimeMins = m.optInt("runtimeMins", 120),
                        genresCsv = genresCsv.ifBlank { "Movie" },
                        castCsv = castCsv,
                        director = m.optString("director", ""),
                        streamingService = m.optString("streamingService", "Streaming"),
                        rating = m.optDouble("rating", 8.0),
                        posterUrl = m.optString("posterUrl", ""),
                        overview = m.optString("overview", ""),
                        watched = m.optBoolean("watched", false),
                        watchedAt = m.optString("watchedAt", ""),
                        userRating = m.optInt("userRating", 0),
                        reaction = m.optString("reaction", ""),
                        rewatchCount = m.optInt("rewatchCount", 0),
                        isFavorite = m.optBoolean("isFavorite", false),
                        notes = m.optString("notes", "")
                    )
                )
                importedCount++
            }
        }

        val actorsArr = root.optJSONArray("actors")
        if (actorsArr != null) {
            dao.deleteAllActorsForUser(cleanUser)
            for (i in 0 until actorsArr.length()) {
                val a = actorsArr.optJSONObject(i) ?: continue
                dao.insertActor(
                    ActorEntity(
                        userId = cleanUser,
                        externalId = a.optString("id", "actor_$i"),
                        name = a.optString("name", "Actor"),
                        role = a.optString("role", "Acting"),
                        imageUrl = a.optString("imageUrl", ""),
                        imdbUrl = a.optString("imdbUrl", ""),
                        knownFor = a.optString("knownFor", "")
                    )
                )
            }
        }
        pushLocalStateToFirestore(dao, cleanUser)
        importedCount
    }
}

