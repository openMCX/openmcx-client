//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

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
import kotlinx.serialization.json.longOrNull
import org.openmcx.client.idms.IdmsEndpoints
import org.openmcx.client.mcdata.FdConfig
import org.openmcx.client.security.Mikey

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
    /** The delays of the new attempts after a failed start-up or sign-in (`recovery`). */
    val recovery: Recovery = Recovery(),
    /**
     * TS 24.482 annex A.2.1.2: the home HTTP proxy (`http.proxy`, an https URI) every HTTP request but
     * the IdMS's and the KMS's goes through; null: direct requests. `http.tunnel_auth` is "one-way"
     * only (TS 24.484 §7.2.2.7 g) gives no private key and no pre-shared key identity for mutual).
     */
    val httpProxy: String? = null,
) {
    data class Sip(
        val publicUserId: String, val privateUserId: String, val password: String,
        val proxy: String, val proxyPort: Int, val transport: String, val registerExpires: Int,
        /** The P-CSCF of the system's IMS AKA offer ("host:port", preconfigured, TS 24.229 §9.2.1); null: not offered. */
        val imsAkaPcscf: String? = null,
    )

    data class Idms(
        val issuer: String, val clientId: String, val redirectUri: String, val username: String?, val password: String?,
        /**
         * TS 33.180 annex B.5.1 leaves to the client when it refreshes the access token: renewed
         * `token_renewal_lead_s` before it expires (halfway for a token that lives less than twice that), and
         * refreshed at start-up when it has less than `token_refresh_before_s` left. Defaults: default pending
         * maintainer choice (no spec value).
         */
        val tokenRenewalLeadS: Long = DEFAULT_TOKEN_RENEWAL_LEAD_S,
        val tokenRefreshBeforeS: Long = DEFAULT_TOKEN_REFRESH_BEFORE_S,
        /** TS 24.484 §7.2.2.7 item 4: `idms_auth_endpoint`, `idms_token_endpoint` and `jwks_uri`, where configured. */
        val endpoints: IdmsEndpoints = IdmsEndpoints(),
    )

    /**
     * TS 33.180 §5.1.1 (the limited service state) gives no retry timing: the first new attempt after a failed
     * start-up or sign-in comes after `first_retry_ms`, each further one after twice the delay before, up to
     * `last_retry_ms`. Defaults: default pending maintainer choice (no spec value).
     */
    data class Recovery(val firstRetryMs: Long = DEFAULT_FIRST_RETRY_MS, val lastRetryMs: Long = DEFAULT_LAST_RETRY_MS)

    data class Mcptt(
        val userId: String, val psi: String, val groups: List<String>,
        val answerMode: String, val sessionExpires: Int, val manualAnswerTimeout: Int,
        /** The "user-profile-index" of `<Default-user-profile>` (TS 24.484 §7.2.2.1): mcptt-user-profile-<index>.xml. */
        val profileIndex: Int = 1,
        /**
         * The functional aliases this user may activate (TS 24.379 §9A.2.1). The CMS of this edition does not put them
         * in the user profile document, so the provisioning names them; the status notifications add any others.
         */
        val functionalAliases: List<String> = emptyList(),
        /**
         * TS 24.484 §6.3.13.2.2: subscribe to the UE configuration, user profile and service configuration documents
         * through the CMS subscription proxy function (`subscribe_configuration`, on by default).
         */
        val subscribeConfiguration: Boolean = true,
        /** TS 24.481 §6.3.13.2.1: subscribe to the group documents of [groups] (`subscribe_group_documents`, on by default). */
        val subscribeGroupDocuments: Boolean = true,
        /**
         * Read the configuration documents only once their NOTIFY says where they are, and authorise the service after
         * that (`documents_on_notify`, off by default: the documents are read at sign-in). As TS 37.579-1 table
         * 5.3.2.6.1-1 expects of a client that subscribes to them.
         */
        val documentsOnNotify: Boolean = false,
        /**
         * TS 24.484 §6.3.13.2.2 b) 2): "the configured public service identity for performing subscription proxy function
         * of the CMS" (`cms_subscription_psi`). No configuration item of TS 24.484 §7 names it (a spec gap, reported):
         * by default the MCPTT server's PSI, as the ETSI test system for TS 37.579 expects.
         */
        val cmsSubscriptionPsi: String? = null,
        /**
         * TS 24.481 §6.3.13.2.1 b): the GMS subscription proxy PSI, the <GMS-URI> of the UE initial configuration
         * (TS 24.484 §7.2.2.1 item 4) (`gms_subscription_psi`); without it no group document subscription is made.
         */
        val gmsSubscriptionPsi: String? = null,
        /** The UE ID of the UE configuration document (TS 24.484 §8.2.1) (`ue_id`, by default the client ID). */
        val ueId: String? = null,
    )

    data class Mcdata(
        val userId: String, val psi: String, val groups: List<String>,
        val maxPayloadSizeSdsCplaneBytes: Int, val tdu1Seconds: Int,
        val fd: FdConfig = FdConfig(),
        /** The MCData functional aliases this user may activate (TS 24.282 §22.2.1), as [Mcptt.functionalAliases]. */
        val functionalAliases: List<String> = emptyList(),
    )

    data class Media(val portRange: IntRange, val amrWbMode: Int)

    /** MCVideo (TS 24.281): identity, PSI, groups, and the video the camera sends. */
    data class Mcvideo(
        val userId: String, val psi: String, val groups: List<String>, val answerMode: String,
        /** Chat groups (TS 24.281 §9.2.2): joined on demand, not affiliated at start-up. */
        val chatGroups: List<String> = emptyList(),
        val width: Int = 640, val height: Int = 480, val frameRate: Int = 15, val bitrate: Int = 500_000,
        /** The MCVideo functional aliases this user may activate (TS 24.281 §20.2.1), as [Mcptt.functionalAliases]. */
        val functionalAliases: List<String> = emptyList(),
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
        /**
         * RFC 3830 5.4: the MIKEY clock skew (seconds), "set by the local policy" (`mikey_clock_skew_s`); default
         * pending maintainer choice (no spec value).
         */
        val mikeyClockSkewS: Long = Mikey.DEFAULT_CLOCK_SKEW_S,
        /**
         * TS 33.180 clause 3.1 ("KMS URI: A unique identifier for a security domain") and annexes D.2.2/D.3.1: the KMS URI
         * the KMS's documents carry (KmsUri), apart from the HTTPS address [kmsUri] it is contacted at (TS 24.484 clause
         * 7.2.2.7 <kms>); `kms_id`, by default that address, as the Linux client.
         */
        val kmsId: String? = null,
        /**
         * `kms_via_proxy` (false by default): the KMS requests go through the home HTTP proxy. TS 24.482 annex A.2.1.2
         * sends every request but the IdMS's through the proxy, TS 33.180 clause 5.3.2 lets a KMS be used without it,
         * and no configuration item tells the UE which applies (spec conflict, Known limitations): direct by default; the
         * option follows the ETSI test system for TS 37.579 (the test suite's reading, a labelled exception, as the Linux
         * client). Through the proxy, clause 5.3.2: "for public safety users the TrK shall be used": it needs a home
         * HTTP proxy, and the key management client refuses an exchange without a TrK.
         */
        val kmsViaProxy: Boolean = false,
    ) {
        val xcapRoot: String? get() = gmsUri?.takeIf { it.endsWith("org.openmobilealliance.groups") }?.removeSuffix("org.openmobilealliance.groups")
    }

    companion object {
        const val DEFAULT_TOKEN_RENEWAL_LEAD_S = 300L
        const val DEFAULT_TOKEN_REFRESH_BEFORE_S = 600L
        const val DEFAULT_FIRST_RETRY_MS = 2_000L
        const val DEFAULT_LAST_RETRY_MS = 60_000L

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
                    sip.int("proxy_port"), sip.str("transport").uppercase(), sip.int("register_expires", 3600),
                    sip.optStr("ims_aka_pcscf")),
                idms = Idms(idms.str("issuer"), idms.str("client_id"), idms.str("redirect_uri"), idms.optStr("username"), idms.optStr("password"),
                    idms.count("token_renewal_lead_s", DEFAULT_TOKEN_RENEWAL_LEAD_S),
                    idms.count("token_refresh_before_s", DEFAULT_TOKEN_REFRESH_BEFORE_S),
                    IdmsEndpoints(idms.optStr("idms_auth_endpoint"), idms.optStr("idms_token_endpoint"),
                        idms.optStr("jwks_uri"))),
                cms = root.str("cms"),
                gms = root.str("gms"),
                mcptt = (root["mcptt"] as? JsonObject)?.let {
                    Mcptt(it.str("user_id"), it.str("psi"), it.strings("groups"), it.optStr("answer_mode") ?: "automatic",
                        it.int("session_expires", 1800), it.int("manual_answer_timeout", 20),
                        it.int("profile_index", 1).takeIf { index -> index in 1..255 } ?: 1, it.strings("functional_aliases"),
                        it.bool("subscribe_configuration", true), it.bool("subscribe_group_documents", true),
                        it.bool("documents_on_notify", false), it.optStr("cms_subscription_psi")?.also(::sipUri),
                        it.optStr("gms_subscription_psi")?.also(::sipUri), it.optStr("ue_id"))
                },
                mcdata = (root["mcdata"] as? JsonObject)?.let {
                    Mcdata(it.str("user_id"), it.str("psi"), it.strings("groups"), it.int("max_payload_size_sds_cplane_bytes", 1000),
                        it.int("tdu1_seconds", 10), FdConfig.parse(it["fd"] as? JsonObject), it.strings("functional_aliases"))
                },
                media = Media(ports[0]..ports[1], media.int("amr_wb_mode", 2)),
                mcvideo = (root["mcvideo"] as? JsonObject)?.let {
                    Mcvideo(it.str("user_id"), it.str("psi"), it.strings("groups"), it.optStr("answer_mode") ?: "automatic",
                        it.strings("chat_groups"),
                        it.int("width", 640), it.int("height", 480), it.int("frame_rate", 15), it.int("bitrate", 500_000),
                        it.strings("functional_aliases"))
                },
                security = (root["security"] as? JsonObject)?.let {
                    val profile = it.obj("key_parameter_profile")
                    Security(it.str("kms_uri"), profile.int("message_type", 10), profile.int("algorithm", 1),
                        profile.int("key_parameters_iei", 0x79), profile.int("encrypted_payload_iei", 0x78),
                        it.bool("protect_media", false), it.bool("protect_floor", false), it.optStr("gms_uri"),
                        (it["mikey_clock_skew_s"] as? JsonPrimitive)?.let { skew ->
                            skew.longOrNull?.takeIf { value -> !skew.isString && value in 0..0xffffffffL }
                                ?: throw ConfigError("mikey_clock_skew_s must be a number of seconds")
                        } ?: Mikey.DEFAULT_CLOCK_SKEW_S,
                        it.optStr("kms_id")?.also { id ->
                            if (id.isEmpty() || id.any { c -> c in "\r\n<>&\"" }) throw ConfigError("kms_id must be a KMS URI")
                        },
                        (it["kms_via_proxy"] as? JsonPrimitive)?.let { v ->
                            v.booleanOrNull?.takeIf { !v.isString } ?: throw ConfigError("security.kms_via_proxy must be a boolean")
                        } ?: false)
                },
                endpoints = (root["endpoints"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
                httpProxy = (root["http"] as? JsonObject)?.let { http ->
                    val method = http.optStr("tunnel_auth") ?: "one-way"
                    if (method != "one-way")
                        throw ConfigError("http.tunnel_auth \"$method\": only one-way TLS tunnel authentication is supported")
                    http.optStr("proxy")?.ifEmpty { null }
                },
                recovery = (root["recovery"] as? JsonObject)?.let {
                    val first = it.count("first_retry_ms", DEFAULT_FIRST_RETRY_MS, 1)
                    Recovery(first, it.count("last_retry_ms", maxOf(first, DEFAULT_LAST_RETRY_MS), first))
                } ?: Recovery(),
            ).also { c ->
                if (c.security?.kmsViaProxy == true && c.httpProxy == null)
                    throw ConfigError("security.kms_via_proxy needs a home HTTP proxy (http.proxy)")
            }
        }

        private fun JsonObject.obj(key: String) = this[key] as? JsonObject ?: throw ConfigError("missing object $key")
        private fun JsonObject.optStr(key: String) = (this[key] as? JsonPrimitive)?.content

        /** A subscription proxy PSI goes into a Request-URI as it is: a SIP URI, nothing that would break the header. */
        private fun sipUri(uri: String) {
            if (!uri.startsWith("sip:") || uri.any { it == '\r' || it == '\n' || it == '<' || it == '>' || it == ' ' })
                throw ConfigError("not a SIP URI: $uri")
        }
        private fun JsonObject.str(key: String) = optStr(key) ?: throw ConfigError("missing $key")
        private fun JsonObject.int(key: String, default: Int? = null) =
            (this[key] as? JsonPrimitive)?.intOrNull ?: default ?: throw ConfigError("missing $key")
        /** A whole number, not a string, of at least [min]; [default] when absent. */
        private fun JsonObject.count(key: String, default: Long, min: Long = 0): Long =
            (this[key] as? JsonPrimitive)?.let { value ->
                value.longOrNull?.takeIf { !value.isString && it >= min } ?: throw ConfigError("$key must be a number of at least $min")
            } ?: default
        private fun JsonObject.bool(key: String, default: Boolean) = (this[key] as? JsonPrimitive)?.booleanOrNull ?: default
        private fun JsonObject.strings(key: String) = (this[key] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
    }
}
