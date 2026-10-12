//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app.telecom

import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.openmcx.client.app.ui.CallDisplay
import org.openmcx.client.mcptt.Call
import org.openmcx.client.mcptt.CallType
import org.openmcx.client.mcptt.McpttService

/**
 * Tells Android's telecom stack about MCPTT calls (Jetpack core-telecom, a self-managed
 * VoIP account): the system then routes audio (earpiece, speaker, Bluetooth, car), keeps a
 * cellular call and an MCPTT call from both holding audio, and lets a headset answer or end
 * the call. The MCPTT call itself stays in [McpttService].
 */
class TelecomBridge(private val context: Context, private val scope: CoroutineScope, private val mcptt: McpttService) {
    private val calls = CallsManager(context)
    private val registered = ConcurrentHashMap<Int, Boolean>()
    @Volatile var lastError: String? = null
        private set
    private val _speaker = MutableStateFlow<Boolean?>(null)
    /** Whether the call's audio is on the loudspeaker; null while telecom has no call. */
    val speaker: StateFlow<Boolean?> = _speaker
    @Volatile private var control: CallControlScope? = null
    @Volatile private var endpoints: List<CallEndpointCompat> = emptyList()

    init {
        runCatching { calls.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE) }
            .onFailure { lastError = it.message; Log.w(TAG, "telecom registration failed", it) }
    }

    /** Called when an MCPTT call appears (outgoing setup or incoming offer). */
    fun track(call: Call) {
        if (registered.putIfAbsent(call.id, true) != null) return
        val name = when (call.type) {
            CallType.PRIVATE -> CallDisplay.caller(call.peer, call.callingAlias, call.calledAlias, "MCPTT")
            else -> call.groupId ?: call.peer ?: "MCPTT group"
        }
        val attributes = CallAttributesCompat(
            displayName = name.removePrefix("sip:"),
            address = Uri.parse(call.groupId ?: call.peer ?: "sip:mcptt"),
            direction = if (call.outgoing) CallAttributesCompat.DIRECTION_OUTGOING else CallAttributesCompat.DIRECTION_INCOMING,
            callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            callCapabilities = 0,
        )
        scope.launch {
            try {
                calls.addCall(
                    attributes,
                    onAnswer = { mcptt.answer() },
                    onDisconnect = { if (call.state != "released") mcptt.hangup(call) },
                    onSetActive = {},
                    onSetInactive = {},
                ) {
                    control = this
                    val routes = launch { availableEndpoints.collect { endpoints = it } }
                    val route = launch { currentCallEndpoint.collect { _speaker.value = it.type == CallEndpointCompat.TYPE_SPEAKER } }
                    launch {
                        mcptt.calls.first { list -> list.none { it.id == call.id && it.state != "released" } || call.state == "established" }
                        // Telecom may still be setting the call up; retry until it takes the call active.
                        var attempt = 0
                        while (call.state == "established" && attempt++ < 10) {
                            val result = setActive()
                            Log.i(TAG, "call ${call.id} setActive: $result")
                            if (result is CallControlResult.Success) break
                            delay(300)
                        }
                        mcptt.calls.first { list -> list.none { it.id == call.id } }
                        disconnect(DisconnectCause(if (call.releaseReason == "released") DisconnectCause.LOCAL else DisconnectCause.REMOTE))
                        routes.cancel(); route.cancel()
                    }
                }
            } catch (e: Exception) {
                lastError = e.message
                Log.w(TAG, "telecom call ${call.id} failed", e)
            } finally {
                registered.remove(call.id)
                if (registered.isEmpty()) { control = null; _speaker.value = null }
            }
        }
    }

    /** For the status view: the endpoints telecom offers, and the last endpoint change's result. */
    val endpointNames: List<String> get() = endpoints.map { "${it.type}:${it.name}" }
    @Volatile var lastEndpointResult: String? = null
        private set

    /** Loudspeaker on, or back to a headset (Bluetooth or wired) if one is there, else the earpiece. */
    fun setSpeaker(on: Boolean) {
        val c = control ?: return
        val target = if (on) endpoints.firstOrNull { it.type == CallEndpointCompat.TYPE_SPEAKER }
        else endpoints.firstOrNull { it.type != CallEndpointCompat.TYPE_SPEAKER && it.type != CallEndpointCompat.TYPE_EARPIECE }
            ?: endpoints.firstOrNull { it.type == CallEndpointCompat.TYPE_EARPIECE }
        target ?: return
        scope.launch {
            val result = c.requestEndpointChange(target)
            lastEndpointResult = "${target.name}: $result"
            Log.i(TAG, "endpoint $lastEndpointResult")
            if (on && result is CallControlResult.Success) audibleSpeaker()
        }
    }

    /**
     * Android keeps a call volume per device, and the loudspeaker's can be set almost to nothing while the earpiece's is
     * fine: switching to the speaker then seems to do nothing. Once the speaker has the call, bring a call volume under
     * half up to about 70 %, and show the volume slider so the user sees it and can change it.
     */
    private suspend fun audibleSpeaker() {
        delay(300) // the route change reaches the audio system after telecom reports it
        val am = context.getSystemService(android.media.AudioManager::class.java)
        val stream = android.media.AudioManager.STREAM_VOICE_CALL
        val max = am.getStreamMaxVolume(stream)
        val now = am.getStreamVolume(stream)
        if (now * 2 < max) runCatching {
            am.setStreamVolume(stream, (max * 7 + 9) / 10, android.media.AudioManager.FLAG_SHOW_UI)
            Log.i(TAG, "speaker call volume $now of $max raised to ${am.getStreamVolume(stream)}")
        }.onFailure { Log.w(TAG, "cannot raise the call volume", it) }
    }

    private companion object {
        const val TAG = "mcx.telecom"
    }
}
