//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openmcx.client.app.attachments.AttachmentFiles
import org.openmcx.client.app.attachments.AttachmentKind
import org.openmcx.client.app.ui.EngineActions
import org.openmcx.client.app.ui.McxApp
import org.openmcx.client.app.ui.OpenMcxTheme
import org.openmcx.client.app.ui.UiActions
import org.openmcx.client.app.ui.UiState
import org.openmcx.client.app.ui.snapshot

class MainActivity : ComponentActivity() {
    private var state by mutableStateOf(UiState())

    /** Pick a provisioning file (from Downloads, a share, a USB copy…) and install it. */
    private val pickProvisioning = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::importProvisioning)
    }

    /** Scan a profile QR code: the profile, or a one-time link to it (QrProfile). */
    private val scanProvisioning = registerForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { result ->
        result.contents?.let(::importScanned)
    }

    /** Pick an attachment through the system document picker: no storage permission is needed. */
    private val pickAttachment = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { Engine.stageAttachment(applicationContext, it) }
    }

    private var pendingVideoTarget: Pair<String, Boolean>? = null
    /** The alert waiting for the location permission. */
    private var pendingAlert: (suspend () -> String)? = null
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val alert = pendingAlert.also { pendingAlert = null } ?: return@registerForActivityResult
        if (granted.values.any { it }) runAlert(alert)
        else Toast.makeText(this, "An emergency alert needs the location permission", Toast.LENGTH_LONG).show()
    }

    /** Run [block] when the camera permission is granted, else ask for it (the user tries again). */
    private fun withCamera(block: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) block()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun runAlert(alert: suspend () -> String) {
        lifecycleScope.launch { Toast.makeText(this@MainActivity, alert(), Toast.LENGTH_LONG).show() }
    }

    /** Raise an alert with this phone's location: ask for the location permission first if it is missing. */
    private fun withLocation(alert: suspend () -> String) {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (granted || Engine.positionOverride != null) runAlert(alert)
        else {
            pendingAlert = alert
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val target = pendingVideoTarget.also { pendingVideoTarget = null } ?: return@registerForActivityResult
        if (granted) EngineActions.videoCall(target.first, target.second)
        else Toast.makeText(this, "Video calls need the camera permission", Toast.LENGTH_LONG).show()
    }

    private val actions = object : UiActions by EngineActions {
        override fun videoCall(target: String, transmissionControl: Boolean) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                pendingVideoTarget = target to transmissionControl
                cameraPermission.launch(Manifest.permission.CAMERA)
            } else EngineActions.videoCall(target, transmissionControl)
        }

        override fun videoCallRinging(target: String) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                cameraPermission.launch(Manifest.permission.CAMERA)
            else EngineActions.videoCallRinging(target)
        }

        override fun videoCallEmergency(target: String) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                cameraPermission.launch(Manifest.permission.CAMERA)
            else EngineActions.videoCallEmergency(target)
        }

        override fun emergencyAlert(on: Boolean, only: String?) =
            if (on) withLocation { Engine.emergencyAlert(true, only) } else runAlert { Engine.emergencyAlert(false, only) }

        override fun adhocAlert(criteria: String) = withLocation { Engine.adhocAlert(criteria) }

        override fun cancelAlertOf(person: String) = runAlert { Engine.cancelAlertOf(person.lowercase()) }

        override fun showOnMap(latitude: Double, longitude: Double) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude(Emergency)"))) }
                .onFailure { Toast.makeText(this@MainActivity, "No map app on this phone", Toast.LENGTH_SHORT).show() }
        }

        override fun videoPush(target: String) = withCamera { EngineActions.videoPush(target) }
        override fun videoPull(target: String) = EngineActions.videoPull(target)
        override fun videoPushToServer() = withCamera { EngineActions.videoPushToServer() }

        override fun videoJoinChat(group: String) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                cameraPermission.launch(Manifest.permission.CAMERA)
            else EngineActions.videoJoinChat(group)
        }

        override fun videoGroupCall(group: String) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                Toast.makeText(this@MainActivity, "Video calls need the camera permission", Toast.LENGTH_LONG).show()
                    .also { cameraPermission.launch(Manifest.permission.CAMERA) }
            else EngineActions.videoGroupCall(group)
        }

        override fun pickAttachment(kind: AttachmentKind) = pickAttachment.launch(kind.mimeTypes)

        override fun openAttachment(id: String) {
            val record = Engine.fd?.record(id)
            val intent = record?.let { AttachmentFiles.viewIntent(this@MainActivity, it) }
            if (intent == null) { Toast.makeText(this@MainActivity, "The file is not available", Toast.LENGTH_SHORT).show(); return }
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this@MainActivity, "No app on this phone opens ${record.metadata.mediaType} files", Toast.LENGTH_LONG).show()
            }
        }

        override fun setServerAddress(host: String?) {
            lifecycleScope.launch {
                val message = withContext(Dispatchers.IO) { Engine.setServerAddress(applicationContext, host) }
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        }

        override fun importProvisioning() = pickProvisioning.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
        override fun scanProvisioning() = withCamera {
            scanProvisioning.launch(com.journeyapps.barcodescanner.ScanOptions()
                .setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                .setPrompt("Scan the MCX profile QR code").setBeepEnabled(false).setOrientationLocked(false))
        }

        @SuppressLint("BatteryLife") // an MC client must stay reachable; the user confirms in the system dialog
        override fun requestBatteryExemption() {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge to edge, with dark status bar icons over the app's light top strip.
        enableEdgeToEdge(statusBarStyle = androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        McxService.start(this)
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
            .launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.CAMERA,
                // Location reporting to the MC service (TS 24.379 clause 13): position and cell.
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        val tab = if (intent.getStringExtra(EXTRA_TAB) == TAB_MESSAGES) 2 else 0 // Call, Video, Message, History, Logs
        setContent { OpenMcxTheme(dark = isSystemInDarkTheme()) { McxApp(state, actions, tab) } }
        // In a call the volume keys set the call's volume (the earpiece's or the loudspeaker's), not the media volume.
        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(Engine.inCall, Engine.inVideoCall) { a, v -> a || v }.collect { calling ->
                volumeControlStream = if (calling) android.media.AudioManager.STREAM_VOICE_CALL else android.media.AudioManager.USE_DEFAULT_STREAM_TYPE
            }
        }
        // The engine's state lives in coroutines and flows; the screen samples it while visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val exempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
                    state = snapshot(exempt)
                    delay(200)
                }
            }
        }
        intent?.data?.let(::importProvisioning)
    }

    private fun importScanned(scanned: String) {
        if (org.openmcx.client.provisioning.Activation.isLink(scanned)) {
            askPassword { password -> activate(scanned, password) }
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    Provisioning(applicationContext).import(org.openmcx.client.provisioning.QrProfile.load(scanned))
                    Engine.restart(applicationContext)
                }
            }
            val message = result.fold({ "Provisioned from the QR code; starting" }, { "QR code not loaded: ${it.message}" })
            Engine.log.add(message)
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    /** The user's password, once, for an MCX Admin activation; it is kept with the profile as an imported one is. */
    private fun askPassword(then: (String) -> Unit) {
        val field = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Password"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Activate this device")
            .setMessage("Your MC account's password, as you set it from the link your administrator sent.")
            .setView(field)
            .setPositiveButton("Activate") { _, _ -> field.text.toString().takeIf { it.isNotEmpty() }?.let(then) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun activate(link: String, password: String) {
        Toast.makeText(this, "Activating…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Engine.activate(applicationContext, link, password) } }
            val message = result.fold({ "Activated; starting" }, { "Activation failed: ${it.message}" })
            Engine.log.add(message)
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun importProvisioning(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                    Provisioning(applicationContext).import(text)
                    Engine.restart(applicationContext)
                }
            }
            val message = result.fold({ "Provisioned; starting" }, { "Provisioning failed: ${it.message}" })
            Engine.log.add(message)
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    // A wired or Bluetooth headset button is a PTT key while this screen is up.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
            if (event.repeatCount == 0) Engine.ptt(true, "headset key")
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
            Engine.ptt(false, "headset key")
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_MESSAGES = "messages"
    }
}
