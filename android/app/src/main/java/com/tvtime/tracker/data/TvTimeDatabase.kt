package com.tvtime.tracker.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Multi-User Local-First SQLite Database stored on the Android device
 * (`/data/data/com.tvtime.tracker/databases/tvtime_local.db`) and synced per-user
 * to Firebase Cloud Firestore (`tvtime_state/user_state_{userId}`).
 */

@Entity(tableName = "shows", indices = [Index("userId"), Index("externalId")])
data class ShowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String = "default",
    val externalId: String,
    val tvmazeId: Int = 0,
    val tmdbId: Int = 0,
    val title: String,
    val year: Int = 2024,
    val network: String = "Streaming",
    val status: String = "Running",
    val runtimeMins: Int = 45,
    val genresCsv: String = "Drama",
    val castCsv: String = "",
    val rating: Double = 8.0,
    val posterUrl: String = "",
    val overview: String = "",
    val userStatus: String = "watching", // "watching" | "watchlist" | "completed"
    val userRating: Int = 0,
    val reaction: String = "",
    val isFavorite: Boolean = false,
    val rewatchCount: Int = 0,
    val watchedCodesCsv: String = "", // e.g. "S01E01,S01E02"
    val episodesJson: String = "[]",  // JSON array of EpisodeModel
    val lastWatchedAt: String = ""
)

@Entity(tableName = "movies", indices = [Index("userId"), Index("externalId")])
data class MovieEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String = "default",
    val externalId: String,
    val tmdbId: Int = 0,
    val title: String,
    val year: Int = 2024,
    val runtimeMins: Int = 120,
    val genresCsv: String = "Movie",
    val castCsv: String = "",
    val director: String = "",
    val streamingService: String = "Streaming",
    val rating: Double = 8.0,
    val posterUrl: String = "",
    val overview: String = "",
    val watched: Boolean = false,
    val watchedAt: String = "",
    val userRating: Int = 0,
    val reaction: String = "",
    val rewatchCount: Int = 0,
    val isFavorite: Boolean = false,
    val notes: String = ""
)

@Entity(tableName = "followed_actors", indices = [Index("userId"), Index("externalId")])
data class ActorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String = "default",
    val externalId: String,
    val name: String,
    val role: String = "Acting",
    val imageUrl: String = "",
    val imdbUrl: String = "",
    val knownFor: String = ""
)

@Dao
interface TvTimeDao {
    @Query("SELECT * FROM shows WHERE userId = :userId ORDER BY id DESC")
    fun observeShowsForUser(userId: String): Flow<List<ShowEntity>>

    @Query("SELECT * FROM shows WHERE userId = :userId ORDER BY id DESC")
    suspend fun getShowsForUser(userId: String): List<ShowEntity>

    @Query("SELECT * FROM shows WHERE id = :id")
    suspend fun getShowById(id: Long): ShowEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShow(show: ShowEntity): Long

    @Update
    suspend fun updateShow(show: ShowEntity)

    @Query("DELETE FROM shows WHERE id = :id")
    suspend fun deleteShow(id: Long)

    @Query("DELETE FROM shows WHERE userId = :userId")
    suspend fun deleteAllShowsForUser(userId: String)

    @Query("SELECT * FROM movies WHERE userId = :userId ORDER BY id DESC")
    fun observeMoviesForUser(userId: String): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE userId = :userId ORDER BY id DESC")
    suspend fun getMoviesForUser(userId: String): List<MovieEntity>

    @Query("SELECT * FROM movies WHERE id = :id")
    suspend fun getMovieById(id: Long): MovieEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMovie(movie: MovieEntity): Long

    @Update
    suspend fun updateMovie(movie: MovieEntity)

    @Query("DELETE FROM movies WHERE id = :id")
    suspend fun deleteMovie(id: Long)

    @Query("DELETE FROM movies WHERE userId = :userId")
    suspend fun deleteAllMoviesForUser(userId: String)

    @Query("SELECT * FROM followed_actors WHERE userId = :userId ORDER BY id DESC")
    fun observeActorsForUser(userId: String): Flow<List<ActorEntity>>

    @Query("SELECT * FROM followed_actors WHERE userId = :userId ORDER BY id DESC")
    suspend fun getActorsForUser(userId: String): List<ActorEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActor(actor: ActorEntity): Long

    @Query("DELETE FROM followed_actors WHERE id = :id")
    suspend fun deleteActor(id: Long)

    @Query("DELETE FROM followed_actors WHERE userId = :userId")
    suspend fun deleteAllActorsForUser(userId: String)
}

@Database(
    entities = [ShowEntity::class, MovieEntity::class, ActorEntity::class],
    version = 1,
    exportSchema = false
)
abstract class TvTimeDatabase : RoomDatabase() {
    abstract fun dao(): TvTimeDao
}

