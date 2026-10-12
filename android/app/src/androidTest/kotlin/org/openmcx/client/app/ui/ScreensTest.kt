//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openmcx.client.app.attachments.AttachmentKind
import org.openmcx.client.app.data.HistoryEntry
import org.openmcx.client.mcptt.CallType

@RunWith(AndroidJUnit4::class)
class ScreensTest {
    @get:Rule val compose = createComposeRule()

    private val recorded = mutableListOf<String>()
    private val actions = object : UiActions {
        override fun callGroup(group: String) { recorded += "group $group" }
        override fun callPrivate(target: String) { recorded += "private $target" }
        override fun answer() { recorded += "answer" }
        override fun reject() { recorded += "reject" }
        override fun hangup() { recorded += "hangup" }
        override fun ptt(pressed: Boolean) { recorded += if (pressed) "ptt down" else "ptt up" }
        override fun send(target: String, group: Boolean, text: String) { recorded += "send $target $group $text" }
        override fun displayed(messageId: String) { recorded += "displayed $messageId" }
        override fun setSpeaker(on: Boolean) { recorded += "speaker $on" }
        override fun clearLogs() { recorded += "clear logs" }
        override fun clearMessages() { recorded += "clear messages" }
        override fun importProvisioning() { recorded += "import" }
        override fun scanProvisioning() { recorded += "scan" }
        override fun locate(target: String) { recorded += "locate $target" }
        override fun showOnMap(latitude: Double, longitude: Double) { recorded += "map" }
        override fun callPrivateNoFloor(target: String) { recorded += "no-floor $target" }
        override fun setMuted(muted: Boolean) { recorded += "muted $muted" }
        override fun clearHistory() { recorded += "clear history" }
        override fun dismissCallFailure() { recorded += "dismiss" }
        override fun videoCall(target: String, transmissionControl: Boolean) { recorded += if (transmissionControl) "video $target" else "two-way $target" }
        override fun callPrivateRinging(target: String) { recorded += "ringing $target" }
        override fun setRingFirst(on: Boolean) { recorded += "ring first $on" }
        override fun pickAttachment(kind: AttachmentKind) { recorded += "pick $kind" }
        override fun sendAttachment(target: String, group: Boolean) { recorded += "send file $target $group" }
        override fun clearAttachment() { recorded += "clear file" }
        override fun downloadAttachment(id: String) { recorded += "download $id" }
        override fun rejectAttachment(id: String) { recorded += "reject $id" }
        override fun openAttachment(id: String) { recorded += "open $id" }
        override fun cancelAttachment(id: String) { recorded += "cancel $id" }
        override fun videoTransmit(pressed: Boolean) { recorded += if (pressed) "transmit down" else "transmit up" }
        override fun videoHangup() { recorded += "video hangup" }
        override fun videoGroupCall(group: String) { recorded += "group video $group" }
        override fun videoJoinChat(group: String) { recorded += "join chat $group" }
        override fun videoCallEmergency(target: String) { recorded += "emergency video $target" }
        override fun emergencyAlert(on: Boolean, only: String?) { recorded += "alert $on${only?.let { " $it" } ?: ""}" }
        override fun adhocAlert(criteria: String) { recorded += "adhoc alert $criteria" }
        override fun cancelAlertOf(person: String) { recorded += "cancel alert $person" }
        override fun setServerAddress(host: String?) { recorded += "server address $host" }
        override fun callGroupPriority(group: String, priority: String) { recorded += "group $priority $group" }
        override fun callPrivateEmergency(target: String) { recorded += "private emergency $target" }
        override fun callPriority(emergency: Boolean, enabled: Boolean) { recorded += "priority $emergency $enabled" }
        override fun videoPush(target: String) { recorded += "push $target" }
        override fun videoPull(target: String) { recorded += "pull $target" }
        override fun videoAmbient(target: String, local: Boolean) { recorded += "ambient $target $local" }
        override fun videoPushToServer() { recorded += "record" }
        override fun videoPullFromServer(url: String) { recorded += "play $url" }
        override fun videoRemote(user: String, start: Boolean) { recorded += "remote $user $start" }
        override fun videoPriority(emergency: Boolean, enabled: Boolean) { recorded += "${if (emergency) "emergency" else "imminent"} $enabled" }
        override fun videoCallRinging(target: String) { recorded += "video ringing $target" }
        override fun videoAnswer() { recorded += "video answer" }
        override fun videoDecline() { recorded += "video decline" }
    }

    private val ready = UiState(
        user = "alice", phase = "ready", registered = true,
        groups = listOf(GroupItem("sip:operations@mcx.test", affiliated = true, selected = true)),
        privateTargets = listOf("sip:bob.mcptt@mcx.test"),
    )

    private fun tab(name: String) = compose.onNodeWithText(name).performClick()

    /** History is a section of Settings: open Settings and show it. */
    private fun openHistory() {
        compose.onNodeWithTag(Tags.SETTINGS).performClick()
        compose.onNodeWithTag(Tags.HISTORY_SECTION).performClick()
    }

    private fun call(floor: String, talker: String? = null) =
        CallItem(1, "operations", CallType.PREARRANGED, "established", floor, talker, null)

    @Test
    fun idleScreenPlacesCalls() {
        compose.setContent { MaterialTheme { McxApp(ready, actions) } }
        compose.onNodeWithTag(Tags.PHASE).assertTextEquals("ready")
        compose.onNodeWithTag(Tags.callGroup("sip:operations@mcx.test")).performClick()
        compose.onNodeWithTag(Tags.callPrivate("sip:bob.mcptt@mcx.test")).performClick()
        assertEquals(listOf("group sip:operations@mcx.test", "private sip:bob.mcptt@mcx.test"), recorded)
    }

    @Test
    fun callButtonsWaitForRegistration() {
        compose.setContent { MaterialTheme { McxApp(ready.copy(phase = "connecting", registered = false), actions) } }
        compose.onNodeWithTag(Tags.callGroup("sip:operations@mcx.test")).assertIsNotEnabled()
    }

    @Test
    fun pttIsPressAndHold() {
        compose.setContent { MaterialTheme { McxApp(ready.copy(call = call("idle")), actions) } }
        compose.onNodeWithTag(Tags.PTT).performTouchInput { down(center) }
        compose.waitForIdle()
        assertEquals(listOf("ptt down"), recorded)
        compose.onNodeWithTag(Tags.PTT).performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(listOf("ptt down", "ptt up"), recorded)
        compose.onNodeWithTag(Tags.HANGUP).performClick()
        assertEquals("hangup", recorded.last())
    }

    @Test
    fun floorStateIsShown() {
        var state by mutableStateOf(ready.copy(call = call("taken", "sip:bob.mcptt@mcx.test")))
        compose.setContent { MaterialTheme { McxApp(state, actions) } }
        compose.onNodeWithTag(Tags.FLOOR).assertTextEquals("Bob is talking")
        state = ready.copy(call = call("talking"))
        compose.onNodeWithTag(Tags.FLOOR).assertTextEquals("You are talking")
        state = ready.copy(call = call("requesting"))
        compose.onNodeWithTag(Tags.FLOOR).assertTextEquals("Requesting the floor…")
    }

    @Test
    fun incomingCallCanBeAnsweredOrDeclined() {
        compose.setContent { MaterialTheme { McxApp(ready.copy(incoming = CallItem(2, "bob", CallType.PRIVATE, "ringing", "idle", null, null)), actions) } }
        compose.onNodeWithText("Incoming private call").assertExists()
        compose.onNodeWithTag(Tags.ANSWER).performClick()
        compose.onNodeWithTag(Tags.REJECT).performClick()
        assertEquals(listOf("answer", "reject"), recorded)
    }

    @Test
    fun messagesAreSentAndNewOnesMarkedDisplayed() {
        val inbox = MessageItem("m1", true, "sip:bob.mcdata@mcx.test", null, "Hello Alice", "new", 0, displayed = false)
        compose.setContent { MaterialTheme { McxApp(ready.copy(messages = listOf(inbox)), actions, initialTab = 2) } }
        compose.onNodeWithText("Hello Alice").assertExists()
        compose.waitForIdle()
        compose.onNodeWithTag(Tags.SEND).assertIsNotEnabled()
        compose.onNodeWithTag(Tags.MESSAGE_TEXT).performTextInput("On my way")
        compose.onNodeWithTag(Tags.SEND).assertIsEnabled().performClick()
        assertEquals(listOf("displayed m1", "send sip:operations@mcx.test true On my way"), recorded)
    }

    @Test
    fun failedCallIsExplainedOnTheCallTab() {
        compose.setContent { MaterialTheme { McxApp(ready.copy(callFailure = "Call to bob failed: busy (in another call)"), actions) } }
        compose.onNodeWithTag(Tags.CALL_FAILURE).assertTextEquals("Call to bob failed: busy (in another call)")
        compose.onNodeWithText("Dismiss").performClick()
        assertEquals(listOf("dismiss"), recorded)
    }

    @Test
    fun privateCallCanRingFirst() {
        compose.setContent { MaterialTheme { McxApp(ready, actions) } }
        compose.onNodeWithTag(Tags.callOptions("sip:bob.mcptt@mcx.test")).performClick()
        compose.onNodeWithTag(Tags.callRinging("sip:bob.mcptt@mcx.test")).performClick()
        assertEquals(listOf("ringing sip:bob.mcptt@mcx.test"), recorded)
    }

    @Test
    fun ringFirstIsASetting() {
        compose.setContent { MaterialTheme { McxApp(ready.copy(provisioned = true), actions) } }
        compose.onNodeWithTag(Tags.SETTINGS).performClick()
        compose.onNodeWithTag(Tags.RING_FIRST).performClick()
        assertEquals(listOf("ring first true"), recorded)
    }

    @Test
    fun videoIconsAreOffered() {
        compose.setContent { MaterialTheme { McxApp(ready, actions) } }
        tab("Video")
        compose.onNodeWithTag(Tags.videoPrivate("sip:bob.mcptt@mcx.test")).performClick()
        assertEquals(listOf("video sip:bob.mcptt@mcx.test"), recorded)
    }

    @Test
    fun speakerTogglesTheCallAudio() {
        compose.setContent { MaterialTheme { McxApp(ready.copy(call = call("idle"), speaker = false), actions) } }
        compose.onNodeWithTag(Tags.SPEAKER).assertTextEquals("Earpiece").performClick()
        assertEquals(listOf("speaker true"), recorded)
    }

    @Test
    fun logsTabClearsTheLogAfterConfirmation() {
        val events = listOf("2026-09-26T14:00:00Z registered")
        val ids = listOf("MCPTT ID" to "sip:alice.mcptt@mcx.test", "MCData ID" to "sip:alice.mcdata@mcx.test")
        compose.setContent { MaterialTheme { McxApp(ready.copy(provisioned = true, events = events, identities = ids), actions, initialTab = 3) } }
        compose.onNodeWithText("Logs").assertExists()
        compose.onNodeWithText("sip:alice.mcptt@mcx.test").assertDoesNotExist() // the profile is under Settings now
        compose.onNodeWithTag(Tags.RING_FIRST).assertDoesNotExist()
        compose.onNodeWithTag(Tags.CLEAR_LOGS).performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(emptyList<String>(), recorded)
        compose.onNodeWithTag(Tags.CLEAR_LOGS).performClick()
        compose.onNodeWithText("Clear").performClick()
        assertEquals(listOf("clear logs"), recorded)
    }

    private val files = ready.copy(filesEnabled = true, fileLimits = mapOf("sip:operations@mcx.test" to 1_000_000L, UiState.ONE_TO_ONE to 2_000_000L))

    @Test
    fun attachmentChoicesCallPickerCategory() {
        compose.setContent { MaterialTheme { McxApp(files, actions, initialTab = 2) } }
        for (kind in AttachmentKind.entries) {
            compose.onNodeWithTag(Tags.ATTACH).performClick()
            compose.onNodeWithTag(Tags.attach(kind)).performClick()
        }
        assertEquals(listOf("pick IMAGE", "pick VIDEO", "pick DOCUMENT"), recorded)
    }

    @Test
    fun noAttachmentOptionWithoutFileDistribution() {
        compose.setContent { MaterialTheme { McxApp(ready, actions, initialTab = 2) } }
        compose.onNodeWithTag(Tags.ATTACH).assertDoesNotExist()
    }

    @Test
    fun sendRequiresTargetAndReadyAttachment() {
        var state by mutableStateOf(files.copy(attachment = PendingItem("…")))
        compose.setContent { MaterialTheme { McxApp(state, actions, initialTab = 2) } }
        compose.onNodeWithText("preparing…").assertExists()
        compose.onNodeWithTag(Tags.SEND).assertIsNotEnabled()
        state = files.copy(attachment = PendingItem("clip.mp4", "video/mp4", 1_500_000, ready = true))
        // 1.5 MB is over the group's 1 MB limit, and within the one-to-one 2 MB one.
        compose.onNodeWithText("too large for this recipient", substring = true).assertExists()
        compose.onNodeWithTag(Tags.SEND).assertIsNotEnabled()
        compose.onNodeWithTag(Tags.MESSAGE_TARGET).performTextClearance()
        compose.onNodeWithTag(Tags.SEND).assertIsNotEnabled()
        compose.onNodeWithTag(Tags.MESSAGE_TARGET).performTextInput("sip:bob.mcdata@mcx.test")
        compose.onNodeWithText("limit 2.0 MB", substring = true).assertExists()
        compose.onNodeWithTag(Tags.SEND).assertIsEnabled().performClick()
        compose.onNodeWithTag(Tags.ATTACHMENT_CLEAR).performClick()
        assertEquals(listOf("send file sip:bob.mcdata@mcx.test false", "clear file"), recorded)
    }

    @Test
    fun offerActionsAndTransferProgressAreVisible() {
        fun offer(state: String, transferred: Long = 0, open: Boolean = false, error: String? = null) = MessageItem("f1", true, "sip:bob.mcdata@mcx.test",
            null, "report.pdf", state, 0, true, FileItem("report.pdf", "application/pdf", 1000, state, transferred, error,
                canDownload = state in setOf("offered", "failed"), canReject = state == "offered", canOpen = open, canCancel = state == "downloading"))
        var state by mutableStateOf(files.copy(messages = listOf(offer("offered"))))
        compose.setContent { MaterialTheme { McxApp(state, actions, initialTab = 2) } }
        compose.onNodeWithText("Document: report.pdf").assertExists()
        compose.onNodeWithTag(Tags.fileOpen("f1")).assertDoesNotExist()
        compose.onNodeWithTag(Tags.fileReject("f1")).performClick()
        compose.onNodeWithTag(Tags.fileDownload("f1")).performClick()
        state = files.copy(messages = listOf(offer("downloading", 500)))
        compose.onNodeWithTag(Tags.fileDownload("f1")).assertDoesNotExist()
        compose.onNodeWithTag(Tags.fileCancel("f1")).performClick()
        state = files.copy(messages = listOf(offer("failed", error = "the file does not match its hash")))
        compose.onNodeWithText("the file does not match its hash").assertExists()
        compose.onNodeWithText("Retry").assertExists()
        state = files.copy(messages = listOf(offer("completed", 1000, open = true)))
        compose.onNodeWithTag(Tags.fileOpen("f1")).performClick()
        assertEquals(listOf("reject f1", "download f1", "cancel f1", "open f1"), recorded)
    }

    private fun videoCall(transmission: String, transmitter: String? = null, tc: Boolean = true) =
        VideoCallItem(1, "bob", "established", transmission, transmitter, tc)

    @Test
    fun videoCallTransmitsWhileHeldAndHangsUp() {
        compose.setContent { MaterialTheme { McxApp(ready.copy(videoCall = videoCall("idle")), actions) } }
        compose.onNodeWithTag(Tags.VIDEO_STATUS).assertTextEquals("Hold Transmit to send your camera and voice")
        compose.onNodeWithTag(Tags.VIDEO_TRANSMIT).performTouchInput { down(center) }
        compose.waitForIdle()
        compose.onNodeWithTag(Tags.VIDEO_TRANSMIT).performTouchInput { up() }
        compose.waitForIdle()
        compose.onNodeWithTag(Tags.VIDEO_HANGUP).performClick()
        assertEquals(listOf("transmit down", "transmit up", "video hangup"), recorded)
    }

    @Test
    fun videoCallShowsWhoTransmits() {
        var state by mutableStateOf(ready.copy(videoCall = videoCall("receiving", "bob")))
        compose.setContent { MaterialTheme { McxApp(state, actions) } }
        compose.onNodeWithTag(Tags.VIDEO_STATUS).assertTextEquals("bob is transmitting")
        state = ready.copy(videoCall = videoCall("transmitting"))
        compose.onNodeWithTag(Tags.VIDEO_STATUS).assertTextEquals("You are transmitting")
        state = ready.copy(videoCall = videoCall("two-way", tc = false))
        compose.onNodeWithTag(Tags.VIDEO_STATUS).assertTextEquals("Two-way video")
        compose.onNodeWithTag(Tags.VIDEO_TRANSMIT).assertDoesNotExist()
    }

    @Test
    fun twoWayVideoIsACallOption() {
        compose.setContent { MaterialTheme { McxApp(ready, actions) } }
        tab("Video")
        compose.onNodeWithTag(Tags.videoOptions("sip:bob.mcptt@mcx.test")).performClick()
        compose.onNodeWithTag(Tags.videoTwoWay("sip:bob.mcptt@mcx.test")).performClick()
        compose.onNodeWithTag(Tags.videoPrivate("sip:bob.mcptt@mcx.test")).performClick()
        assertEquals(listOf("two-way sip:bob.mcptt@mcx.test", "video sip:bob.mcptt@mcx.test"), recorded)
    }

    @Test
    fun groupVideoCallFromTheGroupRow() {
        compose.setContent { MaterialTheme { McxApp(ready, actions) } }
        tab("Video")
        compose.onNodeWithTag(Tags.videoGroup("sip:operations@mcx.test")).performClick()
        assertEquals(listOf("group video sip:operations@mcx.test"), recorded)
    }

    @Test
    fun groupVideoCallShowsTheGroup() {
        compose.setContent { MaterialTheme { McxApp(ready.copy(videoCall = VideoCallItem(1, "operations", "established", "receiving",
            "bob", true, group = "sip:operations@mcx.test")), actions) } }
        compose.onNodeWithText("operations (group video)").assertExists()
        compose.onNodeWithTag(Tags.VIDEO_STATUS).assertTextEquals("bob is transmitting")
    }

    @Test
    fun incomingVideoCallRingsUntilAnsweredOrDeclined() {
        compose.setContent { MaterialTheme { McxApp(ready.copy(videoCall = VideoCallItem(1, "alice", "ringing", "idle", null, true,
            incoming = true)), actions) } }
        compose.onNodeWithTag(Tags.VIDEO_STATUS).assertTextEquals("Incoming video call")
        compose.onNodeWithTag(Tags.VIDEO_TRANSMIT).assertDoesNotExist()
        compose.onNodeWithTag(Tags.VIDEO_DECLINE).performClick()
        compose.onNodeWithTag(Tags.VIDEO_ANSWER).performClick()
        assertEquals(listOf("video decline", "video answer"), recorded)
    }

    @Test
    fun outgoingVideoCallCanRing() {
        var state by mutableStateOf(ready)
        compose.setContent { MaterialTheme { McxApp(state, actions) } }
        tab("Video")
        compose.onNodeWithTag(Tags.videoOptions("sip:bob.mcptt@mcx.test")).performClick()
        compose.onNodeWithTag(Tags.videoRinging("sip:bob.mcptt@mcx.test")).performClick()
        state = ready.copy(videoCall = VideoCallItem(1, "bob", "ringing", "idle", null, true))
        compose.onNodeWithTag(Tags.VIDEO_STATUS).assertTextEquals("Ringing…")
        compose.onNodeWithTag(Tags.VIDEO_ANSWER).assertDoesNotExist()
        compose.onNodeWithTag(Tags.VIDEO_HANGUP).performClick()
        assertEquals(listOf("video ringing sip:bob.mcptt@mcx.test", "video hangup"), recorded)
    }

    @Test
    fun groupVideoCallListsItsParticipants() {
        compose.setContent { MaterialTheme { McxApp(ready.copy(videoCall = VideoCallItem(1, "operations", "established", "idle", null, true,
            group = "sip:operations@mcx.test", participants = listOf("alice", "bob", "client-c"))), actions) } }
        compose.onNodeWithTag(Tags.VIDEO_PARTICIPANTS).assertTextEquals("In call (3): alice, bob, client-c")
    }

    @Test
    fun videoChatGroupsCanBeJoined() {
        compose.setContent { MaterialTheme { McxApp(ready.copy(videoChatGroups = listOf("sip:video-chat@mcx.test")), actions) } }
        tab("Video")
        compose.onNodeWithText("Video chat groups").assertExists()
        compose.onNodeWithTag(Tags.videoChat("sip:video-chat@mcx.test")).performClick()
        assertEquals(listOf("join chat sip:video-chat@mcx.test"), recorded)
    }

    @Test
    fun videoChatCallIsNamedAsSuch() {
        compose.setContent { MaterialTheme { McxApp(ready.copy(videoCall = VideoCallItem(1, "video-chat", "established", "idle", null, true,
            group = "sip:video-chat@mcx.test", chat = true)), actions) } }
        compose.onNodeWithText("video-chat (video chat)").assertExists()
    }

    @Test
    fun emergencyVideoCallIsShownAndCanBeCancelled() {
        var state by mutableStateOf(ready.copy(videoCall = VideoCallItem(1, "operations", "established", "idle", null, true,
            group = "sip:operations@mcx.test")))
        compose.setContent { MaterialTheme { McxApp(state, actions) } }
        compose.onNodeWithTag(Tags.VIDEO_PRIORITY).assertDoesNotExist()
        compose.onNodeWithTag(Tags.VIDEO_EMERGENCY).performClick()
        compose.onNodeWithTag(Tags.VIDEO_IMMINENT).performClick()
        state = state.copy(videoCall = state.videoCall!!.copy(priority = "emergency"))
        compose.onNodeWithTag(Tags.VIDEO_PRIORITY).assertTextEquals("EMERGENCY")
        compose.onNodeWithTag(Tags.VIDEO_IMMINENT).assertDoesNotExist()
        compose.onNodeWithText("Cancel emergency").performClick()
        state = state.copy(videoCall = state.videoCall!!.copy(priority = "imminent"))
        compose.onNodeWithTag(Tags.VIDEO_PRIORITY).assertTextEquals("IMMINENT PERIL")
        assertEquals(listOf("emergency true", "imminent true", "emergency false"), recorded)
    }

    @Test
    fun emergencyVideoCallIsACallOption() {
        compose.setContent { MaterialTheme { McxApp(ready, actions) } }
        tab("Video")
        compose.onNodeWithTag(Tags.videoOptions("sip:bob.mcptt@mcx.test")).performClick()
        compose.onNodeWithTag(Tags.videoEmergency("sip:bob.mcptt@mcx.test")).performClick()
        assertEquals(listOf("emergency video sip:bob.mcptt@mcx.test"), recorded)
    }

    @Test
    fun emergencyAlertsAreSentAndShown() {
        val services = listOf("MCPTT", "MCData", "Video")
        var state by mutableStateOf(ready.copy(alertGroup = "sip:operations@mcx.test", alertServices = services,
            adhocChoices = AlertChoices.of(listOf("sip:operations@mcx.test"))))
        compose.setContent { MaterialTheme { McxApp(state, actions) } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(Tags.EMERGENCY_ALERT))
        compose.onNodeWithText("To Operations").assertExists()
        compose.onNodeWithText("Through MCPTT, MCData, Video").assertExists()
        compose.onNodeWithTag(Tags.EMERGENCY_ALERT).assertTextEquals("Send emergency alert").performClick()
        state = state.copy(ownAlert = services, receivedAlerts = listOf(AlertItem("Bob", "operations", "MCPTT, MCData, Video", 48.8566, 2.3522)))
        compose.onNodeWithText("Your alert is up: MCPTT, MCData, Video").assertExists()
        compose.onNodeWithTag(Tags.EMERGENCY_ALERT).assertTextEquals("Cancel alert").performClick()
        // One banner for Bob, whatever services the alert came through.
        compose.onNodeWithText("EMERGENCY ALERT: Bob").assertExists()
        compose.onNodeWithText("operations · MCPTT, MCData, Video · 48.85660, 2.35220").assertExists()
        compose.onNodeWithTag(Tags.alertCancelOf("Bob")).performClick()
        // ▾: one service only, and an ad hoc alert to a ready-made audience.
        state = state.copy(ownAlert = listOf("MCData"))
        compose.onNodeWithTag(Tags.ALERT_OPTIONS).performClick()
        compose.onNodeWithTag(Tags.alertOnly("MCData")).assertTextEquals("Cancel MCData alert")
        compose.onNodeWithTag(Tags.alertOnly("Video")).assertTextEquals("Video only").performClick()
        compose.onNodeWithTag(Tags.ALERT_OPTIONS).performClick()
        compose.onNodeWithTag(Tags.ADHOC_ALERT).performClick()
        compose.onNodeWithText("Everyone affiliated to Operations").performClick()
        assertEquals(listOf("alert true", "alert false", "cancel alert Bob", "alert true Video",
            "adhoc alert affiliated:sip:operations@mcx.test"), recorded)
    }

    @Test
    fun videoPushPullAndRecordings() {
        val url = "https://mcvideo-server/recordings/abc"
        compose.setContent { MaterialTheme { McxApp(ready.copy(videoGroups = listOf("sip:operations@mcx.test"), videoRecordings = listOf(url)), actions) } }
        tab("Video")
        compose.onNodeWithTag(Tags.videoOptions("sip:bob.mcptt@mcx.test")).performClick()
        compose.onNodeWithTag(Tags.videoPush("sip:bob.mcptt@mcx.test")).performClick()
        compose.onNodeWithTag(Tags.videoOptions("sip:bob.mcptt@mcx.test")).performClick()
        compose.onNodeWithTag(Tags.videoPull("sip:bob.mcptt@mcx.test")).performClick()
        compose.onNodeWithTag(Tags.VIDEO_RECORD).performClick()
        compose.onNodeWithTag(Tags.videoPlay(url)).performClick()
        assertEquals(listOf("push sip:bob.mcptt@mcx.test", "pull sip:bob.mcptt@mcx.test", "record", "play $url"), recorded)
    }

    @Test
    fun pushAndPullCallsSayWhatHappens() {
        var state by mutableStateOf(ready.copy(videoCall = VideoCallItem(1, "bob", "established", "idle", null, true, mode = "one-to-one video pull")))
        compose.setContent { MaterialTheme { McxApp(state, actions) } }
        compose.onNodeWithTag(Tags.VIDEO_STATUS).assertTextEquals("Watching bob's camera")
        compose.onNodeWithTag(Tags.VIDEO_TRANSMIT).assertDoesNotExist()
        state = ready.copy(videoCall = VideoCallItem(1, "alice", "established", "idle", null, true, incoming = true, mode = "one-to-one video push"))
        compose.onNodeWithTag(Tags.VIDEO_STATUS).assertTextEquals("alice is pushing video to you")
        state = ready.copy(videoCall = VideoCallItem(1, "operations", "established", "idle", null, true, group = "sip:operations@mcx.test",
            participantIds = listOf("sip:bob.mcvideo@mcx.test")))
        compose.onNodeWithTag(Tags.videoParticipant("sip:bob.mcvideo@mcx.test")).performClick()
        compose.onNodeWithText("Start their video").performClick()
        assertEquals(listOf("remote sip:bob.mcvideo@mcx.test true"), recorded)
    }

    @Test
    fun ambientViewing() {
        compose.setContent { MaterialTheme { McxApp(ready, actions) } }
        tab("Video")
        compose.onNodeWithTag(Tags.videoOptions("sip:bob.mcptt@mcx.test")).performClick()
        compose.onNodeWithTag(Tags.videoAmbient("sip:bob.mcptt@mcx.test")).performClick()
        compose.onNodeWithTag(Tags.videoOptions("sip:bob.mcptt@mcx.test")).performClick()
        compose.onNodeWithTag(Tags.videoAmbientLocal("sip:bob.mcptt@mcx.test")).performClick()
        assertEquals(listOf("ambient sip:bob.mcptt@mcx.test false", "ambient sip:bob.mcptt@mcx.test true"), recorded)
    }

    @Test
    fun ambientCallScreen() {
        var state by mutableStateOf(ready.copy(videoCall = VideoCallItem(1, "bob", "established", "receiving", "bob", true,
            mode = "ambient-viewing", ambientRole = "viewing")))
        compose.setContent { MaterialTheme { McxApp(state, actions) } }
        compose.onNodeWithTag(Tags.VIDEO_STATUS).assertTextEquals("Viewing bob's camera (ambient)")
        compose.onNodeWithTag(Tags.VIDEO_TRANSMIT).assertDoesNotExist()
        compose.onNodeWithTag(Tags.VIDEO_HANGUP).assertIsEnabled()
        // Asked to be viewed, without the release capability: End is disabled.
        state = ready.copy(videoCall = VideoCallItem(1, "bob", "established", "transmitting", null, true,
            mode = "ambient-viewing", ambientRole = "viewed-to", mayRelease = false))
        compose.onNodeWithTag(Tags.VIDEO_STATUS).assertTextEquals("bob is viewing your camera (ambient)")
        compose.onNodeWithTag(Tags.VIDEO_HANGUP).assertIsNotEnabled()
    }

    @Test
    fun clearMessagesAsksFirst() {
        val inbox = MessageItem("m1", true, "sip:bob.mcdata@mcx.test", null, "Hello Alice", "new", 0, displayed = true)
        compose.setContent { MaterialTheme { McxApp(ready.copy(messages = listOf(inbox)), actions, initialTab = 2) } }
        compose.onNodeWithTag(Tags.CLEAR_MESSAGES).performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(emptyList<String>(), recorded)
        compose.onNodeWithTag(Tags.CLEAR_MESSAGES).performClick()
        compose.onNodeWithText("Clear").performClick()
        assertEquals(listOf("clear messages"), recorded)
    }

    @Test
    fun clearHistoryAsksFirst() {
        val entry = HistoryEntry("call", "1", "out", "sip:bob.mcptt@mcx.test", null, "private: ended", 0)
        compose.setContent { MaterialTheme { McxApp(ready.copy(history = listOf(entry)), actions) } }
        openHistory()
        compose.onNodeWithTag(Tags.CLEAR_HISTORY).performClick()
        compose.onNodeWithText("Clear").performClick()
        assertEquals(listOf("clear history"), recorded)
    }

    @Test
    fun nothingToClear() {
        compose.setContent { MaterialTheme { McxApp(ready, actions, initialTab = 2) } }
        compose.onNodeWithTag(Tags.CLEAR_MESSAGES).assertIsNotEnabled()
    }

    /** The Call tab holds calls and alerts only; video calls, video chat and recordings are on the Video tab. */
    @Test
    fun callTabOrder() {
        compose.setContent { MaterialTheme { McxApp(ready.copy(videoGroups = listOf("sip:operations@mcx.test"),
            videoChatGroups = listOf("sip:video-chat@mcx.test"), alertServices = listOf("MCPTT", "MCData", "Video")), actions) } }
        val top = { text: String -> compose.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y }
        val order = listOf("Groups", "Private calls", "Emergency alert").map(top)
        assertEquals(order.sorted(), order)
        compose.onNodeWithText("Video chat groups").assertDoesNotExist()
        compose.onNodeWithText("Video recordings").assertDoesNotExist()
        compose.onNodeWithTag(Tags.videoPrivate("sip:bob.mcptt@mcx.test")).assertDoesNotExist()
        tab("Video")
        val video = listOf("Group video", "Private video", "Video chat groups", "Video recordings").map(top)
        assertEquals(video.sorted(), video)
    }

    /** The ▾ that opens more choices is twice the size of the old text arrow: at least 56 x 48 dp. */
    @Test
    fun moreButtonsAreLarge() {
        compose.setContent { MaterialTheme { McxApp(ready, actions) } }
        compose.onNodeWithTag(Tags.callOptions("sip:bob.mcptt@mcx.test")).assertWidthIsAtLeast(56.dp).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag(Tags.groupOptions("sip:operations@mcx.test")).assertWidthIsAtLeast(56.dp).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun settingsHoldProvisioningProfileAndAbout() {
        val ids = listOf("MCPTT ID" to "sip:alice.mcptt@mcx.test", "MCData ID" to "sip:alice.mcdata@mcx.test")
        compose.setContent { MaterialTheme { McxApp(ready.copy(provisioned = true, identities = ids), actions) } }
        compose.onNodeWithTag(Tags.SETTINGS).performClick()
        compose.onNodeWithText("sip:alice.mcptt@mcx.test").assertDoesNotExist()
        compose.onNodeWithTag(Tags.PROFILE_INFO).performClick()
        compose.onNodeWithText("sip:alice.mcptt@mcx.test").assertExists()
        compose.onNodeWithText("sip:alice.mcdata@mcx.test").assertExists()
        compose.onNodeWithTag(Tags.IMPORT).performClick()
        compose.onNodeWithTag(Tags.SCAN_QR).performClick()
        compose.onNodeWithText("The OpenMCX project is the first open-source implementation of 3GPP Release 19 Mission Critical Services.").assertExists()
        compose.onNodeWithTag(Tags.ABOUT_LINK).assertExists()
        compose.onNodeWithTag(Tags.SETTINGS_BACK).performClick()
        compose.onNodeWithText("Private calls").assertExists()
        assertEquals(listOf("import", "scan"), recorded)
    }

    @Test
    fun privateCallWithoutFloorControl() {
        compose.setContent { MaterialTheme { McxApp(ready, actions) } }
        compose.onNodeWithTag(Tags.callOptions("sip:bob.mcptt@mcx.test")).performClick()
        compose.onNodeWithTag(Tags.callNoFloor("sip:bob.mcptt@mcx.test")).performClick()
        assertEquals(listOf("no-floor sip:bob.mcptt@mcx.test"), recorded)
    }

    @Test
    fun duplexCallMutesInsteadOfPtt() {
        var state by mutableStateOf(ready.copy(call = call("idle").copy(floorControl = false)))
        compose.setContent { MaterialTheme { McxApp(state, actions) } }
        compose.onNodeWithTag(Tags.PTT).assertDoesNotExist()
        compose.onNodeWithTag(Tags.FLOOR).assertTextEquals("Both can talk")
        compose.onNodeWithTag(Tags.MUTE).performClick()
        state = ready.copy(call = call("idle").copy(floorControl = false, muted = true))
        compose.onNodeWithTag(Tags.FLOOR).assertTextEquals("Muted")
        compose.onNodeWithTag(Tags.MUTE).performClick()
        assertEquals(listOf("muted true", "muted false"), recorded)
    }

    @Test
    fun locationRequestAndResults() {
        compose.setContent { MaterialTheme { McxApp(ready.copy(located = listOf(LocatedItem("Bob", 48.1, 2.1, null, 0))), actions) } }
        compose.onNodeWithTag(Tags.callOptions("sip:bob.mcptt@mcx.test")).performClick()
        compose.onNodeWithTag(Tags.locate("sip:bob.mcptt@mcx.test")).performClick()
        openHistory()
        compose.onNodeWithText("Locations").assertExists()
        compose.onNodeWithText("Map").performClick()
        assertEquals(listOf("locate sip:bob.mcptt@mcx.test", "map"), recorded)
    }

    @Test
    fun settingsSayWhetherLocationIsReported() {
        compose.setContent { MaterialTheme { McxApp(ready.copy(locationSharing = "Reported to the MC service for MCPTT"), actions) } }
        compose.onNodeWithTag(Tags.SETTINGS).performClick()
        compose.onNodeWithText("Reported to the MC service for MCPTT").assertExists()
    }

    @Test
    fun serverAddressIsEditable() {
        var state by mutableStateOf(ready.copy(provisioned = true, serverAddress = "192.0.2.39", profileServerAddress = "192.0.2.39"))
        compose.setContent { MaterialTheme { McxApp(state, actions) } }
        compose.onNodeWithTag(Tags.SETTINGS).performClick()
        compose.onNodeWithTag(Tags.SERVER_ADDRESS).assertTextEquals("192.0.2.39")
        compose.onNodeWithTag(Tags.SERVER_ADDRESS_EDIT).performClick()
        compose.onNodeWithTag(Tags.SERVER_ADDRESS_FIELD).performTextClearance()
        compose.onNodeWithTag(Tags.SERVER_ADDRESS_FIELD).performTextInput("192.0.2.300")
        compose.onNodeWithTag(Tags.SERVER_ADDRESS_SAVE).assertIsNotEnabled()
        compose.onNodeWithTag(Tags.SERVER_ADDRESS_FIELD).performTextClearance()
        compose.onNodeWithTag(Tags.SERVER_ADDRESS_FIELD).performTextInput("192.0.2.20")
        compose.onNodeWithTag(Tags.SERVER_ADDRESS_SAVE).performClick()
        state = state.copy(serverAddress = "192.0.2.20", serverAddressEdited = true)
        compose.onNodeWithTag(Tags.SERVER_ADDRESS).assertTextEquals("192.0.2.20 (edited; profile: 192.0.2.39)")
        compose.onNodeWithTag(Tags.SERVER_ADDRESS_EDIT).performClick()
        compose.onNodeWithTag(Tags.SERVER_ADDRESS_RESET).performClick()
        assertEquals(listOf("server address 192.0.2.20", "server address null"), recorded)
    }

    @Test
    fun emergencyAndImminentPerilVoiceCalls() {
        var state by mutableStateOf(ready)
        compose.setContent { MaterialTheme { McxApp(state, actions) } }
        compose.onNodeWithTag(Tags.groupOptions("sip:operations@mcx.test")).performClick()
        compose.onNodeWithTag(Tags.callEmergency("sip:operations@mcx.test")).performClick()
        compose.onNodeWithTag(Tags.groupOptions("sip:operations@mcx.test")).performClick()
        compose.onNodeWithTag(Tags.callImminent("sip:operations@mcx.test")).performClick()
        compose.onNodeWithTag(Tags.callOptions("sip:bob.mcptt@mcx.test")).performClick()
        compose.onNodeWithTag(Tags.callEmergency("sip:bob.mcptt@mcx.test")).performClick()
        // In a group call: raise imminent peril, then emergency; the banner follows the call's state.
        state = ready.copy(call = call("idle").copy(type = CallType.PREARRANGED, state = "established"))
        compose.onNodeWithTag(Tags.CALL_PRIORITY).assertDoesNotExist()
        compose.onNodeWithTag(Tags.CALL_IMMINENT).assertTextEquals("Imminent peril").performClick()
        state = state.copy(call = state.call!!.copy(priority = "imminent"))
        compose.onNodeWithTag(Tags.CALL_PRIORITY).assertTextEquals("IMMINENT PERIL")
        compose.onNodeWithTag(Tags.CALL_EMERGENCY).performClick()
        state = state.copy(call = state.call!!.copy(priority = "emergency"))
        compose.onNodeWithTag(Tags.CALL_PRIORITY).assertTextEquals("EMERGENCY")
        compose.onNodeWithTag(Tags.CALL_IMMINENT).assertDoesNotExist()
        compose.onNodeWithTag(Tags.CALL_EMERGENCY).assertTextEquals("Cancel emergency").performClick()
        assertEquals(listOf("group emergency sip:operations@mcx.test", "group imminent sip:operations@mcx.test",
            "private emergency sip:bob.mcptt@mcx.test", "priority false true", "priority true true", "priority true false"), recorded)
    }

    /** The tabs are Call, Video, Message and Logs; History is in Settings. */
    @Test
    fun historyIsInSettings() {
        val entry = HistoryEntry("call", "1", "out", "sip:bob.mcptt@mcx.test", null, "private: ended", 0)
        compose.setContent { MaterialTheme { McxApp(ready.copy(history = listOf(entry)), actions) } }
        compose.onNodeWithText("History").assertDoesNotExist()
        compose.onNodeWithTag(Tags.SETTINGS).performClick()
        compose.onNodeWithText("private: ended").assertDoesNotExist()
        compose.onNodeWithTag(Tags.HISTORY_SECTION).performClick()
        compose.onNodeWithText("private: ended").assertExists()
    }
}
