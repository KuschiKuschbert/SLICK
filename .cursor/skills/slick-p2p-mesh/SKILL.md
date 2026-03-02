---
name: slick-p2p-mesh
description: Implements SLICK P2P convoy mesh. Use when working on Nearby Connections, BLE fallback, convoy roles, >4 Protocol throttling, Lead Handover, Democratic Detour voting, encrypted Protobuf packets, or ghost pin projection.
---

# SLICK P2P Convoy Mesh

## Purpose

Creates a local encrypted data mesh between riders using only the phone's internal radios (no external hardware). BLE for discovery, Wi-Fi Direct for data when power allows.

## Nearby Connections Strategy

```kotlin
val strategy = Strategy.P2P_CLUSTER  // All-to-all mesh, every rider is a peer
val serviceId = BuildConfig.P2P_SERVICE_ID  // "com.slick.tactical.mesh.v1"
```

**Realistic range**: ~30-50m in tight formation. Market as "close-formation link" -- never promise 100m.

## Star Topology (Leader-Follower)

- **Lead** (first to create convoy): calls `startAdvertising()` -- is the Hub
- **Followers**: call `startDiscovery()` -- are the Spokes
- Lead stores destination + waypoints; broadcasts to all Followers on change
- **Lead Handover**: if Lead disconnects, second rider in join order auto-promotes (pre-assigned hierarchy stored locally)

## Connection Lifecycle

```kotlin
// Connection callbacks -- must handle all states
val connectionCallback = object : ConnectionLifecycleCallback() {
    override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
        // Auto-accept after validating serviceId signature
        nearbyClient.acceptConnection(endpointId, payloadCallback)
    }
    override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
        if (result.status.isSuccess) {
            connectedEndpoints.add(endpointId)
            // Trigger Ghost Pin creation for this rider
        }
    }
    override fun onDisconnected(endpointId: String) {
        connectedEndpoints.remove(endpointId)
        // Activate Ghost Pin projection for this rider
    }
}
```

## Payload Serialization

All payloads are: `Protobuf.serialize() → AES-256-GCM encrypt → Payload.fromBytes()`

```kotlin
// Sending
val proto = ConvoyTelemetry.newBuilder().apply { ... }.build()
val encrypted = securePayloadManager.encrypt(proto.toByteArray()).getOrThrow()
nearbyClient.sendPayload(connectedEndpoints, Payload.fromBytes(encrypted))

// Receiving
override fun onPayloadReceived(endpointId: String, payload: Payload) {
    val bytes = payload.asBytes() ?: return
    val decrypted = securePayloadManager.decrypt(bytes).getOrElse { return }
    val telemetry = ConvoyTelemetry.parseFrom(decrypted)
    // Update local state
}
```

## The >4 Protocol (Convoy Scaling)

When `connectedEndpoints.size > SlickConstants.CONVOY_CLUSTERING_THRESHOLD`:

```kotlin
// Pack riders throttled to 0.2Hz (every 5s)
// Lead and Sweep remain at 1Hz

fun shouldTransmitThisCycle(role: ConvoyRole, cycleCount: Long): Boolean = when {
    !isThrottled -> true
    role == ConvoyRole.LEADER || role == ConvoyRole.SWEEP -> true
    role == ConvoyRole.PACK -> cycleCount % 5 == 0L  // 0.2Hz when 1Hz base
    else -> false
}
```

MapLibre clustering (`cluster=true`, `clusterRadius=50`) activates simultaneously.

## BLE Fallback (SURVIVAL_MODE or thermal stress)

```kotlin
// When BatterySurvivalManager emits SURVIVAL_MODE:
// 1. Stop Wi-Fi Direct advertising/discovery
// 2. Switch to BLE-only heartbeat

// BLE heartbeat: 100-byte Protobuf every 10 seconds
// Contains: riderId, lat, lon, speed, timestamp
// Full telemetry resumes when FULL_TACTICAL restored
```

## Ghost Pin Projection

When a rider drops offline, project their position along the polyline:

```kotlin
data class GhostProjection(
    val lastKnownLat: Double,
    val lastKnownLon: Double,
    val lastKnownSpeedKmh: Double,
    val lastKnownBearingDeg: Double,
    val goingDarkAt: LocalTime,
)

// Project forward: position = lastKnown + (elapsed * speed * bearing)
// Show as translucent icon. Auto-remove after 5 min without signal.
```

## Democratic Detour Voting

```kotlin
data class DetourProposal(
    val proposerRiderId: String,
    val stopName: String,
    val latitude: Double,
    val longitude: Double,
    val proposedAt24h: String,   // HH:mm
    val voteExpiry24h: String,   // HH:mm (proposedAt + 2 min)
)

// Broadcast via P2P. Each receiver shows voting overlay.
// >50% YES = approved. Leader can veto.
// Weather check: run proposed stop through RouteForecaster before finalizing.
```

## GOTCHAS

- `P2P_CLUSTER` does NOT guarantee mesh routing -- if A-B and B-C are connected but not A-C, C cannot receive A's packets directly. Keep the group tight.
- Nearby Connections uses both BLE and Wi-Fi simultaneously during discovery. This drains battery. Limit discovery windows to < 30 seconds.
- On some Samsung devices, `NEARBY_WIFI_DEVICES` permission is silently denied without `ACCESS_FINE_LOCATION` also granted. Always check both.
- Payload size limit: ~32KB per Nearby Connections payload. Protobuf telemetry packets are typically 80-120 bytes -- well within limits. Route updates with many waypoints can approach the limit; split if >20 waypoints.

## Reference Files

- `engine/mesh/ConvoyMeshManager.kt`
- `engine/mesh/ConvoyLeaderManager.kt`
- `engine/mesh/ConvoyOptimizationManager.kt`
- `engine/crypto/SecurePayloadManager.kt`
- `data/proto/convoy_telemetry.proto`

## RETROFIT LOG

_Updated as the mesh evolves during development._
