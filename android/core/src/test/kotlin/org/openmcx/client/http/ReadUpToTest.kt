//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.http

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/** readUpTo: the bytes InputStream.readNBytes(int) reads, on every Android version (no API 33 method). */
class ReadUpToTest {
    /** A stream that hands out at most [chunk] bytes per read, as a socket may. */
    private class Trickle(private val data: ByteArray, private val chunk: Int) : InputStream() {
        private var position = 0
        override fun read(): Int = if (position < data.size) data[position++].toInt() and 0xff else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(len, chunk, data.size - position)
            System.arraycopy(data, position, b, off, n)
            position += n
            return n
        }
    }

    @Test
    fun exactLengthAcrossShortReads() {
        val data = ByteArray(1000) { it.toByte() }
        assertArrayEquals(data, readUpTo(Trickle(data, 7), 1000))
    }

    @Test
    fun stopsAtTheLengthAskedFor() {
        val data = "0123456789".toByteArray()
        val input = Trickle(data, 3)
        assertArrayEquals("01234".toByteArray(), readUpTo(input, 5))
        assertArrayEquals("56789".toByteArray(), readUpTo(input, 5))
    }

    @Test
    fun fewerBytesAtTheEndOfTheStream() {
        assertArrayEquals("abc".toByteArray(), readUpTo(ByteArrayInputStream("abc".toByteArray()), 10))
        assertArrayEquals(ByteArray(0), readUpTo(ByteArrayInputStream(ByteArray(0)), 4))
    }

    @Test
    fun zeroAndNegativeLengths() {
        assertArrayEquals(ByteArray(0), readUpTo(ByteArrayInputStream("x".toByteArray()), 0))
        assertThrows(IllegalArgumentException::class.java) { readUpTo(ByteArrayInputStream(ByteArray(0)), -1) }
    }
}
