//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.openmcx.client.mcptt.CallType
import java.io.File

/**
 * Renders the main screens in each floor state, light and dark and at a large font scale, on the JVM (Robolectric,
 * a 393 x 873 dp phone), for reviewing the look without a phone. Not a check: it only writes PNGs to build/shots/.
 * Run with `tools/gradle-docker.sh :app:testDebugUnitTest --tests '*ShowcaseShots'`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h873dp-xxhdpi")
class ShowcaseShots {
    @get:Rule val compose = createComposeRule()

    private val groups = listOf(
        GroupItem("sip:operations@mcx.test", affiliated = true, selected = true),
        GroupItem("sip:maintenance@mcx.test", affiliated = true, selected = false),
        GroupItem("sip:yard-north@mcx.test", affiliated = false, selected = false),
    )
    private val ready = UiState(
        user = "alice", phase = "ready", registered = true, provisioned = true, groups = groups,
        privateTargets = listOf("sip:bob.mcptt@mcx.test", "sip:carol.mcptt@mcx.test"),
        alertGroup = "sip:operations@mcx.test", alertServices = listOf("MCPTT", "MCData", "Video"), speaker = true,
    )
    private val members = listOf("sip:alice@mcx.test", "sip:bob@mcx.test", "sip:carol@mcx.test", "sip:dave@mcx.test")
    private fun call(floor: String, talker: String? = null, priority: String = "normal", limit: Int? = null, event: String? = null) =
        CallItem(1, "Operations", CallType.PREARRANGED, "established", floor, talker, event, limit,
            startedAt = System.currentTimeMillis() - 83_000, members = members, priority = priority)

    private val screens: List<Pair<String, UiState>> = listOf(
        "list" to ready,
        "list-alert" to ready.copy(receivedAlerts = listOf(AlertItem("Bob", "Emergency", "MCPTT, MCData", 48.8566, 2.3522, cancellable = true)),
            callFailure = "Call to carol failed: busy (in another call)"),
        "idle" to ready.copy(call = call("idle")),
        "requesting" to ready.copy(call = call("requesting")),
        "talking" to ready.copy(call = call("talking", limit = 30)),
        "taken" to ready.copy(call = call("taken", talker = "sip:bob@mcx.test")),
        "denied" to ready.copy(call = call("idle", event = "denied (another user has the floor)")),
        "emergency" to ready.copy(call = call("talking", priority = "emergency")),
        "imminent" to ready.copy(call = call("taken", talker = "sip:carol@mcx.test", priority = "imminent")),
        "duplex" to ready.copy(call = call("idle").copy(type = CallType.PRIVATE, title = "bob", floorControl = false,
            members = listOf("sip:alice@mcx.test", "sip:bob@mcx.test"))),
        "muted" to ready.copy(call = call("idle").copy(type = CallType.PRIVATE, title = "bob", floorControl = false, muted = true,
            members = listOf("sip:alice@mcx.test", "sip:bob@mcx.test"))),
        "incoming" to ready.copy(incoming = call("idle").copy(type = CallType.PRIVATE, title = "bob", state = "ringing")),
    )

    @Composable
    private fun Themed(dark: Boolean, content: @Composable () -> Unit) = OpenMcxTheme(dark = dark, content = content)

    @Test
    fun shoot() {
        var state by mutableStateOf(ready)
        var dark by mutableStateOf(false)
        var fontScale by mutableStateOf(1f)
        // The PTT pulse and level bars never stop: drive the clock by hand so the test can go idle.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Themed(dark) { McxApp(state, object : UiActions {}) }
            }
        }
        val dir = File("build/shots").apply { deleteRecursively(); mkdirs() }
        for ((variant, d, scale) in listOf(Triple("light", false, 1f), Triple("dark", true, 1f), Triple("large", false, 1.5f))) {
            for ((name, s) in screens) {
                if (variant == "large" && name !in setOf("list", "talking", "taken")) continue
                state = s; dark = d; fontScale = scale
                // Let the new state compose, lay out (the lists are lazy) and settle before the picture.
                repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
                compose.mainClock.advanceTimeBy(600)
                compose.waitForIdle()
                File(dir, "$name-$variant.png").outputStream().use {
                    compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }
}
