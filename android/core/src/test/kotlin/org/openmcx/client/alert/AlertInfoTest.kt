package org.openmcx.client.alert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmcx.client.location.LocationService
import org.openmcx.client.mcdata.McdataInfo
import org.openmcx.client.mcptt.McpttInfo
import org.openmcx.client.mcvideo.EmergencyLocation
import org.openmcx.client.mcvideo.McvideoInfo

/** The alert fields of the info documents, in the order and form the OpenMCX servers write and read them. */
class AlertInfoTest {
    private val mcptt = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><mcpttinfo xmlns=\"urn:3gpp:ns:mcpttInfo:1.0\"><mcptt-Params>"

    @Test
    fun mcpttAlertCancellationOfAnotherUser() {
        assertEquals(mcptt + "<mcptt-request-uri type=\"Normal\"><mcpttURI>sip:operations@mcx.test</mcpttURI></mcptt-request-uri>" +
            "<alert-ind type=\"Normal\"><mcpttBoolean>false</mcpttBoolean></alert-ind>" +
            "<originated-by type=\"Normal\"><mcpttURI>sip:bob.mcptt@mcx.test</mcpttURI></originated-by>" +
            "<mcptt-client-id type=\"Normal\"><mcpttString>urn:uuid:1</mcpttString></mcptt-client-id></mcptt-Params></mcpttinfo>",
            McpttInfo(requestUri = "sip:operations@mcx.test", alert = false, originatedBy = "sip:bob.mcptt@mcx.test", clientId = "urn:uuid:1").encode())
    }

    @Test
    fun mcpttAdhocAlertCarriesItsCriteriaInAnyExt() {
        val xml = McpttInfo(clientId = "urn:uuid:1", adhocAlert = true, participantCriteria = "affiliated:sip:operations@mcx.test").encode()
        assertTrue(xml, xml.endsWith("<anyExt><call-participants-criterias>affiliated:sip:operations@mcx.test</call-participants-criterias>" +
            "<adhoc-alert-ind>true</adhoc-alert-ind></anyExt></mcptt-Params></mcpttinfo>"))
    }

    @Test
    fun parsesTheServersReceiptsAndNotices() {
        val receipt = McpttInfo.parse(mcptt + "<mcptt-request-uri type=\"Normal\"><mcpttURI>sip:alice.mcptt@mcx.test</mcpttURI></mcptt-request-uri>" +
            "<mcptt-calling-user-id type=\"Normal\"><mcpttURI>sip:alice.mcptt@mcx.test</mcpttURI></mcptt-calling-user-id>" +
            "<mcptt-calling-group-id type=\"Normal\"><mcpttURI>sip:operations@mcx.test</mcpttURI></mcptt-calling-group-id>" +
            "<alert-ind type=\"Normal\"><mcpttBoolean>true</mcpttBoolean></alert-ind>" +
            "<alert-ind-rcvd type=\"Normal\"><mcpttBoolean>true</mcpttBoolean></alert-ind-rcvd></mcptt-Params></mcpttinfo>")
        assertEquals(true, receipt.alert)
        assertEquals(true, receipt.alertReceived)
        assertEquals("sip:operations@mcx.test", receipt.callingGroupId)
        val area = McpttInfo.parse(mcptt + "<mcptt-request-uri type=\"Normal\"><mcpttURI>sip:alice.mcptt@mcx.test</mcpttURI></mcptt-request-uri>" +
            "<anyExt><emergency-alert-area-ind>true</emergency-alert-area-ind></anyExt></mcptt-Params></mcpttinfo>")
        assertEquals(true, area.emergencyAlertArea)
        assertEquals(null, area.alert)
        val adhoc = McpttInfo.parse(mcptt + "<mcptt-calling-group-id type=\"Normal\"><mcpttURI>sip:adhoc-1@mcx.test</mcpttURI></mcptt-calling-group-id>" +
            "<anyExt><call-participants-criterias>member:sip:operations@mcx.test</call-participants-criterias>" +
            "<adhoc-alert-ind>true</adhoc-alert-ind><adhoc-alert-ind-rcvd>true</adhoc-alert-ind-rcvd></anyExt></mcptt-Params></mcpttinfo>")
        assertEquals(true, adhoc.adhocAlertReceived)
        assertEquals("member:sip:operations@mcx.test", adhoc.participantCriteria)
    }

    @Test
    fun mcdataAlertRoundTrips() {
        val xml = McdataInfo(requestUri = "sip:operations@mcx.test", clientId = "urn:uuid:1", alert = true).encode()
        assertTrue(xml, "<alert-ind type=\"Normal\"><mcdataBoolean>true</mcdataBoolean></alert-ind><mcdata-client-id" in xml)
        val notice = McdataInfo.parse("<?xml version=\"1.0\"?><mcdatainfo xmlns=\"urn:3gpp:ns:mcdataInfo:1.0\"><mcdata-Params>" +
            "<mcdata-calling-group-id type=\"Normal\"><mcdataURI>sip:operations@mcx.test</mcdataURI></mcdata-calling-group-id>" +
            "<alert-ind type=\"Normal\"><mcdataBoolean>false</mcdataBoolean></alert-ind>" +
            "<originated-by type=\"Normal\"><mcdataURI>sip:bob.mcdata@mcx.test</mcdataURI></originated-by>" +
            "<anyExt><alert-ind-rcvd>true</alert-ind-rcvd><group-geo-area-ind>true</group-geo-area-ind>" +
            "<associated-group-id>sip:operations@mcx.test</associated-group-id></anyExt></mcdata-Params></mcdatainfo>")
        assertEquals(false, notice.alert)
        assertEquals("sip:bob.mcdata@mcx.test", notice.originatedBy)
        assertEquals(true, notice.alertReceived)
        assertEquals(true, notice.groupGeoArea)
        assertEquals("sip:operations@mcx.test", notice.associatedGroupId)
    }

    @Test
    fun mcvideoAdhocAlertUsesItsOwnCriteriaElement() {
        val xml = McvideoInfo(clientId = "urn:uuid:1", adhocAlert = true, participantCriteria = "affiliated:sip:operations@mcx.test").encode()
        assertTrue(xml, "<call-participants-criteria>affiliated:sip:operations@mcx.test</call-participants-criteria><adhoc-alert-ind>true</adhoc-alert-ind>" in xml)
        assertEquals(true, McvideoInfo.parse(xml).adhocAlert)
    }

    @Test
    fun emergencyLocationInEachServicesNamespace() {
        val location = EmergencyLocation(48.8566, 2.3522)
        for (service in LocationService.entries) {
            val xml = location.encode(service)
            assertTrue(xml, "xmlns=\"urn:3gpp:ns:${service.lower}LocationInfo:1.0\"" in xml)
            assertEquals(48.8566, EmergencyLocation.parse(xml).latitude, 1e-4)
        }
    }
}
