package org.openmcx.client.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openmcx.client.mcdata.FdConfig

class ConfigError(message: String) : Exception(message)

/** The UE's provisioning: identities, servers and service settings (from the provisioning bundle). */
data class ClientConfig(
    val name: String,
    val domain: String,
    /** The MC service client ID (TS 24.379 §7.2.1), a `urn:uuid:`. */
    val clientId: String,
    val sip: Sip,
    val idms: Idms,
    val cms: String,
    val gms: String,
    val mcptt: Mcptt?,
    val mcdata: Mcdata?,
    val media: Media,
    val mcvideo: Mcvideo? = null,
    /** End-to-end protection (TS 33.180): the KMS and what is protected; null when calls are in the clear. */
    val security: Security? = null,
    /**
     * `"name:port" -> "address:port"`: where to connect for a server name, when the name does
     * not resolve on the UE's network (a lab reached through the host's published ports).
     */
    val endpoints: Map<String, String> = emptyMap(),
) {
    data class Sip(
        val publicUserId: String, val privateUserId: String, val password: String,
        val proxy: String, val proxyPort: Int, val transport: String, val registerExpires: Int,
    )

    data class Idms(val issuer: String, val clientId: String, val redirectUri: String, val username: String?, val password: String?)

    data class Mcptt(
        val userId: String, val psi: String, val groups: List<String>,
        val answerMode: String, val sessionExpires: Int, val manualAnswerTimeout: Int,
        /** The Resource-Priority of normal, emergency and imminent peril calls (TS 24.379 §6.2.8), as provisioned. */
        val resourcePriority: Map<String, String> = emptyMap(),
        /** The "user-profile-index" of `<Default-user-profile>` (TS 24.484 §7.2.2.1): mcptt-user-profile-<index>.xml. */
        val profileIndex: Int = 1,
    )

    data class Mcdata(
        val userId: String, val psi: String, val groups: List<String>,
        val maxPayloadSizeSdsCplaneBytes: Int, val tdu1Seconds: Int,
        val fd: FdConfig = FdConfig(),
    )

    data class Media(val portRange: IntRange, val amrWbMode: Int)

    /** MCVideo (TS 24.281): identity, PSI, groups, and the video the camera sends. */
    data class Mcvideo(
        val userId: String, val psi: String, val groups: List<String>, val answerMode: String,
        /** Chat groups (TS 24.281 §9.2.2): joined on demand, not affiliated at start-up. */
        val chatGroups: List<String> = emptyList(),
        /** Resource-Priority values (RFC 4412/8101, §6.2.8) by priority: `normal`, `imminent`, `emergency`. */
        val resourcePriority: Map<String, String> = emptyMap(),
        val width: Int = 640, val height: Int = 480, val frameRate: Int = 15, val bitrate: Int = 500_000,
    )

    /**
     * The KMS (TS 33.180 5.3), the Key Parameters wire profile agreed for the deployment (E.6), and
     * whether media (SRTP) and transmission control (SRTCP under the CSK) are protected.
     */
    data class Security(
        val kmsUri: String, val messageType: Int, val algorithm: Int, val keyParametersIei: Int, val encryptedPayloadIei: Int,
        val protectMedia: Boolean, val protectFloor: Boolean,
        /** The GMS's identity (`…/org.openmobilealliance.groups`), which signs the GMKs; its XCAP root precedes that. */
        val gmsUri: String? = null,
    ) {
        val xcapRoot: String? get() = gmsUri?.takeIf { it.endsWith("org.openmobilealliance.groups") }?.removeSuffix("org.openmobilealliance.groups")
    }

    companion object {
        fun parse(text: String): ClientConfig {
            val root = Json.parseToJsonElement(text).jsonObject
            val sip = root.obj("sip")
            val idms = root.obj("idms")
            val media = root.obj("media")
            val ports = media["port_range"]?.jsonArray?.map { it.jsonPrimitive.content.toInt() } ?: listOf(20000, 20400)
            return ClientConfig(
                name = root.str("name"),
                domain = root.str("domain"),
                clientId = root.str("client_id"),
                sip = Sip(sip.str("public_user_id"), sip.str("private_user_id"), sip.str("password"), sip.str("proxy"),
                    sip.int("proxy_port"), sip.str("transport").uppercase(), sip.int("register_expires", 3600)),
                idms = Idms(idms.str("issuer"), idms.str("client_id"), idms.str("redirect_uri"), idms.optStr("username"), idms.optStr("password")),
                cms = root.str("cms"),
                gms = root.str("gms"),
                mcptt = (root["mcptt"] as? JsonObject)?.let {
                    Mcptt(it.str("user_id"), it.str("psi"), it.strings("groups"), it.optStr("answer_mode") ?: "automatic",
                        it.int("session_expires", 1800), it.int("manual_answer_timeout", 20),
                        (it["resource_priority"] as? JsonObject)?.mapValues { v -> v.value.jsonPrimitive.content } ?: emptyMap(),
                        it.int("profile_index", 1).takeIf { index -> index in 1..255 } ?: 1)
                },
                mcdata = (root["mcdata"] as? JsonObject)?.let {
                    Mcdata(it.str("user_id"), it.str("psi"), it.strings("groups"), it.int("max_payload_size_sds_cplane_bytes", 1000),
                        it.int("tdu1_seconds", 10), FdConfig.parse(it["fd"] as? JsonObject))
                },
                media = Media(ports[0]..ports[1], media.int("amr_wb_mode", 2)),
                mcvideo = (root["mcvideo"] as? JsonObject)?.let {
                    Mcvideo(it.str("user_id"), it.str("psi"), it.strings("groups"), it.optStr("answer_mode") ?: "automatic",
                        it.strings("chat_groups"),
                        (it["resource_priority"] as? JsonObject)?.mapValues { v -> v.value.jsonPrimitive.content } ?: emptyMap(),
                        it.int("width", 640), it.int("height", 480), it.int("frame_rate", 15), it.int("bitrate", 500_000))
                },
                security = (root["security"] as? JsonObject)?.let {
                    val profile = it.obj("key_parameter_profile")
                    Security(it.str("kms_uri"), profile.int("message_type", 10), profile.int("algorithm", 1),
                        profile.int("key_parameters_iei", 0x79), profile.int("encrypted_payload_iei", 0x78),
                        it.bool("protect_media", false), it.bool("protect_floor", false), it.optStr("gms_uri"))
                },
                endpoints = (root["endpoints"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
            )
        }

        private fun JsonObject.obj(key: String) = this[key] as? JsonObject ?: throw ConfigError("missing object $key")
        private fun JsonObject.optStr(key: String) = (this[key] as? JsonPrimitive)?.content
        private fun JsonObject.str(key: String) = optStr(key) ?: throw ConfigError("missing $key")
        private fun JsonObject.int(key: String, default: Int? = null) =
            (this[key] as? JsonPrimitive)?.intOrNull ?: default ?: throw ConfigError("missing $key")
        private fun JsonObject.bool(key: String, default: Boolean) = (this[key] as? JsonPrimitive)?.booleanOrNull ?: default
        private fun JsonObject.strings(key: String) = (this[key] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
    }
}
