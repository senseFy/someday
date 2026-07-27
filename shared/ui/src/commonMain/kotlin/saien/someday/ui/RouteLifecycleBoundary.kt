package saien.someday.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

@Composable
internal fun RouteLifecycleBoundary(
    routeKey: Any,
    onEnter: suspend () -> Unit = {},
    onExit: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val currentOnEnter by rememberUpdatedState(onEnter)
    val currentOnExit by rememberUpdatedState(onExit)

    LaunchedEffect(routeKey) {
        currentOnEnter()
    }

    DisposableEffect(routeKey) {
        onDispose {
            currentOnExit()
        }
    }

    content()
}

@Composable
internal fun <T> rememberRouteRetainedValue(
    value: T,
    retainLiveValue: Boolean,
): T {
    var retainedValue by remember { mutableStateOf(value) }
    SideEffect {
        if (retainLiveValue) {
            retainedValue = value
        }
    }
    return if (retainLiveValue) value else retainedValue
}
