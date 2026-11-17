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
import java.text.NumberFormat

/**
 * Hardware-first pedometer.
 *
 * Prefer TYPE_STEP_DETECTOR for one event per step and low latency.
 * Fall back to TYPE_STEP_COUNTER when the detector is unavailable.
 * Raw accelerometer samples are deliberately not used as the user-facing count.
 */
class StepTrackingService : Service(), SensorEventListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
            startForeground(NOTIFICATION_ID, notification("Starting step sensor…"))
        } catch (error: SecurityException) {
            Log.e(TAG, "Cannot start foreground step service", error)
            stopSelf()
            return START_NOT_STICKY
        } catch (error: IllegalStateException) {
            Log.e(TAG, "Foreground service start rejected", error)
            stopSelf()
            return START_NOT_STICKY
        }

        val detector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        val counter = if (detector == null) {
            sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER, true)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        } else null
        val selected = detector ?: counter

        if (selected == null) {
            updateNotification("No hardware step sensor is available on this phone.")
            stopSelf()
            return START_NOT_STICKY
        }

        sensorManager.unregisterListener(this)
        activeSensor = selected
        lastCounterValue = -1L

        // Special-trigger detector ignores samplingPeriodUs. Zero report latency
        // avoids adding app-side batching on supported sensor implementations.
        val registered = try {
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

        updateNotification(if (selected.type == Sensor.TYPE_STEP_DETECTOR)
            "Live hardware step detection active."
        else "Hardware step counter active.")
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.values.isEmpty()) return
        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> {
                // Android defines each event as exactly one detected step.
                scope.launch { repository.addSteps(1) }
            }
            Sensor.TYPE_STEP_COUNTER -> {
                val current = event.values[0].toLong().coerceAtLeast(0L)
                if (lastCounterValue < 0L) {
                    // Restore the previous sensor baseline across service restarts.
                    val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_LAST_COUNTER, -1L)
                    lastCounterValue = if (saved >= 0L && current >= saved) saved else current
                }
                if (current >= lastCounterValue) {
                    val delta = current - lastCounterValue
                    if (delta > 0L) scope.launch { repository.addSteps(delta.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
                }
                // A lower value means the phone rebooted and its hardware counter reset.
                lastCounterValue = current
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong(KEY_LAST_COUNTER, current).apply()
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
