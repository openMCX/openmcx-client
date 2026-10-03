package org.openmcx.client.mcvideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyLocationTest {
    @Test fun coordinatesSurviveTheThreeOctetCoding() {
        for ((lat, lon) in listOf(52.5200 to 13.4050, -33.8688 to 151.2093, 40.7128 to -74.0060, 0.0 to 0.0, -89.99 to -179.99)) {
            val back = EmergencyLocation.parse(EmergencyLocation(lat, lon).encode())
            assertEquals(lat, back.latitude, 90.0 / (1 shl 23) * 1.01)
            assertEquals(lon, back.longitude, 360.0 / (1 shl 24) * 1.01)
        }
    }

    /** The server's document (protocol.cpp emergency_location_xml): sign bit for the south, two's complement for the west. */
    @Test fun codesMatchTs23032() {
        val south = EmergencyLocation(-45.0, -90.0)
        assertEquals((1 shl 23) or (1 shl 22), south.latitudeCode)
        assertEquals((1 shl 24) - (1 shl 22), south.longitudeCode)
        val xml = south.encode()
        assertTrue(xml.contains("xmlns=\"urn:3gpp:ns:mcvideoLocationInfo:1.0\"") && xml.contains("<Report ReportType=\"Emergency\">"))
        assertTrue(xml.contains("<longitude type=\"Normal\"><threebytes>${(1 shl 24) - (1 shl 22)}</threebytes></longitude>"))
        assertTrue(runCatching { EmergencyLocation.parse(xml.replace("threebytes>${(1 shl 24) - (1 shl 22)}<", "threebytes>99999999<")) }.isFailure)
    }
}
