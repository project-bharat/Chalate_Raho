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
    val calories: Int = 0, val week: List<Int> = List(7) { 0 }
)

class StepRepository(private val context: Context) {
    private val goalKey = intPreferencesKey("daily_goal")
    private val sensorKey = longPreferencesKey("sensor_baseline")
    private val bootKey = intPreferencesKey("boot_count")
    private val dateKey = stringPreferencesKey("active_date")

    val snapshot: Flow<StepSnapshot> = context.stepStore.data.map { prefs ->
        val today = LocalDate.now()
        val week = (6 downTo 0).map { offset ->
            prefs[intPreferencesKey("steps_${today.minusDays(offset.toLong())}")] ?: 0
        }
        val steps = prefs[intPreferencesKey("steps_$today")] ?: 0
        StepSnapshot(steps, prefs[goalKey] ?: 10000, steps * 0.00075, (steps * 0.04).toInt(), week)
    }

    suspend fun recordSensor(totalSinceBoot: Long, currentBoot: Int) {
        context.stepStore.edit { prefs ->
            val today = LocalDate.now().toString()
            val previousDate = prefs[dateKey]
            val previousBoot = prefs[bootKey]
            val key = intPreferencesKey("steps_$today")
            val baseline = prefs[sensorKey]
            if (previousDate != today || previousBoot != currentBoot || baseline == null) {
                prefs[sensorKey] = totalSinceBoot
                prefs[dateKey] = today
                prefs[bootKey] = currentBoot
            } else {
                prefs[key] = (totalSinceBoot - baseline).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
        }
    }

    suspend fun setGoal(goal: Int) {
        context.stepStore.edit { it[goalKey] = goal.coerceIn(1000, 50000) }
    }
}
