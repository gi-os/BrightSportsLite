package com.gios.brightsportslite.ui

import android.view.KeyEvent

/** The wheel's two directions, as they arrive from the hardware. */
enum class WheelTurn { Up, Down }

/**
 * Recognising the Light Phone III's scroll wheel.
 *
 * The wheel is an optical sensor (`Pixart pat9126ja`) that emits one DOWN+UP key pair per
 * notch. LightOS relabels its scancodes in `Generic.kl`:
 *
 * ```
 * key 19    WHEEL_CCW      # wheel up
 * key 20    WHEEL_CW       # wheel down
 * ```
 *
 * Those labels are Light's own additions rather than AOSP keycodes, so their integer values are
 * resolved at runtime from the label, with the raw scancode as a fallback that is only trusted
 * when it comes from the wheel's own device (scancode 19 is also `r` on a Bluetooth keyboard).
 */
object LightKeys {
    private const val SCAN_WHEEL_UP = 19
    private const val SCAN_WHEEL_DOWN = 20
    private const val WHEEL_DEVICE = "Pixart pat9126ja"

    private val byKeyCode: Map<Int, WheelTurn> = buildMap {
        label("WHEEL_CCW")?.let { put(it, WheelTurn.Up) }
        label("WHEEL_CW")?.let { put(it, WheelTurn.Down) }
    }

    private fun label(name: String): Int? {
        val code = runCatching { KeyEvent.keyCodeFromString(name) }.getOrDefault(KeyEvent.KEYCODE_UNKNOWN)
        return code.takeIf { it != KeyEvent.KEYCODE_UNKNOWN }
    }

    /** Which way the wheel turned for [event], or null if it was some other key. */
    fun of(keyCode: Int, event: KeyEvent): WheelTurn? {
        byKeyCode[keyCode]?.let { return it }
        if (event.device?.name != WHEEL_DEVICE) return null
        return when (event.scanCode) {
            SCAN_WHEEL_UP -> WheelTurn.Up
            SCAN_WHEEL_DOWN -> WheelTurn.Down
            else -> null
        }
    }
}
