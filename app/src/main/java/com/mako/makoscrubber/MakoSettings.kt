package com.mako.makoscrubber

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "scrubber_settings")

class MakoSettings(private val context: Context) {

    companion object {
        val TOTAL_SCRUBBED_COUNT = intPreferencesKey("total_scrubbed_count")
        val HAS_ASKED_FOR_REVIEW = booleanPreferencesKey("has_asked_for_review")
        val REVIEW_THRESHOLD = intPreferencesKey("review_threshold")
        val REVIEW_STAGE = intPreferencesKey("review_stage")

        val REVIEW_THRESHOLDS = listOf(20, 75, 200)
    }

    val totalScrubbedCount: Flow<Int> = context.dataStore.data.map { it[TOTAL_SCRUBBED_COUNT] ?: 0 }
    val hasAskedForReview: Flow<Boolean> = context.dataStore.data.map { it[HAS_ASKED_FOR_REVIEW] ?: false }

    // Null once every prompt has been declined. Users from before the stage key kept their
    // old 100 → 500 → 1000 threshold, so map how far they got onto the new stages.
    val reviewThreshold: Flow<Int?> = context.dataStore.data.map { prefs ->
        val stage = prefs[REVIEW_STAGE] ?: when (prefs[REVIEW_THRESHOLD]) {
            null, 100 -> 0
            500 -> 1
            1000 -> 2
            else -> REVIEW_THRESHOLDS.size
        }
        REVIEW_THRESHOLDS.getOrNull(stage)
    }

    suspend fun incrementScrubbedCount(amount: Int) {
        context.dataStore.edit {
            val current = it[TOTAL_SCRUBBED_COUNT] ?: 0
            it[TOTAL_SCRUBBED_COUNT] = current + amount
        }
    }

    suspend fun markReviewAsked() {
        context.dataStore.edit { it[HAS_ASKED_FOR_REVIEW] = true }
    }

    suspend fun postponeReview() {
        context.dataStore.edit { prefs ->
            val current = prefs[TOTAL_SCRUBBED_COUNT] ?: 0
            // Skip stages the user has already passed, so the next prompt is a real wait away.
            val next = REVIEW_THRESHOLDS.indexOfFirst { it > current }
            prefs[REVIEW_STAGE] = if (next == -1) REVIEW_THRESHOLDS.size else next
        }
    }
}