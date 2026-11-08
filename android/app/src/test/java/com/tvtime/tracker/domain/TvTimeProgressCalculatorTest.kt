package com.tvtime.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertEquals(1.0f, summary.progressFraction, 0.001f)
    }

    @Test
    fun `watchStatsSummary formats days and hours accurately`() {
        val stats = WatchStatsSummary(
            totalMinutes = 356580, // 247d 15h
            showMinutes = 312000,  // 216d 16h
            movieMinutes = 44580,  // 30d 23h
            totalEpisodesWatched = 7182,
            totalMoviesWatched = 372
        )
        assertEquals("247d 15h", stats.formattedTotal)
        assertEquals("216d 16h", stats.formattedSeries)
        assertEquals("30d 23h", stats.formattedMovies)
    }
}

