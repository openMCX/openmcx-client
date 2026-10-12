//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.launch

/**
 * Keeps the signalling flow alive while the phone sleeps. The flow's own keepalive runs on a
 * coroutine timer, which stops while the CPU is suspended and in Doze; an alarm allowed while
 * idle wakes the app every [INTERVAL_MS] to ping the flow (RFC 5626 §4.4.1) and, if the pong
 * does not come back, lets the client reconnect before a call arrives on a dead flow.
 */
object KeepaliveAlarm {
    const val INTERVAL_MS = 5 * 60_000L

    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        // Inexact and allowed while idle: needs no exact-alarm permission, and Doze still runs it.
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + INTERVAL_MS, pending(context))
    }

    fun cancel(context: Context) = context.getSystemService(AlarmManager::class.java).cancel(pending(context))

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, KeepaliveReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Runs one keepalive under a short wake lock, then schedules the next alarm. */
class KeepaliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        val lock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "openmcx:keepalive").apply { acquire(20_000) }
        Engine.scope.launch {
            try {
                val client = Engine.client
                if (client != null) Engine.log.add("keepalive alarm: flow ${if (client.probeFlow()) "alive" else "dead, reconnecting"}")
            } finally {
                KeepaliveAlarm.schedule(context.applicationContext)
                if (lock.isHeld) lock.release()
                result.finish()
            }
        }
    }
}
