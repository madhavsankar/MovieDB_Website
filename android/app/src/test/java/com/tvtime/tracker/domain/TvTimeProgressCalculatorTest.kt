package com.tvtime.tracker.domain

import com.tvtime.tracker.data.ActorEntity
import com.tvtime.tracker.data.FirestoreSyncService
import com.tvtime.tracker.data.LiveMediaCatalogService
import com.tvtime.tracker.data.MovieEntity
import com.tvtime.tracker.data.ShowEntity
import com.tvtime.tracker.data.TvTimeDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-memory Room DAO implementation for deterministic end-to-end Android state & sync evals.
 */
class FakeTvTimeDao : TvTimeDao {
    private var nextId = 1L
    private val showsState = MutableStateFlow<List<ShowEntity>>(emptyList())
    private val moviesState = MutableStateFlow<List<MovieEntity>>(emptyList())
    private val actorsState = MutableStateFlow<List<ActorEntity>>(emptyList())

    override fun observeShowsForUser(userId: String): Flow<List<ShowEntity>> =
        showsState.map { list -> list.filter { it.userId == userId }.sortedByDescending { it.id } }

    override suspend fun getShowsForUser(userId: String): List<ShowEntity> =
        showsState.value.filter { it.userId == userId }.sortedByDescending { it.id }

    override suspend fun getShowById(id: Long): ShowEntity? =
        showsState.value.firstOrNull { it.id == id }

    override suspend fun insertShow(show: ShowEntity): Long {
        val assignedId = if (show.id == 0L) nextId++ else show.id
        val entity = show.copy(id = assignedId)
        showsState.value = showsState.value.filterNot { it.id == assignedId } + entity
        return assignedId
    }

    override suspend fun updateShow(show: ShowEntity) {
        showsState.value = showsState.value.map { if (it.id == show.id) show else it }
    }

    override suspend fun deleteShow(id: Long) {
        showsState.value = showsState.value.filterNot { it.id == id }
    }

    override suspend fun deleteAllShowsForUser(userId: String) {
        showsState.value = showsState.value.filterNot { it.userId == userId }
    }

    override fun observeMoviesForUser(userId: String): Flow<List<MovieEntity>> =
        moviesState.map { list -> list.filter { it.userId == userId }.sortedByDescending { it.id } }

    override suspend fun getMoviesForUser(userId: String): List<MovieEntity> =
        moviesState.value.filter { it.userId == userId }.sortedByDescending { it.id }

    override suspend fun getMovieById(id: Long): MovieEntity? =
        moviesState.value.firstOrNull { it.id == id }

    override suspend fun insertMovie(movie: MovieEntity): Long {
        val assignedId = if (movie.id == 0L) nextId++ else movie.id
        val entity = movie.copy(id = assignedId)
        moviesState.value = moviesState.value.filterNot { it.id == assignedId } + entity
        return assignedId
    }

    override suspend fun updateMovie(movie: MovieEntity) {
        moviesState.value = moviesState.value.map { if (it.id == movie.id) movie else it }
    }

    override suspend fun deleteMovie(id: Long) {
        moviesState.value = moviesState.value.filterNot { it.id == id }
    }

    override suspend fun deleteAllMoviesForUser(userId: String) {
        moviesState.value = moviesState.value.filterNot { it.userId == userId }
    }

    override fun observeActorsForUser(userId: String): Flow<List<ActorEntity>> =
        actorsState.map { list -> list.filter { it.userId == userId }.sortedByDescending { it.id } }

    override suspend fun getActorsForUser(userId: String): List<ActorEntity> =
        actorsState.value.filter { it.userId == userId }.sortedByDescending { it.id }

    override suspend fun insertActor(actor: ActorEntity): Long {
        val assignedId = if (actor.id == 0L) nextId++ else actor.id
        val entity = actor.copy(id = assignedId)
        actorsState.value = actorsState.value.filterNot { it.id == assignedId } + entity
        return assignedId
    }

    override suspend fun deleteActor(id: Long) {
        actorsState.value = actorsState.value.filterNot { it.id == id }
    }

    override suspend fun deleteAllActorsForUser(userId: String) {
        actorsState.value = actorsState.value.filterNot { it.userId == userId }
    }
}

class TvTimeProgressCalculatorTest {

    @Test
    fun `computeShowProgress finds exact next unwatched episode and remaining count`() {
        val episodes = listOf(
            EpisodeModel("S01E01", 1, 1, "Good News About Hell"),
            EpisodeModel("S01E02", 1, 2, "Half Loop"),
            EpisodeModel("S01E03", 1, 3, "In Perpetuity"),
            EpisodeModel("S02E01", 2, 1, "Hello, Ms. Cobel")
        )
        val watched = setOf("S01E01", "S01E02")

        val summary = TvTimeProgressCalculator.computeShowProgress(episodes, watched)

        assertEquals(4, summary.totalEpisodes)
        assertEquals(2, summary.watchedCount)
        assertEquals(2, summary.leftCount)
        assertTrue(summary.hasStarted)
        assertFalse(summary.isCompleted)
        assertNotNull(summary.nextEpisode)
        assertEquals("S01E03", summary.nextEpisode?.code)
        assertEquals("S01·E03", summary.nextEpisode?.formattedDotCode)
    }

    @Test
    fun `computeShowProgress marks completed when all episodes are watched`() {
        val episodes = listOf(
            EpisodeModel("S01E01", 1, 1, "Pilot"),
            EpisodeModel("S01E02", 1, 2, "Finale")
        )
        val watched = setOf("S01E01", "S01E02")

        val summary = TvTimeProgressCalculator.computeShowProgress(episodes, watched)

        assertTrue(summary.isCompleted)
        assertEquals(0, summary.leftCount)
        assertNull(summary.nextEpisode)
        assertEquals(1.0f, summary.progressFraction, 0.001f)
    }

    @Test
    fun `deriveEffectiveShowStatus transitions accurately across watchlist, in_progress, completed, and stopped`() {
        val episodes = listOf(
            EpisodeModel("S01E01", 1, 1, "Ep 1"),
            EpisodeModel("S01E02", 1, 2, "Ep 2"),
            EpisodeModel("S01E03", 1, 3, "Ep 3")
        )

        // 0 watched -> Watchlist ("interested") even if stale userStatus was "in_progress"
        assertEquals(
            "interested",
            TvTimeProgressCalculator.deriveEffectiveShowStatus(episodes, emptySet(), "interested")
        )
        assertEquals(
            "interested",
            TvTimeProgressCalculator.deriveEffectiveShowStatus(episodes, emptySet(), "in_progress")
        )

        // 1 watched -> "in_progress"
        assertEquals(
            "in_progress",
            TvTimeProgressCalculator.deriveEffectiveShowStatus(episodes, setOf("S01E01"), "interested")
        )

        // Stopped show with partial episodes stays "stopped"
        assertEquals(
            "stopped",
            TvTimeProgressCalculator.deriveEffectiveShowStatus(episodes, setOf("S01E01"), "stopped")
        )

        // All watched -> "completed"
        val allWatched = setOf("S01E01", "S01E02", "S01E03")
        assertEquals(
            "completed",
            TvTimeProgressCalculator.deriveEffectiveShowStatus(episodes, allWatched, "in_progress")
        )

        // Unchecking 1 episode from completed transitions back to "in_progress"
        assertEquals(
            "in_progress",
            TvTimeProgressCalculator.deriveEffectiveShowStatus(episodes, setOf("S01E01", "S01E02"), "completed")
        )

        // Unchecking all episodes from completed transitions back to "interested" (Watchlist)
        assertEquals(
            "interested",
            TvTimeProgressCalculator.deriveEffectiveShowStatus(episodes, emptySet(), "completed")
        )
    }

    @Test
    fun `episodeList and watchedSet serialization round-trips losslessly`() {
        val episodes = listOf(
            EpisodeModel("S01E01", 1, 1, "Pilot", "2024-01-01", 52, "https://img/1.jpg", "First ep"),
            EpisodeModel("S02E05", 2, 5, "Midseason", "2025-02-10", 48, "https://img/2.jpg", "Fifth ep")
        )
        val json = TvTimeProgressCalculator.serializeEpisodeList(episodes)
        val parsed = TvTimeProgressCalculator.parseEpisodeList(json)
        assertEquals(2, parsed.size)
        assertEquals("S01E01", parsed[0].code)
        assertEquals("S02·E05", parsed[1].formattedDotCode)
        assertEquals(52, parsed[0].runtimeMins)
        assertEquals("First ep", parsed[0].overview)

        val csv = TvTimeProgressCalculator.serializeWatchedSet(listOf(" s01e02 ", "S01E01", "s01e02"))
        assertEquals("S01E01,S01E02", csv)
        val watchedSet = TvTimeProgressCalculator.parseWatchedSet(csv)
        assertEquals(setOf("S01E01", "S01E02"), watchedSet)
    }

    @Test
    fun `watchStatsSummary formats days and decimal hours accurately`() {
        val stats = WatchStatsSummary(
            totalMinutes = 356580, // 247d 15h = 5943.0 hrs
            showMinutes = 312000,  // 216d 16h = 5200.0 hrs
            movieMinutes = 44580,  // 30d 23h = 743.0 hrs
            totalEpisodesWatched = 7182,
            totalMoviesWatched = 372,
            totalSeriesCount = 45,
            totalMoviesCount = 380
        )
        assertEquals("247d 15h", stats.formattedTotal)
        assertEquals("216d 16h", stats.formattedSeries)
        assertEquals("30d 23h", stats.formattedMovies)
        assertEquals("5943.0 hrs", stats.formattedTotalHours)
        assertEquals("5200.0 hrs", stats.formattedSeriesHours)
        assertEquals("743.0 hrs", stats.formattedMoviesHours)
    }

    @Test
    fun `US streaming platform filter matches all 8 major services and network aliases`() {
        assertEquals(8, LiveMediaCatalogService.US_PLATFORMS.size)
        assertTrue(LiveMediaCatalogService.matchesUsPlatforms("Any Network", emptySet()))
        assertTrue(LiveMediaCatalogService.matchesUsPlatforms("Netflix", setOf("Netflix")))
        assertTrue(LiveMediaCatalogService.matchesUsPlatforms("HBO / Max", setOf("Max")))
        assertTrue(LiveMediaCatalogService.matchesUsPlatforms("Amazon Prime Video", setOf("Prime Video")))
        assertTrue(LiveMediaCatalogService.matchesUsPlatforms("FX on Hulu", setOf("Hulu")))
        assertTrue(LiveMediaCatalogService.matchesUsPlatforms("Showtime / Paramount+", setOf("Paramount+")))
        assertTrue(LiveMediaCatalogService.matchesUsPlatforms("NBC / Peacock", setOf("Peacock")))
        assertTrue(LiveMediaCatalogService.matchesUsPlatforms("Apple TV+", setOf("Apple TV+", "Disney+")))
        assertFalse(LiveMediaCatalogService.matchesUsPlatforms("Apple TV+", setOf("Netflix", "Hulu")))
    }

    @Test
    fun `watchlist toggle removal and JSON-CSV backup round-trip work end-to-end on FakeTvTimeDao`() = runBlocking {
        val dao = FakeTvTimeDao()
        val userId = FirestoreSyncService.sanitizeUserId("TestUser@gmail.com")
        assertEquals("testuser", userId)

        FirestoreSyncService.ensureUserSeeded(dao, userId)
        val initialShows = dao.getShowsForUser(userId)
        val initialMovies = dao.getMoviesForUser(userId)
        assertTrue(initialShows.isNotEmpty())
        assertTrue(initialMovies.isNotEmpty())

        // Add a new Watchlist show and verify toggle-remove deletes it
        val watchlistShowId = dao.insertShow(
            ShowEntity(
                userId = userId,
                externalId = "tmdb_tv_99999",
                tmdbId = 99999,
                title = "Watchlist Test Series",
                userStatus = "interested",
                watchedCodesCsv = ""
            )
        )
        assertNotNull(dao.getShowById(watchlistShowId))
        dao.deleteShow(watchlistShowId)
        assertNull(dao.getShowById(watchlistShowId))

        // Add a new Watchlist movie and verify toggle-remove deletes it
        val watchlistMovieId = dao.insertMovie(
            MovieEntity(
                userId = userId,
                externalId = "movie_88888",
                tmdbId = 88888,
                title = "Watchlist Test Movie",
                watched = false
            )
        )
        assertNotNull(dao.getMovieById(watchlistMovieId))
        dao.deleteMovie(watchlistMovieId)
        assertNull(dao.getMovieById(watchlistMovieId))

        // Export to JSON and CSV
        val exportedJson = FirestoreSyncService.exportUserStateToJson(dao, userId)
        val exportedCsv = FirestoreSyncService.exportUserStateToCsv(dao, userId)
        assertTrue(exportedJson.contains("\"userId\": \"testuser\""))
        assertTrue(exportedCsv.startsWith("Type,Title,Year,Status,WatchedProgress,Rating,Reaction,Favorite,RewatchCount,NetworkOrDirector"))

        // Wipe and restore from JSON backup
        dao.deleteAllShowsForUser(userId)
        dao.deleteAllMoviesForUser(userId)
        assertEquals(0, dao.getShowsForUser(userId).size)
        assertEquals(0, dao.getMoviesForUser(userId).size)

        val importedCount = FirestoreSyncService.importUserStateFromJson(dao, userId, exportedJson)
        assertEquals(initialShows.size + initialMovies.size, importedCount)
        assertEquals(initialShows.size, dao.getShowsForUser(userId).size)
        assertEquals(initialMovies.size, dao.getMoviesForUser(userId).size)
    }
}
