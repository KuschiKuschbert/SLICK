---
name: slick-orbit-mvi
description: Implements Orbit-MVI state management for SLICK. Use when creating ViewModels, handling multi-stream inputs (GPS + Weather + P2P), writing MVI containers, or testing state machines with TestScope.
---

# SLICK Orbit-MVI

## Purpose

Orbit-MVI enforces strict unidirectional data flow: Intent → Reducer → State. Prevents the UI from directly mutating engine state. Critical for SLICK because state comes from multiple simultaneous streams: GPS updates, P2P telemetry, weather sync, and battery events.

## Dependencies

```kotlin
implementation("org.orbit-mvi:orbit-core:9.0.0")       // verify latest
implementation("org.orbit-mvi:orbit-compose:9.0.0")    // Compose integration
testImplementation("org.orbit-mvi:orbit-test:9.0.0")   // Testing
```

## Container Setup

```kotlin
@HiltViewModel
class ConvoyViewModel @Inject constructor(
    private val meshManager: ConvoyMeshManager,
    private val repository: RouteRepository,
) : ViewModel(), ContainerHost<ConvoyState, ConvoySideEffect> {

    override val container: Container<ConvoyState, ConvoySideEffect> =
        container(ConvoyState.Idle)

    // Intent: triggered by UI or engine events
    fun onJoinConvoy(convoyId: String) = intent {
        reduce { state.copy(isConnecting = true) }

        meshManager.joinConvoy(convoyId)
            .onSuccess { reduce { state.copy(isConnecting = false, convoyId = convoyId) } }
            .onFailure { e -> postSideEffect(ConvoySideEffect.ShowError(e.message ?: "Connection failed")) }
    }

    // Collecting from Flow (GPS, weather, P2P)
    fun observeRiderPositions() = intent {
        repeatOnSubscription {
            meshManager.riderPositions.collect { positions ->
                reduce { state.copy(riderPositions = positions) }
            }
        }
    }
}
```

## State, SideEffect, Intent

```kotlin
// State -- immutable snapshot of everything the UI needs
data class ConvoyState(
    val isConnecting: Boolean = false,
    val convoyId: String? = null,
    val riderPositions: Map<String, RiderTelemetry> = emptyMap(),
    val operationalMode: OperationalState = OperationalState.FULL_TACTICAL,
    val currentNode: WeatherNode? = null,
)

// SideEffect -- one-time events (toasts, navigation, dialogs)
sealed class ConvoySideEffect {
    data class ShowError(val message: String) : ConvoySideEffect()
    object NavigateToSurvivalMode : ConvoySideEffect()
    data class TriggerSOS(val riderId: String) : ConvoySideEffect()
}
```

## Compose Integration

```kotlin
@Composable
fun ConvoyScreen(viewModel: ConvoyViewModel = hiltViewModel()) {
    val state by viewModel.collectAsState()
    val sideEffectChannel = viewModel.container.sideEffectFlow

    LaunchedEffect(Unit) {
        sideEffectChannel.collect { effect ->
            when (effect) {
                is ConvoySideEffect.ShowError -> showSnackbar(effect.message)
                ConvoySideEffect.NavigateToSurvivalMode -> navController.navigate("survival")
                is ConvoySideEffect.TriggerSOS -> launchSOSFlow(effect.riderId)
            }
        }
    }

    // UI only reads state, never mutates directly
    Zone1Header(speedKmh = state.speedKmh, eta24h = state.eta)
}
```

## Multi-Stream Input Pattern

SLICK receives simultaneous updates from GPS, weather, P2P, and battery. Handle each in its own coroutine within the container:

```kotlin
init {
    observeGpsUpdates()
    observeConvoyMesh()
    observeWeatherNodes()
    observeBatteryState()
}

private fun observeGpsUpdates() = intent {
    repeatOnSubscription {
        locationManager.locationFlow.collect { location ->
            reduce { state.copy(speedKmh = location.speedKmh, currentLat = location.lat) }
        }
    }
}
```

`repeatOnSubscription` re-subscribes if the container is destroyed and recreated (e.g., process death).

## Testing with TestScope

```kotlin
@Test fun `joining convoy updates state correctly`() = runTest {
    val viewModel = ConvoyViewModel(
        meshManager = FakeConvoyMeshManager(),
        repository = FakeRouteRepository(),
    )
    viewModel.test {
        viewModel.onJoinConvoy("convoy-abc")
        assertEquals(ConvoyState(isConnecting = true), awaitState())
        assertEquals(ConvoyState(isConnecting = false, convoyId = "convoy-abc"), awaitState())
        expectNoEvents()
    }
}
```

## GOTCHAS

- Never call `reduce {}` from outside an `intent {}` block -- will throw a `MissingCoroutineScopeException`.
- `repeatOnSubscription` is only available in `orbit-core 8.0+`. Earlier versions use `viewModelScope.launch`.
- Multiple `intent {}` blocks run concurrently -- they do not queue. If order matters, use `singletonIntent {}` to cancel the previous.
- `postSideEffect()` delivers exactly once -- don't use it for persistent state (use `reduce` instead).

## Reference Files

- `ui/inflight/ConvoyViewModel.kt`
- `ui/preflight/PreFlightViewModel.kt`
- `ui/survival/SurvivalViewModel.kt`

## RETROFIT LOG

_Updated as MVI patterns evolve during development._
