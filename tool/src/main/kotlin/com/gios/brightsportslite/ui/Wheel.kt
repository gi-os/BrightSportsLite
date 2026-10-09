package com.gios.brightsportslite.ui

import android.view.KeyEvent
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.thelightphone.sdk.LightViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A view model that hears the wheel. One notch is a full DOWN+UP pair; the DOWN is turned into a
 * step (+1 down, -1 up) and both halves are consumed so nothing else reacts to the UP.
 */
abstract class WheelViewModel<R> : LightViewModel<R>() {
    private val _wheel = MutableSharedFlow<Int>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val wheel: SharedFlow<Int> = _wheel.asSharedFlow()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val turn = LightKeys.of(keyCode, event) ?: return super.onKeyDown(keyCode, event)
        _wheel.tryEmit(if (turn == WheelTurn.Down) 1 else -1)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (LightKeys.of(keyCode, event) != null) return true
        return super.onKeyUp(keyCode, event)
    }
}

/** How far one notch scrolls, in pixels. About two lines of copy. */
private const val NOTCH_PX = 140

/** Scrolls [state] by the wheel while this composable is on screen. */
@Composable
fun WheelScroll(wheel: SharedFlow<Int>, state: ScrollState) {
    LaunchedEffect(wheel, state) {
        // Instant rather than animated: a quick spin is several notches inside one animation,
        // and queued animations make the page trail behind the wheel.
        wheel.collect { step -> state.scrollTo((state.value + step * NOTCH_PX).coerceIn(0, state.maxValue)) }
    }
}
