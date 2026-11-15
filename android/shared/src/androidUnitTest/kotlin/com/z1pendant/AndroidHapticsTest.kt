package com.z1pendant

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The strength slider on phones without vibration primitives (e.g. the Galaxy S21). */
class AndroidHapticsTest {
    private val vib = mockk<Vibrator>(relaxed = true)

    private fun haptics(primitives: Boolean, amplitude: Boolean): AndroidHaptics {
        every { vib.areAllPrimitivesSupported(*anyIntVararg()) } returns primitives
        every { vib.hasAmplitudeControl() } returns amplitude
        val mgr = mockk<VibratorManager> { every { defaultVibrator } returns vib }
        val ctx = mockk<Context> { every { getSystemService(Context.VIBRATOR_MANAGER_SERVICE) } returns mgr }
        return AndroidHaptics(ctx)
    }

    @Before fun mockEffects() {
        mockkStatic(VibrationEffect::class)
        every { VibrationEffect.createOneShot(any(), any()) } returns mockk()
        every { VibrationEffect.createPredefined(any()) } returns mockk()
    }

    @After fun unmock() = unmockkStatic(VibrationEffect::class)

    @Test fun amplitudeFollowsTheStrength() {
        assertEquals(255, HapticPulse.amplitude(1f))
        assertEquals(128, HapticPulse.amplitude(0.5f))
        assertEquals(1, HapticPulse.amplitude(0.001f))      // never 0: Android takes that as "off"
        assertEquals(255, HapticPulse.amplitude(2f))
    }

    @Test fun noPrimitivesButAmplitude_pulsesAtTheSliderStrength() {
        val h = haptics(primitives = false, amplitude = true)
        assertTrue(h.strengthAdjustable)
        h.strength = 0.5f
        h.tick()                                           // tick = 0.8 × strength
        verify { VibrationEffect.createOneShot(12, HapticPulse.amplitude(0.4f)) }
        h.strength = 1f
        h.tick()
        verify { VibrationEffect.createOneShot(12, HapticPulse.amplitude(0.8f)) }
        verify(exactly = 0) { VibrationEffect.createPredefined(any()) }
    }

    @Test fun noPrimitivesNoAmplitude_saysTheSliderIsOnOffOnly() {
        val h = haptics(primitives = false, amplitude = false)
        assertFalse(h.strengthAdjustable)
        h.tick()
        verify { VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK) }
        h.strength = 0f
        h.tick()
        verify(exactly = 1) { vib.vibrate(any<VibrationEffect>()) }   // 0 % = off
    }
}
