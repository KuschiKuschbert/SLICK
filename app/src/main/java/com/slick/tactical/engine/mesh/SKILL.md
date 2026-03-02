---
name: engine-mesh
description: Domain knowledge for the SLICK P2P convoy mesh subsystem. Use when working on ConvoyMeshManager, ConvoyOptimizationManager, or DetourManager in this directory.
---

# Convoy Mesh Domain

## Files in this package

| File | Purpose |
|------|---------|
| `ConvoyMeshManager.kt` | Nearby Connections P2P_CLUSTER manager. Encrypt, broadcast, receive. |
| `ConvoyOptimizationManager.kt` | >4 Protocol: broadcast throttling + clustering trigger. |
| `DetourManager.kt` | Democratic Detour voting: proposal, vote collection, majority detection. |
| `RiderState.kt` (inline) | Position state for a connected rider (online or ghost pin). |

## Key Invariants

- All payloads must be AES-256-GCM encrypted before `sendPayload()` -- no exceptions
- `localRiderId` rotates per convoy session -- never exposes device MAC or user account
- Pack riders throttle to 0.2Hz ONLY when convoy > 4 (not when == 4)
- Ghost Pin activates when `RiderState.isOnline == false` -- do not remove the rider from map

## GOTCHAS

_Updated as issues are discovered during development._
