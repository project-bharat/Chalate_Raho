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
import android.provider.Settings
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
 * Uses the hardware step detector for candidate footfalls, then independently
 * validates cadence and acceleration before persisting any steps.
 */
class StepTrackingService : Service(), SensorEventListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var sensorManager: SensorManager
    private lateinit var repository: StepRepository
    private var stepDetector: Sensor? = null
    private var motionSensor: Sensor? = null
    private var motionSensorIsLinear = false
    private var snapshotJob: Job? = null
    private lateinit var walkingFilter: WalkingMotionFilter

    override fun onCreate() {
        super.onCreate()
        repository = StepRepository(applicationContext)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        val linearAcceleration = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        motionSensor = linearAcceleration ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        motionSensorIsLinear = linearAcceleration != null
        walkingFilter = WalkingMotionFilter { validatedCount ->
            scope.launch { repository.addValidatedSteps(validatedCount) }
        }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification("Checking walking movement…"))

        val detector = stepDetector
        val motion = motionSensor
        if (detector == null || motion == null) {
            val reason = if (detector == null) {
                "This phone does not provide a step-detector sensor."
            } else {
                "This phone does not provide the motion sensor needed to validate steps."
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification(reason))
            stopSelf()
            return START_NOT_STICKY
        }

        sensorManager.unregisterListener(this)
        val detectorRegistered = sensorManager.registerListener(
            this, detector, SensorManager.SENSOR_DELAY_NORMAL
        )
        val motionRegistered = sensorManager.registerListener(
            this, motion, SensorManager.SENSOR_DELAY_GAME
        )
        if (!detectorRegistered || !motionRegistered) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification("Unable to activate walking sensors."))
            stopSelf()
            return START_NOT_STICKY
        }

        snapshotJob?.cancel()
        snapshotJob = scope.launch {
            repository.snapshot.collect { snapshot ->
                val count = NumberFormat.getIntegerInstance().format(snapshot.steps)
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(NOTIFICATION_ID, notification("$count verified steps today"))
            }
        }
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ACCELEROMETER -> {
                walkingFilter.onAcceleration(
                    event.values[0], event.values[1], event.values[2],
                    event.timestamp, motionSensorIsLinear
                )
                walkingFilter.resetIfStale(event.timestamp)
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                walkingFilter.onStepDetector(event.timestamp)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
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
            channel.description = "Ongoing validated walking activity tracking"
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
