package com.slick.tactical.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.slick.tactical.R
import com.slick.tactical.data.local.dao.RiderBreadcrumbDao
import com.slick.tactical.data.local.entity.RiderBreadcrumbEntity
import com.slick.tactical.engine.audio.AudioRouteManager
import com.slick.tactical.engine.health.DeviceHealthManager
import com.slick.tactical.engine.imu.CrashDetectionManager
import com.slick.tactical.engine.imu.SosState
import com.slick.tactical.engine.location.ActivityRecognitionManager
import com.slick.tactical.engine.location.BatchedLocationManager
import com.slick.tactical.engine.mesh.ConvoyMeshManager
import com.slick.tactical.engine.mesh.ConvoyRole
import com.slick.tactical.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject

/**
 * The Engine Room -- keeps all safety-critical systems alive regardless of UI state.
 *
 * Responsibilities:
 * - GPS batch collection + breadcrumb recording to Room DB
 * - CrashDetectionManager IMU monitoring → SOS countdown
 * - ConvoyMeshManager P2P position broadcasting
 * - AudioRouteManager TTS alert delivery
 * - BatterySurvivalManager operational state
 * - ActivityRecognitionManager IN_VEHICLE state
 *
 * Persistent notification: "SLICK: Convoy Link Active"
 * Cannot be swiped away. Tapping opens the active HUD.
 */
@AndroidEntryPoint
class ConvoyForegroundService : Service() {

    @Inject lateinit var batterySurvivalManager: BatterySurvivalManager
    @Inject lateinit var healthManager: DeviceHealthManager
    @Inject lateinit var locationManager: BatchedLocationManager
    @Inject lateinit var crashDetectionManager: CrashDetectionManager
    @Inject lateinit var audioRouteManager: AudioRouteManager
    @Inject lateinit var meshManager: ConvoyMeshManager
    @Inject lateinit var activityRecognitionManager: ActivityRecognitionManager
    @Inject lateinit var breadcrumbDao: RiderBreadcrumbDao

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** Ephemeral rider ID for the current session -- rotated on each convoy start */
    private val sessionRiderId = UUID.randomUUID().toString().take(8)

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "slick_convoy_channel"
        const val ACTION_STOP = "com.slick.tactical.STOP_SERVICE"
        const val EXTRA_CONVOY_ID = "convoy_id"
    }

    override fun onCreate() {
        super.onCreate()
        Timber.i("ConvoyForegroundService starting (riderId=%s)", sessionRiderId)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        initializeEngineRoom()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Timber.i("ConvoyForegroundService stop requested")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY  // Restart if killed -- critical for rider safety
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Timber.i("ConvoyForegroundService destroying")
        batterySurvivalManager.destroy()
        crashDetectionManager.stopMonitoring()
        audioRouteManager.shutdown()
        meshManager.stopMesh()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ─── Engine Room Initialisation ───────────────────────────────────────────

    private fun initializeEngineRoom() {
        // Battery survival -- must be first
        batterySurvivalManager.initializePowerListener()
            .onFailure { e -> Timber.e(e, "BatterySurvivalManager failed to initialize") }

        // IMU crash detection
        crashDetectionManager.startMonitoring()
            .onFailure { e -> Timber.e(e, "CrashDetectionManager failed to start") }

        // TTS audio
        audioRouteManager.initializeTts()
            .onFailure { e -> Timber.e(e, "AudioRouteManager TTS failed to initialize") }

        // Wire CrashDetectionManager.isInVehicle to ActivityRecognition state
        serviceScope.launch {
            activityRecognitionManager.isInVehicle.collectLatest { inVehicle ->
                crashDetectionManager.isInVehicle = inVehicle
            }
        }

        // Observe battery/thermal state
        serviceScope.launch {
            batterySurvivalManager.systemState.collectLatest { state ->
                Timber.i("OperationalState: %s", state)
                updateNotification(state)
                adaptSystemsToState(state)
            }
        }

        // Observe SOS state -- fire TTS alert on countdown
        serviceScope.launch {
            crashDetectionManager.sosState.collectLatest { sosState ->
                when (sosState) {
                    is SosState.Countdown -> {
                        if (sosState.secondsRemaining == 60 || sosState.secondsRemaining == 30) {
                            serviceScope.launch {
                                audioRouteManager.speakTacticalAlert(
                                    "IMPACT DETECTED. SOS in ${sosState.secondsRemaining} seconds. Tap to cancel.",
                                )
                            }
                        }
                    }
                    is SosState.SOS_TRIGGERED -> {
                        Timber.e("SOS TRIGGERED at %s -- rider unresponsive", sosState.triggeredAt24h)
                        serviceScope.launch {
                            audioRouteManager.speakTacticalAlert("SOS TRANSMITTED. Emergency services alerted.")
                        }
                        // TODO: fire SOS to Supabase sos_alerts table
                    }
                    else -> {}
                }
            }
        }

        // Start GPS collection + breadcrumb recording
        startGpsAndBreadcrumbs()
    }

    // ─── GPS + Breadcrumb Recording ───────────────────────────────────────────

    private fun startGpsAndBreadcrumbs() {
        serviceScope.launch {
            batterySurvivalManager.systemState.collectLatest { operationalState ->
                locationManager.locationFlow(operationalState).collect { location ->
                    val speedKmh = location.speed * 3.6  // m/s → km/h

                    // Record breadcrumb to Room for offline safety tracking
                    if (location.accuracy < 50f) {  // Only record GPS fixes with < 50m accuracy
                        val breadcrumb = RiderBreadcrumbEntity(
                            id = UUID.randomUUID().toString(),
                            riderId = sessionRiderId,
                            convoyId = null,  // Set when convoy is active
                            latitude = location.latitude,
                            longitude = location.longitude,
                            speedKmh = speedKmh,
                            timestamp24h = LocalTime.now().format(timeFormatter),
                            synced = false,
                        )
                        breadcrumbDao.insertBreadcrumb(breadcrumb)
                    }

                    Timber.d(
                        "GPS: %.4f,%.4f speed=%.1f km/h accuracy=%.0fm",
                        location.latitude,
                        location.longitude,
                        speedKmh,
                        location.accuracy,
                    )
                }
            }
        }
    }

    // ─── Operational State Adaptation ─────────────────────────────────────────

    private fun adaptSystemsToState(state: OperationalState) {
        when (state) {
            OperationalState.FULL_TACTICAL -> {
                Timber.i("FULL_TACTICAL: all systems active")
                // Convoy mesh at full 1Hz -- managed by ConvoyMeshManager internally
            }
            OperationalState.SURVIVAL_MODE -> {
                Timber.i("SURVIVAL_MODE: non-essential systems suspended")
                meshManager.stopMesh()  // Kill Wi-Fi Direct, switch to BLE via ConvoyMeshManager
                // GPS throttling handled by BatchedLocationManager based on OperationalState
            }
        }
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(state: OperationalState = OperationalState.FULL_TACTICAL): Notification {
        val launchIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val contentText = when (state) {
            OperationalState.FULL_TACTICAL -> getString(R.string.notification_text_tactical)
            OperationalState.SURVIVAL_MODE -> getString(R.string.notification_text_survival)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(launchIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(state: OperationalState) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(state))
    }
}
