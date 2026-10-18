package dev.omalauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import dev.omalauncher.LauncherData
import dev.omalauncher.LockService
import dev.omalauncher.Runtime
import dev.omalauncher.linux.LinuxState

/** What a gesture on home does. */
enum class GestureAction(
    /** Saved in preferences: never rename. */
    val id: String,
    val label: String,
) {
    Drawer("drawer", "app drawer"),
    Notifications("notifications", "notifications"),
    Settings("settings", "settings"),
    Style("style", "style"),
    Store("store", "widget store"),
    Agents("agents", "coding agents"),
    Terminal("terminal", "terminal"),
    Lock("lock", "lock screen"),
    None("none", "nothing"),
    ;

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/** The actions picked for home's gestures: swiping up, swiping down and double-tapping empty space. */
class Gestures(
    context: Context,
) {
    private val context = context.applicationContext
    private val prefs = LauncherData.prefs(context)

    private var up by mutableStateOf(GestureAction.of(prefs.getString(SWIPE_UP, null)) ?: GestureAction.Drawer)
    private var down by mutableStateOf(GestureAction.of(prefs.getString(SWIPE_DOWN, null)) ?: GestureAction.Notifications)
    private var twice by mutableStateOf(GestureAction.of(prefs.getString(DOUBLE_TAP, null)) ?: GestureAction.None)

    /** The app drawer by default, like stock launchers. Saved as it's set. */
    var swipeUp: GestureAction
        get() = up
        set(action) {
            up = action
            prefs.edit().putString(SWIPE_UP, action.id).apply()
        }

    /** Notifications by default, like stock launchers. */
    var swipeDown: GestureAction
        get() = down
        set(action) {
            down = action
            prefs.edit().putString(SWIPE_DOWN, action.id).apply()
        }

    /** Nothing by default: a double tap is easy to make by accident. */
    var doubleTap: GestureAction
        get() = twice
        set(action) {
            twice = action
            prefs.edit().putString(DOUBLE_TAP, action.id).apply()
        }

    /** Runs [action] from a gesture; screens that need Linux offer to install it instead. */
    fun perform(
        action: GestureAction,
        state: LauncherState,
        runtime: Runtime,
    ) {
        when (action) {
            GestureAction.Drawer -> state.open()
            GestureAction.Notifications -> expandNotifications()
            GestureAction.Settings -> state.show(SettingsPage.Settings)
            GestureAction.Style -> state.show(SettingsPage.Style)
            GestureAction.Store -> state.show(SettingsPage.Store)
            GestureAction.Agents -> state.show(if (runtime.bootstrap.state.value == LinuxState.Installed) SettingsPage.Agents else SettingsPage.Linux)
            GestureAction.Terminal -> runtime.terminal(null, "Terminal")
            GestureAction.Lock -> LockService.lock(context)
            GestureAction.None -> {}
        }
    }

    /**
     * Pulls down the notification shade, as stock launchers do on a swipe down. StatusBarManager's
     * expandNotificationsPanel isn't public, but launchers have reflected on it for years; needs EXPAND_STATUS_BAR.
     */
    @SuppressLint("WrongConstant", "PrivateApi")
    private fun expandNotifications() {
        runCatching {
            val statusBar = context.getSystemService("statusbar") ?: error("no statusbar service")
            statusBar.javaClass.getMethod("expandNotificationsPanel").invoke(statusBar)
        }.onFailure { Log.w(TAG, "notifications panel", it) }
    }

    private companion object {
        const val TAG = "gestures"
        const val SWIPE_UP = "gesture_swipe_up"
        const val SWIPE_DOWN = "gesture_swipe_down"
        const val DOUBLE_TAP = "gesture_double_tap"
    }
}

/**
 * Dragging up opens the drawer under the finger and dragging down closes it, as on stock launchers: the panel
 * follows the drag and a fling or how far it got decides where it settles. Scrollables inside (a widget's
 * list, the recent apps strip) take their own direction first.
 */
@Composable
internal fun Modifier.drawerSwipe(
    state: LauncherState,
    enabled: Boolean = true,
): Modifier {
    val screenHeight = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    return draggable(
        orientation = Orientation.Vertical,
        enabled = enabled,
        state = rememberDraggableState { dy -> state.dragBy(-dy / screenHeight) },
        onDragStopped = { velocity -> state.settle(velocity) },
    )
}

/**
 * Swiping up or down on home (the pages, the recent apps strip, the bar) does what's picked in gesture settings.
 * The drawer follows the finger when swiping up opens it; any other action runs once the swipe is long or fast
 * enough. With the drawer open, the swipe drags it whatever the setting, so it can still be pulled closed.
 */
@Composable
internal fun Modifier.homeSwipe(
    runtime: Runtime,
    state: LauncherState,
    enabled: Boolean = true,
): Modifier {
    val gestures = runtime.gestures
    val up = gestures.swipeUp
    val down = gestures.swipeDown
    val drawerShown by remember(state) { derivedStateOf { state.drawer.value > 0f } }
    // Whether the drawer shows is only read when it matters: read here, it would recompose all of home (its Box is
    // inline) each time the drawer opens or closes.
    val followsDrawer = up == GestureAction.Drawer || drawerShown
    val screenHeight = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val threshold = with(LocalDensity.current) { SwipeAction.toPx() }
    var dragged by remember { mutableStateOf(0f) }
    var drawerAtStart by remember { mutableStateOf(false) }
    // One drag handler, only turned on or off: a new one mid-swipe (as the drawer starts to show) would drop the drag.
    return draggable(
        orientation = Orientation.Vertical,
        enabled = enabled && (followsDrawer || up != GestureAction.None || down != GestureAction.None),
        state =
            rememberDraggableState { dy ->
                dragged += dy
                if (followsDrawer) state.dragBy(-dy / screenHeight)
            },
        onDragStarted = {
            dragged = 0f
            drawerAtStart = state.drawer.value > 0f
        },
        onDragStopped = { velocity ->
            // A drag that moved the drawer, or pulled up at it, settles it; one down from a closed drawer is the swipe down.
            val pulledDrawer = followsDrawer && (drawerAtStart || dragged < 0f || state.drawer.value > 0f)
            when {
                pulledDrawer -> state.settle(velocity)
                // Far enough, or flung without coming back.
                dragged < -threshold || (velocity < -FlingVelocity && dragged < 0f) -> gestures.perform(up, state, runtime)
                dragged > threshold || (velocity > FlingVelocity && dragged > 0f) -> gestures.perform(down, state, runtime)
            }
        },
    )
}

/** How far a swipe goes before it runs an action that isn't the drawer. */
private val SwipeAction = 96.dp

/** px/s: a flick this fast counts however short it was, as [LauncherState.settle] counts one for the drawer. */
private const val FlingVelocity = 800f

/** Gesture settings: what each gesture on home does, picked from a dropdown. */
@Composable
fun GesturesScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
) {
    val gestures = runtime.gestures
    OverlayPanel("gestures", onDismiss, contentWidth = ReadingWidth) {
        GestureRow("swipe up", gestures.swipeUp) { gestures.swipeUp = it }
        GestureRow("swipe down", gestures.swipeDown) { gestures.swipeDown = it }
        GestureRow("double tap", gestures.doubleTap) { gestures.doubleTap = it }
    }
}

/** A gesture and the action it runs; tapping the action lists the others under it. */
@Composable
private fun GestureRow(
    label: String,
    action: GestureAction,
    onPick: (GestureAction) -> Unit,
) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.card(onClick = { open = !open }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, Modifier.weight(1f), style = Mono.copy(fontSize = TextSize.Item))
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(action.label, style = Mono.copy(color = Palette.accent, fontSize = TextSize.Item))
                Symbol(if (open) Glyph.ChevronUp else Glyph.ChevronDown, Palette.muted, Modifier.padding(start = 4.dp))
            }
            if (open) {
                ChoicePopup(
                    GestureAction.entries,
                    action,
                    BelowAnchorEnd,
                    onDismiss = { open = false },
                    onPick = { picked ->
                        onPick(picked)
                        // Locking needs the accessibility service: take the user to turn it on while they're at it.
                        if (picked == GestureAction.Lock && !LockService.enabled(context)) LockService.requestAccess(context)
                    },
                    label = { it.label },
                )
            }
        }
    }
}

/** Just under the anchor, lined up with its end, and kept on screen. */
private object BelowAnchorEnd : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ) = IntOffset(
        (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
        (anchorBounds.bottom + 8).coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0)),
    )
}
