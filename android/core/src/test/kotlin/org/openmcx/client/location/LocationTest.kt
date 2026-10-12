package org.openmcx.client.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Ported from the OpenMCX server's tests/test_location.cpp: the same known answers and the same trigger scenario. */
class LocationTest {
    private fun near(a: Double, b: Double, tolerance: Double) = abs(a - b) <= tolerance
    private fun square(lat: Double, lon: Double, half: Double) = Area(polygon = listOf(Point.at(lat - half, lon - half),
        Point.at(lat - half, lon + half), Point.at(lat + half, lon + half), Point.at(lat + half, lon - half)))
    private val ecgi = "001010" + "1".repeat(28)
    private val ncgi = "001010" + "0".repeat(36)
    private val box = square(48.0, 2.0, 0.01)
    private val disc = Area(center = Point.at(10.0, 20.0), radius = 1000, includedAngle = 179)
    private val sector = disc.copy(offsetAngle = 0, includedAngle = 44)

    private val c = Configuration(
        nonEmergency = Requested(coordinate = true, servingEcgi = true, servingNcgi = true, minimumInterval = 5),
        emergency = Requested(coordinate = true, bearingSpeed = true, minimumInterval = 1),
        triggers = listOf(
            Trigger(Trigger.Kind.AnyChange, "cell", Dimension.CELL),
            Trigger(Trigger.Kind.Enter, "cell-in", Dimension.CELL, ecgi),
            Trigger(Trigger.Kind.Exit, "nr-out", Dimension.NR_CELL, ncgi),
            Trigger(Trigger.Kind.AnyChange, "ta", Dimension.TRACKING_AREA),
            Trigger(Trigger.Kind.Enter, "plmn", Dimension.PLMN, "001010"),
            Trigger(Trigger.Kind.AnyChange, "rat", Dimension.RAT),
            Trigger(Trigger.Kind.Exit, "ta5g", Dimension.TRACKING_AREA_5G, "00AB12"),
            Trigger(Trigger.Kind.Periodic, "periodic", amount = 30),
            Trigger(Trigger.Kind.Distance, "distance", amount = 250),
            Trigger(Trigger.Kind.Signalling, "event-0", event = Event.InitialLogOn),
            Trigger(Trigger.Kind.Signalling, "event-1", event = Event.GroupCallNonEmergency),
            Trigger(Trigger.Kind.AnyArea, "any-area"),
            Trigger(Trigger.Kind.EnterArea, "enter", area = box.copy(innerBorder = 20)),
            Trigger(Trigger.Kind.ExitArea, "exit", area = sector.copy(outerBorder = 30))),
        emergencyTriggers = listOf(Trigger(Trigger.Kind.Periodic, "emergency-periodic", amount = 2)))

    @Test fun coordinateCoding() {
        assertTrue(encodeLatitude(0.0) == 0 && encodeLatitude(90.0) == 0x7fffff && encodeLatitude(-45.0) == 0xc00000)
        assertTrue(encodeLongitude(0.0) == 0 && encodeLongitude(90.0) == 0x400000 && encodeLongitude(-180.0) == 0x800000 &&
            encodeLongitude(-90.0) == 0xc00000 && encodeLongitude(180.0) == 0x800000)
        for (lat in listOf(48.8584, -33.8568, 0.0, 89.9999, -89.9999)) assertTrue(near(decodeLatitude(encodeLatitude(lat)), lat, 90.0 / 8388608))
        for (lon in listOf(2.2945, 151.2153, -73.9857, 179.9999, -179.9999)) assertTrue(near(decodeLongitude(encodeLongitude(lon)), lon, 360.0 / 16777216))
        assertTrue(runCatching { encodeLatitude(90.5) }.isFailure)
        assertTrue(near(distance(0.0, 0.0, 0.0, 1.0), 111195.0, 5.0))
        assertEquals(0, uncertaintyCode(0.0)); assertEquals(1, uncertaintyCode(1.0))
    }

    @Test fun areas() {
        assertTrue(inside(box, 48.0, 2.0) && !inside(box, 48.02, 2.0))
        assertTrue(near(depth(box, 48.0, 2.0), 744.0, 5.0))
        assertTrue(near(depth(box, 48.011, 2.0), -111.0, 3.0))
        assertTrue(inside(disc, 10.0, 20.005) && !inside(disc, 10.0, 20.02))
        assertTrue(near(depth(disc, 10.0, 20.0), 1000.0, 2.0))
        assertTrue(inside(sector, 10.004, 20.004) && !inside(sector, 9.996, 19.996))
    }

    @Test fun configurationRoundTrip() {
        for (s in LocationService.entries) {
            val xml = LocationInfo.configurationXml(c, s)
            assertTrue(xml.contains("<${s.capitalised}SignallingEvent>") && xml.contains(s.namespace))
            val (parsed, service) = LocationInfo.parse(xml)
            assertEquals(s, service)
            assertEquals(c, parsed)
        }
        assertTrue((LocationInfo.parse(LocationInfo.configurationXml(Configuration(full = false), LocationService.MCPTT)).first as Configuration).cancels)
    }

    @Test fun requestAndReportRoundTrip() {
        val q = Request("req-1", true, Filter("enter", false))
        assertEquals(q to LocationService.MCPTT, LocationInfo.parse(LocationInfo.requestXml(q, LocationService.MCPTT)))
        val r = Report("req-1", true, listOf("cell", "periodic"), "sip:alice@example.org", Current(
            servingEcgi = ecgi, neighbouringEcgi = listOf(ecgi, "001010" + "0".repeat(28)), mbmsSa = "100", mbsfn = "7",
            coordinate = Point.at(48.8584, 2.2945).copy(altitude = -12, horizontalAccuracy = 20, verticalAccuracy = 30),
            servingNcgi = ncgi, neighbouringNcgi = listOf(ncgi), mbsfsa = "abcdef", bearingSpeed = "0011223344556a",
            timestamp = "2026-09-25T10:00:00Z", functionalAlias = "sip:driver@example.org"))
        for (s in LocationService.entries) {
            val xml = LocationInfo.reportXml(r, s)
            assertTrue(xml.contains("<${s.lower}-reporting-uri>"))
            assertEquals(r to s, LocationInfo.parse(xml))
        }
    }

    @Test fun tolerantAndStrictParsing() {
        val ns = LocationService.MCPTT.namespace
        val report = LocationInfo.parse("<location-info xmlns=\"$ns\"><Report ReportType=\"NonEmergency\"><Unknown/><CurrentLocation>" +
            "<CurrentCoordinate><longitude><threebytes>4194304</threebytes></longitude><latitude><threebytes>0</threebytes>" +
            "</latitude></CurrentCoordinate></CurrentLocation></Report></location-info>").first as Report
        assertTrue(near(report.location.coordinate!!.lon, 90.0, 1e-4))
        for (bad in listOf(
            "<location-info xmlns=\"$ns\"><Report ReportType=\"NonEmergency\"><CurrentLocation><CurrentCoordinate><longitude type=\"Encrypted\"><EncryptedData/></longitude><latitude><threebytes>0</threebytes></latitude></CurrentCoordinate></CurrentLocation></Report></location-info>",
            "<location-info xmlns=\"$ns\"><Request RequestId=\"a\"/><Report ReportType=\"Emergency\"><CurrentLocation/></Report></location-info>",
            "<location-info xmlns=\"urn:other\"><Request RequestId=\"a\"/></location-info>",
            "<location-info xmlns=\"$ns\"><Report ReportType=\"Sometimes\"><CurrentLocation/></Report></location-info>",
            "<location-info xmlns=\"$ns\"><Request/></location-info>"))
            assertTrue(bad, runCatching { LocationInfo.parse(bad) }.isFailure)
    }

    /** The server test's trigger scenario, step by step (TS 24.379 §13.3). */
    @Test fun reporterScenario() {
        val rep = Reporter()
        assertNull(rep.poll(0))
        rep.position(Point.at(48.0, 1.97))
        val answered = rep.answer("r0", 0)
        assertTrue(answered.id == "r0" && answered.location.coordinate != null)
        rep.configure(c, 1000)
        assertTrue(rep.poll(1000) == null && rep.pending.isEmpty())
        rep.signal(Event.InitialLogOn)
        assertNull(rep.poll(1500)) // the timer started by the configuration holds the report
        val first = rep.poll(6000)!!
        assertTrue(first.triggers == listOf("event-0") && first.location.coordinate != null && !first.emergency && first.location.servingEcgi == null)
        assertNull(rep.poll(7000))
        rep.position(Point.at(48.0, 1.9901)) // outside
        rep.position(Point.at(48.0, 1.99005 + 0.00008)) // inside the contour, within the 20 m border band
        assertFalse("enter" in rep.pending)
        rep.position(Point.at(48.0, 2.0))
        assertTrue("enter" in rep.pending && "any-area" in rep.pending && "distance" in rep.pending)
        assertEquals(3, rep.poll(12000)!!.triggers.size)
        rep.radio(Dimension.CELL, ecgi)
        assertTrue("cell-in" in rep.pending && "cell" !in rep.pending) // the first cell is no change
        rep.radio(Dimension.NR_CELL, ncgi)
        rep.radio(Dimension.NR_CELL, "001010" + "1".repeat(36))
        assertTrue("nr-out" in rep.pending && "cell" in rep.pending)
        val third = rep.poll(20000)!!
        assertTrue(third.location.servingEcgi == ecgi && third.location.servingNcgi != null)
        assertTrue(rep.poll(30000) == null && rep.poll(32000) != null) // periodic, 30 s after the configuration
        rep.signal(Event.GroupCallNonEmergency)
        val now = rep.answer("r1", 33000)
        assertTrue(now.id == "r1" && now.triggers.size == 1)
        rep.signal(Event.GroupCallNonEmergency)
        assertTrue(rep.poll(35000) == null && rep.poll(38000) != null) // the request restarted the timer
        rep.emergency = true
        assertNull(rep.poll(41000))
        val e1 = rep.poll(43000)!!
        assertTrue(e1.emergency && e1.triggers == listOf("emergency-periodic"))
        rep.emergency = false
        rep.configure(Configuration(full = false, triggers = listOf(Trigger(Trigger.Kind.Signalling, "configured", event = Event.LocationConfigurationReceived))), 50000)
        assertTrue(rep.configuration.triggers.size == c.triggers.size + 1 && "configured" in rep.pending)
        rep.configure(Configuration(), 60000)
        rep.signal(Event.InitialLogOn)
        assertTrue(!rep.configured && rep.poll(90000) == null)
    }

    @Test fun adaptiveTrigger() {
        val rep = Reporter()
        rep.position(Point.at(0.0, 0.0))
        rep.configure(Configuration(nonEmergency = Requested(coordinate = true),
            triggers = listOf(Trigger(Trigger.Kind.Adaptive, "ad", adaptive = Adaptive(5, 2, 10, 50)))), 0)
        rep.position(Point.at(0.0, 0.001)) // about 111 m
        assertNull(rep.poll(4000)); assertNull(rep.poll(5000))
        assertNotNull(rep.poll(7000))
        assertNull(rep.poll(20000)); assertNotNull(rep.poll(24000))
    }
}
