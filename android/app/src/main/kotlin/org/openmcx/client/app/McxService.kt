//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.wifi.WifiManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Foreground service that owns the protocol engine for the life of the MC UE. */
class McxService : LifecycleService() {
    private var control: Job? = null
    private var network: ConnectivityManager.NetworkCallback? = null
    private var watchingCalls: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /**
     * Idle, the service holds the signalling flow (special use). In a call it adds phoneCall
     * and, when the user may talk, microphone, so capture keeps working with the screen off.
     */
    private fun foreground(inCall: Boolean, inVideoCall: Boolean = false) {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (inVideoCall && checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (inCall || inVideoCall) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        }
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(inCall || (inVideoCall && Engine.mcvideo?.active?.hidden != true)), types) }
            .onFailure { Engine.log.add("foreground service types $types refused: ${it.message}") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        foreground(inCall = false)
        runCatching { Engine.start(applicationContext) }.onFailure { Engine.log.add("engine start failed: ${it.message}") }
        if (watchingCalls == null) watchingCalls = lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(Engine.inCall, Engine.inVideoCall) { a, v -> a to v }.collect { (a, v) -> foreground(a, v) }
        }
        if (network == null) network = watchNetwork()
        // Keep Wi-Fi out of power save while the screen is off, so incoming signalling and the
        // keepalive pongs are not held back; the alarm covers the times the timer cannot run.
        if (wifiLock == null) wifiLock = runCatching {
            @Suppress("DEPRECATION") // still honoured for a foreground service; LOW_LATENCY needs the screen on
            getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "openmcx:flow")
                .apply { setReferenceCounted(false); acquire() }
        }.onFailure { Engine.log.add("wifi lock: ${it.message}") }.getOrNull()
        KeepaliveAlarm.schedule(applicationContext)
        if (control == null && BuildConfig.DEBUG) {
            control = lifecycleScope.launch { DebugControl(this@McxService).serve() }
        }
        return START_STICKY
    }

    /**
     * Follow the default network: when it changes, the flow's local address is gone, so
     * reconnect at once rather than waiting for the keepalive to notice (RFC 5626 §4.4).
     */
    private fun watchNetwork(): ConnectivityManager.NetworkCallback {
        val manager = getSystemService(ConnectivityManager::class.java)
        var current: Network? = null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val previous = current
                current = network
                if (previous != null && previous != network) Engine.client?.reconnect("network changed")
            }

            override fun onLost(network: Network) {
                if (network == current) { current = null; Engine.log.add("network lost") }
            }

            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                val local = Engine.client?.ua?.flow?.connection?.localHost ?: return
                if (network == current && properties.linkAddresses.none { it.address.hostAddress == local }) {
                    Engine.client?.reconnect("local address $local gone")
                }
            }
        }
        manager.registerDefaultNetworkCallback(callback)
        return callback
    }

    override fun onDestroy() {
        network?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        wifiLock?.let { if (it.isHeld) it.release() }
        KeepaliveAlarm.cancel(applicationContext)
        super.onDestroy()
    }

    private fun notification(inCall: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_service), NotificationManager.IMPORTANCE_LOW),
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(getString(if (inCall) R.string.notification_in_call else R.string.notification_service))
            .setContentIntent(android.app.PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "service"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, McxService::class.java))
        }
    }
}
