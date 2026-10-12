package org.openmcx.client.app.media

/**
 * How a camera frame is turned upright for sending. Camera2 sets a rotation transform on every
 * output stream (the sensor orientation, and a horizontal mirror for the front camera), and a
 * SurfaceTexture applies it in its transform matrix: a frame sampled through that matrix is already
 * upright for the phone held in its natural orientation, and mirrored when it comes from the front
 * camera. What is left to do is the way the phone is held, and, for the sent picture, undoing the
 * front camera's mirror (the selfie view keeps it).
 */
object VideoOrientation {
    /**
     * How far to turn a camera frame clockwise to be upright, from the sensor orientation and the
     * phone's orientation (OrientationEventListener degrees rounded to 0/90/180/270), as for
     * JPEG_ORIENTATION. Its parity gives the shape of the upright picture (turned by 90 or 270: the
     * camera size's sides swapped).
     */
    fun uprightRotation(sensorOrientation: Int, deviceOrientation: Int, frontFacing: Boolean): Int =
        if (frontFacing) (sensorOrientation - deviceOrientation + 360) % 360 else (sensorOrientation + deviceOrientation) % 360

    /**
     * The clockwise turn still to apply to a frame sampled through the SurfaceTexture's transform
     * matrix, which already turned it by the sensor orientation (and mirrored it for the front camera):
     * [upright] (from [uprightRotation]) less the sensor orientation. In the front camera's mirrored
     * picture a turn goes the other way, so the remaining turn is the phone's orientation for both cameras.
     */
    fun textureTurn(upright: Int, sensorOrientation: Int, frontFacing: Boolean): Int =
        if (frontFacing) (sensorOrientation - upright + 360) % 360 else (upright - sensorOrientation + 360) % 360

    /** The sent picture undoes the front camera's mirror; the local preview keeps it (a selfie view). */
    fun mirrorSent(frontFacing: Boolean): Boolean = frontFacing
}
