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
    }

    val totalScrubbedCount: Flow<Int> = context.dataStore.data.map { it[TOTAL_SCRUBBED_COUNT] ?: 0 }
    val hasAskedForReview: Flow<Boolean> = context.dataStore.data.map { it[HAS_ASKED_FOR_REVIEW] ?: false }
    val reviewThreshold: Flow<Int> = context.dataStore.data.map { it[REVIEW_THRESHOLD] ?: 100 }

    suspend fun incrementScrubbedCount(amount: Int) {
        context.dataStore.edit {
            val current = it[TOTAL_SCRUBBED_COUNT] ?: 0
            it[TOTAL_SCRUBBED_COUNT] = current + amount
        }
    }

    suspend fun markReviewAsked() {
        context.dataStore.edit { it[HAS_ASKED_FOR_REVIEW] = true }
    }

    suspend fun setReviewThreshold(threshold: Int) {
        context.dataStore.edit { it[REVIEW_THRESHOLD] = threshold }
    }
}