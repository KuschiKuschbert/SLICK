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
import com.slick.tactical.engine.health.DeviceHealthManager
import com.slick.tactical.engine.location.BatchedLocationManager
import com.slick.tactical.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * The Engine Room -- the persistent Android ForegroundService that keeps
 * all safety-critical systems alive regardless of UI state.
 *
 * This service:
 * - Survives screen off, app backgrounding, and Android Doze
 * - Owns all engine managers (GPS, IMU, P2P, Audio, Weather)
 * - Publishes [OperationalState] that the UI observes via Hilt injection
 * - Must never be stopped by OEM task killers -- users must grant Unrestricted battery access
 *
 * Persistent notification: "SLICK: Convoy Link Active"
 * Cannot be swiped away. Tapping opens the active HUD screen.
 */
@AndroidEntryPoint
class ConvoyForegroundService : Service() {

    @Inject lateinit var batterySurvivalManager: BatterySurvivalManager
    @Inject lateinit var healthManager: DeviceHealthManager
    @Inject lateinit var locationManager: BatchedLocationManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "slick_convoy_channel"
        const val ACTION_STOP = "com.slick.tactical.STOP_SERVICE"
    }

    override fun onCreate() {
        super.onCreate()
        Timber.i("ConvoyForegroundService starting")

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
        return START_STICKY  // Restart if killed -- critical for safety
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Timber.i("ConvoyForegroundService destroying")
        batterySurvivalManager.destroy()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun initializeEngineRoom() {
        // Initialize battery survival manager -- must be first
        batterySurvivalManager.initializePowerListener()
            .onFailure { e -> Timber.e(e, "BatterySurvivalManager failed to initialize") }

        // Observe operational state changes and adapt systems accordingly
        serviceScope.launch {
            batterySurvivalManager.systemState.collectLatest { state ->
                Timber.i("OperationalState changed to: %s", state)
                updateNotification(state)
                adaptSystemsToState(state)
            }
        }

        // Start GPS collection -- observe location updates
        serviceScope.launch {
            val currentState = batterySurvivalManager.systemState.value
            locationManager.locationFlow(currentState).collect { location ->
                val speedKmh = location.speed * 3.6
                Timber.d("GPS update: lat=%.6f, speed=%.1f km/h", location.latitude, speedKmh)
                // TODO Phase 2: Feed into RouteForecaster
                // TODO Phase 3: Broadcast via ConvoyMeshManager
            }
        }
    }

    private fun adaptSystemsToState(state: OperationalState) {
        when (state) {
            OperationalState.FULL_TACTICAL -> {
                // TODO Phase 3: Restore Wi-Fi Direct mesh
                // TODO Phase 6: Resume MapLibre rendering (via UI state)
                Timber.i("FULL_TACTICAL: all systems active")
            }
            OperationalState.SURVIVAL_MODE -> {
                // TODO Phase 3: Terminate Wi-Fi Direct, switch to BLE heartbeat
                // TODO Phase 7: Suspend MapLibre (via UI state)
                Timber.i("SURVIVAL_MODE: non-essential systems suspended")
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SLICK Convoy Link",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Active convoy tracking and safety monitoring"
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(state: OperationalState = OperationalState.FULL_TACTICAL): Notification {
        val launchIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val contentText = when (state) {
            OperationalState.FULL_TACTICAL -> "Convoy Link Active"
            OperationalState.SURVIVAL_MODE -> "Survival Mode -- BLE heartbeat only"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SLICK")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(launchIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(state: OperationalState) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
    }
}
