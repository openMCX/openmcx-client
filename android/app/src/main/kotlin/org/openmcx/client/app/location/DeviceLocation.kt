//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.TelephonyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.openmcx.client.location.Dimension
import org.openmcx.client.location.LocationClient
import org.openmcx.client.location.Point

/**
 * What the phone observes for location reporting: its position (fused, GPS or network provider) and the identities of
 * the cell it is registered on (TelephonyManager), coded as TS 24.379 Annex F.3 has them, fed to every [LocationClient].
 * Both need the location permission; until it is granted they are retried every ten seconds.
 */
class DeviceLocation(private val context: Context, private val scope: CoroutineScope, private val clients: () -> List<LocationClient>) {
    @Volatile var lastFix: Location? = null
        private set
    @Volatile var radio: Map<Dimension, String> = emptyMap()
        private set
    @Volatile var lastError: String? = null
        private set
    /** A position set by hand (the debug endpoint, for area tests): the phone's own fixes are not reported meanwhile. */
    @Volatile var override: Point? = null
        set(value) {
            field = value
            // Cleared: back to the phone's last fix.
            (value ?: lastFix?.let(::point))?.let { p -> clients().forEach { it.position(p) } }
        }
    private var job: Job? = null
    private var listening: LocationListener? = null

    private fun granted() = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                if (granted()) {
                    if (listening == null) runCatching { listen() }.onFailure { lastError = "location: ${it.message}" }
                    runCatching { cells() }.onFailure { lastError = "cells: ${it.message}" }
                }
                delay(if (granted()) 5_000 else 10_000)
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        listening?.let { l -> runCatching { context.getSystemService(LocationManager::class.java).removeUpdates(l) } }
        listening = null
    }

    @SuppressLint("MissingPermission") // checked by granted()
    private fun listen() {
        val manager = context.getSystemService(LocationManager::class.java)
        val providers = listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) { lastError = "no location provider enabled"; return }
        val listener = LocationListener { fix -> onFix(fix) }
        // The fused provider combines the others; without it, GPS and the network provider both.
        for (p in if ("fused" in providers) listOf("fused") else providers) manager.requestLocationUpdates(p, 5_000L, 5f, listener, Looper.getMainLooper())
        providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }?.let(::onFix)
        listening = listener
        lastError = null
    }

    private fun onFix(fix: Location) {
        if (lastFix?.let { it.time > fix.time } == true) return
        lastFix = fix
        if (override != null) return
        val point = point(fix)
        clients().forEach { it.position(point) }
    }

    private fun point(fix: Location) = Point.at(fix.latitude, fix.longitude, if (fix.hasAltitude()) fix.altitude.toInt() else null,
        if (fix.hasAccuracy()) fix.accuracy.toDouble() else null)

    @SuppressLint("MissingPermission") // checked by granted()
    private fun cells() {
        val telephony = context.getSystemService(TelephonyManager::class.java) ?: return
        val found = mutableMapOf<Dimension, String>()
        for (info in telephony.allCellInfo.orEmpty().filter { it.isRegistered }) {
            when (info) {
                is CellInfoLte -> lte(info.cellIdentity, found)
                is CellInfoNr -> nr(info.cellIdentity as CellIdentityNr, found)
            }
        }
        if (found.isEmpty()) return
        radio = found
        clients().forEach { c -> found.forEach { (d, v) -> c.radio(d, v) } }
    }

    private fun lte(id: CellIdentityLte, out: MutableMap<Dimension, String>) {
        val plmn = plmn(id.mccString, id.mncString) ?: return
        if (id.ci in 0..0x0fffffff) out[Dimension.CELL] = plmn + bits(id.ci.toLong(), 28)
        if (id.tac in 0..0xffff) out[Dimension.TRACKING_AREA] = plmn + bits(id.tac.toLong(), 16)
        out[Dimension.PLMN] = plmn
        out[Dimension.RAT] = "LTE"
    }

    private fun nr(id: CellIdentityNr, out: MutableMap<Dimension, String>) {
        val plmn = plmn(id.mccString, id.mncString) ?: return
        if (id.nci in 0..0xfffffffffL) out[Dimension.NR_CELL] = plmn + bits(id.nci, 36)
        if (id.tac in 0..0xffffff) out[Dimension.TRACKING_AREA_5G] = "%06X".format(id.tac)
        out[Dimension.PLMN] = plmn
        out[Dimension.RAT] = "NR"
    }

    companion object {
        /** MCC and MNC as the six digits of Annex F.3; a two-digit MNC is followed by a 0, as OpenMCX writes "001010" for 001/01. */
        fun plmn(mcc: String?, mnc: String?): String? {
            if (mcc == null || mnc == null || mcc.length != 3 || mnc.length !in 2..3 || !(mcc + mnc).all(Char::isDigit)) return null
            return mcc + mnc.padEnd(3, '0')
        }

        fun bits(value: Long, width: Int) = java.lang.Long.toBinaryString(value).padStart(width, '0')
    }
}
