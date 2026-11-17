package com.projectbharat.chalateraho.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

private val Context.stepStore by preferencesDataStore(name = "walking_data")

data class StepSnapshot(
    val steps: Int = 0, val goal: Int = 10000, val distanceKm: Double = 0.0,
    val calories: Int = 0,
    val week: List<Int> = List(7) { 0 },
    val month: List<Int> = List(30) { 0 },
    val activeDays: Int = 0,
    val averageSteps: Int = 0,
    val bestDaySteps: Int = 0
)

class StepRepository(private val context: Context) {
    private val goalKey = intPreferencesKey("daily_goal")
    private val sensorKey = longPreferencesKey("sensor_baseline")
    private val bootKey = intPreferencesKey("boot_count")
    private val baseStepsKey = intPreferencesKey("steps_at_sensor_baseline")
    private val dateKey = stringPreferencesKey("active_date")

    val snapshot: Flow<StepSnapshot> = context.stepStore.data.map { prefs ->
        val today = LocalDate.now()
        val week = (6 downTo 0).map { offset ->
            prefs[intPreferencesKey("steps_${today.minusDays(offset.toLong())}")] ?: 0
        }
        val month = (29 downTo 0).map { offset ->
            prefs[intPreferencesKey("steps_${today.minusDays(offset.toLong())}")] ?: 0
        }
        val steps = prefs[intPreferencesKey("steps_$today")] ?: 0
        val completedDays = month.filter { it > 0 }
        StepSnapshot(
            steps = steps,
            goal = prefs[goalKey] ?: 10000,
            distanceKm = steps * 0.00075,
            calories = (steps * 0.04).toInt(),
            week = week,
            month = month,
            activeDays = completedDays.size,
            averageSteps = if (completedDays.isEmpty()) 0 else completedDays.average().toInt(),
            bestDaySteps = month.maxOrNull() ?: 0
        )
    }

    /**
     * Adds only steps accepted by WalkingMotionFilter. The current date key
     * naturally starts a fresh daily total at local midnight.
     */
    suspend fun addValidatedSteps(count: Int) {
        if (count <= 0) return
        context.stepStore.edit { prefs ->
            val todayKey = intPreferencesKey("steps_${LocalDate.now()}")
            val current = prefs[todayKey] ?: 0
            prefs[todayKey] = (current.toLong() + count)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }

    suspend fun setGoal(goal: Int) {
        context.stepStore.edit { it[goalKey] = goal.coerceIn(1000, 50000) }
    }
}
