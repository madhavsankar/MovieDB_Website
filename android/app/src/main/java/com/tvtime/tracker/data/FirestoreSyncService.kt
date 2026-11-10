package com.tvtime.tracker.data

import com.tvtime.tracker.BuildConfig
import com.tvtime.tracker.domain.EpisodeModel
import com.tvtime.tracker.domain.TvTimeProgressCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class SocialFriendsState(
    val friends: List<String> = emptyList(),
    val incomingRequests: List<String> = emptyList(),
    val outgoingRequests: List<String> = emptyList()
)

data class FriendUserSummary(
    val userId: String,
    val email: String,
    val showCount: Int,
    val movieCount: Int,
    val relation: String // "friend" | "incoming" | "outgoing" | "none"
)

data class FriendLibraryData(
    val userId: String,
    val email: String,
    val shows: List<ShowEntity>,
    val movies: List<MovieEntity>
)

/**
 * Multi-User Cloud Firestore NoSQL Sync Service (`tvtime_state/user_state_{userId}`).
 *
 * Shares the same free Firebase Cloud Firestore instance as PackTrack (`packtrack_state`)
 * and PlacesTracker (`places_tracker`), storing per-user watch history in `tvtime_state`.
 */
object FirestoreSyncService {

    const val DEFAULT_USER_ID = "default"
    private val syncMutex = Mutex()
    private val socialCache = ConcurrentHashMap<String, SocialFriendsState>()

    private fun parseStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val set = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val clean = sanitizeUserId(arr.optString(i, ""))
            if (clean.isNotBlank() && clean != DEFAULT_USER_ID) {
                set.add(clean)
            }
        }
        return set.toList()
    }

    private fun extractSocialFromPayload(payload: JSONObject, userId: String): SocialFriendsState {
        val state = SocialFriendsState(
            friends = parseStringList(payload.optJSONArray("friends")),
            incomingRequests = parseStringList(payload.optJSONArray("incomingFriendRequests")),
            outgoingRequests = parseStringList(payload.optJSONArray("outgoingFriendRequests"))
        )
        socialCache[sanitizeUserId(userId)] = state
        return state
    }

    fun getCachedSocialState(userId: String): SocialFriendsState {
        return socialCache[sanitizeUserId(userId)] ?: SocialFriendsState()
    }

    fun deduplicateShows(shows: List<ShowEntity>): List<ShowEntity> {
        val byKey = LinkedHashMap<String, ShowEntity>()
        fun statusRank(st: String): Int = when (st.lowercase()) {
            "completed" -> 4
            "in_progress", "watching" -> 3
            "stopped" -> 2
            else -> 1
        }
        for (s in shows) {
            val titleKey = s.title.trim().lowercase()
            val key = if (titleKey.isNotEmpty()) "title:$titleKey" else s.externalId.ifBlank { "id:${s.id}" }
            val existing = byKey[key]
            if (existing == null) {
                byKey[key] = s
            } else {
                val mergedWatched = TvTimeProgressCalculator.parseWatchedSet(existing.watchedCodesCsv) +
                    TvTimeProgressCalculator.parseWatchedSet(s.watchedCodesCsv)
                val existingEps = TvTimeProgressCalculator.parseEpisodeList(existing.episodesJson)
                val newEps = TvTimeProgressCalculator.parseEpisodeList(s.episodesJson)
                val bestEpsJson = if (newEps.size > existingEps.size) s.episodesJson else existing.episodesJson
                val bestStatus = if (statusRank(s.userStatus) > statusRank(existing.userStatus)) s.userStatus else existing.userStatus
                byKey[key] = existing.copy(
                    tvmazeId = if (existing.tvmazeId != 0) existing.tvmazeId else s.tvmazeId,
                    tmdbId = if (existing.tmdbId != 0) existing.tmdbId else s.tmdbId,
                    network = if (existing.network.isNotBlank() && existing.network != "Streaming") existing.network else s.network,
                    castCsv = existing.castCsv.ifBlank { s.castCsv },
                    userStatus = bestStatus,
                    watchedCodesCsv = TvTimeProgressCalculator.serializeWatchedSet(mergedWatched),
                    episodesJson = bestEpsJson
                )
            }
        }
        return byKey.values.toList()
    }

    fun deduplicateMovies(movies: List<MovieEntity>): List<MovieEntity> {
        val byKey = LinkedHashMap<String, MovieEntity>()
        for (m in movies) {
            val titleKey = m.title.trim().lowercase()
            val key = if (titleKey.isNotEmpty()) "title:$titleKey:${m.year}" else m.externalId.ifBlank { "id:${m.id}" }
            val existing = byKey[key]
            if (existing == null) {
                byKey[key] = m
            } else {
                byKey[key] = existing.copy(
                    tmdbId = if (existing.tmdbId != 0) existing.tmdbId else m.tmdbId,
                    watched = existing.watched || m.watched,
                    watchedAt = existing.watchedAt.ifBlank { m.watchedAt },
                    streamingService = if (existing.streamingService.isNotBlank() && existing.streamingService != "Streaming") {
                        existing.streamingService
                    } else m.streamingService,
                    castCsv = existing.castCsv.ifBlank { m.castCsv },
                    director = existing.director.ifBlank { m.director }
                )
            }
        }
        return byKey.values.toList()
    }

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
        syncMutex.withLock {
            try {
                val cleanUser = sanitizeUserId(userId)
                val docUrl = docUrlForUser(cleanUser) ?: return@withLock false
                val shows = deduplicateShows(dao.getShowsForUser(cleanUser))
                val movies = deduplicateMovies(dao.getMoviesForUser(cleanUser))
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

                val social = socialCache[cleanUser] ?: readSocialFromRemoteUnlocked(cleanUser)

                val payloadObj = JSONObject().apply {
                    put("userId", cleanUser)
                    put("email", displayUserLabel(cleanUser))
                    put("shows", showsArr)
                    put("movies", moviesArr)
                    put("actors", actorsArr)
                    put("decidedIds", decidedIdsArr)
                    put("followingUsers", JSONArray(listOf("partner")))
                    put("friends", JSONArray(social.friends))
                    put("incomingFriendRequests", JSONArray(social.incomingRequests))
                    put("outgoingFriendRequests", JSONArray(social.outgoingRequests))
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
    }

    suspend fun pullFromFirestoreIfNeeded(
        dao: TvTimeDao,
        userId: String = DEFAULT_USER_ID
    ): Boolean = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val cleanUser = sanitizeUserId(userId)
            val docUrl = docUrlForUser(cleanUser)
            if (docUrl == null) {
                ensureUserSeeded(dao, cleanUser)
                return@withLock false
            }
            try {
                val conn = (URL(docUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                if (conn.responseCode !in 200..299) {
                    ensureUserSeeded(dao, cleanUser)
                    return@withLock false
                }

                val docText = conn.inputStream.bufferedReader().use { it.readText() }
                val docJson = JSONObject(docText)
                val payloadStr = docJson.optJSONObject("fields")
                    ?.optJSONObject("payload")
                    ?.optString("stringValue", "") ?: ""
                if (payloadStr.isBlank()) {
                    ensureUserSeeded(dao, cleanUser)
                    return@withLock false
                }

                val payload = JSONObject(payloadStr)
                extractSocialFromPayload(payload, cleanUser)
                val showsArr = payload.optJSONArray("shows")
                val moviesArr = payload.optJSONArray("movies")
                val actorsArr = payload.optJSONArray("actors")

                if (showsArr != null) {
                    val parsedShows = mutableListOf<ShowEntity>()
                    for (i in 0 until showsArr.length()) {
                        val s = showsArr.optJSONObject(i) ?: continue
                        val watchedArr = s.optJSONArray("watchedEpisodes") ?: JSONArray()
                        val watchedCodes = (0 until watchedArr.length()).mapNotNull { watchedArr.optString(it) }
                        val genresArr = s.optJSONArray("genres") ?: JSONArray()
                        val genresCsv = (0 until genresArr.length()).mapNotNull { genresArr.optString(it) }.joinToString(", ")
                        val castArr = s.optJSONArray("cast") ?: JSONArray()
                        val castCsv = (0 until castArr.length()).mapNotNull { castArr.optString(it) }.joinToString(", ")
                        val epsArr = s.optJSONArray("episodes") ?: JSONArray()
                        parsedShows.add(
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
                    dao.deleteAllShowsForUser(cleanUser)
                    for (showEntity in deduplicateShows(parsedShows)) {
                        dao.insertShow(showEntity)
                    }
                }

                if (moviesArr != null) {
                    val parsedMovies = mutableListOf<MovieEntity>()
                    for (i in 0 until moviesArr.length()) {
                        val m = moviesArr.optJSONObject(i) ?: continue
                        val genresArr = m.optJSONArray("genres") ?: JSONArray()
                        val genresCsv = (0 until genresArr.length()).mapNotNull { genresArr.optString(it) }.joinToString(", ")
                        val castArr = m.optJSONArray("cast") ?: JSONArray()
                        val castCsv = (0 until castArr.length()).mapNotNull { castArr.optString(it) }.joinToString(", ")
                        parsedMovies.add(
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
                    dao.deleteAllMoviesForUser(cleanUser)
                    for (movieEntity in deduplicateMovies(parsedMovies)) {
                        dao.insertMovie(movieEntity)
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

    private fun parseCsvRows(rawText: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < rawText.length) {
            val ch = rawText[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < rawText.length && rawText[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> inQuotes = true
                    ',' -> {
                        row.add(field.toString().trim())
                        field.setLength(0)
                    }
                    '\n', '\r' -> {
                        if (ch == '\r' && i + 1 < rawText.length && rawText[i + 1] == '\n') {
                            i++
                        }
                        row.add(field.toString().trim())
                        if (row.any { it.isNotEmpty() }) {
                            rows.add(row)
                        }
                        row = mutableListOf()
                        field.setLength(0)
                    }
                    else -> field.append(ch)
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString().trim())
            if (row.any { it.isNotEmpty() }) {
                rows.add(row)
            }
        }
        return rows
    }

    suspend fun importUserStateFromJson(dao: TvTimeDao, userId: String, rawText: String): Int = withContext(Dispatchers.IO) {
        val cleanUser = sanitizeUserId(userId)
        val trimmed = rawText.trim()
        if (!trimmed.startsWith("{")) {
            return@withContext importUserStateFromCsv(dao, cleanUser, trimmed)
        }
        val root = JSONObject(trimmed)
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

    suspend fun importUserStateFromCsv(dao: TvTimeDao, userId: String, rawText: String): Int = withContext(Dispatchers.IO) {
        val cleanUser = sanitizeUserId(userId)
        val rows = parseCsvRows(rawText)
        if (rows.isEmpty()) return@withContext 0

        val firstRowLower = rows[0].map { it.lowercase().replace(Regex("[^a-z0-9]"), "") }
        val hasHeader = firstRowLower.contains("title") ||
            firstRowLower.contains("name") ||
            firstRowLower.contains("show") ||
            firstRowLower.contains("type")

        fun colIdx(vararg names: String): Int {
            for (n in names) {
                val idx = firstRowLower.indexOf(n)
                if (idx >= 0) return idx
            }
            return -1
        }

        val idxType = if (hasHeader) colIdx("type", "mediatype", "kind") else -1
        val idxTitle = if (hasHeader) colIdx("title", "name", "show", "series", "movie") else 0
        val idxYear = if (hasHeader) colIdx("year", "releaseyear") else -1
        val idxRating = if (hasHeader) colIdx("rating", "score", "imdbrating") else -1
        val idxStatus = if (hasHeader) colIdx("status", "userstatus", "watchstatus") else -1
        val idxProgress = if (hasHeader) colIdx("progress", "watchedprogress", "episodes") else -1
        val idxRuntime = if (hasHeader) colIdx("runtimeminutes", "runtime", "runtimemins") else -1
        val idxFav = if (hasHeader) colIdx("favorite", "isfavorite") else -1
        val idxNet = if (hasHeader) colIdx("network", "networkordirector", "streamingservice", "platform") else -1
        val idxPoster = if (hasHeader) colIdx("posterurl", "poster", "image", "imageurl") else -1
        val idxTvmaze = if (hasHeader) colIdx("tvmazeid") else -1
        val idxTmdb = if (hasHeader) colIdx("tmdbid") else -1

        val startIdx = if (hasHeader) 1 else 0
        val parsedShows = mutableListOf<ShowEntity>()
        val parsedMovies = mutableListOf<MovieEntity>()
        var importedCount = 0

        for (r in startIdx until rows.size) {
            val cols = rows[r]
            val titleCol = if (idxTitle >= 0) idxTitle else 0
            val title = cols.getOrNull(titleCol)?.trim().orEmpty()
            if (title.isEmpty() || title.endsWith(":")) continue

            val typeVal = cols.getOrNull(idxType)?.trim()?.lowercase().orEmpty().ifBlank { "show" }
            val year = cols.getOrNull(idxYear)?.toIntOrNull() ?: 2020
            val rating = cols.getOrNull(idxRating)?.toDoubleOrNull() ?: 8.0
            val rawStatus = cols.getOrNull(idxStatus)?.trim()?.lowercase().orEmpty().ifBlank { "watched" }
            val progressStr = cols.getOrNull(idxProgress)?.trim().orEmpty()
            val runtime = cols.getOrNull(idxRuntime)?.toIntOrNull() ?: if (typeVal == "movie") 120 else 45
            val isFav = cols.getOrNull(idxFav)?.trim()?.equals("true", ignoreCase = true) == true
            val network = cols.getOrNull(idxNet)?.trim().orEmpty().ifBlank { "Streaming" }
            val posterUrl = cols.getOrNull(idxPoster)?.trim().orEmpty()
            val tvmazeId = cols.getOrNull(idxTvmaze)?.toIntOrNull() ?: 0
            val tmdbId = cols.getOrNull(idxTmdb)?.toIntOrNull() ?: 0
            val slug = title.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

            if (typeVal == "movie" || typeVal == "film") {
                val watched = rawStatus == "watched" || rawStatus == "completed" || rawStatus == "true"
                parsedMovies.add(
                    MovieEntity(
                        userId = cleanUser,
                        externalId = if (tmdbId != 0) "tmdb_movie_$tmdbId" else "csv_movie_$slug",
                        tmdbId = tmdbId,
                        title = title,
                        year = year,
                        runtimeMins = runtime,
                        genresCsv = "Drama",
                        director = "",
                        streamingService = network,
                        rating = rating,
                        posterUrl = posterUrl,
                        overview = "",
                        watched = watched,
                        watchedAt = if (watched) Instant.now().toString().take(10) else "",
                        isFavorite = isFav
                    )
                )
                importedCount++
            } else {
                var watchedCount = 16
                var totalCount = 16
                val slashParts = progressStr.split("/")
                if (slashParts.size >= 2) {
                    val wNum = slashParts[0].replace(Regex("[^0-9]"), "").toIntOrNull()
                    val tNum = slashParts[1].replace(Regex("[^0-9]"), "").toIntOrNull()
                    if (tNum != null && tNum > 0) totalCount = tNum.coerceAtMost(500)
                    if (wNum != null && wNum >= 0) watchedCount = wNum.coerceAtMost(totalCount)
                }

                val userStatus = when (rawStatus) {
                    "watchlist", "interested", "plan to watch" -> {
                        watchedCount = 0
                        "interested"
                    }
                    "in progress", "in_progress", "watching" -> {
                        if (watchedCount >= totalCount) watchedCount = (totalCount - 1).coerceAtLeast(1)
                        "in_progress"
                    }
                    "stopped", "dropped" -> "stopped"
                    else -> {
                        watchedCount = totalCount
                        "completed"
                    }
                }

                val epsArr = JSONArray()
                val watchedSet = LinkedHashSet<String>()
                for (ep in 1..totalCount) {
                    val code = "S01E" + ep.toString().padStart(2, '0')
                    epsArr.put(
                        JSONObject().apply {
                            put("code", code)
                            put("season", 1)
                            put("number", ep)
                            put("title", "Episode $ep")
                            put("airDate", "$year-01-01")
                            put("runtimeMins", runtime)
                        }
                    )
                    if (ep <= watchedCount) {
                        watchedSet.add(code)
                    }
                }

                parsedShows.add(
                    ShowEntity(
                        userId = cleanUser,
                        externalId = if (tvmazeId != 0) "tvmaze_$tvmazeId" else "csv_show_$slug",
                        tvmazeId = tvmazeId,
                        tmdbId = tmdbId,
                        title = title,
                        year = year,
                        network = network,
                        status = "Ended",
                        runtimeMins = runtime,
                        genresCsv = "Drama",
                        rating = rating,
                        posterUrl = posterUrl,
                        overview = "",
                        userStatus = userStatus,
                        isFavorite = isFav,
                        watchedCodesCsv = TvTimeProgressCalculator.serializeWatchedSet(watchedSet),
                        episodesJson = epsArr.toString(),
                        lastWatchedAt = Instant.now().toString()
                    )
                )
                importedCount++
            }
        }

        if (parsedShows.isNotEmpty()) {
            val mergedShows = deduplicateShows(parsedShows + dao.getShowsForUser(cleanUser))
            dao.deleteAllShowsForUser(cleanUser)
            for (s in mergedShows) dao.insertShow(s)
        }
        if (parsedMovies.isNotEmpty()) {
            val mergedMovies = deduplicateMovies(parsedMovies + dao.getMoviesForUser(cleanUser))
            dao.deleteAllMoviesForUser(cleanUser)
            for (m in mergedMovies) dao.insertMovie(m)
        }
        pushLocalStateToFirestore(dao, cleanUser)
        importedCount
    }

    private fun readRawPayloadUnlocked(userId: String): JSONObject? {
        val clean = sanitizeUserId(userId)
        val docUrl = docUrlForUser(clean) ?: return null
        return try {
            val conn = (URL(docUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (conn.responseCode !in 200..299) return null
            val docText = conn.inputStream.bufferedReader().use { it.readText() }
            val docJson = JSONObject(docText)
            val payloadStr = docJson.optJSONObject("fields")
                ?.optJSONObject("payload")
                ?.optString("stringValue", "") ?: ""
            if (payloadStr.isBlank()) null else JSONObject(payloadStr)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeRawPayloadUnlocked(userId: String, payloadObj: JSONObject): Boolean {
        val clean = sanitizeUserId(userId)
        val docUrl = docUrlForUser(clean) ?: return false
        return try {
            payloadObj.put("userId", clean)
            payloadObj.put("updatedAt", Instant.now().toString())
            val showsCount = payloadObj.optJSONArray("shows")?.length() ?: 0
            val moviesCount = payloadObj.optJSONArray("movies")?.length() ?: 0
            val actorsCount = payloadObj.optJSONArray("actors")?.length() ?: 0
            val firestoreBody = JSONObject().apply {
                put(
                    "fields",
                    JSONObject().apply {
                        put("userId", JSONObject().put("stringValue", clean))
                        put("email", JSONObject().put("stringValue", payloadObj.optString("email", clean)))
                        put("payload", JSONObject().put("stringValue", payloadObj.toString()))
                        put("showCount", JSONObject().put("integerValue", showsCount.toString()))
                        put("movieCount", JSONObject().put("integerValue", moviesCount.toString()))
                        put("actorCount", JSONObject().put("integerValue", actorsCount.toString()))
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

    private fun readSocialFromRemoteUnlocked(userId: String): SocialFriendsState {
        val payload = readRawPayloadUnlocked(userId) ?: return SocialFriendsState()
        return extractSocialFromPayload(payload, userId)
    }

    private fun defaultPayloadForUser(userId: String): JSONObject {
        val clean = sanitizeUserId(userId)
        return JSONObject().apply {
            put("userId", clean)
            put("email", clean)
            put("shows", JSONArray())
            put("movies", JSONArray())
            put("actors", JSONArray())
            put("decidedIds", JSONArray())
            put("followingUsers", JSONArray(listOf("partner")))
            put("friends", JSONArray())
            put("incomingFriendRequests", JSONArray())
            put("outgoingFriendRequests", JSONArray())
        }
    }

    suspend fun fetchSocialFriendsState(userId: String): SocialFriendsState = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val clean = sanitizeUserId(userId)
            readSocialFromRemoteUnlocked(clean)
        }
    }

    suspend fun searchUsersByUsername(
        currentUserId: String,
        rawQuery: String
    ): List<FriendUserSummary> = withContext(Dispatchers.IO) {
        val me = sanitizeUserId(currentUserId)
        val q = sanitizeUserId(rawQuery).let { if (it == DEFAULT_USER_ID) "" else it }
        val social = fetchSocialFriendsState(me)
        val friendsSet = social.friends.toSet()
        val incomingSet = social.incomingRequests.toSet()
        val outgoingSet = social.outgoingRequests.toSet()

        fun relationFor(uid: String): String = when {
            friendsSet.contains(uid) -> "friend"
            incomingSet.contains(uid) -> "incoming"
            outgoingSet.contains(uid) -> "outgoing"
            else -> "none"
        }

        val results = LinkedHashMap<String, FriendUserSummary>()
        val projectId = firebaseProjectId
        if (projectId.isNotBlank()) {
            try {
                val listUrl = "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents/tvtime_state?pageSize=50"
                val conn = (URL(listUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                if (conn.responseCode in 200..299) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(text)
                    val docs = json.optJSONArray("documents") ?: JSONArray()
                    for (i in 0 until docs.length()) {
                        val doc = docs.optJSONObject(i) ?: continue
                        val fields = doc.optJSONObject("fields") ?: continue
                        val docName = doc.optString("name", "")
                        val docSuffix = docName.substringAfterLast("user_state_", "")
                        val uid = sanitizeUserId(
                            fields.optJSONObject("userId")?.optString("stringValue", "")?.ifBlank { docSuffix }
                        )
                        if (uid.isBlank() || uid == DEFAULT_USER_ID || uid == me) continue
                        val email = fields.optJSONObject("email")?.optString("stringValue", uid) ?: uid
                        if (q.isNotEmpty() && !uid.contains(q) && !email.lowercase().contains(q)) continue
                        val showCount = fields.optJSONObject("showCount")?.optString("integerValue", "0")?.toIntOrNull() ?: 0
                        val movieCount = fields.optJSONObject("movieCount")?.optString("integerValue", "0")?.toIntOrNull() ?: 0
                        results[uid] = FriendUserSummary(
                            userId = uid,
                            email = email,
                            showCount = showCount,
                            movieCount = movieCount,
                            relation = relationFor(uid)
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }

        if (q.isNotEmpty() && q != me && !results.containsKey(q)) {
            val directPayload = readRawPayloadUnlocked(q)
            val sCount = directPayload?.optJSONArray("shows")?.length() ?: 0
            val mCount = directPayload?.optJSONArray("movies")?.length() ?: 0
            results[q] = FriendUserSummary(
                userId = q,
                email = directPayload?.optString("email", q) ?: q,
                showCount = sCount,
                movieCount = mCount,
                relation = relationFor(q)
            )
        }

        results.values.take(20).toList()
    }

    suspend fun sendFriendRequest(
        fromUserId: String,
        targetUsername: String
    ): Result<SocialFriendsState> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val me = sanitizeUserId(fromUserId)
            val target = sanitizeUserId(targetUsername)
            if (me == DEFAULT_USER_ID || target == DEFAULT_USER_ID) {
                return@withLock Result.failure(IllegalArgumentException("Enter a valid username."))
            }
            if (me == target) {
                return@withLock Result.failure(IllegalArgumentException("You cannot send a friend request to yourself."))
            }

            val myPayload = readRawPayloadUnlocked(me) ?: defaultPayloadForUser(me)
            val targetPayload = readRawPayloadUnlocked(target) ?: defaultPayloadForUser(target)

            val mySocial = extractSocialFromPayload(myPayload, me)
            val targetSocial = extractSocialFromPayload(targetPayload, target)

            val myFriends = LinkedHashSet(mySocial.friends)
            val myIncoming = LinkedHashSet(mySocial.incomingRequests)
            val myOutgoing = LinkedHashSet(mySocial.outgoingRequests)

            val targetFriends = LinkedHashSet(targetSocial.friends)
            val targetIncoming = LinkedHashSet(targetSocial.incomingRequests)
            val targetOutgoing = LinkedHashSet(targetSocial.outgoingRequests)

            if (myFriends.contains(target)) {
                return@withLock Result.success(mySocial)
            }

            if (myIncoming.contains(target) || targetOutgoing.contains(me)) {
                myIncoming.remove(target)
                myOutgoing.remove(target)
                targetIncoming.remove(me)
                targetOutgoing.remove(me)
                myFriends.add(target)
                targetFriends.add(me)
            } else {
                myOutgoing.add(target)
                targetIncoming.add(me)
            }

            myPayload.put("friends", JSONArray(myFriends.toList()))
            myPayload.put("incomingFriendRequests", JSONArray(myIncoming.toList()))
            myPayload.put("outgoingFriendRequests", JSONArray(myOutgoing.toList()))

            targetPayload.put("friends", JSONArray(targetFriends.toList()))
            targetPayload.put("incomingFriendRequests", JSONArray(targetIncoming.toList()))
            targetPayload.put("outgoingFriendRequests", JSONArray(targetOutgoing.toList()))

            writeRawPayloadUnlocked(me, myPayload)
            writeRawPayloadUnlocked(target, targetPayload)

            Result.success(extractSocialFromPayload(myPayload, me))
        }
    }

    suspend fun respondToFriendRequest(
        myUserId: String,
        requesterUsername: String,
        accept: Boolean
    ): Result<SocialFriendsState> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val me = sanitizeUserId(myUserId)
            val requester = sanitizeUserId(requesterUsername)
            if (me == DEFAULT_USER_ID || requester == DEFAULT_USER_ID) {
                return@withLock Result.failure(IllegalArgumentException("Invalid username."))
            }

            val myPayload = readRawPayloadUnlocked(me) ?: defaultPayloadForUser(me)
            val reqPayload = readRawPayloadUnlocked(requester) ?: defaultPayloadForUser(requester)

            val mySocial = extractSocialFromPayload(myPayload, me)
            val reqSocial = extractSocialFromPayload(reqPayload, requester)

            val myFriends = LinkedHashSet(mySocial.friends)
            val myIncoming = LinkedHashSet(mySocial.incomingRequests)
            val myOutgoing = LinkedHashSet(mySocial.outgoingRequests)

            val reqFriends = LinkedHashSet(reqSocial.friends)
            val reqIncoming = LinkedHashSet(reqSocial.incomingRequests)
            val reqOutgoing = LinkedHashSet(reqSocial.outgoingRequests)

            myIncoming.remove(requester)
            myOutgoing.remove(requester)
            reqOutgoing.remove(me)
            reqIncoming.remove(me)

            if (accept) {
                myFriends.add(requester)
                reqFriends.add(me)
            }

            myPayload.put("friends", JSONArray(myFriends.toList()))
            myPayload.put("incomingFriendRequests", JSONArray(myIncoming.toList()))
            myPayload.put("outgoingFriendRequests", JSONArray(myOutgoing.toList()))

            reqPayload.put("friends", JSONArray(reqFriends.toList()))
            reqPayload.put("incomingFriendRequests", JSONArray(reqIncoming.toList()))
            reqPayload.put("outgoingFriendRequests", JSONArray(reqOutgoing.toList()))

            writeRawPayloadUnlocked(me, myPayload)
            writeRawPayloadUnlocked(requester, reqPayload)

            Result.success(extractSocialFromPayload(myPayload, me))
        }
    }

    suspend fun removeFriendOrRequest(
        myUserId: String,
        otherUsername: String
    ): Result<SocialFriendsState> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val me = sanitizeUserId(myUserId)
            val other = sanitizeUserId(otherUsername)
            if (me == DEFAULT_USER_ID || other == DEFAULT_USER_ID) {
                return@withLock Result.failure(IllegalArgumentException("Invalid username."))
            }

            val myPayload = readRawPayloadUnlocked(me) ?: defaultPayloadForUser(me)
            val otherPayload = readRawPayloadUnlocked(other) ?: defaultPayloadForUser(other)

            val mySocial = extractSocialFromPayload(myPayload, me)
            val otherSocial = extractSocialFromPayload(otherPayload, other)

            myPayload.put("friends", JSONArray(mySocial.friends.filter { it != other }))
            myPayload.put("incomingFriendRequests", JSONArray(mySocial.incomingRequests.filter { it != other }))
            myPayload.put("outgoingFriendRequests", JSONArray(mySocial.outgoingRequests.filter { it != other }))

            otherPayload.put("friends", JSONArray(otherSocial.friends.filter { it != me }))
            otherPayload.put("incomingFriendRequests", JSONArray(otherSocial.incomingRequests.filter { it != me }))
            otherPayload.put("outgoingFriendRequests", JSONArray(otherSocial.outgoingRequests.filter { it != me }))

            writeRawPayloadUnlocked(me, myPayload)
            writeRawPayloadUnlocked(other, otherPayload)

            Result.success(extractSocialFromPayload(myPayload, me))
        }
    }

    suspend fun fetchFriendLibrary(
        myUserId: String,
        friendUsername: String
    ): Result<FriendLibraryData> = withContext(Dispatchers.IO) {
        val me = sanitizeUserId(myUserId)
        val friend = sanitizeUserId(friendUsername)
        if (me == DEFAULT_USER_ID || friend == DEFAULT_USER_ID) {
            return@withContext Result.failure(IllegalArgumentException("Invalid friend username."))
        }
        val mySocial = fetchSocialFriendsState(me)
        val friendPayload = readRawPayloadUnlocked(friend)
            ?: return@withContext Result.failure(IllegalStateException("Friend library not found."))
        val friendSocial = extractSocialFromPayload(friendPayload, friend)
        if (!mySocial.friends.contains(friend) && !friendSocial.friends.contains(me)) {
            return@withContext Result.failure(IllegalAccessException("Friend request must be accepted before viewing library."))
        }

        val showsArr = friendPayload.optJSONArray("shows") ?: JSONArray()
        val parsedShows = mutableListOf<ShowEntity>()
        for (i in 0 until showsArr.length()) {
            val s = showsArr.optJSONObject(i) ?: continue
            val watchedArr = s.optJSONArray("watchedEpisodes") ?: JSONArray()
            val watchedCodes = (0 until watchedArr.length()).mapNotNull { watchedArr.optString(it) }
            val genresArr = s.optJSONArray("genres") ?: JSONArray()
            val genresCsv = (0 until genresArr.length()).mapNotNull { genresArr.optString(it) }.joinToString(", ")
            val castArr = s.optJSONArray("cast") ?: JSONArray()
            val castCsv = (0 until castArr.length()).mapNotNull { castArr.optString(it) }.joinToString(", ")
            val epsArr = s.optJSONArray("episodes") ?: JSONArray()
            parsedShows.add(
                ShowEntity(
                    userId = friend,
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

        val moviesArr = friendPayload.optJSONArray("movies") ?: JSONArray()
        val parsedMovies = mutableListOf<MovieEntity>()
        for (i in 0 until moviesArr.length()) {
            val m = moviesArr.optJSONObject(i) ?: continue
            val genresArr = m.optJSONArray("genres") ?: JSONArray()
            val genresCsv = (0 until genresArr.length()).mapNotNull { genresArr.optString(it) }.joinToString(", ")
            val castArr = m.optJSONArray("cast") ?: JSONArray()
            val castCsv = (0 until castArr.length()).mapNotNull { castArr.optString(it) }.joinToString(", ")
            parsedMovies.add(
                MovieEntity(
                    userId = friend,
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

        Result.success(
            FriendLibraryData(
                userId = friend,
                email = friendPayload.optString("email", friend),
                shows = deduplicateShows(parsedShows),
                movies = deduplicateMovies(parsedMovies)
            )
        )
    }
}

