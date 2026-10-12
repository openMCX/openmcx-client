//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class JitterBufferTest {
    private fun frame(n: Int) = AmrWb.Frame(2, true, byteArrayOf(n.toByte()))

    private fun played(b: JitterBuffer, count: Int) = List(count) { b.pull() }

    @Test
    fun startsAtTargetDepthAndPlaysInOrder() {
        val b = JitterBuffer(minDepth = 2)
        b.push(1000, listOf(frame(0)), 0)
        assertEquals(JitterBuffer.Pull.Silence, b.pull())
        b.push(1320, listOf(frame(1)), 20)
        b.push(1640, listOf(frame(2)), 40)
        val out = played(b, 3).map { (it as JitterBuffer.Pull.Frame).frame.speech[0].toInt() }
        assertEquals(listOf(0, 1, 2), out)
    }

    @Test
    fun reordersAndConcealsLoss() {
        val b = JitterBuffer(minDepth = 3)
        b.push(0, listOf(frame(0)), 0)
        b.push(640, listOf(frame(2)), 20)   // frame 1 lost
        b.push(960, listOf(frame(3)), 25)
        b.push(1280, listOf(frame(4)), 60)
        val out = played(b, 5)
        assertEquals(JitterBuffer.Pull.Frame(frame(0)), out[0])
        assertEquals(JitterBuffer.Pull.Lost, out[1])
        assertEquals(JitterBuffer.Pull.Frame(frame(2)), out[2])
        assertEquals(1, b.concealed)
    }

    @Test
    fun multiFramePacketsAndTimestampWrap() {
        val b = JitterBuffer(minDepth = 2)
        b.push(0xffffff00L, listOf(frame(0), frame(1)), 0)
        b.push((0xffffff00L + 640) and 0xffffffffL, listOf(frame(2), frame(3)), 40)
        val out = played(b, 4).map { (it as JitterBuffer.Pull.Frame).frame.speech[0].toInt() }
        assertEquals(listOf(0, 1, 2, 3), out)
    }

    @Test
    fun targetGrowsWithJitter() {
        val calm = JitterBuffer()
        val rough = JitterBuffer()
        val rnd = Random(1)
        for (i in 0 until 200) {
            calm.push(i * 320L, listOf(frame(i)), i * 20L)
            rough.push(i * 320L, listOf(frame(i)), i * 20L + rnd.nextLong(0, 80))
        }
        assertEquals(2, calm.target)
        assertTrue("rough target ${rough.target}", rough.target > calm.target)
    }
}
