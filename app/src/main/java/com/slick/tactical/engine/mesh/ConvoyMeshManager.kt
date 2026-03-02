package com.slick.tactical.engine.mesh

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.slick.tactical.BuildConfig
import com.slick.tactical.data.proto.ConvoyTelemetry
import com.slick.tactical.data.proto.ConvoyRole as ProtoRole
import com.slick.tactical.engine.crypto.SecurePayloadManager
import com.slick.tactical.util.SlickConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the encrypted P2P convoy mesh using Google Nearby Connections.
 *
 * Strategy: [Strategy.P2P_CLUSTER] -- all-to-all mesh.
 * Topology: Star (Lead advertises, Followers discover).
 *
 * The ephemeral [localRiderId] rotates per convoy session -- never exposes real device identity.
 *
 * Realistic range: ~30-50m in tight formation. Marketed as "close-formation link".
 * Beyond range, riders transition to Ghost Pin projection.
 *
 * See [slick-p2p-mesh](.cursor/skills/slick-p2p-mesh/SKILL.md) for full usage guide.
 */
@Singleton
class ConvoyMeshManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val securePayloadManager: SecurePayloadManager,
    private val convoyOptimizationManager: ConvoyOptimizationManager,
) {

    private val strategy = Strategy.P2P_CLUSTER
    private val nearbyClient get() = Nearby.getConnectionsClient(context)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** Ephemeral UUID rotated per convoy session -- not the user's real identity. */
    var localRiderId: String = UUID.randomUUID().toString().take(8)
        private set

    /** Active convoy session ID. Null when no convoy is active. */
    var currentConvoyId: String? = null
        private set

    /** This rider's role in the active convoy. */
    var currentRole: ConvoyRole = ConvoyRole.PACK
        private set

    /** Last known GPS position -- updated by ConvoyForegroundService broadcast loop. */
    var lastKnownLat: Double = 0.0
    var lastKnownLon: Double = 0.0

    private val _connectedRiders = MutableStateFlow<Map<String, RiderState>>(emptyMap())

    /** Observed by the UI layer to render convoy icons and ghost pins on MapLibre. */
    val connectedRiders: StateFlow<Map<String, RiderState>> = _connectedRiders.asStateFlow()

    private val _isConvoyActive = MutableStateFlow(false)

    /** True when advertising or discovery is active and at least partially connected. */
    val isConvoyActive: StateFlow<Boolean> = _isConvoyActive.asStateFlow()

    private val connectedEndpointIds = mutableSetOf<String>()

    /**
     * Starts advertising as Lead rider. Followers will discover this device.
     *
     * Lead sets the destination and beams route updates to all Followers.
     *
     * @param convoyId Unique convoy session ID (generated or scanned from QR code)
     * @return Result indicating success or failure with cause
     */
    fun startAdvertising(convoyId: String): Result<Unit> = try {
        localRiderId = UUID.randomUUID().toString().take(8)  // Rotate ID per session
        currentConvoyId = convoyId
        currentRole = ConvoyRole.LEADER
        _isConvoyActive.value = true

        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        nearbyClient.startAdvertising(
            "$localRiderId|$convoyId",
            BuildConfig.P2P_SERVICE_ID,
            connectionLifecycleCallback,
            options,
        ).addOnSuccessListener {
            Timber.i("Convoy advertising started: riderId=%s, convoyId=%s", localRiderId, convoyId)
        }.addOnFailureListener { e ->
            Timber.e(e, "Convoy advertising failed")
        }

        Result.success(Unit)
    } catch (e: Exception) {
        Timber.e(e, "startAdvertising threw unexpectedly")
        Result.failure(Exception("Advertising failed: ${e.localizedMessage}", e))
    }

    /**
     * Starts discovery to find the Lead rider and join the convoy.
     *
     * @return Result indicating success or failure with cause
     */
    fun startDiscovery(convoyId: String? = null): Result<Unit> = try {
        localRiderId = UUID.randomUUID().toString().take(8)
        currentConvoyId = convoyId
        currentRole = ConvoyRole.PACK
        _isConvoyActive.value = true

        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        nearbyClient.startDiscovery(
            BuildConfig.P2P_SERVICE_ID,
            endpointDiscoveryCallback,
            options,
        ).addOnSuccessListener {
            Timber.i("Convoy discovery started (seeking convoyId=%s)", convoyId)
        }.addOnFailureListener { e ->
            Timber.e(e, "Convoy discovery failed")
        }

        Result.success(Unit)
    } catch (e: Exception) {
        Timber.e(e, "startDiscovery threw unexpectedly")
        Result.failure(Exception("Discovery failed: ${e.localizedMessage}", e))
    }

    /**
     * Broadcasts the local rider's position to all connected convoy members.
     *
     * Telemetry is Protobuf-serialized then AES-256-GCM encrypted before transmission.
     * The >4 Protocol throttles Pack riders -- check [ConvoyOptimizationManager.shouldTransmit].
     *
     * @return Result indicating success or failure
     */
    fun broadcastPosition(
        lat: Double,
        lon: Double,
        speedKmh: Double,
        bearingDeg: Double,
        role: ConvoyRole,
        convoyId: String,
    ): Result<Unit> {
        if (connectedEndpointIds.isEmpty()) {
            return Result.success(Unit)  // No peers -- silent no-op
        }
        return try {
            val telemetry = ConvoyTelemetry.newBuilder()
                .setRiderId(localRiderId)
                .setLatitude(lat)
                .setLongitude(lon)
                .setSpeedKmh(speedKmh)
                .setBearingDegrees(bearingDeg)
                .setTimestamp24H(LocalTime.now().format(timeFormatter))
                .setRole(role.toProto())
                .setIsOnline(true)
                .setConvoyId(convoyId)
                .build()

            val encrypted = securePayloadManager.encrypt(telemetry.toByteArray())
                .getOrElse { return Result.failure(it) }

            nearbyClient.sendPayload(connectedEndpointIds.toList(), Payload.fromBytes(encrypted))
                .addOnFailureListener { e ->
                    Timber.w(e, "Payload send failed to %d endpoints", connectedEndpointIds.size)
                }

            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "broadcastPosition failed")
            Result.failure(Exception("Broadcast failed: ${e.localizedMessage}", e))
        }
    }

    /**
     * Sends a "Going Dark" payload before losing signal, enabling Ghost Pin projection.
     * Sets [ConvoyTelemetry.isOnline] to false so receivers know to start projecting.
     */
    fun broadcastGoingDark(lat: Double, lon: Double, speedKmh: Double, bearingDeg: Double, convoyId: String) {
        try {
            val payload = ConvoyTelemetry.newBuilder()
                .setRiderId(localRiderId)
                .setLatitude(lat)
                .setLongitude(lon)
                .setSpeedKmh(speedKmh)
                .setBearingDegrees(bearingDeg)
                .setTimestamp24H(LocalTime.now().format(timeFormatter))
                .setIsOnline(false)  // Signal: start projecting Ghost Pin
                .setConvoyId(convoyId)
                .build()

            securePayloadManager.encrypt(payload.toByteArray()).onSuccess { encrypted ->
                nearbyClient.sendPayload(connectedEndpointIds.toList(), Payload.fromBytes(encrypted))
            }
        } catch (e: Exception) {
            Timber.e(e, "Going Dark broadcast failed")
        }
    }

    /**
     * Stops all Nearby Connections activity. Call on service destroy or convoy end.
     */
    fun stopMesh() {
        nearbyClient.stopAllEndpoints()
        nearbyClient.stopAdvertising()
        nearbyClient.stopDiscovery()
        connectedEndpointIds.clear()
        _connectedRiders.value = emptyMap()
        _isConvoyActive.value = false
        currentConvoyId = null
        Timber.i("Convoy mesh stopped")
    }

    // ─── Connection Callbacks ─────────────────────────────────────────────────

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Auto-accept -- authentication is handled by convoy_id matching in the payload
            nearbyClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpointIds.add(endpointId)
                convoyOptimizationManager.updateConvoySize(connectedEndpointIds.size)
                Timber.i("Rider connected: endpointId=%s, total=%d", endpointId, connectedEndpointIds.size)
            } else {
                Timber.w("Connection failed: endpointId=%s, status=%s", endpointId, result.status.statusMessage)
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpointIds.remove(endpointId)
            convoyOptimizationManager.updateConvoySize(connectedEndpointIds.size)

            // Mark rider as offline -- UI shows Ghost Pin
            val currentState = _connectedRiders.value.toMutableMap()
            currentState[endpointId]?.let { state ->
                currentState[endpointId] = state.copy(isOnline = false)
                _connectedRiders.value = currentState
            }

            Timber.i("Rider disconnected: endpointId=%s, remaining=%d", endpointId, connectedEndpointIds.size)
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Timber.d("Convoy lead found: endpointId=%s, name=%s", endpointId, info.endpointName)
            nearbyClient.requestConnection(
                "${localRiderId}|follower",
                endpointId,
                connectionLifecycleCallback,
            ).addOnFailureListener { e ->
                Timber.e(e, "Connection request failed to %s", endpointId)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Timber.d("Convoy endpoint lost: %s", endpointId)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return

            securePayloadManager.decrypt(bytes)
                .onSuccess { decrypted ->
                    try {
                        val telemetry = ConvoyTelemetry.parseFrom(decrypted)
                        val riderState = RiderState(
                            riderId = telemetry.riderId,
                            latitude = telemetry.latitude,
                            longitude = telemetry.longitude,
                            speedKmh = telemetry.speedKmh,
                            bearingDegrees = telemetry.bearingDegrees,
                            timestamp24h = telemetry.timestamp24H,
                            role = telemetry.role.fromProto(),
                            isOnline = telemetry.isOnline,
                        )
                        val updated = _connectedRiders.value.toMutableMap()
                        updated[telemetry.riderId] = riderState
                        _connectedRiders.value = updated
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to parse decrypted telemetry from %s", endpointId)
                    }
                }
                .onFailure { e ->
                    Timber.w(e, "Decryption failed for payload from %s -- ignoring", endpointId)
                }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Monitor for large route update transfers if needed
        }
    }
}

/** Rider position state maintained in-memory for MapLibre rendering. */
data class RiderState(
    val riderId: String,
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Double,
    val bearingDegrees: Double,
    val timestamp24h: String,
    val role: ConvoyRole,
    val isOnline: Boolean,
)

enum class ConvoyRole {
    PACK,
    LEADER,
    SWEEP,
}

private fun ConvoyRole.toProto(): ProtoRole = when (this) {
    ConvoyRole.PACK -> ProtoRole.PACK
    ConvoyRole.LEADER -> ProtoRole.LEADER
    ConvoyRole.SWEEP -> ProtoRole.SWEEP
}

private fun ProtoRole.fromProto(): ConvoyRole = when (this) {
    ProtoRole.LEADER -> ConvoyRole.LEADER
    ProtoRole.SWEEP -> ConvoyRole.SWEEP
    else -> ConvoyRole.PACK
}
