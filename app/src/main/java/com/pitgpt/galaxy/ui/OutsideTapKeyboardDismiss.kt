package com.pitgpt.galaxy.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.SoftwareKeyboardController

/**
 * Clears text focus when a tap lands outside every registered text field.
 *
 * The observer runs in the final pointer pass and never consumes events, so
 * child buttons, scroll containers and text fields keep their normal behavior.
 */
@Stable
class OutsideTapKeyboardDismissState internal constructor(
    private val focusManager: FocusManager,
    private val keyboardController: SoftwareKeyboardController?,
    private val touchSlop: Float
) {
    private val textFieldBounds = mutableStateMapOf<String, Rect>()
    private var rootCoordinates: LayoutCoordinates? = null

    fun rootModifier(): Modifier =
        Modifier
            .onGloballyPositioned { rootCoordinates = it }
            .pointerInput(this) {
                awaitPointerEventScope {
                    var downX = 0f
                    var downY = 0f
                    var hasDown = false
                    var movedBeyondSlop = false

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        event.changes.forEach { change ->
                            when {
                                change.changedToDownIgnoreConsumed() -> {
                                    downX = change.position.x
                                    downY = change.position.y
                                    hasDown = true
                                    movedBeyondSlop = false
                                }

                                change.changedToUpIgnoreConsumed() -> {
                                    val coordinates = rootCoordinates
                                    if (hasDown && !movedBeyondSlop && coordinates != null) {
                                        val rootPosition = coordinates.localToRoot(change.position)
                                        val tappedTextField = textFieldBounds.values.any {
                                            it.contains(rootPosition)
                                        }
                                        if (!tappedTextField) {
                                            focusManager.clearFocus(force = true)
                                            keyboardController?.hide()
                                        }
                                    }
                                    hasDown = false
                                    movedBeyondSlop = false
                                }

                                change.pressed && hasDown -> {
                                    val dx = change.position.x - downX
                                    val dy = change.position.y - downY
                                    if (dx * dx + dy * dy > touchSlop * touchSlop) {
                                        movedBeyondSlop = true
                                    }
                                }
                            }
                        }
                    }
                }
            }

    fun textFieldModifier(key: String): Modifier =
        Modifier.onGloballyPositioned { textFieldBounds[key] = it.boundsInRoot() }
}

@Composable
fun rememberOutsideTapKeyboardDismissState(): OutsideTapKeyboardDismissState {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    return remember(focusManager, keyboardController, touchSlop) {
        OutsideTapKeyboardDismissState(focusManager, keyboardController, touchSlop)
    }
}
