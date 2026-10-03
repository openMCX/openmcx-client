package org.openmcx.client.app.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/** Call types with a Transmit button: in push and pull the sender transmits by itself. */
private val PLAIN_MODES = setOf("private", "prearranged", "chat")

/** A SurfaceView that reports its surface as it comes and goes. */
@Composable
private fun VideoSurface(modifier: Modifier, onSurface: (android.view.Surface?) -> Unit) = AndroidView(
    factory = { context ->
        SurfaceView(context).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = onSurface(holder.surface)
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                override fun surfaceDestroyed(holder: SurfaceHolder) = onSurface(null)
            })
        }
    },
    modifier = modifier,
)

/**
 * The MCVideo call: the transmitter's video full screen, our camera in a small preview while we
 * transmit, and a hold-to-transmit button (TS 24.581 transmission control, like PTT).
 */
@Composable
fun VideoCallScreen(call: VideoCallItem, actions: UiActions) {
    Box(Modifier.fillMaxSize().background(Color.Black).testTag(Tags.VIDEO_SCREEN)) {
        // Emergency and imminent peril are shown above everything (TS 24.281 §6.2.8).
        // The received picture at its own shape (portrait from an upright phone), centred with black bars.
        val aspect = call.videoAspect
        VideoSurface(if (aspect != null) Modifier.align(Alignment.Center).fillMaxWidth().aspectRatio(aspect, matchHeightConstraintsFirst = aspect < 1f)
            .testTag(Tags.VIDEO_REMOTE) else Modifier.fillMaxSize()) { actions.remoteSurface(it) }
        Column(Modifier.fillMaxWidth().background(Color(0x99000000)).padding(12.dp)) {
            when (call.priority) {
                "emergency" -> Text("EMERGENCY", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFB91C1C)).padding(6.dp).testTag(Tags.VIDEO_PRIORITY))
                "imminent" -> Text("IMMINENT PERIL", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFF59E0B)).padding(6.dp).testTag(Tags.VIDEO_PRIORITY))
            }
            Text(when { call.chat -> "${call.peer} (video chat)"; call.group != null -> "${call.peer} (group video)"; else -> call.peer }, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            val status = when {
                call.state == "ringing" && call.incoming -> "Incoming video call"
                call.state == "established" && call.mode == "one-to-one video push" ->
                    if (call.incoming) "${call.peer} is pushing video to you" else "Pushing your video to ${call.peer}"
                call.state == "established" && call.mode == "one-to-one video pull" ->
                    if (call.incoming) "${call.peer} is watching your camera" else "Watching ${call.peer}'s camera"
                call.state == "established" && call.mode == "one-to-server video push" ->
                    "Recording your video on the server" + (call.recordedAt?.let { " (saved)" } ?: "")
                call.state == "established" && call.mode == "one-from-server video pull" -> "Playing a recording from the server"
                call.state == "established" && call.ambientRole == "viewing" -> "Viewing ${call.peer}'s camera (ambient)"
                call.state == "established" && call.ambientRole == "viewed-to" -> "${call.peer} is viewing your camera (ambient)"
                call.state != "established" -> if (call.state == "ringing") "Ringing…" else "Connecting…"
                call.transmission == "transmitting" -> "You are transmitting"
                call.transmission == "requesting" -> "Requesting to transmit…"
                call.transmission == "receiving" -> "${call.transmitter ?: "The other side"} is transmitting"
                call.transmission == "two-way" -> "Two-way video"
                else -> "Hold Transmit to send your camera and voice"
            }
            Text(status, color = Color(0xFFFFCC80), fontSize = 14.sp, modifier = Modifier.testTag(Tags.VIDEO_STATUS))
            if (call.group != null && call.participants.isNotEmpty())
                Text("In call (${call.participants.size}): ${call.participants.joinToString()}", color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.testTag(Tags.VIDEO_PARTICIPANTS))
            Text("sent ${call.picturesSent} · shown ${call.picturesShown}", color = Color(0xFFBDBDBD), fontSize = 11.sp)
            // In a group call: start or end another participant's transmission (TS 24.581 remote transmission).
            if (call.group != null && call.participantIds.isNotEmpty()) Row {
                call.participantIds.forEach { id ->
                    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { open = true }, modifier = Modifier.testTag(Tags.videoParticipant(id))) { Text(id.short(), color = Color.White) }
                        androidx.compose.material3.DropdownMenu(open, { open = false }) {
                            androidx.compose.material3.DropdownMenuItem(text = { Text("Start their video") }, onClick = { open = false; actions.videoRemote(id, true) })
                            androidx.compose.material3.DropdownMenuItem(text = { Text("Stop their video") }, onClick = { open = false; actions.videoRemote(id, false) })
                        }
                    }
                }
            }
            if (call.state == "established" && call.transmissionControl && call.mode in PLAIN_MODES) Row {
                val emergency = call.priority == "emergency"
                TextButton(onClick = { actions.videoPriority(true, !emergency) }, modifier = Modifier.testTag(Tags.VIDEO_EMERGENCY)) {
                    Text(if (emergency) "Cancel emergency" else "Emergency", color = Color(0xFFFCA5A5))
                }
                if (call.group != null && !emergency) {
                    val peril = call.priority == "imminent"
                    TextButton(onClick = { actions.videoPriority(false, !peril) }, modifier = Modifier.testTag(Tags.VIDEO_IMMINENT)) {
                        Text(if (peril) "End imminent peril" else "Imminent peril", color = Color(0xFFFCD34D))
                    }
                }
            }
        }
        // Our camera, while it runs (transmitting, or always in two-way video).
        VideoSurface(Modifier.align(Alignment.TopEnd).padding(top = 90.dp, end = 12.dp).size(96.dp, 128.dp)
            .border(1.dp, Color.White, RoundedCornerShape(6.dp))) { actions.previewSurface(it) }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x99000000)).padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            if (call.state == "ringing" && call.incoming) {
                // Manual commencement (TS 24.281 §10.2.2.2.2): the user decides.
                Button(onClick = actions::videoDecline, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB91C1C)),
                    modifier = Modifier.testTag(Tags.VIDEO_DECLINE)) { Text("Decline") }
                Button(onClick = actions::videoAnswer, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF15803D)),
                    modifier = Modifier.testTag(Tags.VIDEO_ANSWER)) { Text("Answer") }
                return@Row
            }
            TextButton(onClick = actions::switchCamera) { Text("Flip", color = Color.White) }
            if (call.transmissionControl && call.state == "established" && call.mode in PLAIN_MODES) {
                val transmitting = call.transmission == "transmitting"
                Box(Modifier.size(96.dp).background(if (transmitting) Color(0xFFC2410C) else Color(0xFF292524), CircleShape)
                    .testTag(Tags.VIDEO_TRANSMIT)
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            actions.videoTransmit(true)
                            tryAwaitRelease()
                            actions.videoTransmit(false)
                        })
                    }, contentAlignment = Alignment.Center) {
                    Text(if (transmitting) "LIVE" else "Transmit", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            Button(onClick = actions::videoHangup, enabled = call.mayRelease, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB91C1C)),
                modifier = Modifier.testTag(Tags.VIDEO_HANGUP)) { Text("End") }
        }
    }
}
