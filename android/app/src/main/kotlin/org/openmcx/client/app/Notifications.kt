package org.openmcx.client.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import kotlinx.coroutines.launch
import org.openmcx.client.mcdata.FdRecord
import org.openmcx.client.mcdata.ReceivedSds
import org.openmcx.client.mcvideo.VideoCall
import org.openmcx.client.mcptt.Call
import org.openmcx.client.mcptt.CallType

/** Incoming-call and message notifications. */
class Notifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(NotificationChannel(CALLS, context.getString(R.string.channel_calls), NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel(MESSAGES, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH))
    }

    private fun open(extra: String? = null) = PendingIntent.getActivity(context, extra.hashCode(),
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { extra?.let { putExtra(MainActivity.EXTRA_TAB, it) } },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun action(name: String) = PendingIntent.getBroadcast(context, name.hashCode(),
        Intent(context, CallActionReceiver::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE)

    /** A ringing call (manual commencement): a CallStyle notification with a full-screen intent. */
    fun incomingCall(call: Call) {
        if (call.state != "ringing" && call.type != CallType.PRIVATE) return
        val caller = Person.Builder().setName(call.peer?.removePrefix("sip:") ?: "MCPTT").setImportant(true).build()
        val notification = NotificationCompat.Builder(context, CALLS)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, action(ACTION_REJECT), action(ACTION_ANSWER)))
            .setContentTitle(context.getString(R.string.incoming_call))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(open(), true)
            .setOngoing(true)
            .build()
        manager.notify(CALL_ID + call.id, notification)
    }

    fun cancelIncoming(call: Call) = manager.cancel(CALL_ID + call.id)

    private val inProgress = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    private fun name(uri: String?) = uri?.removePrefix("sip:")?.substringBefore('@')?.removeSuffix(".mcptt")?.removeSuffix(".mcvideo")
        ?.replaceFirstChar { it.titlecase() } ?: ""

    /**
     * A call in progress: who or which group, a running time and Hang up. A call that started without the user
     * placing it (answered automatically, or a group call joined) alerts once; one the user placed is silent.
     */
    private fun inProgress(id: Int, title: String, startedAt: Long, alert: Boolean, video: Boolean, hangup: String) {
        if (!inProgress.add(id)) return
        val notification = NotificationCompat.Builder(context, CALLS)
            .setSmallIcon(if (video) android.R.drawable.presence_video_online else android.R.drawable.stat_sys_phone_call)
            .setContentTitle(title)
            .setContentText(context.getString(if (alert) R.string.call_started else R.string.call_in_progress))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(if (alert) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setSilent(!alert)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setWhen(startedAt).setUsesChronometer(true).setShowWhen(true)
            .setContentIntent(open())
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.hang_up), action(hangup))
            .build()
        manager.notify(id, notification)
    }

    private fun endProgress(id: Int) { if (inProgress.remove(id)) manager.cancel(id) }

    /** An MCPTT call: shown once it is established, removed when it ends. */
    fun callChanged(call: Call) {
        val id = ONGOING_ID + call.id
        when (call.state) {
            "established" -> inProgress(id, if (call.type == CallType.PRIVATE) context.getString(R.string.call_with, name(call.peer))
                else context.getString(R.string.group_call_on, name(call.groupId ?: call.peer)), call.startedAt, !call.outgoing, false, ACTION_HANGUP)
            "released" -> endProgress(id)
        }
    }

    /** An MCVideo call, as [callChanged]; never for the viewed-to side of an ambient viewing call. */
    fun videoCallChanged(call: VideoCall) {
        val id = VIDEO_ONGOING_ID + call.id
        when {
            call.state == "released" || call.hidden -> endProgress(id)
            call.state == "established" -> inProgress(id, if (call.isGroup) context.getString(R.string.video_group_call_on, name(call.groupId))
                else context.getString(R.string.video_call_with, name(call.peer)), call.startedAt, !call.outgoing, true, ACTION_VIDEO_HANGUP)
        }
    }

    /** A ringing video call (manual commencement): full screen, with Answer and Decline. */
    fun incomingVideoCall(call: VideoCall) {
        if (call.state != "ringing" && call.isGroup) return
        val caller = Person.Builder().setName(call.peer?.removePrefix("sip:")?.substringBefore('@')?.removeSuffix(".mcvideo") ?: "MCVideo")
            .setImportant(true).build()
        val notification = NotificationCompat.Builder(context, CALLS)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, action(ACTION_VIDEO_DECLINE), action(ACTION_VIDEO_ANSWER)).setIsVideo(true))
            .setContentTitle(context.getString(R.string.incoming_video_call))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(open(), true)
            .setOngoing(true)
            .build()
        manager.notify(VIDEO_CALL_ID + call.id, notification)
    }

    fun cancelIncomingVideo(call: VideoCall) = manager.cancel(VIDEO_CALL_ID + call.id)

    /** Another member's emergency alert (TS 24.281 §11.2), or its end. */
    private val posted = mutableSetOf<Int>()

    /** Other users' emergency alerts: one ongoing notification per person, with the services it came through. */
    @Synchronized fun emergencyAlerts(alerts: List<org.openmcx.client.app.alert.PersonAlert>) {
        val now = mutableSetOf<Int>()
        for (alert in alerts) {
            val id = VIDEO_ALERT_ID + Math.floorMod(alert.person.hashCode(), 1000)
            now += id
            val where = alert.latitude?.let { "%.5f, %.5f".format(it, alert.longitude) } ?: "location unknown"
            val what = if (alert.adhoc) "ad hoc (${alert.criteria ?: ""})" else alert.group?.removePrefix("sip:")?.substringBefore('@') ?: ""
            val via = alert.services.joinToString(", ") { org.openmcx.client.app.alert.AlertCenter.label(it) }
            val name = alert.person.replaceFirstChar { it.uppercase() }
            val notification = NotificationCompat.Builder(context, CALLS)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(context.getString(R.string.video_alert) + ": $name")
                .setContentText("$what · $via · $where")
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setContentIntent(open())
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build()
            manager.notify(id, notification)
        }
        (posted - now).forEach { manager.cancel(it) }
        posted.clear(); posted += now
    }

    /** The server's notice of entering or leaving an emergency alert area or a group's geographic area. */
    fun areaNotice(notice: org.openmcx.client.alert.AreaNotice) {
        val text = if (notice.alertArea) {
            if (notice.entered) "You entered an emergency alert area" + (notice.alerted?.let { ": emergency alert raised on ${it.removePrefix("sip:").substringBefore('@')}" } ?: "")
            else "You left the emergency alert area"
        } else "You ${if (notice.entered) "entered" else "left"} the area of ${notice.group?.removePrefix("sip:")?.substringBefore('@') ?: "a group"}"
        val notification = NotificationCompat.Builder(context, CALLS)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(context.getString(R.string.video_alert))
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open())
            .setAutoCancel(true)
            .build()
        manager.notify(AREA_ID, notification)
    }

    fun message(message: ReceivedSds) {
        val notification = NotificationCompat.Builder(context, MESSAGES)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(message.group?.removePrefix("sip:")?.let { "${message.sender.removePrefix("sip:")} on $it" }
                ?: message.sender.removePrefix("sip:"))
            .setContentText(message.text)
            .setContentIntent(open(MainActivity.TAB_MESSAGES))
            .setAutoCancel(true)
            .build()
        manager.notify(message.messageId.hashCode(), notification)
    }

    /** A file offered to this user: open Messages to download or reject it. */
    fun attachment(record: FdRecord) {
        val from = record.peer.removePrefix("sip:")
        val notification = NotificationCompat.Builder(context, MESSAGES)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(record.group?.removePrefix("sip:")?.let { "$from on $it" } ?: from)
            .setContentText("File: ${record.metadata.name}${if (record.mandatory) "" else " (tap to download)"}")
            .setContentIntent(open(MainActivity.TAB_MESSAGES))
            .setAutoCancel(true)
            .build()
        manager.notify(record.id.hashCode(), notification)
    }

    companion object {
        const val CALLS = "calls"
        const val MESSAGES = "messages"
        const val CALL_ID = 1000
        const val VIDEO_CALL_ID = 2000
        const val VIDEO_ALERT_ID = 3000
        const val AREA_ID = 6000
        const val ONGOING_ID = 4000
        const val VIDEO_ONGOING_ID = 5000
        const val ACTION_HANGUP = "org.openmcx.client.action.HANGUP"
        const val ACTION_VIDEO_HANGUP = "org.openmcx.client.action.VIDEO_HANGUP"
        const val ACTION_VIDEO_ANSWER = "org.openmcx.client.action.VIDEO_ANSWER"
        const val ACTION_VIDEO_DECLINE = "org.openmcx.client.action.VIDEO_DECLINE"
        const val ACTION_ANSWER = "org.openmcx.client.action.ANSWER"
        const val ACTION_REJECT = "org.openmcx.client.action.REJECT"
    }
}

/** Answer and decline from the incoming-call notification. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Notifications.ACTION_ANSWER -> Engine.mcptt?.answer()
            Notifications.ACTION_REJECT -> Engine.mcptt?.reject()
            Notifications.ACTION_VIDEO_ANSWER -> {
                Engine.mcvideo?.answer()
                // The camera needs the app in front: open the call screen.
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            Notifications.ACTION_VIDEO_DECLINE -> Engine.mcvideo?.reject()
            Notifications.ACTION_HANGUP -> Engine.scope.launch { runCatching { Engine.mcptt?.hangup() } }
            Notifications.ACTION_VIDEO_HANGUP -> Engine.scope.launch { runCatching { Engine.mcvideo?.hangup() } }
        }
    }
}
