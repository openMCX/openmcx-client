//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmcx.client.body.Xml
import org.openmcx.client.body.descendants
import org.openmcx.client.body.text

/** TS 24.545 clause 7: the SEAL location-info documents of the location management client. */
class SealLocationTest {
    private val configuration = "<?xml version=\"1.0\"?><location-info xmlns=\"urn:3gpp:ns:sealLocationInfo:1.0\">" +
        "<identity><VAL-user-id><sealURI>sip:alice@x</sealURI></VAL-user-id></identity><configuration>" +
        "<location-information><current-geographical-coordinate/></location-information>" +
        "<triggering-criteria><periodic-report trigger-id=\"periodic\">2</periodic-report></triggering-criteria>" +
        "<minimum-interval-length>1</minimum-interval-length></configuration><anyExt>" +
        "<report-uri xmlns=\"urn:openmcx:ns:sealLocation:1.0\">https://p/lms/seal-lm/v1/users/sip%3Aalice%40x/reports</report-uri>" +
        "<history-uri xmlns=\"urn:openmcx:ns:sealLocation:1.0\">https://p/lms/seal-lm/v1/users/sip%3Aalice%40x/history</history-uri>" +
        "</anyExt></location-info>"

    @Test fun configuration() {
        val c = SealLocation.parseConfiguration(configuration)
        assertEquals(2, c.periodic); assertEquals(1, c.minimumInterval)
        assertTrue(c.reportUri.endsWith("/reports") && c.historyUri.endsWith("/history"))
        assertTrue(runCatching { SealLocation.parseConfiguration(configuration.replace(">2<", ">0<")) }.isFailure)
        assertTrue(runCatching { SealLocation.parseConfiguration("<location-info xmlns=\"urn:x\"/>") }.isFailure)
        assertTrue(runCatching { SealLocation.parseConfiguration(configuration.replace("<configuration>", "<other>").replace("</configuration>", "</other>")) }.isFailure)
    }

    @Test fun reportAndHistory() {
        val fix = SealLocation.Fix(encodeLatitude(48.0), encodeLongitude(2.0), "2026-11-25T10:00:00Z")
        val report = Xml.parse(SealLocation.reportXml("sip:alice@x", "periodic", fix))
        assertEquals(SealLocation.NS, report.namespaceURI)
        assertEquals("sip:alice@x", report.descendants("sealURI").single().text())
        assertEquals("periodic", report.descendants("trigger-id").single().text())
        assertEquals(fix.latitude.toString(), report.descendants("latitude").single().text())
        assertEquals("2026-11-25T10:00:00Z", report.descendants("timestamp").single().text())
        val history = Xml.parse(SealLocation.historyXml("history", listOf(fix, fix.copy(timestamp = "2026-11-25T10:00:02Z"))))
        assertEquals(1, history.descendants("history-loc-result-report").size)
        assertEquals(2, history.descendants("reports").size)
        assertEquals("history", history.descendants("subscription-identifier").single().text())
    }
}
