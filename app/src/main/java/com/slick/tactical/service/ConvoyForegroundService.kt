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
import com.slick.tactical.engine.mesh.ConvoyOptimizationManager
import com.slick.tactical.engine.mesh.ConvoyRole
import com.slick.tactical.ui.MainActivity
import com.slick.tactical.util.SlickConstants
import dagger.hilt.android.AndroidEntryPoint
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
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
 * - CrashDetectionManager IMU monitoring → SOS countdown → Supabase sos_alerts insert
 * - ConvoyMeshManager P2P position broadcasting at 1Hz (Pack: 0.2Hz via >4 Protocol)
 * - AudioRouteManager TTS alert delivery
 * - BatterySurvivalManager operational state
 * - ActivityRecognitionManager IN_VEHICLE state
 *
 * Persistent notification: "SLICK: Convoy Link Active"
 */
@AndroidEntryPoint
class ConvoyForegroundService : Service() {

    @Inject lateinit var batterySurvivalManager: BatterySurvivalManager
    @Inject lateinit var healthManager: DeviceHealthManager
    @Inject lateinit var locationManager: BatchedLocationManager
    @Inject lateinit var crashDetectionManager: CrashDetectionManager
    @Inject lateinit var audioRouteManager: AudioRouteManager
    @Inject lateinit var meshManager: ConvoyMeshManager
    @Inject lateinit var convoyOptimizationManager: ConvoyOptimizationManager
    @Inject lateinit var activityRecognitionManager: ActivityRecognitionManager
    @Inject lateinit var breadcrumbDao: RiderBreadcrumbDao
    @Inject lateinit var supabaseClient: SupabaseClient

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
        return START_STICKY
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
        batterySurvivalManager.initializePowerListener()
            .onFailure { e -> Timber.e(e, "BatterySurvivalManager failed to initialize") }

        crashDetectionManager.startMonitoring()
            .onFailure { e -> Timber.e(e, "CrashDetectionManager failed to start") }

        audioRouteManager.initializeTts()
            .onFailure { e -> Timber.e(e, "AudioRouteManager TTS failed to initialize") }

        // Sync ActivityRecognition IN_VEHICLE state to crash detector phone-drop filter
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

        // Observe SOS state -- fire TTS alerts and Supabase insert on trigger
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
                            insertSosAlert(sosState.triggeredAt24h)
                        }
                    }
                    else -> {}
                }
            }
        }

        // Start GPS collection + breadcrumb recording
        startGpsAndBreadcrumbs()

        // Start P2P broadcast loop (1Hz base, 0.2Hz for Pack via >4 Protocol)
        startP2pBroadcastLoop()
    }

    // ─── GPS + Breadcrumb Recording ───────────────────────────────────────────

    private fun startGpsAndBreadcrumbs() {
        serviceScope.launch {
            batterySurvivalManager.systemState.collectLatest { operationalState ->
                locationManager.locationFlow(operationalState).collect { location ->
                    val speedKmh = location.speed * 3.6

                    // Update mesh manager with latest position for broadcast loop
                    meshManager.lastKnownLat = location.latitude
                    meshManager.lastKnownLon = location.longitude

                    // Record breadcrumb to Room for offline safety tracking
                    if (location.accuracy < 50f) {
                        val breadcrumb = RiderBreadcrumbEntity(
                            id = UUID.randomUUID().toString(),
                            riderId = sessionRiderId,
                            convoyId = meshManager.currentConvoyId,
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

    // ─── P2P Broadcast Loop (1Hz / 0.2Hz for Pack) ───────────────────────────

    /**
     * Runs a 1Hz coroutine that broadcasts the rider's GPS position to the convoy mesh.
     *
     * Pack riders are throttled to 0.2Hz (once per 5 cycles) via [ConvoyOptimizationManager]
     * when the convoy exceeds [SlickConstants.CONVOY_CLUSTERING_THRESHOLD] riders.
     *
     * Only broadcasts when a convoy is active ([ConvoyMeshManager.isConvoyActive]).
     * Stops broadcasting in SURVIVAL_MODE (Wi-Fi Direct killed by [adaptSystemsToState]).
     */
    private fun startP2pBroadcastLoop() {
        serviceScope.launch(Dispatchers.IO) {
            var cycleCount = 0L
            while (true) {
                delay(SlickConstants.LEAD_BROADCAST_INTERVAL_MS)  // 1 second base

                val convoyId = meshManager.currentConvoyId ?: continue
                val role = meshManager.currentRole
                val operationalState = batterySurvivalManager.systemState.value

                if (operationalState == OperationalState.SURVIVAL_MODE) continue
                if (!meshManager.isConvoyActive.value) continue

                cycleCount++

                if (!convoyOptimizationManager.shouldTransmitThisCycle(role)) {
                    Timber.d("P2P broadcast skipped this cycle (Pack throttle)")
                    continue
                }

                val lat = meshManager.lastKnownLat
                val lon = meshManager.lastKnownLon
                if (lat == 0.0 && lon == 0.0) continue  // No GPS fix yet

                meshManager.broadcastPosition(
                    lat = lat,
                    lon = lon,
                    speedKmh = 0.0,  // Speed is updated by GPS flow above
                    bearingDeg = 0.0,
                    role = role,
                    convoyId = convoyId,
                ).onFailure { e ->
                    Timber.w(e, "P2P broadcast failed on cycle %d", cycleCount)
                }
            }
        }
    }

    // ─── SOS Supabase Insert ──────────────────────────────────────────────────

    private suspend fun insertSosAlert(triggeredAt24h: String) {
        try {
            supabaseClient.postgrest["sos_alerts"].insert(
                SosAlertRow(
                    rider_id = sessionRiderId,
                    convoy_id = meshManager.currentConvoyId,
                    latitude = meshManager.lastKnownLat,
                    longitude = meshManager.lastKnownLon,
                    triggered_at_24h = triggeredAt24h,
                ),
            )
            Timber.i("SOS alert inserted to Supabase")
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert SOS alert to Supabase -- rider may not have signal")
        }
    }

    // ─── Operational State Adaptation ─────────────────────────────────────────

    private fun adaptSystemsToState(state: OperationalState) {
        when (state) {
            OperationalState.FULL_TACTICAL -> {
                Timber.i("FULL_TACTICAL: all systems active")
            }
            OperationalState.SURVIVAL_MODE -> {
                Timber.i("SURVIVAL_MODE: non-essential systems suspended")
                meshManager.stopMesh()
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
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(
                when (state) {
                    OperationalState.FULL_TACTICAL -> getString(R.string.notification_text_tactical)
                    OperationalState.SURVIVAL_MODE -> getString(R.string.notification_text_survival)
                },
            )
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

@Serializable
private data class SosAlertRow(
    val rider_id: String,
    val convoy_id: String?,
    val latitude: Double,
    val longitude: Double,
    val triggered_at_24h: String,
)
