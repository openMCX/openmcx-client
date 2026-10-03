package org.openmcx.client.app.telecom

import android.net.Uri
import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallsManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The self-managed call lifecycle this app relies on, checked against Telecom's own state. */
@RunWith(AndroidJUnit4::class)
class TelecomTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun telecomCalls(): String {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys telecom")
        val dump = android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().readText()
        return dump.substringAfter("mCalls:", "").substringBefore("mCallAudioManager")
    }

    private fun lifecycle(direction: Int) = runBlocking {
        val calls = CallsManager(context)
        calls.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
        val attributes = CallAttributesCompat("bob", Uri.parse("sip:bob.mcptt@mcx.test"), direction)
        val active = CompletableDeferred<String>()
        val done = CompletableDeferred<Unit>()
        val job = launch {
            calls.addCall(attributes, onAnswer = {}, onDisconnect = {}, onSetActive = {}, onSetInactive = {}) {
                launch {
                    if (direction == CallAttributesCompat.DIRECTION_INCOMING) answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL)
                    val result = setActive()
                    assertTrue("setActive: $result", result is CallControlResult.Success)
                    var state = ""
                    repeat(20) {
                        state = telecomCalls().lines().firstOrNull { it.contains(context.packageName) } ?: ""
                        if (state.contains("state=ACTIVE")) return@repeat
                        delay(250)
                    }
                    active.complete(state)
                    disconnect(DisconnectCause(DisconnectCause.LOCAL))
                    done.complete(Unit)
                }
            }
        }
        val state = withTimeout(15_000) { active.await() }
        withTimeout(5_000) { done.await() }
        job.cancel()
        assertTrue("telecom state: $state", state.contains("state=ACTIVE") && state.contains("self_mng"))
    }

    @Test
    fun incomingCallGoesActive() = lifecycle(CallAttributesCompat.DIRECTION_INCOMING)

    @Test
    fun outgoingCallGoesActive() = lifecycle(CallAttributesCompat.DIRECTION_OUTGOING)
}
