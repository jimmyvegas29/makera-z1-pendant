package com.z1pendant

/** Detent feel for the handwheel and button feedback. Android: vibrator primitives; iOS: UIKit feedback generators. */
interface Haptics {
    /** 0 = off, 1 = full. */
    var strength: Float
    /** Shown in Settings (what the hardware supports). */
    val description: String
    /** False when the phone can only vibrate at one strength: then [strength] only switches it on or off. */
    val strengthAdjustable: Boolean get() = true

    /** One detent. */
    fun tick()
    /** Every 10th detent. */
    fun majorTick()
    /** Clicks dropped because the machine can't keep up, or blocked (alarm, disconnected). */
    fun thud()
    /** Soft-limit reached: a double knock. */
    fun limit()
    /** Button press confirmation. */
    fun press()
}
