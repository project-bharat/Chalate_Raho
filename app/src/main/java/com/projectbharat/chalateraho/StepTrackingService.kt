package com.projectbharat.chalateraho

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.projectbharat.chalateraho.data.StepRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.text.NumberFormat

/**
 * Continuous accelerometer-based pedometer with separate raw and cadence-filtered
 * counts. Walk-step totals, not simple detections, feed the daily goal.
 */
class StepTrackingService : Service(), SensorEventListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var sensorManager: SensorManager
    private lateinit var repository: StepRepository
    private var accelerometer: Sensor? = null
    private var snapshotJob: Job? = null
    private lateinit var pedometer: WalkingMotionFilter

    override fun onCreate() {
        super.onCreate()
        repository = StepRepository(applicationContext)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        pedometer = WalkingMotionFilter { walkStepsToAdd ->
            scope.launch { repository.addDetectedStep(walkStepsToAdd) }
        }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A service startup failure must not take down the launcher activity.
        try {
            startForeground(NOTIFICATION_ID, notification("Starting accelerometer pedometer…"))
        } catch (security: SecurityException) {
            stopSelf()
            return START_NOT_STICKY
        } catch (state: IllegalStateException) {
            stopSelf()
            return START_NOT_STICKY
        }

        val sensor = accelerometer
        if (sensor == null) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification("This phone has no accelerometer; step tracking is unavailable."))
            stopSelf()
            return START_NOT_STICKY
        }

        sensorManager.unregisterListener(this)
        val registered = try {
            sensorManager.registerListener(
                this, sensor, SensorManager.SENSOR_DELAY_GAME
            )
        } catch (failure: RuntimeException) {
            false
        }
        if (!registered) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification("Unable to activate the accelerometer."))
            stopSelf()
            return START_NOT_STICKY
        }

        snapshotJob?.cancel()
        snapshotJob = scope.launch {
            repository.snapshot.collect { snapshot ->
                val count = NumberFormat.getIntegerInstance().format(snapshot.steps)
                val raw = NumberFormat.getIntegerInstance().format(snapshot.simpleSteps)
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(NOTIFICATION_ID, notification("$count walk steps • $raw simple detections"))
            }
        }
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER && event.values.size >= 3) {
            pedometer.onAcceleration(
                event.values[0], event.values[1], event.values[2], event.timestamp
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        if (::sensorManager.isInitialized) sensorManager.unregisterListener(this)
        snapshotJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Step tracking", NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Ongoing accelerometer-based step tracking"
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

    companion object {
        private const val CHANNEL_ID = "chalate_raho_steps"
        private const val NOTIFICATION_ID = 41
    }
}
