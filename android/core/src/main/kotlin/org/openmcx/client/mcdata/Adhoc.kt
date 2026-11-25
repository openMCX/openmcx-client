//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

import org.openmcx.client.body.McService
import org.openmcx.client.body.Multipart
import org.openmcx.client.body.Part
import org.openmcx.client.body.ResourceLists
import org.openmcx.client.sip.SipResponse
import java.util.UUID

/**
 * The target of an ad hoc group communication (TS 24.282 §24.2.2.1.1, §9.2.2.2.1 step 3A), written as the reference
 * client's `adhoc=<spec>`: the MCData IDs (and functional aliases, §9.2.2.2.1 step 3A b ii) of its participants
 * ("ID,ID,..."), participant criteria ("criteria=<criteria>"), or an ad hoc group already created ("group=<ad hoc group>").
 */
data class AdhocTarget(val participants: List<String> = emptyList(), val criteria: String? = null, val group: String? = null) {
    /** The resource list of the participants, when they are listed (§9.2.2.2.1 step 3A a ii). */
    fun resourceLists(): List<Part> = if (participants.isEmpty()) emptyList()
        else listOf(Part(ResourceLists.CONTENT_TYPE, ResourceLists.encode(participants).toByteArray()))

    /** The mcdata-info of the request: the criteria (§24.2.2.1.1 step 5) or the ad hoc group already created. */
    fun info(base: McdataInfo) = base.copy(participantCriteria = criteria, requestUri = group)

    val label: String get() = group ?: criteria?.let { "criteria $it" } ?: participants.joinToString(",")

    companion object {
        const val PREFIX = "adhoc="

        fun parse(spec: String): AdhocTarget = when {
            spec.startsWith("criteria=") -> AdhocTarget(criteria = spec.removePrefix("criteria=").takeIf { it.isNotBlank() })
            spec.startsWith("group=") -> AdhocTarget(group = spec.removePrefix("group=").takeIf { it.isNotBlank() })
            else -> AdhocTarget(spec.split(',').map { it.trim() }.filter { it.isNotEmpty() })
        }.also { if (it.participants.isEmpty() && it.criteria == null && it.group == null)
            throw SdsError("an ad hoc group needs MCData IDs, criteria=<criteria> or group=<ad hoc group>") }

        /** The ad hoc target of [target] when it is written `adhoc=...`, else null. */
        fun of(target: String): AdhocTarget? = if (target.startsWith(PREFIX)) parse(target.removePrefix(PREFIX)) else null

        /** TS 24.282 §24.2.2.1.1, on a SIP 2xx response, step 4: the ad hoc group the 2xx names in <mcdata-calling-group-id>. */
        fun groupOf(response: SipResponse): String? = runCatching {
            Multipart.parse(response.contentType, response.body).firstOrNull { it.mediaType == McdataInfo.CONTENT_TYPE }
                ?.let { McdataInfo.parse(it.text).callingGroupId }
        }.getOrNull()
    }
}

/**
 * A one-to-one communication over the media plane to a functional alias, written `fa=<alias>` (TS 24.282 §22, with
 * §9.2.3.2.3, §9.2.4.2.3 and §10.2.5.2.3 for the alias in use): the INVITE names the alias in its resource list with
 * <call-to-functional-alias-ind>; a 300 (Multiple Choices) names the MCData ID to invite instead, the alias then
 * called (as the signalling plane's §9.2.2.2.1 and IP connectivity's §20.2.1).
 */
object AliasTarget {
    const val PREFIX = "fa="
    fun of(target: String): String? = target.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf { it.isNotBlank() }

    /** After a 300: the MCData ID of <mcdata-request-uri>, or null. */
    fun selected(response: SipResponse): String? = runCatching {
        Multipart.parse(response.contentType, response.body).firstOrNull { it.mediaType == McdataInfo.CONTENT_TYPE }
            ?.let { McdataInfo.parse(it.text).requestUri }
    }.getOrNull()

    /** The parts of the INVITE again, to [selected], naming the [alias] called. */
    fun redirected(parts: List<Part>, selected: String, alias: String): List<Part> = parts.map { part ->
        when (part.mediaType) {
            ResourceLists.CONTENT_TYPE -> Part(ResourceLists.CONTENT_TYPE, ResourceLists.encode(listOf(selected)).toByteArray())
            McdataInfo.CONTENT_TYPE -> Part(McdataInfo.CONTENT_TYPE,
                McdataInfo.parse(part.text).copy(callToFunctionalAlias = null, calledFunctionalAlias = alias).encode().toByteArray())
            else -> part
        }
    }
}

/** The ad hoc group communications a user may start (the server's last word, §24.3.2.1.1 step 7: warning 238). */
internal object AdhocRequest {
    /** The request types of the media plane for an ad hoc target (TS 24.282 §24.2.2.1.1, the server's §24.3.2.1.1). */
    fun mediaRequestType(requestType: String) = if (requestType.endsWith("-fd")) "adhoc-group-fd-session" else "adhoc-group-sds-session"
}

/**
 * TS 24.282 §9.2.2.2.1 step 3A: an ad hoc group standalone SDS over the signalling plane: mcdata-info with request type
 * "ad-hoc-group-sds", the client ID and the criteria or the ad hoc group, and the participants in a resource list;
 * the ad hoc group the 2xx names is kept with the sent message. An ad hoc group has no group key: the payload goes in
 * the clear (the Linux client's choice), signed when payloads are authenticated.
 */
suspend fun McdataService.sendAdhoc(target: AdhocTarget, text: String, disposition: DispositionRequest? = null,
                                    conversationId: UUID = UUID.randomUUID()): SentSds {
    val data = DataPayload(listOf(Payload.text(text)))
    if (data.size > clientConfig.maxPayloadSizeSdsCplaneBytes) {
        val media = media ?: throw SdsError("payload of ${data.size} octets is above the signalling-plane limit")
        return media.sendStandalone(AdhocTarget.PREFIX + target.spec(), text, false, disposition, conversationId)
    }
    val signalling = SdsSignalling(System.currentTimeMillis() / 1000, conversationId, UUID.randomUUID(), disposition = disposition)
    val alias = mcxClient.aliasesOf(McService.MCDATA).value.let { a -> a.inUse?.takeIf { it in a.active } }
    val info = target.info(McdataInfo(requestType = "ad-hoc-group-sds", clientId = mcxClient.config.clientId, functionalAlias = alias))
    val parts = mutableListOf(Part(McdataInfo.CONTENT_TYPE, info.encode().toByteArray()))
    parts += target.resourceLists()
    parts += Part(McdataService.SIGNALLING, signalling.encode())
    val payload = data.encode()
    parts += Part(McdataService.PAYLOAD, mcxClient.mcdataProtection?.sign(payload) ?: payload)
    val response = mcxClient.ua.send(message(parts))
    if (!response.isSuccess) {
        val warning = response.headers["Warning"]?.let { " ($it)" } ?: ""
        throw SdsError("${response.status} ${response.reason}$warning", response.status)
    }
    val group = AdhocTarget.groupOf(response)
    val sent = SentSds(signalling.messageId, conversationId, group ?: AdhocTarget.PREFIX + target.spec(), true, text, disposition,
        response.status, plane = "signalling")
    recordSent(sent)
    mcxClient.event("mcdata: sent ad hoc group SDS ${sent.messageId} to ${target.label}${group?.let { " (ad hoc group $it)" } ?: ""}: ${response.status}")
    return sent
}

/** The `adhoc=` spec of a target, as [AdhocTarget.parse] reads it. */
fun AdhocTarget.spec(): String = group?.let { "group=$it" } ?: criteria?.let { "criteria=$it" } ?: participants.joinToString(",")
