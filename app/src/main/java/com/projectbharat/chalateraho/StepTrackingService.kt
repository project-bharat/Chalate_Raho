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
import android.os.SystemClock
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
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate

class StepTrackingService : Service(), SensorEventListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var sensorManager: SensorManager
    private lateinit var repository: StepRepository
    private var stepSensor: Sensor? = null
    private var snapshotJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        repository = StepRepository(applicationContext)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification("Tracking your walk"))
        val sensor = stepSensor ?: run { stopSelf(); return START_NOT_STICKY }
        sensorManager.unregisterListener(this)
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        snapshotJob?.cancel()
        snapshotJob = scope.launch {
            repository.snapshot.collect { snapshot ->
                val count = NumberFormat.getIntegerInstance().format(snapshot.steps)
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(NOTIFICATION_ID, notification("$count steps today"))
            }
        }
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_STEP_COUNTER) return
        val boot = runCatching { Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(0)
        val total = event.values.firstOrNull()?.toLong() ?: return
        val bootDate = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())
            .atZone(ZoneId.systemDefault()).toLocalDate()
        val bootStartedToday = bootDate == LocalDate.now()
        scope.launch { repository.recordSensor(total, boot, bootStartedToday) }
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
            val channel = NotificationChannel(CHANNEL_ID, "Step tracking", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Ongoing walking activity tracking"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(message: String): Notification {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("Chalate Raho").setContentText(message)
            .setContentIntent(pending).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).build()
    }

    companion object {
        private const val CHANNEL_ID = "chalate_raho_steps"
        private const val NOTIFICATION_ID = 41
    }
}
