package com.projectbharat.chalateraho

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.projectbharat.chalateraho.data.StepRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Counter-first pedometer.
 *
 * TYPE_STEP_COUNTER is the source of truth for daily totals because it reports
 * a cumulative hardware count and can catch up after delayed delivery. The
 * detector is used only on devices without a step counter; it is never
 * registered alongside the counter, avoiding double counting.
 */
class StepTrackingService : Service(), SensorEventListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val counterMutex = Mutex()
    private lateinit var sensorManager: SensorManager
    private lateinit var repository: StepRepository
    private var activeSensor: Sensor? = null
    private var lastCounterValue = -1L

    override fun onCreate() {
        super.onCreate()
        repository = StepRepository(applicationContext)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startForeground(NOTIFICATION_ID, notification("Starting hardware step counter…"))
        } catch (error: SecurityException) {
            Log.e(TAG, "Cannot start foreground step service", error)
            stopSelf()
            return START_NOT_STICKY
        } catch (error: IllegalStateException) {
            Log.e(TAG, "Foreground service start rejected", error)
            stopSelf()
            return START_NOT_STICKY
        }

        // Counter first: cumulative readings are the canonical daily total.
        // Detector is a compatibility fallback only when counter is absent.
        val counter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val detector = if (counter == null) {
            sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR, true)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        } else null
        val selected = counter ?: detector

        if (selected == null) {
            updateNotification("No hardware step sensor is available on this phone.")
            stopSelf()
            return START_NOT_STICKY
        }

        sensorManager.unregisterListener(this)
        activeSensor = selected
        lastCounterValue = -1L

        val registered = try {
            // Zero report latency requests no additional app-side batching.
            sensorManager.registerListener(this, selected, SensorManager.SENSOR_DELAY_NORMAL, 0)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Step sensor registration failed", error)
            false
        }
        if (!registered) {
            updateNotification("Unable to activate the hardware step sensor.")
            stopSelf()
            return START_NOT_STICKY
        }

        updateNotification(
            if (selected.type == Sensor.TYPE_STEP_COUNTER)
                "Cumulative hardware step counter active."
            else
                "Step counter unavailable; detector fallback active."
        )
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.values.isEmpty()) return

        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val current = event.values[0].toLong().coerceAtLeast(0L)
                scope.launch {
                    counterMutex.withLock {
                        if (lastCounterValue < 0L) {
                            lastCounterValue = getSharedPreferences(PREFS, MODE_PRIVATE)
                                .getLong(KEY_LAST_COUNTER, -1L)
                        }

                        // Normal case: add only the newly accumulated difference.
                        // If the value fell, Android's counter reset after reboot;
                        // the new post-reboot reading is itself today's new steps.
                        val delta = if (lastCounterValue < 0L || current < lastCounterValue) {
                            current
                        } else {
                            current - lastCounterValue
                        }

                        // Persist the daily total before advancing the saved baseline.
                        // This prevents a process death from silently losing a delta.
                        if (delta > 0L) {
                            repository.addSteps(delta.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        }
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putLong(KEY_LAST_COUNTER, current)
                            .apply()
                        lastCounterValue = current
                    }
                }
            }

            Sensor.TYPE_STEP_DETECTOR -> {
                // Only registered if the cumulative counter is unavailable.
                scope.launch { repository.addSteps(1) }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        if (::sensorManager.isInitialized) sensorManager.unregisterListener(this)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Step tracking", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Ongoing hardware step tracking"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(message: String): Notification {
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("Chalate Raho")
            .setContentText(message)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    companion object {
        private const val TAG = "ChalateRahoSteps"
        private const val PREFS = "hardware_step_state"
        private const val KEY_LAST_COUNTER = "last_counter_since_boot"
        private const val CHANNEL_ID = "chalate_raho_steps"
        private const val NOTIFICATION_ID = 41
    }
}
