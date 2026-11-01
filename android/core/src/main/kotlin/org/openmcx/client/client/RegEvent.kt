//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.client

import org.openmcx.client.body.RegInfo
import java.math.BigInteger

/**
 * The rules of the client's reg event subscription and of its registration timing (TS 24.229 V19.7.0 §5.1.1.3,
 * §5.1.1.4.1, §5.1.1.5A and §5.1.1.7; RFC 3680 §5.2): pure functions, so that they can be tested apart from the SIP
 * flow (McxClient applies them).
 */
object RegEventRules {
    /** §5.1.1.3 item e): "an Expires header field set to 600 000 seconds". */
    const val SUBSCRIPTION_EXPIRES_S = 600_000L
    const val EVENT = "reg"

    /**
     * §5.1.1.3: the subscription is refreshed "either 600 seconds before the expiration time if the initial subscription
     * was for greater than 1200 seconds, or when half of the time has expired if the initial subscription was for 1200
     * seconds or less"; §5.1.1.4.1 times a reregistration the same way from "the previous registration" (also for the
     * expiration time a shortened registration is adjusted to, §5.1.1.5A item 1). The delay from now, seconds: the
     * expiration time is [remaining] seconds away, the subscription (or registration) was for [duration] seconds; 0 when
     * the time has already come.
     */
    fun refreshDelayS(duration: Long, remaining: Long): Long {
        val before = if (duration > 1200) 600 else duration / 2
        return (remaining - before).coerceAtLeast(0)
    }

    /**
     * RFC 3680 §5.2: "If the value in the document is less than the local version, the document is discarded without
     * processing"; one equal to it is the same document again. Whether a received document is processed: the first one
     * of a subscription always.
     */
    fun versionNew(local: BigInteger?, received: BigInteger): Boolean = local == null || received > local

    /**
     * RFC 3680 §5.2: "If the version is more than one higher than the local version, the subscriber SHOULD generate a
     * refresh request to trigger a full state notification".
     */
    fun versionGap(local: BigInteger?, received: BigInteger): Boolean = local != null && received > local + BigInteger.ONE

    /**
     * The user, host and port of a SIP URI ("user@host:port", without scheme, parameters or angle brackets), for
     * matching the <uri> of a reginfo <contact> with this client's own contact address (§5.1.1.5A: "set to the Contact
     * address that the UE registered"), whose parameters may be written differently.
     */
    fun contactAddress(uri: String): String {
        var value = uri.trim()
        value.indexOf('<').takeIf { it >= 0 }?.let { value = value.substring(it + 1) }
        value.indexOf(':').takeIf { it >= 0 }?.let { value = value.substring(it + 1) }
        return value.substring(0, value.indexOfAny(charArrayOf(';', '>', '?')).takeIf { it >= 0 } ?: value.length)
    }

    /** What a reginfo document asks of this client, for its own contact ([action]). */
    sealed class Action {
        /** §5.1.1.5A: the registration is shortened to [expires] seconds: reregister at the §5.1.1.4.1 time. */
        data class Shortened(val expires: Long) : Action()
        /** §5.1.1.7: the network removed this client's binding; [event] "unregistered", "rejected" or "deactivated". */
        data class Deregistered(val event: String) : Action()
    }

    /**
     * §5.1.1.5A and §5.1.1.7: the registration state of this client's contact ([ownContact], its address as
     * [contactAddress] gives it) under the public user identity [aor] in [info]:
     * - <registration> active and the <contact> active with the event "shortened" and an expires: [Action.Shortened]
     *   ("use the expires attribute ... to adjust the expiration time");
     * - the <contact> terminated with the event "unregistered", "rejected" or "deactivated" (§5.1.1.7 items 1 and 2;
     *   with one contact per client this removes its only binding, so the identity is deregistered):
     *   [Action.Deregistered]. Other events ("expired", "probation" of RFC 3680 §4.7.1) are no deregistration of
     *   §5.1.1.7.
     * Null when the document asks nothing; a deregistration wins over a shortening in the same document.
     */
    fun action(info: RegInfo, aor: String, ownContact: String): Action? {
        var shortened: Action? = null
        for (registration in info.registrations) {
            if (registration.aor != aor) continue
            for (contact in registration.contacts) {
                if (contactAddress(contact.uri) != ownContact) continue
                if (contact.state == "terminated" && contact.event in DEREGISTRATION_EVENTS) return Action.Deregistered(contact.event)
                if (registration.state == "active" && contact.state == "active" && contact.event == "shortened" && contact.expires != null)
                    shortened = Action.Shortened(contact.expires)
            }
        }
        return shortened
    }

    private val DEREGISTRATION_EVENTS = setOf("unregistered", "rejected", "deactivated")

    /**
     * §5.1.1.3 item 2): the expiration time "as indicated in the "expires" header field parameter of the
     * Subscription-State header field, if present, of the NOTIFY request"; null when absent.
     */
    fun stateExpires(subscriptionState: String?): Long? =
        subscriptionState?.split(';')?.drop(1)?.map { it.trim() }
            ?.firstOrNull { it.startsWith("expires=", ignoreCase = true) }?.substringAfter('=')?.trim()?.toLongOrNull()
}
