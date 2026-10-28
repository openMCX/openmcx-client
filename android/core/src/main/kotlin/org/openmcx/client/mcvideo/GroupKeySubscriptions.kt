//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcvideo

import org.openmcx.client.body.ResourceLists
import org.openmcx.client.security.KmsDocuments
import org.openmcx.client.sip.Dialog
import org.openmcx.client.sip.SipRequest
import org.openmcx.client.sip.SipResponse
import org.openmcx.client.sip.UserAgent
import java.util.concurrent.ConcurrentHashMap

/**
 * The GKTP subscriptions (xcap-diff, TS 24.481 §6.3.13.2.1): each one's group by Call-ID, so a NOTIFY finds its group
 * even before the SUBSCRIBE's 2xx, the dialog of each one accepted, so it can be refreshed (RFC 6665 §4.1.2.2) and
 * ended (§4.1.2.3) in it, and when its refresh is due ([clock], milliseconds).
 */
internal class GroupKeySubscriptions(private val clock: () -> Long = System::currentTimeMillis) {
    private val groups = ConcurrentHashMap<String, String>()
    private val dialogs = ConcurrentHashMap<String, Dialog>()
    private val refreshAt = ConcurrentHashMap<String, Long>()

    /** A SUBSCRIBE for [group] is sent with [callId]. */
    fun sent(callId: String, group: String) { groups[callId] = group }

    /**
     * Its 2xx came (the initial SUBSCRIBE's or a refresh's): [dialog] is the subscription's dialog, active for
     * [grantedSeconds] (RFC 6665 §4.1.2.1). The refresh is due at nine tenths of it: an implementation choice within
     * RFC 6665 4.1.2.2 (any time before expiry).
     */
    fun accepted(dialog: Dialog, grantedSeconds: Long) {
        if (!groups.containsKey(dialog.callId)) return
        dialogs[dialog.callId] = dialog
        if (grantedSeconds > 0) refreshAt[dialog.callId] = clock() + grantedSeconds * 900
        else refreshAt.remove(dialog.callId)
    }

    /** It was refused, or a NOTIFY ended it (Subscription-State terminated). */
    fun ended(callId: String) { groups.remove(callId); dialogs.remove(callId); refreshAt.remove(callId) }

    /** When the next refresh is due (milliseconds of [clock]), or null when none is. */
    fun nextRefresh(): Long? = refreshAt.values.minOrNull()

    /** The subscriptions due for a refresh now, with their groups; each is off the schedule until [accepted] again. */
    fun due(): List<Pair<Dialog, String>> {
        val now = clock()
        return refreshAt.entries.filter { it.value <= now }.mapNotNull { (callId, _) ->
            refreshAt.remove(callId)
            val dialog = dialogs[callId]; val group = groups[callId]
            if (dialog != null && group != null) dialog to group else null
        }
    }

    /** The group of the subscription with [callId], if it is one of these. */
    fun group(callId: String): String? = groups[callId]

    /**
     * The dialogs to end, taken out so each is ended once. The group stays known by Call-ID, so the notifier's final
     * NOTIFY (Subscription-State terminated, RFC 6665 §4.1.2.3) is still answered.
     */
    fun takeDialogs(): List<Dialog> = dialogs.keys.toList().mapNotNull { refreshAt.remove(it); dialogs.remove(it) }

    companion object {
        const val EVENT = "xcap-diff"
        const val ACCEPT = "application/xcap-diff+xml"
        /**
         * RFC 5875 §4.5 (TS 24.481 §6.3.13.2.1 sends the SUBSCRIBE "according to ... IETF RFC 5875"): "The default
         * expiration time for subscriptions within this package is 3600 seconds"; the GKTP subscriptions ask for it.
         */
        const val EXPIRES_SECONDS = 3600L

        /**
         * RFC 6665 §4.1.2.1: the Expires of the 2xx "indicates the actual duration for which the subscription will
         * remain active"; a 2xx "MUST contain an Expires header field" (without one: the duration asked for).
         */
        fun granted(response: SipResponse): Long =
            response.headers["Expires"]?.trim()?.toLongOrNull() ?: EXPIRES_SECONDS

        /**
         * TS 24.481 §6.3.13.2.1: the "SIP re-SUBSCRIBE request" in [dialog] (RFC 6665 §4.1.2.2: "on the same dialog")
         * with the application/resource-lists+xml body of [group]'s <GKTPs>, asking for [EXPIRES_SECONDS] again.
         */
        fun refresh(ua: UserAgent, dialog: Dialog, group: String): SipRequest {
            val request = ua.inDialog(dialog, "SUBSCRIBE")
            request.headers.add("Event", EVENT).add("Accept", ACCEPT).add("Expires", EXPIRES_SECONDS.toString())
                .add("Content-Type", ResourceLists.CONTENT_TYPE)
            val body = KmsDocuments.gktpResourceList(group).toByteArray()
            return SipRequest(request.method, request.uri, request.headers, body)
        }

        /** RFC 6665 §4.1.2.3: the SUBSCRIBE in [dialog] that ends the subscription (Expires 0). */
        fun unsubscribe(ua: UserAgent, dialog: Dialog): SipRequest {
            val request = ua.inDialog(dialog, "SUBSCRIBE")
            request.headers.add("Event", EVENT).add("Accept", ACCEPT).add("Expires", "0")
            return request
        }
    }
}
