package com.tvtime.tracker

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.tvtime.tracker.data.FirestoreSyncService
import com.tvtime.tracker.data.TvTimeDatabase
import com.tvtime.tracker.ui.TvTimeMainScreen
import com.tvtime.tracker.ui.theme.TvTimeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Main Entry Point for TV Time: Show & Movie Tracker (Android):
 *  1. Initializes local Room SQLite DB (`/data/data/com.tvtime.tracker/databases/tvtime_local.db`)
 *  2. Pulls or seeds starter TV shows, movies, and followed actors per user (`tvtime_state/user_state_{userId}`)
 *  3. Renders the 3-Tab TV Time UI (`Home ⌂`, `Search 🔍`, `Profile 👤`)
 */
class MainActivity : ComponentActivity() {

    private lateinit var database: TvTimeDatabase
    private var activeUserId by mutableStateOf(FirestoreSyncService.DEFAULT_USER_ID)
    private var sharedSearchQuery by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        try {
            FirebaseApp.initializeApp(this)
            FirebaseAnalytics.getInstance(this)
        } catch (_: Exception) {
            // Ignored when built without google-services.json
        }

        database = Room.databaseBuilder(
            applicationContext,
            TvTimeDatabase::class.java,
            "tvtime_local.db"
        ).fallbackToDestructiveMigration().build()

        val prefs = getSharedPreferences("tvtime_prefs", Context.MODE_PRIVATE)
        val initialDefault = BuildConfig.DEFAULT_USER_ID.ifBlank { FirestoreSyncService.DEFAULT_USER_ID }
        val savedUser = prefs.getString("active_user_id", initialDefault)
        activeUserId = if (FirestoreSyncService.isUserAllowed(savedUser)) {
            FirestoreSyncService.sanitizeUserId(savedUser)
        } else {
            FirestoreSyncService.DEFAULT_USER_ID
        }

        if (savedInstanceState == null) {
            handleIncomingShareIntent(intent)
        }

        setContent {
            TvTimeTheme {
                TvTimeMainScreen(
                    dao = database.dao(),
                    initialUserId = activeUserId,
                    sharedSearchQuery = sharedSearchQuery,
                    onConsumeSharedQuery = { sharedSearchQuery = null },
                    onSwitchUser = { nextUser ->
                        val clean = FirestoreSyncService.sanitizeUserId(nextUser)
                        activeUserId = clean
                        prefs.edit().putString("active_user_id", clean).apply()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val rawText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            if (rawText.isNotEmpty()) {
                val cleaned = rawText
                    .lineSequence()
                    .firstOrNull { !it.startsWith("http://") && !it.startsWith("https://") }
                    ?.trim()
                    ?: rawText.substringBefore("http").trim()
                if (cleaned.isNotEmpty()) {
                    sharedSearchQuery = cleaned.take(80)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::database.isInitialized && FirestoreSyncService.isUserAllowed(activeUserId)) {
            lifecycleScope.launch(Dispatchers.IO) {
                FirestoreSyncService.pullFromFirestoreIfNeeded(database.dao(), activeUserId)
            }
        }
    }
}

