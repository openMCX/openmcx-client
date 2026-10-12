//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcvideo

import org.openmcx.client.body.Xml
import org.openmcx.client.body.child
import org.openmcx.client.body.text
import org.openmcx.client.location.LocationService
import kotlin.math.floor

/**
 * The location of an emergency alert (TS 24.379 Annex F.3; TS 24.282 D.4 and TS 24.281 F.3 have it in their own
 * namespaces, chosen by [encode]'s service): an Emergency report with the current coordinate, each value in
 * three octets as TS 23.032 codes it (latitude: a sign bit and 23 bits of |lat|/90°; longitude:
 * 24 bits in two's complement of lon/360°).
 */
data class EmergencyLocation(val latitude: Double, val longitude: Double) {
    val latitudeCode: Int get() {
        val n = floor(kotlin.math.abs(latitude) / 90.0 * (1 shl 23)).toInt().coerceIn(0, (1 shl 23) - 1)
        return (if (latitude < 0) 1 shl 23 else 0) or n
    }
    val longitudeCode: Int get() {
        val n = floor(longitude / 360.0 * (1 shl 24)).toInt().coerceIn(-(1 shl 23), (1 shl 23) - 1)
        return n and 0xffffff
    }

    /** The Emergency report in [service]'s namespace: only the coordinate, as the servers accept it. */
    fun encode(service: LocationService = LocationService.MCVIDEO): String = "<?xml version=\"1.0\"?><location-info xmlns=\"${service.namespace}\"><Report ReportType=\"Emergency\">" +
        "<CurrentLocation><CurrentCoordinate>" +
        "<longitude type=\"Normal\"><threebytes>$longitudeCode</threebytes></longitude>" +
        "<latitude type=\"Normal\"><threebytes>$latitudeCode</threebytes></latitude>" +
        "</CurrentCoordinate></CurrentLocation></Report></location-info>"

    companion object {
        const val NAMESPACE = "urn:3gpp:ns:mcvideoLocationInfo:1.0"
        const val CONTENT_TYPE = "application/vnd.3gpp.mcvideo-location-info+xml"

        fun fromCodes(latitudeCode: Int, longitudeCode: Int): EmergencyLocation {
            val lat = (latitudeCode and 0x7fffff) * 90.0 / (1 shl 23) * if (latitudeCode and 0x800000 != 0) -1 else 1
            val lonN = if (longitudeCode and 0x800000 != 0) longitudeCode - (1 shl 24) else longitudeCode
            return EmergencyLocation(lat, lonN * 360.0 / (1 shl 24))
        }

        fun parse(text: String): EmergencyLocation {
            val root = Xml.parse(text)
            require(root.localName == "location-info") { "not a location-info document" }
            val coordinate = root.child("Report")?.child("CurrentLocation")?.child("CurrentCoordinate") ?: error("no current coordinate")
            fun code(name: String) = coordinate.child(name)?.child("threebytes")?.text()?.toIntOrNull()?.takeIf { it in 0..0xffffff }
                ?: error("invalid $name")
            return fromCodes(code("latitude"), code("longitude"))
        }
    }
}
