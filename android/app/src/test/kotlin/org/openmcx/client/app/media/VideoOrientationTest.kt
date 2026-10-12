//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sent picture is upright and unmirrored for every sensor orientation, phone orientation and
 * camera, with the SurfaceTexture's transform matrix doing what Camera2 sets on a preview stream:
 * the frame turned upright for the phone's natural orientation, mirrored for the front camera.
 * Pictures are modelled as 2×2 matrices on (x right, y up); a turn is clockwise.
 */
class VideoOrientationTest {
    private val identity = listOf(1, 0, 0, 1)
    private val mirror = listOf(-1, 0, 0, 1)

    private fun times(a: List<Int>, b: List<Int>) = listOf(
        a[0] * b[0] + a[1] * b[2], a[0] * b[1] + a[1] * b[3],
        a[2] * b[0] + a[3] * b[2], a[2] * b[1] + a[3] * b[3],
    )

    /** A clockwise turn by [degrees] (a multiple of 90). */
    private fun turn(degrees: Int): List<Int> = when ((degrees % 360 + 360) % 360) {
        0 -> identity
        90 -> listOf(0, 1, -1, 0)
        180 -> listOf(-1, 0, 0, -1)
        else -> listOf(0, -1, 1, 0)
    }

    /** The sent picture: raw frame, then the transform matrix (sensor turn, front mirror), then our turn and mirror. */
    private fun sent(sensor: Int, device: Int, front: Boolean): List<Int> {
        val upright = VideoOrientation.uprightRotation(sensor, device, front)
        val raw = turn(-upright) // a raw frame needs `upright` clockwise to be upright
        val matrix = if (front) times(mirror, turn(sensor)) else turn(sensor)
        var picture = times(matrix, raw)
        picture = times(turn(VideoOrientation.textureTurn(upright, sensor, front)), picture)
        if (VideoOrientation.mirrorSent(front)) picture = times(mirror, picture)
        return picture
    }

    @Test fun everySentPictureIsUprightAndUnmirrored() {
        for (sensor in listOf(0, 90, 180, 270)) for (device in listOf(0, 90, 180, 270)) for (front in listOf(false, true))
            assertEquals("sensor $sensor, phone $device, front $front", identity, sent(sensor, device, front))
    }

    @Test fun anUprightPhoneNeedsNoFurtherTurn() {
        // The cause of the 90° tilt: the frame is upright after the transform matrix; turning it by the sensor
        // orientation again tilts it.
        assertEquals(0, VideoOrientation.textureTurn(VideoOrientation.uprightRotation(90, 0, false), 90, false))
        assertEquals(0, VideoOrientation.textureTurn(VideoOrientation.uprightRotation(270, 0, true), 270, true))
    }

    @Test fun theRemainingTurnIsHowThePhoneIsHeld() {
        for (device in listOf(0, 90, 180, 270)) {
            assertEquals(device, VideoOrientation.textureTurn(VideoOrientation.uprightRotation(90, device, false), 90, false))
            assertEquals(device, VideoOrientation.textureTurn(VideoOrientation.uprightRotation(270, device, true), 270, true))
        }
    }

    @Test fun theUprightPictureShapeFollowsTheFullTurn() {
        assertEquals(90, VideoOrientation.uprightRotation(90, 0, false)) // portrait: the camera size's sides swapped
        assertEquals(0, VideoOrientation.uprightRotation(90, 270, false) % 180) // held landscape: landscape
    }
}
