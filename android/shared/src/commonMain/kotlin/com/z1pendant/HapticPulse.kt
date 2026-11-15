package com.z1pendant

/** Pulses for phones without vibration primitives (plain vibrator with amplitude control). */
object HapticPulse {
    /** Strength 0..1 as a vibrator amplitude, 1..255 (0 would mean "off" to Android). */
    fun amplitude(strength: Float): Int = (strength.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(1, 255)
}
