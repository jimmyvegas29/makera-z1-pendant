package com.z1pendant

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Detent feel for the handwheel. Uses the vibration motor's "primitives" (short, sharp waveforms
 * tuned per device; both the Pixel 10 and S24 Ultra support them). Phones without them (e.g. the
 * Galaxy S21) get short pulses at a set amplitude instead, so the strength slider still works. A
 * phone without amplitude control either gets the predefined tick/click effects: on or off only.
 */
class AndroidHaptics(ctx: Context) : Haptics {
    private val vib: Vibrator =
        (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator

    /** 0 = off, 1 = full. */
    @Volatile override var strength: Float = 0.7f

    /**
     * Plays as a "physical emulation" vibration (Android 13+): the detents stand in for a real
     * handwheel. Without attributes Android files short primitives as touch feedback, so turning
     * off the phone's Touch feedback (a common choice, it also silences the keyboard) silenced
     * the dial too.
     */
    private val attrs: VibrationAttributes? =
        if (Build.VERSION.SDK_INT >= 33) VibrationAttributes.createForUsage(VibrationAttributes.USAGE_PHYSICAL_EMULATION) else null

    private fun play(effect: VibrationEffect) {
        if (attrs != null) vib.vibrate(effect, attrs) else vib.vibrate(effect)
    }

    private val hasTick = vib.areAllPrimitivesSupported(Composition.PRIMITIVE_TICK)
    private val hasClick = vib.areAllPrimitivesSupported(Composition.PRIMITIVE_CLICK)
    private val hasThud = vib.areAllPrimitivesSupported(Composition.PRIMITIVE_THUD)
    private val hasLowTick = vib.areAllPrimitivesSupported(Composition.PRIMITIVE_LOW_TICK)
    private val hasAmplitude = vib.hasAmplitudeControl()
    override val description: String
        get() = "primitives: tick=$hasTick click=$hasClick thud=$hasThud lowTick=$hasLowTick · amplitude=$hasAmplitude"
    override val strengthAdjustable: Boolean get() = hasTick || hasClick || hasAmplitude

    /**
     * One primitive at [scale] × strength; without it, a [pulseMs] pulse at that amplitude; without
     * amplitude control, the predefined [fallback] effect (fixed strength).
     */
    private fun prim(p: Int, supported: Boolean, scale: Float, pulseMs: Long, fallback: Int) {
        if (strength <= 0f) return
        val s = (scale * strength).coerceIn(0.05f, 1f)
        val effect = when {
            supported -> VibrationEffect.startComposition().addPrimitive(p, s).compose()
            hasAmplitude -> VibrationEffect.createOneShot(pulseMs, HapticPulse.amplitude(s))
            else -> VibrationEffect.createPredefined(fallback)
        }
        play(effect)
    }

    /** One detent. */
    override fun tick() = prim(Composition.PRIMITIVE_TICK, hasTick, 0.8f, 12, VibrationEffect.EFFECT_TICK)
    /** Every 10th detent. */
    override fun majorTick() = prim(Composition.PRIMITIVE_CLICK, hasClick, 0.9f, 20, VibrationEffect.EFFECT_CLICK)
    /** Clicks dropped because the machine can't keep up, or blocked (alarm, disconnected). */
    override fun thud() = prim(if (hasThud) Composition.PRIMITIVE_THUD else Composition.PRIMITIVE_CLICK,
        hasThud || hasClick, 1f, 40, VibrationEffect.EFFECT_HEAVY_CLICK)
    /** Soft-limit reached: a double knock. */
    override fun limit() {
        if (strength <= 0f) return
        when {
            hasClick -> play(VibrationEffect.startComposition()
                .addPrimitive(Composition.PRIMITIVE_CLICK, strength)
                .addPrimitive(Composition.PRIMITIVE_CLICK, strength, 60).compose())
            hasAmplitude -> HapticPulse.amplitude(strength).let { a ->
                play(VibrationEffect.createWaveform(longArrayOf(0, 20, 60, 20), intArrayOf(0, a, 0, a), -1))
            }
            else -> play(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
        }
    }
    /** Button press confirmation. */
    override fun press() = prim(if (hasLowTick) Composition.PRIMITIVE_LOW_TICK else Composition.PRIMITIVE_TICK,
        hasLowTick || hasTick, 1f, 12, VibrationEffect.EFFECT_TICK)
}
