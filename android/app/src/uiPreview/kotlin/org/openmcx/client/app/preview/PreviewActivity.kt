//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app.preview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.openmcx.client.app.ui.AlertItem
import org.openmcx.client.app.ui.CallItem
import org.openmcx.client.app.ui.GroupItem
import org.openmcx.client.app.ui.Mcx
import org.openmcx.client.app.ui.McxApp
import org.openmcx.client.app.ui.OpenMcxTheme
import org.openmcx.client.app.ui.UiActions
import org.openmcx.client.app.ui.UiState
import org.openmcx.client.mcptt.CallType

/**
 * The client's screens with sample data and no engine: pick a state at the bottom, or use the screens. The PTT key
 * works locally (hold it: the floor is granted; let go: idle), and Answer, Hang up, Mute and Speaker change the state.
 */
class PreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Preview() }
    }
}

private val groups = listOf(
    GroupItem("sip:operations@mcx.test", affiliated = true, selected = true),
    GroupItem("sip:maintenance@mcx.test", affiliated = true, selected = false),
    GroupItem("sip:yard-north@mcx.test", affiliated = false, selected = false),
)
private val ready = UiState(
    user = "Alice", phase = "ready", registered = true, provisioned = true, groups = groups,
    privateTargets = listOf("sip:bob.mcptt@mcx.test", "sip:carol.mcptt@mcx.test"),
    alertGroup = "sip:operations@mcx.test", alertServices = listOf("MCPTT", "MCData", "Video"), speaker = true,
)
private val members = listOf("sip:alice@mcx.test", "sip:bob@mcx.test", "sip:carol@mcx.test", "sip:dave@mcx.test")
private fun call(floor: String, talker: String? = null, priority: String = "normal", limit: Int? = null, event: String? = null) =
    CallItem(1, "Operations", CallType.PREARRANGED, "established", floor, talker, event, limit,
        startedAt = System.currentTimeMillis() - 83_000, members = members, priority = priority)
private fun privateCall(muted: Boolean) = call("idle").copy(type = CallType.PRIVATE, title = "bob", floorControl = false, muted = muted,
    members = listOf("sip:alice@mcx.test", "sip:bob@mcx.test"))

private val samples: List<Pair<String, () -> UiState>> = listOf(
    "Lists" to { ready },
    "Alert in" to { ready.copy(receivedAlerts = listOf(AlertItem("Bob", "Emergency", "MCPTT, MCData", 48.8566, 2.3522)),
        callFailure = "Call to carol failed: busy (in another call)") },
    "Idle" to { ready.copy(call = call("idle")) },
    "Requesting" to { ready.copy(call = call("requesting")) },
    "Talking" to { ready.copy(call = call("talking", limit = 30)) },
    "Taken" to { ready.copy(call = call("taken", talker = "sip:bob@mcx.test")) },
    "Denied" to { ready.copy(call = call("idle", event = "denied (another user has the floor)")) },
    "Emergency" to { ready.copy(call = call("talking", priority = "emergency")) },
    "Peril" to { ready.copy(call = call("taken", talker = "sip:carol@mcx.test", priority = "imminent")) },
    "No floor" to { ready.copy(call = privateCall(muted = false)) },
    "Incoming" to { ready.copy(incoming = call("idle").copy(type = CallType.PRIVATE, title = "bob", state = "ringing")) },
)

@Composable
private fun Preview() {
    var pick by rememberSaveable { mutableStateOf(2) }
    var state by remember { mutableStateOf(samples[pick].second()) }
    var dark by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val actions = object : UiActions {
        override fun ptt(pressed: Boolean) {
            val c = state.call ?: return
            state = state.copy(call = c.copy(floor = if (pressed) "talking" else "idle", talker = null, talkLimitSeconds = if (pressed) 30 else null))
        }
        override fun setMuted(muted: Boolean) { state = state.copy(call = state.call?.copy(muted = muted)) }
        override fun setSpeaker(on: Boolean) { state = state.copy(speaker = on) }
        override fun hangup() { state = ready }
        override fun answer() { state = ready.copy(call = privateCall(muted = false).copy(floorControl = true)) }
        override fun reject() { state = ready }
        override fun callGroup(group: String) { state = ready.copy(call = call("idle")) }
        override fun callPriority(emergency: Boolean, enabled: Boolean) {
            state = state.copy(call = state.call?.copy(priority = if (!enabled) "normal" else if (emergency) "emergency" else "imminent"))
        }
        override fun dismissCallFailure() { state = state.copy(callFailure = null) }
        override fun cancelAlertOf(person: String) { state = state.copy(receivedAlerts = emptyList()) }
    }
    OpenMcxTheme(dark = dark ?: isSystemInDarkTheme()) {
        Column(Modifier.fillMaxSize().background(Mcx.Ivory)) {
            Box(Modifier.weight(1f)) { McxApp(state, actions) }
            Row(Modifier.fillMaxWidth().background(Mcx.Strip).windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                .horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = dark == true, onClick = { dark = if (dark == true) false else true }, label = { Text("Dark") })
                samples.forEachIndexed { i, (name, make) ->
                    FilterChip(selected = pick == i, onClick = { pick = i; state = make() }, label = { Text(name) })
                }
            }
        }
    }
}
