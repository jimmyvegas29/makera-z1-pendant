package com.z1pendant

import com.z1pendant.run.Anchor
import com.z1pendant.run.AnchorJson
import com.z1pendant.run.AnchorsController
import com.z1pendant.run.Origin
import com.z1pendant.run.RunCommands
import com.z1pendant.run.RunSetup
import com.z1pendant.run.WorkOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Custom anchors: storage, the `$#` reply, and the origin commands Run sends for one. */
class AnchorsTest {
    private val vise = Anchor("a1", "Vise fixed jaw", -150.25, -110.6, -82.4, "3D probe", 1_795_000_000)
    private val bracket = Anchor("a2", "Bracket corner", -190.612, -194.531, null, "Typed in")
    private val edge = Anchor("a3", "Fence", -120.0, null, null, "3D probe")

    @Test fun jsonRoundTripKeepsMissingAxesMissing() {
        val back = AnchorJson.decode(AnchorJson.encode(listOf(vise, bracket, edge)))
        assertEquals(listOf(vise, bracket, edge), back)
        assertNull(back[2].y)
        assertEquals(emptyList<Anchor>(), AnchorJson.decode(null))
        assertEquals(emptyList<Anchor>(), AnchorJson.decode("not json"))
    }

    @Test fun workOffsetFromTheFirmwaresHashReply() {
        assertEquals(Triple(-150.25, -110.6, -82.4), WorkOffset.parse("[G54:-150.2500,-110.6000,-82.4000,0.0000,0.0000]"))
        assertNull(WorkOffset.parse("[G55:0.0000,0.0000,0.0000,0.0000,0.0000]"))
        assertNull(WorkOffset.parse("[PRB:-9.691,-13.580,-76.677:1]"))
    }

    @Test fun customAnchorWithZSetsXYZ() {
        val s = RunSetup().withAnchor(vise).copy(aOffX = 2.0, aOffY = -1.5)
        assertTrue(s.anchorSetsZ)
        assertFalse("its own Z: no Z probe needed", s.zOn)
        assertEquals(listOf("G10L2P0X-148.250Y-112.100Z-82.400"), RunCommands.originLines(s, null))
    }

    @Test fun savedZTurnedOffMeansTheZProbe() {
        val s = RunSetup().withAnchor(vise).copy(useAnchorZ = false)
        assertFalse(s.anchorSetsZ)
        assertTrue("locked on, as with Anchor 1", s.probeZLocked && s.zOn)
        assertEquals(listOf("G10L2P0X-150.250Y-110.600"), RunCommands.originLines(s, null))
    }

    @Test fun anchorWithoutZLocksTheZProbe() {
        val s = RunSetup().withAnchor(bracket)
        assertTrue(s.probeZLocked && s.zOn)
        assertEquals(listOf("G10L2P0X-190.612Y-194.531"), RunCommands.originLines(s, null))
    }

    @Test fun edgeAnchorSetsOnlyItsAxis() {
        val s = RunSetup().withAnchor(edge).copy(aOffX = 5.0)
        assertEquals(listOf("G10L2P0X-115.000"), RunCommands.originLines(s, null))
    }

    @Test fun pickingAnAnchorResetsItsOffsetsButNotAnchor1s() {
        val s = RunSetup(offX = 3.0, offY = 4.0).withAnchor(vise).copy(aOffX = 9.0).withAnchor(bracket)
        assertEquals(Origin.CUSTOM, s.origin)
        assertEquals(0.0, s.aOffX, 0.0)
        assertEquals(3.0, s.offX, 0.0)
    }

    @Test fun compareReportsTheLargestChange() {
        val c = AnchorsController.Compare(vise, -150.236, -110.609, -82.9)
        assertEquals(0.5, c.maxChange, 1e-9)
        val xOnly = AnchorsController.Compare(bracket, -190.6, null, -80.0)
        assertEquals("Z isn't compared when the anchor has none", 0.012, xOnly.maxChange, 1e-9)
    }
}
