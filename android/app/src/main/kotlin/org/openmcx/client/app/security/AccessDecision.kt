//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app.security

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.openmcx.client.client.ClientConfig
import org.openmcx.client.client.SipAccess
import org.openmcx.client.sip.ImsAkaConfig
import org.openmcx.client.sip.ImsAkaRegistrar
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * How the app reaches the SIP core, decided at each initial registration, never by the user (TS 33.203 clause 8.0: the
 * user does not change the private user identity):
 * - a card with carrier privileges whose ISIM has its identities, and a system that offers IMS AKA: IMS AKA through the
 *   offered P-CSCF, on any network (TS 33.180 clause 6.2.1; TS 33.203 allows IMS AKA on any access);
 * - otherwise on mobile data: no registration, as SIP digest "shall not apply" there (TS 33.203 clauses N.1, O.1.1);
 * - otherwise (Wi-Fi, Ethernet): SIP digest over TLS, which TS 33.180 clause 6.2.1 allows on non-3GPP access.
 * No fallback to digest after an IMS AKA failure (TS 33.203 clause O.1.1 NOTE 2): the next decision comes with the
 * next start or network change.
 */
object AccessDecision {
    /** The protected server port port_us, fixed for the app (TS 33.203 clause 7.1 NOTE 10: it stays). */
    const val PROTECTED_SERVER_PORT = 5064

    /** The last decision: the method, why, and the card used. */
    data class State(val method: String, val reason: String?, val card: String?)

    private val _current = MutableStateFlow<State?>(null)
    val current: StateFlow<State?> = _current

    /** The default network is mobile data, also under a VPN when no Wi-Fi or Ethernet network is up. */
    private fun onMobileData(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return true
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
        @Suppress("DEPRECATION")
        val others = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }.filter { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
        return others.none { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) }
            && others.any { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) }
    }

    suspend fun decide(context: Context, config: ClientConfig, log: (String) -> Unit): SipAccess = withContext(Dispatchers.IO) {
        val card = IsimCard(context)
        val privileged = runCatching { card.privileged() }.getOrNull()
        val offered = config.sip.imsAkaPcscf
        val identities = privileged?.let { runCatching { card.identities(it.first) }.getOrNull() }
        val cardText = privileged?.let { "slot ${it.second + 1}, ISIM" }
        // One line each in Settings.
        val why: String? = when {
            privileged == null -> "no card with carrier privileges"
            identities == null -> "no identities on the card's ISIM"
            offered == null -> "network does not offer IMS AKA"
            else -> null
        }
        val decided: Pair<SipAccess, State> = if (why == null) {
            val host = offered!!.substringBeforeLast(':')
            val port = offered.substringAfterLast(':').toInt()
            val pcscf = context.getSystemService(ConnectivityManager::class.java).activeNetwork?.getByName(host)
                ?: java.net.InetAddress.getByName(host)
            val aka = ImsAkaConfig(identities!!.impi, identities.impu, identities.domain, pcscf, port, PROTECTED_SERVER_PORT)
            SipAccess.ImsAka(AndroidImsAkaPlatform(context, card), aka) to
                State("IMS AKA", "card and network ready", cardText)
        } else if (onMobileData(context)) {
            SipAccess.Refused("mobile data needs the card: $why") to State("not registered", "mobile data needs the card", cardText)
        } else {
            SipAccess.Digest to State("SIP digest", why, cardText)
        }
        _current.value = decided.second
        log("SIP core access: ${decided.second.method} (${decided.second.reason}${if (why != null) "; $why" else ""})")
        decided.first
    }

    private val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    /**
     * The security associations' algorithms by name (TS 33.203 annexes H and I: 128-bit keys; with AES-GCM, an AEAD
     * algorithm, "alg" is "null" and AES-GCM gives both encryption and integrity).
     */
    fun espName(alg: String?, ealg: String?): String {
        val integrity = when (alg) {
            "hmac-sha2-256-128" -> "HMAC-SHA-256"; "hmac-sha-1-96" -> "HMAC-SHA-1"
            "aes-gmac", "aes-gmac-us" -> "AES-128-GMAC"; else -> null
        }
        return "IPsec ESP, " + when (ealg) {
            "aes-gcm", "aes-gcm-us" -> "AES-128-GCM"
            "aes-cbc" -> "AES-128-CBC" + (integrity?.let { " + $it" } ?: "")
            else -> (integrity ?: "no integrity") + ", no encryption"
        }
    }

    /** The Settings rows of the decision and the registration, one line each (no spec references in the UI). */
    fun rows(aka: ImsAkaRegistrar?, connectionSecurity: String?): List<Pair<String, String>> {
        val state = _current.value ?: return emptyList()
        val rows = mutableListOf(
            "Card used" to (state.card ?: "none"),
            "Registration" to when (state.method) {
                "IMS AKA" -> "IMS AKA with the card"
                "SIP digest" -> "SIP digest"
                else -> "not registered"
            },
        )
        state.reason?.let { rows += "Reason" to it }
        if (state.method == "IMS AKA") {
            rows += "Last authentication" to (aka?.lastAuthentication?.let { (at, outcome) -> "$outcome, ${time.format(Instant.ofEpochSecond(at))}" } ?: "none yet")
            rows += "Protection" to (aka?.selected?.let { espName(it.alg, it.ealg) } ?: "none yet")
        } else if (state.method == "SIP digest") {
            rows += "Protection" to (connectionSecurity ?: "TLS")
        }
        return rows
    }

    /** The same for the debug status: the decision, and with IMS AKA its registration ("ims_aka"). */
    fun json(aka: ImsAkaRegistrar?): Any {
        val state = _current.value ?: return JSONObject.NULL
        val out = JSONObject().put("method", state.method).put("reason", state.reason ?: JSONObject.NULL).put("card", state.card ?: JSONObject.NULL)
        if (state.method != "IMS AKA") return out
        return out.put("ims_aka", JSONObject().put("registered", aka?.registered ?: false)
            .put("alg", aka?.selected?.alg ?: JSONObject.NULL).put("ealg", aka?.selected?.ealg ?: JSONObject.NULL)
            .put("port_uc", aka?.portUc ?: JSONObject.NULL).put("sets_used", aka?.setsUsed ?: 0).put("old_sets_closed", aka?.oldSetsClosed ?: 0)
            .put("associated_uris", org.json.JSONArray(aka?.associatedUris ?: emptyList<String>()))
            .put("last_authentication", aka?.lastAuthentication?.let { JSONObject().put("at", it.first).put("outcome", it.second) } ?: JSONObject.NULL))
    }
}
