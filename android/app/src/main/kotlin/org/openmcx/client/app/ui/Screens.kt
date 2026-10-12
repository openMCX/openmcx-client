//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import org.openmcx.client.app.attachments.AttachmentFiles
import org.openmcx.client.app.attachments.AttachmentKind
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openmcx.client.mcptt.CallType
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import kotlin.math.PI
import kotlin.math.sin

object Tags {
    const val PHASE = "phase"
    const val PTT = "ptt"
    const val FLOOR = "floor"
    const val HANGUP = "hangup"
    const val ANSWER = "answer"
    const val REJECT = "reject"
    const val MESSAGE_TEXT = "message-text"
    const val MESSAGE_TARGET = "message-target"
    const val SEND = "send"
    const val IMPORT = "import"
    const val SCAN_QR = "scan-qr"
    const val SPEAKER = "speaker"
    const val CLEAR_LOGS = "clear-logs"
    const val SETTINGS = "settings"
    const val MUTE = "mute"
    fun callNoFloor(target: String) = "call-no-floor-$target"
    fun locate(target: String) = "locate-$target"
    const val LOCATIONS = "locations"
    const val SETTINGS_BACK = "settings-back"
    const val PROFILE_INFO = "profile-info"
    const val CALL_ALIAS = "call-alias"
    fun alias(id: String) = "alias-$id"
    fun aliasAction(id: String, action: String) = "alias-$action-$id"
    const val HISTORY_SECTION = "history-section"
    const val ABOUT_LINK = "about-link"
    const val CLEAR_MESSAGES = "clear-messages"
    const val CLEAR_HISTORY = "clear-history"
    const val CALL_FAILURE = "call-failure"
    const val VIDEO = "video"
    const val RING_FIRST = "ring-first"
    const val VIDEO_SCREEN = "video-screen"
    const val VIDEO_STATUS = "video-status"
    const val VIDEO_TRANSMIT = "video-transmit"
    const val VIDEO_HANGUP = "video-hangup"
    const val VIDEO_FAILURE = "video-failure"
    const val VIDEO_ANSWER = "video-answer"
    const val VIDEO_PARTICIPANTS = "video-participants"
    const val VIDEO_REMOTE = "video-remote"
    const val VIDEO_PRIORITY = "video-priority"
    const val VIDEO_EMERGENCY = "video-emergency"
    const val VIDEO_IMMINENT = "video-imminent"
    const val VIDEO_DECLINE = "video-decline"
    const val ATTACH = "attach"
    const val ATTACHMENT = "attachment"
    const val ATTACHMENT_CLEAR = "attachment-clear"
    fun attach(kind: AttachmentKind) = "attach-${kind.name.lowercase()}"
    fun fileDownload(id: String) = "file-download-$id"
    fun fileReject(id: String) = "file-reject-$id"
    fun fileOpen(id: String) = "file-open-$id"
    fun fileCancel(id: String) = "file-cancel-$id"
    fun fileStatus(id: String) = "file-status-$id"
    fun callOptions(target: String) = "call-options-$target"
    fun videoOptions(target: String) = "video-options-$target"
    fun callRinging(target: String) = "call-ringing-$target"
    fun videoPrivate(target: String) = "video-private-$target"
    fun videoTwoWay(target: String) = "video-two-way-$target"
    fun videoGroup(group: String) = "video-group-$group"
    fun videoRinging(target: String) = "video-ringing-$target"
    fun videoChat(group: String) = "video-chat-$group"
    fun videoEmergency(target: String) = "video-emergency-$target"
    fun videoPush(target: String) = "video-push-$target"
    fun videoPull(target: String) = "video-pull-$target"
    fun videoAmbient(target: String) = "video-ambient-$target"
    fun videoAmbientLocal(target: String) = "video-ambient-local-$target"
    fun videoPlay(url: String) = "video-play-$url"
    fun videoParticipant(id: String) = "video-participant-$id"
    const val VIDEO_RECORD = "video-record"
    fun alertFrom(person: String) = "alert-from-$person"
    fun alertCancelOf(person: String) = "alert-cancel-of-$person"
    fun groupOptions(group: String) = "group-options-$group"
    fun callEmergency(target: String) = "call-emergency-$target"
    fun callImminent(group: String) = "call-imminent-$group"
    const val CALL_PRIORITY = "call-priority"
    const val CALL_EMERGENCY = "call-emergency"
    const val CALL_IMMINENT = "call-imminent"
    const val SERVER_ADDRESS = "server-address"
    const val SERVER_ADDRESS_EDIT = "server-address-edit"
    const val SERVER_ADDRESS_FIELD = "server-address-field"
    const val SERVER_ADDRESS_SAVE = "server-address-save"
    const val SERVER_ADDRESS_RESET = "server-address-reset"
    const val EMERGENCY_ALERT = "emergency-alert"
    const val ALERT_OPTIONS = "alert-options"
    fun alertOnly(service: String) = "alert-only-$service"
    const val ADHOC_ALERT = "adhoc-alert"
    fun adhocChoice(criteria: String) = "adhoc-choice-$criteria"
    const val ALERT_RESULT = "alert-result"
    fun callGroup(group: String) = "call-group-$group"
    fun callPrivate(target: String) = "call-private-$target"
}

@Composable
fun McxApp(state: UiState, actions: UiActions, initialTab: Int = 0) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var settings by rememberSaveable { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = settings) { settings = false }
    Scaffold(topBar = { StatusBar(state) { settings = true } }) { padding ->
        state.videoCall?.let { Box(Modifier.padding(padding).fillMaxSize()) { VideoCallScreen(it, actions) }; return@Scaffold }
        if (settings) { Box(Modifier.padding(padding).fillMaxSize()) { SettingsScreen(state, actions) { settings = false } }; return@Scaffold }
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Other people's emergency alerts, above everything else, once each whatever services they came through.
            state.receivedAlerts.forEach { alert ->
                Row(Modifier.fillMaxWidth().background(Mcx.Stop).padding(horizontal = 12.dp, vertical = 8.dp).testTag(Tags.alertFrom(alert.person)),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WarningIcon(Color.White, 24.dp)
                    Column(Modifier.weight(1f)) {
                        Text("EMERGENCY ALERT: ${alert.person}", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(alert.what + " · " + alert.via + (if (alert.latitude != null) " · %.5f, %.5f".format(alert.latitude, alert.longitude) else ""),
                            color = Color.White, fontSize = 13.sp)
                    }
                    if (alert.latitude != null && alert.longitude != null)
                        TextButton(onClick = { actions.showOnMap(alert.latitude, alert.longitude) }) { Text("Map", color = Color.White) }
                    if (alert.cancellable) TextButton(onClick = { actions.cancelAlertOf(alert.person) },
                        modifier = Modifier.testTag(Tags.alertCancelOf(alert.person))) { Text("Cancel", color = Color.White) }
                }
            }
            state.videoFailure?.let { failure ->
                Row(Modifier.fillMaxWidth().background(Mcx.WarnBg).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(failure, color = Mcx.WarnInk, modifier = Modifier.weight(1f).testTag(Tags.VIDEO_FAILURE))
                    TextButton(onClick = actions::dismissVideoFailure) { Text("Dismiss") }
                }
            }
            state.incoming?.let { IncomingCall(it, actions) }
            if (!state.provisioned) Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions::importProvisioning, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Mcx.Ink)) { Text("Import MCX profile") }
                OutlinedButton(onClick = actions::scanProvisioning, modifier = Modifier.weight(1f)) { Text("Scan QR code") }
            }
            PrimaryTabRow(selectedTabIndex = tab, containerColor = Mcx.Ivory, contentColor = Mcx.Ink,
                indicator = { TabRowDefaults.PrimaryIndicator(Modifier.tabIndicatorOffset(tab, matchContentSize = true), color = Mcx.Ink) }) {
                listOf("Call", "Video", "Message", "Logs").forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i }, selectedContentColor = Mcx.Ink, unselectedContentColor = Mcx.Muted,
                        text = {
                            // One line at any font scale: a long name shrinks rather than breaks.
                            Text(title, fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
                                autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = MaterialTheme.typography.titleSmall.fontSize * LocalDensity.current.fontScale))
                        })
                }
            }
            when (tab) {
                0 -> TalkScreen(state, actions)
                1 -> VideoScreen(state, actions)
                2 -> MessagesScreen(state, actions)
                else -> LogsScreen(state, actions)
            }
        }
    }
}

@Composable
private fun StatusBar(state: UiState, openSettings: () -> Unit) {
    val ready = state.phase == "ready"
    // Edge to edge (Android 15+): the strip runs up behind the status bar, and its content keeps clear of the
    // status bar icons and the camera cutout.
    Column(Modifier.background(Mcx.Strip).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Logo()
            Text(state.user.ifEmpty { "OpenMCX" }, color = Mcx.Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            // Settings, left of the login status.
            IconButton(onClick = openSettings, modifier = Modifier.size(36.dp).testTag(Tags.SETTINGS).semantics { contentDescription = "Settings" }) {
                GearIcon(Mcx.Ink, 22.dp)
            }
            Row(
                Modifier.background(if (ready) Mcx.OkBg else Mcx.WarnBg, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(7.dp).background(if (ready) Mcx.OkDot else Mcx.Requesting, CircleShape))
                Text(state.phase, color = if (ready) Mcx.OkInk else Mcx.WarnInk, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag(Tags.PHASE))
            }
        }
        HorizontalDivider(color = Mcx.Line)
    }
}

/** A gear: a toothed ring with a hole. */
@Composable
private fun GearIcon(colour: Color, size: Dp) = Canvas(Modifier.size(size)) {
    val r = this.size.minDimension / 2
    val stroke = r * 0.2f
    for (i in 0 until 8) rotate(i * 45f) {
        drawLine(colour, Offset(center.x, center.y - r * 0.62f), Offset(center.x, center.y - r * 0.98f), strokeWidth = r * 0.3f)
    }
    drawCircle(colour, radius = r * 0.62f, style = Stroke(stroke))
    drawCircle(colour, radius = r * 0.2f, style = Stroke(stroke * 0.8f))
}

/** Settings: how calls are answered, the provisioning file, and who this phone is provisioned as. */
@Composable
private fun SettingsScreen(state: UiState, actions: UiActions, close: () -> Unit) {
    var profile by rememberSaveable { mutableStateOf(false) }
    var history by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                TextButton(onClick = close, modifier = Modifier.testTag(Tags.SETTINGS_BACK)) { Text("‹ Back") }
                Text("Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Section("Calls")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ring me first for private calls")
                    Text("Private calls ring until you answer, instead of connecting at once. Group calls and forced calls still connect.",
                        style = MaterialTheme.typography.bodySmall, color = Mcx.Muted)
                }
                Switch(checked = state.ringFirst, onCheckedChange = actions::setRingFirst, modifier = Modifier.testTag(Tags.RING_FIRST))
            }
            Section("Provisioning")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                Button(onClick = actions::importProvisioning, modifier = Modifier.testTag(Tags.IMPORT)) { Text("Import MCX profile") }
                OutlinedButton(onClick = actions::scanProvisioning, modifier = Modifier.testTag(Tags.SCAN_QR)) { Text("Scan QR code") }
            }
            Text("Import the profile file, or scan the QR code your administrator shows: it carries the profile or a one-time link to it.",
                style = MaterialTheme.typography.bodySmall, color = Mcx.Muted)
            if (state.provisioned) ServerAddress(state, actions)
            // A section of its own, like Provisioning: tap the heading to show or hide the profile.
            Row(Modifier.fillMaxWidth().clickable { profile = !profile }.testTag(Tags.PROFILE_INFO), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Section("Show MCX profile") }
                Text(if (profile) "▾" else "▸", color = Mcx.Muted, modifier = Modifier.padding(top = 12.dp))
            }
            if (profile) {
                Text(if (state.provisioned) "Provisioned as ${state.user}" else "Not provisioned")
                state.identities.forEach { (label, value) ->
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text(label, style = MaterialTheme.typography.bodySmall, color = Mcx.Muted, modifier = Modifier.width(120.dp))
                        Text(value, style = MaterialTheme.typography.bodySmall, color = Mcx.Ink)
                    }
                }
                state.endpoints.forEach { (name, target) -> Text("$name → $target", style = MaterialTheme.typography.bodySmall) }
            }
            if (state.aliases.isNotEmpty()) FunctionalAliases(state, actions)
            // History: calls, messages and files, and who was located; tap the heading to show it.
            Row(Modifier.fillMaxWidth().clickable { history = !history }.testTag(Tags.HISTORY_SECTION), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Section("History") }
                Text(if (history) "▾" else "▸", color = Mcx.Muted, modifier = Modifier.padding(top = 12.dp))
            }
            if (history) HistoryContent(state, actions)
            Section("Location")
            Text(state.locationSharing ?: "Your location is not being reported: no MC service has asked for it.",
                style = MaterialTheme.typography.bodySmall, color = Mcx.Muted)
            Section("About OpenMCX")
            Text("The OpenMCX project is the first open-source implementation of 3GPP Release ${org.openmcx.client.RELEASE} Mission Critical Services.")
            val uri = androidx.compose.ui.platform.LocalUriHandler.current
            TextButton(onClick = { runCatching { uri.openUri("https://openmcx.com") } }, contentPadding = PaddingValues(0.dp),
                modifier = Modifier.testTag(Tags.ABOUT_LINK)) { Text("Visit openmcx.com for more information") }
            TextButton(onClick = { runCatching { uri.openUri("mailto:contact@openmcx.com") } }, contentPadding = PaddingValues(0.dp)) {
                Text("Contact: contact@openmcx.com")
            }
            Text("Version ${org.openmcx.client.app.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = Mcx.Muted,
                modifier = Modifier.padding(bottom = 16.dp))
        }
    }
}

/**
 * The address the servers are reached at: the profile's, or one the user set (a new DHCP address, another network).
 * Only the address changes; the certificates name the servers, so they are still checked against the profile's CA.
 */
@Composable
private fun ServerAddress(state: UiState, actions: UiActions) {
    var editing by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Server address")
            Text((state.serverAddress ?: "set by the profile") + if (state.serverAddressEdited) " (edited; profile: ${state.profileServerAddress ?: "none"})" else "",
                style = MaterialTheme.typography.bodySmall, color = Mcx.Muted, modifier = Modifier.testTag(Tags.SERVER_ADDRESS))
        }
        TextButton(onClick = { editing = true }, modifier = Modifier.testTag(Tags.SERVER_ADDRESS_EDIT)) { Text("Edit") }
    }
    if (!editing) return
    var text by remember { mutableStateOf(state.serverAddress ?: "") }
    val valid = org.openmcx.client.app.Provisioning.validHost(text.trim())
    AlertDialog(
        onDismissRequest = { editing = false },
        title = { Text("Server address") },
        text = {
            Column {
                Text("The IP address or host name the MC servers are reached at. Everything else in your MCX profile stays as it is.",
                    style = MaterialTheme.typography.bodySmall, color = Mcx.Muted)
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, isError = text.isNotBlank() && !valid,
                    label = { Text("IP address or host name") }, placeholder = { Text(state.profileServerAddress ?: "192.0.2.10") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(Tags.SERVER_ADDRESS_FIELD))
            }
        },
        confirmButton = {
            TextButton(onClick = { editing = false; actions.setServerAddress(text.trim()) }, enabled = valid,
                modifier = Modifier.testTag(Tags.SERVER_ADDRESS_SAVE)) { Text("Save and reconnect") }
        },
        dismissButton = {
            Row {
                if (state.serverAddressEdited) TextButton(onClick = { editing = false; actions.setServerAddress(null) },
                    modifier = Modifier.testTag(Tags.SERVER_ADDRESS_RESET)) { Text("Reset to profile") }
                TextButton(onClick = { editing = false }) { Text("Cancel") }
            }
        },
    )
}

/**
 * The launcher icon in small: "open" over "MCX" on the brand orange. A mark, not text to read: it keeps its dark ink
 * in dark mode and its size whatever the font scale (the tile is fixed).
 */
@Composable
private fun Logo() {
    val ink = LightMcx.Ink
    with(LocalDensity.current) {
        Column(Modifier.size(49.dp).background(Mcx.Brand, RoundedCornerShape(13.dp)),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("open", color = ink, fontSize = 11.5.dp.toSp(), lineHeight = 13.dp.toSp())
            Text("MCX", color = ink, fontSize = 15.8.dp.toSp(), lineHeight = 17.3.dp.toSp(), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun IncomingCall(call: CallItem, actions: UiActions) {
    Column(Modifier.fillMaxWidth().padding(12.dp).background(Mcx.Card, RoundedCornerShape(16.dp)).border(1.dp, Mcx.Line, RoundedCornerShape(16.dp))
        .padding(16.dp)) {
        Text("Incoming ${call.type.sessionType} call", style = MaterialTheme.typography.titleMedium, color = Mcx.Muted)
        Text(call.title, color = Mcx.Ink, style = CallTitle)
        call.alias?.let { Text(it, color = Mcx.Ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag(Tags.CALL_ALIAS)) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
            Button(onClick = actions::answer, modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag(Tags.ANSWER), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32), contentColor = Color.White)) {
                Text("Answer", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
            OutlinedButton(onClick = actions::reject, modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag(Tags.REJECT),
                shape = RoundedCornerShape(16.dp), border = BorderStroke(1.5.dp, Mcx.Ink)) {
                Text("Decline", color = Mcx.Ink, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            }
        }
    }
}

@Composable
private fun TalkScreen(state: UiState, actions: UiActions) {
    val call = state.call
    if (call != null) {
        CallPanel(call, actions, state.user, state.speaker, state.callFailure)
        return
    }
    val adhoc = remember { mutableStateListOf<String>() }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        state.callFailure?.let { failure ->
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp).background(Mcx.WarnBg, RoundedCornerShape(12.dp))
                        .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(failure, color = Mcx.WarnInk, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).testTag(Tags.CALL_FAILURE))
                    TextButton(onClick = actions::dismissCallFailure) { Text("Dismiss", color = Mcx.WarnInk) }
                }
            }
        }
        item { Section("Groups") }
        items(state.groups) { g ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) {
                RadioButton(selected = g.selected, onClick = { actions.selectGroup(g.id) })
                Column(Modifier.weight(1f)) {
                    Text(g.id.short(), style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(7.dp).background(if (g.affiliated) Mcx.OkDot else Color.Transparent, CircleShape)
                            .border(1.dp, if (g.affiliated) Mcx.OkDot else Mcx.Muted, CircleShape))
                        Text(if (g.affiliated) "affiliated" else "not affiliated", style = MaterialTheme.typography.bodySmall, color = Mcx.Muted)
                    }
                }
                Button(onClick = { actions.callGroup(g.id) }, enabled = state.registered,
                    modifier = Modifier.testTag(Tags.callGroup(g.id))) { Text("Call") }
                GroupOptions(g.id, state.registered, actions)
            }
        }
        item { Section("Private calls") }
        items(state.privateTargets) { t ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Checkbox(checked = t in adhoc, onCheckedChange = { if (it) adhoc += t else adhoc -= t })
                Text(t.short(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { actions.callPrivate(t) }, enabled = state.registered,
                    modifier = Modifier.testTag(Tags.callPrivate(t))) { Text("Call") }
                CallOptions(t, state.registered, actions)
            }
        }
        item {
            Button(onClick = { actions.callAdhoc(adhoc.toList()) }, enabled = state.registered && adhoc.isNotEmpty(),
                modifier = Modifier.padding(vertical = 8.dp)) { Text("Ad hoc call with ${adhoc.size} selected") }
            Text("Press PTT with no call to call the selected group.", style = MaterialTheme.typography.bodySmall, color = Mcx.Muted)
        }
        if (state.alertServices.isNotEmpty()) {
            item { Section("Emergency alert") }
            item { EmergencyAlert(state, actions) }
        }
    }
}

/** The Video tab: video calls, video chat and recordings (MCVideo), apart from the Call tab's calls and alerts. */
@Composable
private fun VideoScreen(state: UiState, actions: UiActions) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item { Section("Group video") }
        items(state.groups) { g ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(g.id.short(), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                VideoButton("Video call to ${g.id.short()}", Modifier.testTag(Tags.videoGroup(g.id))) { actions.videoGroupCall(g.id) }
            }
        }
        item { Section("Private video") }
        items(state.privateTargets) { t ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(t.short(), modifier = Modifier.weight(1f))
                VideoButton("Video call to ${t.short()}", Modifier.testTag(Tags.videoPrivate(t))) { actions.videoCall(t) }
                VideoOptions(t, state.registered, actions)
            }
        }
        if (state.videoChatGroups.isNotEmpty()) {
            item { Section("Video chat groups") }
            items(state.videoChatGroups) { g ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(g.short(), modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { actions.videoJoinChat(g) }, enabled = state.registered,
                        modifier = Modifier.testTag(Tags.videoChat(g))) { Text("Join video") }
                }
            }
        }
        if (state.videoGroups.isNotEmpty()) {
            item { Section("Video recordings") }
            item {
                OutlinedButton(onClick = actions::videoPushToServer, enabled = state.registered, modifier = Modifier.testTag(Tags.VIDEO_RECORD)) {
                    Text("Record my video on the server")
                }
            }
            items(state.videoRecordings.reversed()) { url ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(url.substringAfterLast('/'), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { actions.videoPullFromServer(url) }, modifier = Modifier.testTag(Tags.videoPlay(url))) { Text("Play") }
                }
            }
        }
    }
}

/**
 * This user's functional aliases (TS 24.379 §9A.2.1): each with its status in words, and what can be done with it:
 * activate, take over (when another user holds it and this user may), deactivate, present it on outgoing calls.
 */
@Composable
private fun FunctionalAliases(state: UiState, actions: UiActions) {
    Section("Functional aliases")
    Text("A functional alias is a role others can call, such as a dispatcher. Activate one to receive its calls; " +
        "present it so your calls show it.", style = MaterialTheme.typography.bodySmall, color = Mcx.Muted)
    // A call presents at most one alias (TS 24.379 §9A): say which.
    val presented = state.aliases.filter { it.inUse }.joinToString { CallDisplay.role(it.id) }
    Text(if (presented.isEmpty()) "Your calls present no role." else "Your calls present: $presented",
        style = MaterialTheme.typography.bodySmall, color = Mcx.Ink, modifier = Modifier.padding(top = 4.dp))
    state.aliases.forEach { alias ->
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).background(Mcx.Card, RoundedCornerShape(12.dp))
            .border(1.dp, Mcx.Line, RoundedCornerShape(12.dp)).padding(12.dp).testTag(Tags.alias("${alias.service}-${alias.id.short()}"))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(CallDisplay.role(alias.id), color = Mcx.Ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(when (alias.service) { "mcdata" -> "MCData"; "mcvideo" -> "MCVideo"; else -> "MCPTT" }, color = Mcx.Muted,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
                val (label, ink) = when (alias.status) {
                    "active" -> (if (alias.inUse) "Active · on your calls" else "Active") to Mcx.OkInk
                    "take-over-possible" -> "Held by another user" to Mcx.WarnInk
                    else -> "Not active" to Mcx.Muted
                }
                Text(label, color = ink, style = MaterialTheme.typography.bodySmall)
            }
            // The server leaves a refused alias out of its NOTIFY without a reason (TS 24.379 §9A.2.2.3.3): held by
            // another user at its limit without a take-over right for this user, or not allowed for this user.
            if (alias.status == "inactive" && alias.outcome == "refused")
                Text("Not activated: another user holds it and you may not take it over, or it is not allowed for you.",
                    color = Mcx.WarnInk, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                val tag = { action: String -> Modifier.heightIn(min = 48.dp).testTag(Tags.aliasAction("${alias.service}-${alias.id.short()}", action)) }
                when (alias.status) {
                    "active" -> {
                        OutlinedButton(onClick = { actions.aliasChange("deactivate", alias.id, alias.service) }, modifier = tag("deactivate")) { Text("Deactivate") }
                        if (alias.inUse) OutlinedButton(onClick = { actions.aliasUse(null, alias.service) }, modifier = tag("unuse")) { Text("Stop presenting") }
                        else Button(onClick = { actions.aliasUse(alias.id, alias.service) }, modifier = tag("use")) { Text("Present on calls") }
                    }
                    "take-over-possible" -> Button(onClick = { actions.aliasChange("takeover", alias.id, alias.service) }, modifier = tag("takeover")) { Text("Take over") }
                    else -> Button(onClick = { actions.aliasChange("activate", alias.id, alias.service) }, enabled = state.registered, modifier = tag("activate")) { Text("Activate") }
                }
                // MCData aliases are reached by short data, not calls (the Messages tab's SDS to an alias is not offered yet).
                if (alias.status != "active" && alias.service != "mcdata") OutlinedButton(onClick = { actions.callAlias(alias.id, alias.service) },
                    enabled = state.registered, modifier = tag("call")) { Text("Call") }
            }
        }
    }
    state.aliasNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Mcx.Muted) }
}

@Composable
private fun Section(title: String) =
    Text(title, style = MaterialTheme.typography.titleSmall, color = Mcx.Muted, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))

/** What the PTT key shows besides its colour, so a state reads without it (colour blindness, glare). */
private enum class KeySign { MIC, SPEAKER, WAIT, MUTED }

/** How a floor state looks: [fill] for the PTT key and badge, [ink] for its name on the background, and a [sign]. */
private data class FloorLook(val fill: Color, val ink: Color, val label: String, val hint: String, val button: String, val sign: KeySign)

@Composable
private fun floorLook(call: CallItem): FloorLook = when (call.floor) {
    "talking" -> FloorLook(Mcx.Talking, Mcx.OnTalking, "You are talking", "Release PTT when done", "Release to stop", KeySign.MIC)
    "requesting" -> FloorLook(Mcx.Requesting, Mcx.OnRequesting, "Requesting the floor…", "Keep holding", "Waiting…", KeySign.WAIT)
    // TS 24.380 §6.2.4.9: the request waits in the floor queue; releasing PTT leaves the queue (§6.2.4.9.6).
    "queued" -> FloorLook(Mcx.Requesting, Mcx.OnRequesting,
        call.queuePosition?.takeIf { it in 1..253 }?.let { "Queued, position $it" } ?: "Queued",
        "Keep holding to stay in the queue; release to leave it", "In queue", KeySign.WAIT)
    // §6.2.4.9.4: a queued grant waits for the user's acceptance (T132).
    "offered" -> FloorLook(Mcx.Talking, Mcx.OnTalking, "The floor is yours", "Press PTT now to talk, or it goes to the next",
        "Press to talk", KeySign.MIC)
    "releasing" -> FloorLook(Mcx.Releasing, Mcx.OnReleasing, "Releasing…", "Handing the floor back", "Hold to talk", KeySign.WAIT)
    "taken" -> FloorLook(Mcx.Taken, Mcx.OnTaken, "${call.talker?.short() ?: "Someone"} is talking", "Wait for the floor to be free", "Floor busy",
        KeySign.SPEAKER)
    else -> if (call.state == "established") FloorLook(Mcx.Idle, Mcx.OnIdle, "Floor idle", "Press and hold PTT to talk", "Hold to talk", KeySign.MIC)
    else FloorLook(Mcx.Idle, Mcx.OnIdle, call.state.replaceFirstChar { it.uppercase() } + "…", "Setting up the call", "Hold to talk", KeySign.WAIT)
}

private data class RecentTalk(val who: String, val millis: Long, val at: Long)

private fun clock(millis: Long): String { val s = millis.coerceAtLeast(0) / 1000; return "%d:%02d".format(s / 60, s % 60) }

/** The call's name and the floor state: condensed, to be read at a glance. */
private val CallTitle = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 38.sp)
private val FloorLabel = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 24.sp)

/** The active call: who, the floor with the talk time left, a press-and-hold PTT key and the recent talkers. */
@Composable
fun CallPanel(call: CallItem, actions: UiActions, user: String = "", speaker: Boolean? = null, failure: String? = null) {
    // Without floor control both talk at once: the big key mutes the microphone instead of requesting the floor.
    val duplex = !call.floorControl
    val look = if (!duplex) floorLook(call) else if (call.muted)
        FloorLook(Mcx.Releasing, Mcx.OnReleasing, "Muted", "Your microphone is off; you still hear the other side", "Tap to unmute", KeySign.MUTED)
    else FloorLook(Mcx.Talking, Mcx.OnTalking, "Both can talk", "Speak freely, like a phone call", "Tap to mute", KeySign.MIC)
    val still = reducedMotion()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(200) } }
    // Who holds the floor; a talk burst is timed from when that changes, and listed when it ends.
    val holder = when (call.floor) { "talking" -> "You"; "taken" -> call.talker?.short() ?: "Someone"; else -> null }
    var burstStart by remember(call.id) { mutableLongStateOf(0L) }
    val recent = remember(call.id) { mutableStateListOf<RecentTalk>() }
    val haptics = LocalHapticFeedback.current
    // A queued grant waits for the user: a cue besides the screen (§6.2.4.9.4 step 2).
    LaunchedEffect(call.id, call.floor) { if (call.floor == "offered") haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    DisposableEffect(call.id, holder) {
        val start = System.currentTimeMillis()
        burstStart = start
        if (holder == "You") haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onDispose {
            if (holder != null) {
                val end = System.currentTimeMillis()
                recent.add(0, RecentTalk(holder, end - start, end))
                while (recent.size > 3) recent.removeAt(recent.lastIndex)
            }
        }
    }
    val limitMs = call.talkLimitSeconds?.let { it * 1000L }
    val elapsed = if (call.floor == "talking") now - burstStart else 0L
    val remaining = limitMs?.let { (it - elapsed).coerceAtLeast(0) }
    val used = if (limitMs != null && call.floor == "talking") (elapsed.toFloat() / limitMs).coerceIn(0f, 1f) else 0f

    Column(Modifier.fillMaxSize().background(Mcx.Ivory)) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(when (call.type) { CallType.PRIVATE -> "Private call"; CallType.PREARRANGED -> "Group call"; CallType.ADHOC -> "Ad hoc call" },
                    color = Mcx.Muted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (call.startedAt > 0) Text(clock(now - call.startedAt), color = Mcx.Muted, style = MaterialTheme.typography.labelLarge.merge(Tabular))
            }
            when (call.priority) {
                "emergency" -> PriorityBanner("EMERGENCY", Mcx.Stop, Color.White)
                "imminent" -> PriorityBanner("IMMINENT PERIL", Mcx.Peril, Color.Black)
            }
            Text(call.title, color = Mcx.Ink, style = CallTitle)
            call.alias?.let { Text(it, color = Mcx.Ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag(Tags.CALL_ALIAS)) }
            if (call.members.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    call.members.forEach { member ->
                        val name = member.short()
                        val you = name == user
                        val on = (you && call.floor == "talking") || (!you && call.floor == "taken" && call.talker?.short() == name)
                        Row(
                            Modifier.background(if (on) Mcx.Card else Color.Transparent, RoundedCornerShape(50))
                                .border(if (on) 2.dp else 1.dp, if (on) look.fill else Mcx.Line, RoundedCornerShape(50))
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(Modifier.size(7.dp).background(if (on) look.fill else Mcx.Muted, CircleShape))
                            Text(if (you) "$name (you)" else name, fontSize = 13.sp, color = if (on) look.ink else Mcx.Ink,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }
        }

        // The floor: a badge in the state's colour with its sign, the state's name and what to do.
        Row(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp).fillMaxWidth()
                .background(Mcx.Card, RoundedCornerShape(16.dp)).border(1.dp, Mcx.Line, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(40.dp).background(look.fill, CircleShape), contentAlignment = Alignment.Center) {
                if (holder != null) LevelBars(Color.White, still) else KeySignIcon(look.sign, Color.White, 22.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(look.label, modifier = Modifier.testTag(Tags.FLOOR), color = look.ink, style = FloorLabel)
                Text(look.hint, color = Mcx.Muted, fontSize = 13.sp)
            }
            if (remaining != null && call.floor == "talking")
                Text(clock(remaining + 999), color = if (remaining < 5000) Mcx.OnStop else Mcx.Ink, style = FloorLabel.merge(Tabular))
        }

        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            // The key is 256 dp across where there is room, smaller on short screens or with a long talker list.
            BoxWithConstraints(Modifier.weight(1f, fill = false).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val ring = minOf(256.dp, maxWidth, maxHeight)
                TalkRing(ring, used, if (remaining != null && remaining < 5000) Mcx.Stop else look.fill,
                    pulsing = (call.floor == "requesting" || call.floor == "offered") && !still)
                var held by remember { mutableStateOf(false) }
                if (duplex) PttKey(ring * 0.84f, look, if (call.muted) "MUTED" else "LIVE", held,
                    Modifier.testTag(Tags.MUTE)
                        .semantics { role = Role.Button; contentDescription = if (call.muted) "Unmute the microphone" else "Mute the microphone" }
                        .pointerInput(call.id) {
                            detectTapGestures(onPress = { held = true; tryAwaitRelease(); held = false }, onTap = { actions.setMuted(!call.muted) })
                        })
                else PttKey(ring * 0.84f, look, "PTT", held,
                    Modifier.testTag(Tags.PTT)
                        .semantics { role = Role.Button; contentDescription = "Push to talk: press and hold" }
                        .pointerInput(call.id) {
                            detectTapGestures(onPress = {
                                held = true
                                actions.ptt(true)
                                tryAwaitRelease()
                                actions.ptt(false)
                                held = false
                            })
                        })
            }
            call.lastFloorEvent?.takeIf { it.startsWith("denied") || it.startsWith("revoked") || it.startsWith("request failed") }?.let {
                Text("Floor $it", color = Mcx.WarnInk, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 14.dp).background(Mcx.WarnBg, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }

        if (recent.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Recent talkers", color = Mcx.Muted, style = MaterialTheme.typography.titleSmall)
                recent.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(t.who, color = Mcx.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text(clock(t.millis + 500), color = Mcx.Muted, fontSize = 13.sp, style = Tabular)
                        Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t.at)), color = Mcx.Muted, fontSize = 13.sp, style = Tabular)
                    }
                }
            }
        }

        failure?.let {
            Text(it, color = Mcx.WarnInk, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                .background(Mcx.WarnBg, RoundedCornerShape(8.dp)).padding(8.dp).testTag(Tags.CALL_FAILURE))
        }
        if (call.state == "established") Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val emergency = call.priority == "emergency"
            OutlinedButton(onClick = { actions.callPriority(true, !emergency) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(Tags.CALL_EMERGENCY),
                shape = RoundedCornerShape(14.dp), border = BorderStroke(1.5.dp, Mcx.OnStop)) {
                Text(if (emergency) "Cancel emergency" else "Emergency", color = Mcx.OnStop, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            }
            if (call.type != CallType.PRIVATE && !emergency) {
                val peril = call.priority == "imminent"
                OutlinedButton(onClick = { actions.callPriority(false, !peril) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(Tags.CALL_IMMINENT),
                    shape = RoundedCornerShape(14.dp), border = BorderStroke(1.5.dp, Mcx.OnRequesting)) {
                    Text(if (peril) "End imminent peril" else "Imminent peril", color = Mcx.OnRequesting, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center)
                }
            }
        }
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (speaker != null) {
                Button(onClick = { actions.setSpeaker(!speaker) }, modifier = Modifier.height(56.dp).testTag(Tags.SPEAKER),
                    shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Mcx.Ink),
                    colors = ButtonDefaults.buttonColors(containerColor = if (speaker) Mcx.Ink else Mcx.Card, contentColor = if (speaker) Mcx.Ivory else Mcx.Ink)) {
                    Text(if (speaker) "Speaker" else "Earpiece", fontWeight = FontWeight.SemiBold)
                }
            }
            Box(Modifier.size(56.dp).background(Mcx.Card, RoundedCornerShape(16.dp)).border(1.dp, Mcx.Ink, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center) {
                VideoButton("Video call", Modifier.testTag(Tags.VIDEO)) { actions.videoCall(call.members.firstOrNull { it.short() != user } ?: call.title) }
            }
            Button(onClick = actions::hangup, modifier = Modifier.weight(1f).height(56.dp).testTag(Tags.HANGUP), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Mcx.Stop, contentColor = Color.White)) {
                Text("Hang up", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        }
    }
}

/** Emergency or imminent peril, across the call: the strongest thing on the screen. */
@Composable
private fun PriorityBanner(text: String, fill: Color, ink: Color) {
    Row(Modifier.fillMaxWidth().background(fill, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WarningIcon(ink, 22.dp)
        Text(text, color = ink, style = FloorLabel, letterSpacing = 1.sp, modifier = Modifier.testTag(Tags.CALL_PRIORITY))
    }
}

/**
 * The push-to-talk key, drawn like a radio's: raised, lit from above and casting a shadow in its own colour; while
 * held it sinks (shading reversed, no highlight, a shallow shadow). In dark mode a light rim keeps its edge visible.
 */
@Composable
private fun PttKey(diameter: Dp, look: FloorLook, word: String, held: Boolean, modifier: Modifier) {
    val rim = if (Mcx.dark) Color.White.copy(alpha = 0.30f) else Color.Black.copy(alpha = 0.10f)
    val top = lerp(look.fill, Color.White, if (held) 0f else 0.12f)
    val bottom = lerp(look.fill, Color.Black, if (held) 0f else 0.16f)
    Box(
        modifier.size(diameter)
            .shadow(if (held) 2.dp else 14.dp, CircleShape, ambientColor = look.fill, spotColor = look.fill)
            .background(Brush.verticalGradient(if (held) listOf(bottom, look.fill, top) else listOf(top, look.fill, bottom)), CircleShape)
            .border(2.dp, rim, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (!held) Canvas(Modifier.matchParentSize()) {
            val inset = 7.dp.toPx()
            drawArc(Color.White.copy(alpha = 0.28f), 205f, 130f, false, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset),
                style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            KeySignIcon(look.sign, Color.White, 36.dp)
            Text(word, color = Color.White, fontFamily = Condensed, fontSize = 42.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text(look.button, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun KeySignIcon(sign: KeySign, colour: Color, size: Dp) = when (sign) {
    KeySign.MIC -> MicIcon(colour, size)
    KeySign.SPEAKER -> SpeakerIcon(colour, size)
    KeySign.WAIT -> WaitIcon(colour, size)
    KeySign.MUTED -> MutedMicIcon(colour, size)
}

/** The talk-time ring around the PTT key, and a pulse while the floor is being requested. */
@Composable
private fun TalkRing(diameter: Dp, used: Float, colour: Color, pulsing: Boolean) {
    val track = Mcx.Line
    val pulseColour = Mcx.Requesting
    val pulse = rememberInfiniteTransition(label = "pulse")
    val beat by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "beat")
    Canvas(Modifier.size(diameter)) {
        val width = 8.dp.toPx()
        val inset = width / 2 + 2.dp.toPx()
        val topLeft = Offset(inset, inset)
        val box = Size(size.width - 2 * inset, size.height - 2 * inset)
        drawArc(track, 0f, 360f, false, topLeft, box, style = Stroke(width))
        if (used > 0f) drawArc(colour, -90f, 360f * used, false, topLeft, box, style = Stroke(width, cap = StrokeCap.Round))
        if (pulsing) drawCircle(pulseColour.copy(alpha = 0.35f + 0.55f * beat), radius = (size.minDimension / 2 - 13.dp.toPx()) * (1f + 0.04f * beat),
            style = Stroke(6.dp.toPx()))
    }
}

/** Four bars bouncing while someone holds the floor; standing still when animations are off. */
@Composable
private fun LevelBars(colour: Color, still: Boolean = false) {
    val t = rememberInfiniteTransition(label = "bars")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "phase")
    Row(Modifier.height(18.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(4) { i ->
            val level = if (still) 0.3f + 0.2f * (i % 2) else (sin((phase + i * 0.17f) * 2 * PI).toFloat() + 1) / 2
            Box(Modifier.width(3.dp).height((5 + 11 * level).dp).background(colour, RoundedCornerShape(2.dp)))
        }
    }
}

/** A loudspeaker with two sound waves: someone else is talking, you listen. */
@Composable
private fun SpeakerIcon(colour: Color, size: Dp) = Canvas(Modifier.size(size)) {
    val u = this.size.width / 24f
    val body = Path().apply {
        moveTo(3 * u, 9 * u); lineTo(7 * u, 9 * u); lineTo(12 * u, 4.5f * u); lineTo(12 * u, 19.5f * u); lineTo(7 * u, 15 * u); lineTo(3 * u, 15 * u); close()
    }
    drawPath(body, colour)
    val stroke = Stroke(2 * u, cap = StrokeCap.Round)
    drawArc(colour, -45f, 90f, false, Offset(9 * u, 8 * u), Size(8 * u, 8 * u), style = stroke)
    drawArc(colour, -50f, 100f, false, Offset(7 * u, 4 * u), Size(16 * u, 16 * u), style = stroke)
}

/** An hourglass: the floor is being asked for or handed back. */
@Composable
private fun WaitIcon(colour: Color, size: Dp) = Canvas(Modifier.size(size)) {
    val u = this.size.width / 24f
    val stroke = Stroke(2 * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawLine(colour, Offset(6 * u, 3 * u), Offset(18 * u, 3 * u), 2 * u, StrokeCap.Round)
    drawLine(colour, Offset(6 * u, 21 * u), Offset(18 * u, 21 * u), 2 * u, StrokeCap.Round)
    val glass = Path().apply {
        moveTo(7.5f * u, 3 * u); lineTo(7.5f * u, 7 * u); lineTo(12 * u, 12 * u); lineTo(7.5f * u, 17 * u); lineTo(7.5f * u, 21 * u)
        moveTo(16.5f * u, 3 * u); lineTo(16.5f * u, 7 * u); lineTo(12 * u, 12 * u); lineTo(16.5f * u, 17 * u); lineTo(16.5f * u, 21 * u)
    }
    drawPath(glass, colour, style = stroke)
    drawPath(Path().apply { moveTo(9 * u, 19.5f * u); lineTo(12 * u, 15.5f * u); lineTo(15 * u, 19.5f * u); close() }, colour)
}

/** The microphone struck through: muted. */
@Composable
private fun MutedMicIcon(colour: Color, size: Dp) = Box(Modifier.size(size)) {
    MicIcon(colour, size)
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 24f
        drawLine(colour, Offset(4 * u, 3 * u), Offset(20 * u, 21 * u), 2.2f * u, StrokeCap.Round)
    }
}

/** A warning triangle with an exclamation mark, for emergency notices. */
@Composable
private fun WarningIcon(colour: Color, size: Dp) = Canvas(Modifier.size(size)) {
    val u = this.size.width / 24f
    drawPath(Path().apply { moveTo(12 * u, 3 * u); lineTo(22.5f * u, 21 * u); lineTo(1.5f * u, 21 * u); close() }, colour,
        style = Stroke(2 * u, join = StrokeJoin.Round))
    drawLine(colour, Offset(12 * u, 9.5f * u), Offset(12 * u, 14.5f * u), 2.2f * u, StrokeCap.Round)
    drawCircle(colour, 1.3f * u, Offset(12 * u, 17.8f * u))
}

/** ▾ beside a private Call: connect at once (the default) or ring the other phone first. */
@Composable
private fun CallOptions(target: String, enabled: Boolean, actions: UiActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        MoreButton("More ways to call ${target.short()}", Tags.callOptions(target), enabled) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Call now (connects at once)") }, onClick = { open = false; actions.callPrivate(target) })
            DropdownMenuItem(text = { Text("Call and ring (they answer)") }, modifier = Modifier.testTag(Tags.callRinging(target)),
                onClick = { open = false; actions.callPrivateRinging(target) })
            DropdownMenuItem(text = { Text("Call, both talk (no floor control)") }, modifier = Modifier.testTag(Tags.callNoFloor(target)),
                onClick = { open = false; actions.callPrivateNoFloor(target) })
            DropdownMenuItem(text = { Text("Emergency call", color = Mcx.OnStop) }, modifier = Modifier.testTag(Tags.callEmergency(target)),
                onClick = { open = false; actions.callPrivateEmergency(target) })
            DropdownMenuItem(text = { Text("Where are they? (location)") }, modifier = Modifier.testTag(Tags.locate(target)),
                onClick = { open = false; actions.locate(target) })
        }
    }
}

/** A private video call's other kinds (MCVideo): ringing, emergency, push, pull, ambient viewing, two-way. */
@Composable
private fun VideoOptions(target: String, enabled: Boolean, actions: UiActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        MoreButton("More video with ${target.short()}", Tags.videoOptions(target), enabled) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Video call, hold to transmit (with voice)") }, onClick = { open = false; actions.videoCall(target) })
            DropdownMenuItem(text = { Text("Video call and ring (they answer)") }, modifier = Modifier.testTag(Tags.videoRinging(target)),
                onClick = { open = false; actions.videoCallRinging(target) })
            DropdownMenuItem(text = { Text("Emergency video call", color = Mcx.OnStop) }, modifier = Modifier.testTag(Tags.videoEmergency(target)),
                onClick = { open = false; actions.videoCallEmergency(target) })
            DropdownMenuItem(text = { Text("Push my video to them") }, modifier = Modifier.testTag(Tags.videoPush(target)),
                onClick = { open = false; actions.videoPush(target) })
            DropdownMenuItem(text = { Text("Watch their camera (pull)") }, modifier = Modifier.testTag(Tags.videoPull(target)),
                onClick = { open = false; actions.videoPull(target) })
            DropdownMenuItem(text = { Text("View their camera (ambient)") }, modifier = Modifier.testTag(Tags.videoAmbient(target)),
                onClick = { open = false; actions.videoAmbient(target, local = false) })
            DropdownMenuItem(text = { Text("Let them view my camera (ambient)") }, modifier = Modifier.testTag(Tags.videoAmbientLocal(target)),
                onClick = { open = false; actions.videoAmbient(target, local = true) })
            DropdownMenuItem(text = { Text("Two-way video (no voice)") }, modifier = Modifier.testTag(Tags.videoTwoWay(target)),
                onClick = { open = false; actions.videoCall(target, transmissionControl = false) })
        }
    }
}

/**
 * The ▾ that opens a menu of more choices: twice the size of a text arrow, so it is easy to hit
 * (at least 56 x 48 dp, above the 48 dp touch target).
 */
@Composable
private fun MoreButton(description: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(width = 56.dp, height = 48.dp).testTag(tag).semantics { contentDescription = description }) {
        Text("▾", fontSize = 36.sp, lineHeight = 36.sp)
    }
}

/**
 * The emergency alert: one button raises it on the selected group through every service (MCPTT, then MCData, then
 * video) or cancels it everywhere; ▾ offers one service only, or an ad hoc alert to a ready-made audience.
 */
@Composable
private fun EmergencyAlert(state: UiState, actions: UiActions) {
    var open by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    val up = state.ownAlert.isNotEmpty() || state.ownAdhoc != null
    Column(Modifier.fillMaxWidth()) {
        // Who it goes to above, and the button across the width below: easy to hit, and nothing squeezed at large text.
        Text(state.alertGroup?.short()?.let { "To $it" } ?: "No group selected", style = MaterialTheme.typography.titleMedium)
        Text(if (up) "Your alert is up: ${(state.ownAlert + listOfNotNull(state.ownAdhoc?.let { "ad hoc to $it" })).joinToString(", ")}"
            else "Through ${state.alertServices.joinToString(", ")}", style = MaterialTheme.typography.bodySmall,
            color = if (up) Mcx.OnStop else Mcx.Muted)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Button(onClick = { actions.emergencyAlert(!up) }, enabled = state.registered && (up || state.alertGroup != null),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (up) Mcx.Releasing else Mcx.Stop, contentColor = Color.White),
                modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag(Tags.EMERGENCY_ALERT)) {
                Text(if (up) "Cancel alert" else "Send emergency alert", fontWeight = FontWeight.Bold, fontSize = 17.sp, textAlign = TextAlign.Center)
            }
            Box {
                MoreButton("More emergency alert choices", Tags.ALERT_OPTIONS, state.registered) { open = true }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    state.alertServices.forEach { service ->
                        val on = service in state.ownAlert
                        DropdownMenuItem(text = { Text(if (on) "Cancel $service alert" else "$service only") },
                            modifier = Modifier.testTag(Tags.alertOnly(service)),
                            onClick = { open = false; actions.emergencyAlert(!on, service) })
                    }
                    if (state.adhocChoices.isNotEmpty())
                        DropdownMenuItem(text = { Text("Ad hoc alert by criteria…") }, modifier = Modifier.testTag(Tags.ADHOC_ALERT),
                            enabled = state.ownAdhoc == null, onClick = { open = false; choosing = true })
                }
            }
        }
        state.alertResult?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = Mcx.Muted, modifier = Modifier.padding(top = 2.dp).testTag(Tags.ALERT_RESULT))
        }
    }
    if (choosing) AlertDialog(
        onDismissRequest = { choosing = false },
        title = { Text("Ad hoc emergency alert") },
        text = {
            Column {
                Text("Alert everyone who:", style = MaterialTheme.typography.bodySmall, color = Mcx.Muted)
                state.adhocChoices.forEach { c ->
                    TextButton(onClick = { choosing = false; actions.adhocAlert(c.criteria) },
                        modifier = Modifier.fillMaxWidth().testTag(Tags.adhocChoice(c.criteria))) { Text(c.label, modifier = Modifier.fillMaxWidth()) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { choosing = false }) { Text("Cancel") } },
    )
}

/** A group's other calls: emergency and imminent peril (TS 24.379 §6.2.8.1). */
@Composable
private fun GroupOptions(group: String, enabled: Boolean, actions: UiActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        MoreButton("More ways to call ${group.short()}", Tags.groupOptions(group), enabled) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Emergency group call", color = Mcx.OnStop) }, modifier = Modifier.testTag(Tags.callEmergency(group)),
                onClick = { open = false; actions.callGroupPriority(group, "emergency") })
            DropdownMenuItem(text = { Text("Imminent peril group call", color = Mcx.OnRequesting) }, modifier = Modifier.testTag(Tags.callImminent(group)),
                onClick = { open = false; actions.callGroupPriority(group, "imminent") })
        }
    }
}

/** A camera icon button; video calls come with MCVideo, so for now it only says so. */
@Composable
private fun VideoButton(description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = modifier.semantics { contentDescription = description }) { VideoIcon(Mcx.Ink, 24.dp) }
}

@Composable
private fun VideoIcon(colour: Color, size: Dp) = Canvas(Modifier.size(size)) {
    val u = this.size.width / 24f
    val stroke = Stroke(2 * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawRoundRect(colour, Offset(2 * u, 6 * u), Size(13 * u, 12 * u), CornerRadius(2 * u), style = stroke)
    val lens = Path().apply { moveTo(15 * u, 10.5f * u); lineTo(22 * u, 7 * u); lineTo(22 * u, 17 * u); lineTo(15 * u, 13.5f * u); close() }
    drawPath(lens, colour, style = stroke)
}

/** A paperclip, tilted as mail apps draw their attach button: one wire bent into three loops. */
@Composable
private fun PaperclipIcon(colour: Color, size: Dp) = Canvas(Modifier.size(size)) {
    val u = this.size.width / 24f
    fun box(cx: Float, cy: Float, r: Float) = Rect(Offset((cx - r) * u, (cy - r) * u), Size(2 * r * u, 2 * r * u))
    val clip = Path().apply {
        moveTo(15.5f * u, 7f * u); lineTo(15.5f * u, 16f * u)
        arcTo(box(12f, 16f, 3.5f), 0f, 180f, false)
        lineTo(8.5f * u, 5.5f * u)
        arcTo(box(11f, 5.5f, 2.5f), 180f, 180f, false)
        lineTo(13.5f * u, 15.5f * u)
        arcTo(box(12f, 15.5f, 1.5f), 0f, 180f, false)
        lineTo(10.5f * u, 7f * u)
    }
    // Mirrored, then tilted: the open loop leans to the right as in mail apps.
    scale(-1f, 1f, Offset(12f * u, 12f * u)) {
        rotate(45f, Offset(12f * u, 11.25f * u)) {
            drawPath(clip, colour, style = Stroke(1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** A paper plane pointing right, the usual send symbol. */
@Composable
private fun SendIcon(colour: Color, size: Dp) = Canvas(Modifier.size(size)) {
    val u = this.size.width / 24f
    val plane = Path().apply {
        moveTo(3f * u, 20f * u); lineTo(21.5f * u, 12f * u); lineTo(3f * u, 4f * u)
        lineTo(3f * u, 10.2f * u); lineTo(15f * u, 12f * u); lineTo(3f * u, 13.8f * u); close()
    }
    drawPath(plane, colour)
}

@Composable
private fun MicIcon(colour: Color, size: Dp) = Canvas(Modifier.size(size)) {
    val u = this.size.width / 24f
    val stroke = Stroke(2 * u, cap = StrokeCap.Round)
    drawRoundRect(colour, Offset(9 * u, 3 * u), Size(6 * u, 11 * u), CornerRadius(3 * u), style = stroke)
    drawArc(colour, 0f, 180f, false, Offset(5 * u, 4 * u), Size(14 * u, 14 * u), style = stroke)
    drawLine(colour, Offset(12 * u, 18 * u), Offset(12 * u, 21 * u), 2 * u, StrokeCap.Round)
}

@Composable
private fun MessagesScreen(state: UiState, actions: UiActions) {
    // Showing a message is its display indication (TS 24.282 §9.2.1.3): report the new ones.
    LaunchedEffect(state.messages.filter { it.incoming && !it.displayed }.map { it.id }) {
        state.messages.filter { it.incoming && !it.displayed }.forEach { actions.displayed(it.id) }
    }
    var target by rememberSaveable { mutableStateOf(state.groups.firstOrNull()?.id ?: "") }
    var text by rememberSaveable { mutableStateOf("") }
    val group = state.groups.any { it.id == target }
    val attachment = state.attachment
    val limit = if (target.isBlank()) null else state.fileLimit(target, group)
    val tooLarge = attachment?.size != null && limit != null && attachment.size > limit
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth()) {
            SmallClear("Clear messages", enabled = state.messages.isNotEmpty(), tag = Tags.CLEAR_MESSAGES) { confirmClear = true }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(state.messages, key = { it.id }) { m -> MessageRow(m, actions) }
        }
        // Pill-shaped fields, as chat apps draw them.
        OutlinedTextField(target, { target = it }, label = { Text("To (group or MCData ID)") }, singleLine = true,
            shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().testTag(Tags.MESSAGE_TARGET))
        if (attachment != null) PendingAttachment(attachment, limit, tooLarge, actions)
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The paperclip sits inside the message box, at its right edge.
            OutlinedTextField(text, { text = it }, label = { Text("Message") }, shape = RoundedCornerShape(28.dp),
                trailingIcon = if (state.filesEnabled) ({ AttachMenu(actions) }) else null,
                modifier = Modifier.weight(1f).padding(top = 4.dp).testTag(Tags.MESSAGE_TEXT))
            val fileReady = attachment?.ready == true && !tooLarge
            val canSend = state.registered && target.isNotBlank() && (text.isNotBlank() || fileReady)
            IconButton(onClick = {
                if (fileReady) actions.sendAttachment(target, group)
                if (text.isNotBlank()) actions.send(target, group, text)
                text = ""
            }, enabled = canSend, modifier = Modifier.testTag(Tags.SEND).semantics { contentDescription = "Send" }) {
                SendIcon(if (canSend) Mcx.Ink else Mcx.Line, 28.dp)
            }
        }
    }
    if (confirmClear) ConfirmClear("Clear messages?",
        "This removes the messages and files from this phone. Files still being sent or received stay; the History tab keeps its entries.",
        onConfirm = actions::clearMessages) { confirmClear = false }
}

/** A small, quiet "Clear" action for the corner of a list. */
@Composable
private fun SmallClear(label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        modifier = Modifier.height(32.dp).testTag(tag)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ConfirmClear(title: String, text: String, onConfirm: () -> Unit, onClose: () -> Unit) = AlertDialog(
    onDismissRequest = onClose,
    title = { Text(title) },
    text = { Text(text) },
    confirmButton = { TextButton(onClick = { onClose(); onConfirm() }) { Text("Clear") } },
    dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
)

/** Image, Video or Document: each opens the system picker with its filter. */
@Composable
private fun AttachMenu(actions: UiActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(Tags.ATTACH).semantics { contentDescription = "Attach a file" }) {
            PaperclipIcon(Mcx.Muted, 30.dp)
        }
        DropdownMenu(open, { open = false }) {
            for ((kind, label) in listOf(AttachmentKind.IMAGE to "Image", AttachmentKind.VIDEO to "Video", AttachmentKind.DOCUMENT to "Document")) {
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; actions.pickAttachment(kind) },
                    modifier = Modifier.testTag(Tags.attach(kind)))
            }
        }
    }
}

@Composable
private fun PendingAttachment(item: PendingItem, limit: Long?, tooLarge: Boolean, actions: UiActions) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp).background(Mcx.Line, RoundedCornerShape(8.dp)).padding(8.dp).testTag(Tags.ATTACHMENT),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyMedium)
            val details = listOfNotNull(item.type, item.size?.let(AttachmentFiles::readableSize),
                limit?.let { "limit ${AttachmentFiles.readableSize(it)}" }).joinToString(" · ")
            when {
                item.error != null -> Text(item.error, color = Mcx.OnStop, style = MaterialTheme.typography.labelSmall)
                tooLarge -> Text("$details: too large for this recipient", color = Mcx.OnStop, style = MaterialTheme.typography.labelSmall)
                !item.ready -> Text("preparing…", style = MaterialTheme.typography.labelSmall)
                else -> Text(details, style = MaterialTheme.typography.labelSmall)
            }
        }
        TextButton(onClick = actions::clearAttachment, modifier = Modifier.testTag(Tags.ATTACHMENT_CLEAR)) { Text("Remove") }
    }
}

@Composable
private fun MessageRow(m: MessageItem, actions: UiActions = object : UiActions {}) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if (m.incoming) Arrangement.Start else Arrangement.End) {
        Column(Modifier.background(if (m.incoming) Mcx.Card else Mcx.Strip, RoundedCornerShape(12.dp)).padding(10.dp)) {
            val who = if (m.incoming) m.peer.short() + (m.group?.let { " on ${it.short()}" } ?: "") else "to ${m.peer.short()}"
            Text(who, style = MaterialTheme.typography.labelSmall)
            val file = m.file
            if (file == null) Text(m.text) else FileBlock(m.id, file, actions)
            Text("${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.at))} · ${m.status}", style = MaterialTheme.typography.labelSmall,
                modifier = if (file != null) Modifier.testTag(Tags.fileStatus(m.id)) else Modifier)
        }
    }
}

@Composable
private fun FileBlock(id: String, file: FileItem, actions: UiActions) {
    val kind = when {
        file.type.startsWith("image/") -> "Image"
        file.type.startsWith("video/") -> "Video"
        else -> "Document"
    }
    Text("$kind: ${file.name}", style = MaterialTheme.typography.bodyMedium)
    Text("${AttachmentFiles.readableSize(file.size)} · ${file.type}", style = MaterialTheme.typography.labelSmall)
    if ((file.state == "downloading" || file.state == "uploading") && file.size > 0)
        LinearProgressIndicator(progress = { (file.transferred.toFloat() / file.size).coerceIn(0f, 1f) },
            modifier = Modifier.width(180.dp).padding(vertical = 4.dp))
    file.error?.let { Text(it, color = Mcx.OnStop, style = MaterialTheme.typography.labelSmall) }
    Row {
        if (file.canDownload) TextButton(onClick = { actions.downloadAttachment(id) }, modifier = Modifier.testTag(Tags.fileDownload(id))) {
            Text(if (file.state == "failed" || file.state == "interrupted") "Retry" else "Download")
        }
        if (file.canReject) TextButton(onClick = { actions.rejectAttachment(id) }, modifier = Modifier.testTag(Tags.fileReject(id))) { Text("Reject") }
        if (file.canCancel) TextButton(onClick = { actions.cancelAttachment(id) }, modifier = Modifier.testTag(Tags.fileCancel(id))) { Text("Cancel") }
        if (file.canOpen) TextButton(onClick = { actions.openAttachment(id) }, modifier = Modifier.testTag(Tags.fileOpen(id))) { Text("Open") }
    }
}

/** The event log, with the provisioning and battery status someone setting up a phone needs to see. */
@Composable
private fun LogsScreen(state: UiState, actions: UiActions) {
    var confirmClear by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        item {
            if (!state.batteryExempt) {
                Section("Battery")
                Text("Battery optimisation can stop the client between calls.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = actions::requestBatteryExemption) { Text("Keep running in the background") }
            }
            state.error?.let { Section("Last error"); Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Section("Events") }
                OutlinedButton(onClick = { confirmClear = true }, enabled = state.events.isNotEmpty(),
                    modifier = Modifier.testTag(Tags.CLEAR_LOGS)) { Text("Clear logs") }
            }
            if (state.events.isEmpty()) Text("No events", style = MaterialTheme.typography.bodySmall, color = Mcx.Muted)
        }
        items(state.events) { Text(it.substringAfter('T').substringAfter(' '), style = MaterialTheme.typography.bodySmall) }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear logs?") },
        text = { Text("This empties the event log and the SIP trace on this phone.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; actions.clearLogs() }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

/** The history, in Settings: who was located, then calls, messages and files, the newest first. */
@Composable
private fun HistoryContent(state: UiState, actions: UiActions) {
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear) ConfirmClear("Clear history?", "This removes the call, message and file history from this phone.",
        onConfirm = actions::clearHistory) { confirmClear = false }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SmallClear("Clear history", enabled = state.history.isNotEmpty(), tag = Tags.CLEAR_HISTORY) { confirmClear = true }
        }
        if (state.located.isNotEmpty()) {
            Text("Locations", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
            state.located.forEach { l ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag(Tags.LOCATIONS)) {
                    Column(Modifier.weight(1f)) {
                        Text(l.name, fontWeight = FontWeight.Medium)
                        Text((if (l.latitude != null) "%.5f, %.5f".format(l.latitude, l.longitude) else "position unknown") + " · " +
                            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(l.at)), style = MaterialTheme.typography.bodySmall, color = Mcx.Muted)
                    }
                    if (l.latitude != null && l.longitude != null) TextButton(onClick = { actions.showOnMap(l.latitude, l.longitude) }) { Text("Map") }
                }
            }
        }
        if (state.history.isEmpty()) Text("No calls, messages or files yet.", style = MaterialTheme.typography.bodySmall, color = Mcx.Muted)
        state.history.take(200).forEach { h ->
            Column(Modifier.padding(vertical = 6.dp)) {
                Text("${when (h.kind) { "call" -> "Call"; "file" -> "File"; else -> "Message" }} ${if (h.direction == "in") "from" else "to"} ${(h.group ?: h.peer ?: "").short()}",
                    fontWeight = FontWeight.Medium)
                Text(h.summary, style = MaterialTheme.typography.bodySmall)
                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(h.at)), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
