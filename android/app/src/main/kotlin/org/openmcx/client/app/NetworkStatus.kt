//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app

import android.content.Context
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * What the phone's network and SIM offer for SIP core access, shown in Settings, the log and the debug status.
 * The app registers with SIP digest whatever it finds: IMS AKA (TS 33.203) is required on 3GPP access
 * (TS 33.180 clause 6.2.1), and an Android app can run it only with carrier privileges for an ISIM.
 */
object NetworkStatus {
    /** The ISIM application identifier prefix (ETSI TS 101 220 annex E: RID A000000087, application 1004). */
    private const val ISIM_AID = "A0000000871004"

    data class Snapshot(
        /** wifi, cellular, ethernet, vpn, other or none: the default network's transport. */
        val transport: String,
        val validated: Boolean,
        /** A network with the MCX capability (NET_CAPABILITY_MCX) is up, used or not. */
        val mcxNetwork: Boolean,
        /** Each SIM slot's card state (inserted means loaded and unlocked, not registered), with the SIM's PLMN. */
        val sims: List<String>,
        /** The PLMN the phone is registered on for mobile service, null when it is not registered. */
        val mobileNetwork: String?,
        val carrierPrivileges: Boolean,
        /** true or false when the app can open the ISIM, null when it cannot look (no carrier privileges). */
        val isim: Boolean?,
        val scheme: String = "SIP digest",
    ) {
        fun json(): JSONObject = JSONObject().put("transport", transport).put("validated", validated).put("mcx_network", mcxNetwork)
            .put("sims", org.json.JSONArray(sims)).put("mobile_network", mobileNetwork ?: JSONObject.NULL).put("carrier_privileges", carrierPrivileges).put("isim", isim ?: JSONObject.NULL)
            .put("scheme", scheme).put("ims_aka", "not supported")

        /** One line for the log. */
        fun line(): String = "network: $transport${if (validated) "" else " (not validated)"}, MCX network ${if (mcxNetwork) "up" else "none"}, " +
            "SIM ${sims.joinToString(" / ")}, mobile network ${mobileNetwork ?: "not registered"}, carrier privileges ${if (carrierPrivileges) "yes" else "no"}, ISIM ${isim?.let { if (it) "yes" else "no" } ?: "unknown"}; " +
            "$scheme (IMS AKA not supported)"

        /** Label and value rows for Settings. */
        fun rows(): List<Pair<String, String>> = listOf(
            "Network" to (when (transport) { "wifi" -> "Wi-Fi"; "vpn" -> "VPN"; "none" -> "none"; else -> transport.replaceFirstChar { it.titlecase() } } +
                if (validated || transport == "none") "" else " (not validated)"),
            "MCX network" to if (mcxNetwork) "up" else "none",
            "SIM" to sims.joinToString("\n"),
            "Mobile network" to (mobileNetwork?.let { "registered on $it" } ?: "not registered"),
            "Carrier privileges" to if (carrierPrivileges) "yes" else "no",
            "ISIM" to (isim?.let { if (it) "present" else "not found" } ?: "unknown, needs carrier privileges"),
            "Registration" to scheme,
            "IMS AKA" to "not supported, SIP digest is used",
        )
    }

    private val _current = MutableStateFlow<Snapshot?>(null)
    val current: StateFlow<Snapshot?> = _current
    @Volatile private var capabilities: NetworkCapabilities? = null
    @Volatile private var mcxNetworks = 0

    /** The default network changed ([caps] null when there is none). */
    fun defaultNetwork(context: Context, caps: NetworkCapabilities?) { capabilities = caps; refresh(context) }

    /** A network with the MCX capability came up ([up]) or went away. */
    fun mcxNetwork(context: Context, up: Boolean) { mcxNetworks = (mcxNetworks + if (up) 1 else -1).coerceAtLeast(0); refresh(context) }

    @Synchronized
    fun refresh(context: Context) {
        val next = snapshot(context)
        if (next != _current.value) {
            _current.value = next
            Engine.log.add(next.line())
        }
    }

    private fun snapshot(context: Context): Snapshot {
        val caps = capabilities
        val transport = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val mcx = mcxNetworks > 0 || caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_MCX) == true
        val telephony = context.getSystemService(TelephonyManager::class.java)
        // Per slot, without READ_PHONE_STATE: the card state of each slot and, for the default one, the SIM's PLMN.
        val slots = runCatching { telephony!!.activeModemCount }.getOrDefault(1).coerceAtLeast(1)
        val states = (0 until slots).map { slot -> runCatching { telephony!!.getSimState(slot) }.getOrDefault(TelephonyManager.SIM_STATE_UNKNOWN) }
        val simPlmn = telephony?.simOperator?.takeIf { it.length >= 5 }?.let { plmn(it) }
        val sims = states.mapIndexed { slot, state ->
            val text = when (state) {
                TelephonyManager.SIM_STATE_READY -> "inserted" + (simPlmn?.takeIf { states.count { it == TelephonyManager.SIM_STATE_READY } == 1 }?.let { ", PLMN $it" } ?: "")
                TelephonyManager.SIM_STATE_ABSENT -> "no SIM"
                TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN required"
                TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK required"
                TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "network locked"
                TelephonyManager.SIM_STATE_NOT_READY -> "not ready"
                TelephonyManager.SIM_STATE_PERM_DISABLED -> "disabled"
                TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "card error"
                TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "restricted"
                else -> "unknown"
            }
            if (slots > 1) "slot ${slot + 1}: $text" else text
        }
        val inserted = TelephonyManager.SIM_STATE_READY in states
        // The registered PLMN; empty when the phone has no mobile service.
        val mobileNetwork = telephony?.networkOperator?.takeIf { it.length >= 5 }?.let { plmn(it) }
        val privileges = inserted && runCatching { telephony!!.hasCarrierPrivileges() }.getOrDefault(false)
        val isim = if (privileges) isimPresent(telephony!!) else null
        return Snapshot(transport, validated, mcx, sims, mobileNetwork, privileges, isim)
    }

    private fun plmn(numeric: String) = "${numeric.take(3)}-${numeric.drop(3)}"

    /** Opens and closes a logical channel to the ISIM; only an app with carrier privileges may. */
    private fun isimPresent(telephony: TelephonyManager): Boolean? = runCatching {
        val channel = telephony.iccOpenLogicalChannel(ISIM_AID, 0x04)
        val open = channel.status == android.telephony.IccOpenLogicalChannelResponse.STATUS_NO_ERROR
        if (open) telephony.iccCloseLogicalChannel(channel.channel)
        open
    }.getOrNull()
}
