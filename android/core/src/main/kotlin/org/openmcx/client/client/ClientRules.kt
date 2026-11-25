//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.client

import org.openmcx.client.mcptt.FloorIndicator

/**
 * Small rules of the client, free of state so that the unit tests reach them (as the Linux client's client_rules).
 *
 * Specifications: TS 24.379 V19.7.0, TS 24.380 V19.2.0, RFC 5373, RFC 3261.
 */
object ClientRules {
    /**
     * RFC 5373 section 2: `Answer-Mode = "Answer-Mode" HCOLON answer-mode-value *(SEMI answer-mode-param)`; the value
     * is the token before any parameter (`Auto;require` is "Auto"), compared without case (RFC 3261 section 7.3.1:
     * tokens are case-insensitive). Null for an absent header field.
     */
    fun answerModeToken(header: String?): String? = header?.substringBefore(';')?.trim()?.lowercase()?.ifEmpty { null }

    /**
     * TS 24.379 §11.1.1.2.1.2 steps 9 and 10 (TS 24.281 the same): manual commencement of an incoming call. A
     * Priv-Answer-Mode "Auto" forces the automatic answer; Answer-Mode "Manual" asks for the manual one; Answer-Mode
     * "Auto" may still be answered manually by a private call's called user who set manual; otherwise the local
     * setting. The header values by token ([answerModeToken]).
     */
    fun manualCommencement(answerMode: String?, privAnswerMode: String?, localMode: String, privateCall: Boolean): Boolean {
        val answer = answerModeToken(answerMode)
        return when {
            answerModeToken(privAnswerMode) == "auto" -> false
            answer == "manual" -> true
            answer == "auto" -> localMode == "manual" && privateCall
            else -> localMode == "manual"
        }
    }

    /**
     * TS 24.379 §7.2.2: a refused service authorisation leaves the client not authorised, and when to try again is the
     * client's choice: the start-up's growing delay ([backoffMs]). RFC 3261 section 20.33: the Retry-After of a 500 or
     * 503 gives the delay instead, bounded to 1 s to 10 minutes (as the Linux client's authorization_retry_delay_ms);
     * its delta-seconds may be followed by a comment or parameters, which are ignored.
     */
    fun authorisationRetryDelayMs(status: Int?, retryAfter: String?, backoffMs: Long): Long {
        if (status != 500 && status != 503) return backoffMs
        val digits = retryAfter?.trim()?.takeWhile { it.isDigit() }?.take(6)?.ifEmpty { null } ?: return backoffMs
        return digits.toLong().coerceIn(1, 600) * 1000
    }

    /**
     * TS 24.380 §6.2.4.3.5 item 1 b) and table 8.2.3.15-2: the Floor Indicator of the Floor Request (and the Floor
     * Release) of a call: bit B for a broadcast group call, D for an emergency call, E for an imminent peril call
     * (emergency taking precedence, as the floor control server does), A (normal) for any other; with bit F when the
     * call negotiated queueing ("mc_queueing" in the offer and the answer, §14): §8.2.3.15 allows it next to the call
     * type, the §14.6.2 NOTE has the participants acknowledge queueing in the Floor Indicator, and TS 37.579-1 tables
     * 5.5.6.2-1 and 5.5.6.5-1 expect it. System calls (bit C) are not made by this client.
     */
    fun floorIndicator(broadcast: Boolean, emergency: Boolean, imminent: Boolean, queueing: Boolean): Int {
        val priority = if (emergency) FloorIndicator.EMERGENCY else if (imminent) FloorIndicator.IMMINENT_PERIL else 0
        val type = if (broadcast) FloorIndicator.BROADCAST or priority else if (priority != 0) priority else FloorIndicator.NORMAL
        return type or if (queueing) FloorIndicator.QUEUEING else 0
    }

    /**
     * TS 24.380 §14.4: "if the attribute contains attribute parameters that were not present in the associated offer,
     * the offerer shall discard those attribute parameters": an answer's "mc_implicit_request", "mc_granted" or
     * "mc_queueing" counts only when this client's offer had it.
     */
    fun answerParameterKept(answered: Boolean, offered: Boolean): Boolean = answered && offered
}
