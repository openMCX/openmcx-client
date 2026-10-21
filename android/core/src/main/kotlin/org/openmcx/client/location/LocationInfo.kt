//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.location

import org.openmcx.client.body.Xml
import org.w3c.dom.Element
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class LocationError(message: String) : Exception("location-info: $message")

/** The MC service a location-info body belongs to: one schema (TS 24.379/24.282/24.281 Annex F.3) in three namespaces. */
enum class LocationService(val lower: String, val capitalised: String) {
    MCPTT("mcptt", "Mcptt"), MCDATA("mcdata", "Mcdata"), MCVIDEO("mcvideo", "Mcvideo");

    val mime get() = "application/vnd.3gpp.$lower-location-info+xml"
    val namespace get() = "urn:3gpp:ns:${lower}LocationInfo:1.0"
}

/** A position coded as TS 23.032 §6.1 codes it: latitude a sign bit and 23 bits, longitude 24 bits in two's complement. */
data class Point(val latitude: Int, val longitude: Int, val altitude: Int? = null,
                 val horizontalAccuracy: Int? = null, val verticalAccuracy: Int? = null) {
    val lat get() = decodeLatitude(latitude)
    val lon get() = decodeLongitude(longitude)

    companion object {
        fun at(latitude: Double, longitude: Double, altitude: Int? = null, accuracyMetres: Double? = null) =
            Point(encodeLatitude(latitude), encodeLongitude(longitude), altitude, accuracyMetres?.let { uncertaintyCode(it) })
    }
}

fun encodeLatitude(degrees: Double): Int {
    if (!(degrees >= -90 && degrees <= 90)) throw IllegalArgumentException("latitude out of range")
    val n = min(floor(abs(degrees) * 8388608.0 / 90).toLong(), 8388607L).toInt()
    return (if (degrees < 0) 0x800000 else 0) or n
}

fun encodeLongitude(degrees: Double): Int {
    if (!(degrees >= -180 && degrees <= 180)) throw IllegalArgumentException("longitude out of range")
    val d = if (degrees == 180.0) -180.0 else degrees
    return floor(d * 16777216.0 / 360).toInt() and 0xffffff
}

fun decodeLatitude(encoded: Int): Double {
    val value = ((encoded and 0x7fffff) + 0.5) * 90 / 8388608.0
    return if (encoded and 0x800000 != 0) -value else value
}

fun decodeLongitude(encoded: Int): Double {
    val n = if (encoded and 0x800000 != 0) (encoded and 0xffffff) - 16777216 else encoded and 0xffffff
    return (n + 0.5) * 360 / 16777216.0
}

/** TS 23.032 §6.2: the uncertainty code K whose uncertainty 10 · (1.1^K − 1) metres is the nearest above [metres]. */
fun uncertaintyCode(metres: Double): Int {
    var k = 0
    while (k < 127 && 10 * (1.1.pow(k) - 1) < metres) k++
    return k
}

private const val EARTH_RADIUS = 6371008.8
private fun radians(d: Double) = d * PI / 180

/** Great-circle distance in metres. */
fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val a = sin(radians(lat2 - lat1) / 2).pow(2) + cos(radians(lat1)) * cos(radians(lat2)) * sin(radians(lon2 - lon1) / 2).pow(2)
    return 2 * EARTH_RADIUS * asin(min(1.0, sqrt(a)))
}

/** A polygon of 3 to 15 corners (TS 23.032 §5.4) or an ellipsoid arc (§5.7), with border widths in metres. */
data class Area(val polygon: List<Point> = emptyList(), val center: Point? = null, val radius: Long = 0,
                val offsetAngle: Int = 0, val includedAngle: Int = 0, val innerBorder: Int = 0, val outerBorder: Int = 0)

private data class Xy(val x: Double, val y: Double)

private fun project(lat0: Double, lon0: Double, lat: Double, lon: Double) =
    Xy(radians(lon - lon0) * cos(radians(lat0)) * EARTH_RADIUS, radians(lat - lat0) * EARTH_RADIUS)

private fun segmentDistance(p: Xy, a: Xy, b: Xy): Double {
    val dx = b.x - a.x; val dy = b.y - a.y; val length = dx * dx + dy * dy
    val t = if (length == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / length).coerceIn(0.0, 1.0)
    return hypot(p.x - a.x - t * dx, p.y - a.y - t * dy)
}

private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val y = sin(radians(lon2 - lon1)) * cos(radians(lat2))
    val x = cos(radians(lat1)) * sin(radians(lat2)) - sin(radians(lat1)) * cos(radians(lat2)) * cos(radians(lon2 - lon1))
    return (atan2(y, x) * 180 / PI + 360) % 360
}

private fun destination(lat: Double, lon: Double, bearingDegrees: Double, metres: Double): Pair<Double, Double> {
    val d = metres / EARTH_RADIUS; val b = radians(bearingDegrees); val p = radians(lat)
    val lat2 = asin(sin(p) * cos(d) + cos(p) * sin(d) * cos(b))
    val lon2 = radians(lon) + atan2(sin(b) * sin(d) * cos(p), cos(d) - sin(p) * sin(lat2))
    return lat2 * 180 / PI to ((lon2 * 180 / PI + 540) % 360 - 180)
}

/** Signed distance in metres from the area's contour: positive inside, negative outside. */
fun depth(area: Area, lat: Double, lon: Double): Double {
    if (area.polygon.isNotEmpty()) {
        val v = area.polygon.map { project(lat, lon, it.lat, it.lon) }
        var inside = false
        var nearest = Double.POSITIVE_INFINITY
        var j = v.size - 1
        for (i in v.indices) {
            if ((v[i].y > 0) != (v[j].y > 0) && 0 < (v[j].x - v[i].x) * (0 - v[i].y) / (v[j].y - v[i].y) + v[i].x) inside = !inside
            nearest = min(nearest, segmentDistance(Xy(0.0, 0.0), v[j], v[i]))
            j = i
        }
        return if (inside) nearest else -nearest
    }
    val c = area.center ?: return Double.NEGATIVE_INFINITY
    val r = area.radius.toDouble()
    val d = distance(c.lat, c.lon, lat, lon)
    val start = 2.0 * area.offsetAngle; val span = 2.0 * (area.includedAngle + 1)
    if (span >= 360) return r - d
    val inSector = (bearing(c.lat, c.lon, lat, lon) - start + 360) % 360 <= span
    val (e1lat, e1lon) = destination(c.lat, c.lon, start, r)
    val (e2lat, e2lon) = destination(c.lat, c.lon, start + span, r)
    val cc = project(lat, lon, c.lat, c.lon); val e1 = project(lat, lon, e1lat, e1lon); val e2 = project(lat, lon, e2lat, e2lon)
    var nearest = min(segmentDistance(Xy(0.0, 0.0), cc, e1), segmentDistance(Xy(0.0, 0.0), cc, e2))
    nearest = min(nearest, if (inSector) abs(r - d) else min(hypot(e1.x, e1.y), hypot(e2.x, e2.y)))
    return if (inSector && d <= r) nearest else -nearest
}

fun inside(area: Area, lat: Double, lon: Double) = depth(area, lat, lon) >= 0

/** The radio identities a trigger follows, with their Annex F.3 formats. */
enum class Dimension(val change: String, val any: String, val enter: String, val exit: String, private val pattern: Regex) {
    CELL("CellChange", "AnyCellChange", "EnterSpecificCell", "ExitSpecificCell", Regex("\\d{6}[01]{28}")),
    NR_CELL("CellChange", "AnyCellChange", "EnterSpecificNRCell", "ExitSpecificNRCell", Regex("\\d{6}[01]{36}")),
    TRACKING_AREA("TrackingAreaChange", "AnyTrackingAreaChange", "EnterSpecificTrackingArea", "ExitSpecificTrackingArea", Regex("\\d{6}[01]{16}")),
    TRACKING_AREA_5G("NR5GTrackingAreaChange", "Any5GTrackingAreaChange", "EnterSpecific5GTrackingArea", "ExitSpecific5GTrackingArea",
        Regex("[A-Fa-f0-9]{4}|[A-Fa-f0-9]{6}")),
    PLMN("PlmnChange", "AnyPlmnChange", "EnterSpecificPlmn", "ExitSpecificPlmn", Regex("\\d{6}")),
    MBMS_SA("MbmsSaChange", "AnyMbmsSaChange", "EnterSpecificMbmsSa", "ExitSpecificMbmsSa", Regex("\\d{1,5}")),
    MBSFN("MbsfnAreaChange", "AnyMbsfnAreaChange", "EnterSpecificMbsfnArea", "ExitSpecificMbsfnArea", Regex("\\d{1,3}")),
    MBSFSA_5G("NR5GMbsfsaAreaChange", "Any5GMbsfsaAreaChange", "EnterSpecific5GMbsfsaArea", "ExitSpecific5GMbsfsaArea", Regex("[A-Fa-f0-9]{6}")),
    RAT("RatTypeChange", "AnyRatTypeChange", "", "", Regex(".{1,64}"));

    fun check(value: String) { if (!pattern.matches(value)) throw LocationError("malformed identity '$value'") }
}

enum class Event { InitialLogOn, GroupCallNonEmergency, PrivateCallNonEmergency, LocationConfigurationReceived,
    FunctionalAliasActivation, FunctionalAliasDeactivation }

data class Adaptive(val minPeriod: Long = 0, val persistence: Long = 0, val additional: Long = 1, val minDistance: Long? = null)

/** One triggering criterion (Annex F.3.3), named by its TriggerId. */
data class Trigger(val kind: Kind, val id: String, val dimension: Dimension = Dimension.CELL, val value: String = "",
                   val amount: Long = 0, val event: Event = Event.InitialLogOn, val area: Area = Area(), val adaptive: Adaptive = Adaptive()) {
    enum class Kind { AnyChange, Enter, Exit, Periodic, Distance, Signalling, AnyArea, EnterArea, ExitArea, Adaptive }
}

/** What a report carries (<NonEmergencyLocationInformation>, <EmergencyLocationInformation>). */
data class Requested(val servingEcgi: Boolean = false, val neighbouringEcgi: Boolean = false, val mbmsSa: Boolean = false,
                     val mbsfn: Boolean = false, val coordinate: Boolean = false, val servingNcgi: Boolean = false,
                     val neighbouringNcgi: Boolean = false, val mbsfsa: Boolean = false, val bearingSpeed: Boolean = false,
                     val minimumInterval: Long = 1) {
    /**
     * TS 24.379 annex F.3.3: an "Update" is "in addition to any previous location configuration" (removing an element
     * needs a "Full" one): the elements of [update] are added to these, and its minimumIntervalLength applies.
     */
    fun plus(update: Requested) = update.copy(servingEcgi = update.servingEcgi || servingEcgi,
        neighbouringEcgi = update.neighbouringEcgi || neighbouringEcgi, mbmsSa = update.mbmsSa || mbmsSa,
        mbsfn = update.mbsfn || mbsfn, coordinate = update.coordinate || coordinate, servingNcgi = update.servingNcgi || servingNcgi,
        neighbouringNcgi = update.neighbouringNcgi || neighbouringNcgi, mbsfsa = update.mbsfsa || mbsfsa,
        bearingSpeed = update.bearingSpeed || bearingSpeed)
}

data class Configuration(val full: Boolean = true, val nonEmergency: Requested? = null, val emergency: Requested? = null,
                         val triggers: List<Trigger> = emptyList(), val emergencyTriggers: List<Trigger> = emptyList()) {
    /** TS 23.280 table 10.9.2.1-1 NOTE 1: nothing requested and no trigger cancels reporting. */
    val cancels get() = nonEmergency == null && emergency == null && triggers.isEmpty() && emergencyTriggers.isEmpty()
}

data class Filter(val area: String, val inside: Boolean = true)
data class Request(val id: String, val refresh: Boolean = false, val filter: Filter? = null)

data class Current(val servingEcgi: String? = null, val neighbouringEcgi: List<String> = emptyList(), val mbmsSa: String? = null,
                   val mbsfn: String? = null, val coordinate: Point? = null, val servingNcgi: String? = null,
                   val neighbouringNcgi: List<String> = emptyList(), val mbsfsa: String? = null, val bearingSpeed: String? = null,
                   val timestamp: String? = null, val functionalAlias: String? = null)

data class Report(val id: String = "", val emergency: Boolean = false, val triggers: List<String> = emptyList(),
                  val reportingUri: String = "", val location: Current = Current())

/** The location-info document (TS 24.379 Annex F.3): exactly one Configuration, Request or Report. */
object LocationInfo {
    private fun esc(s: String) = Xml.escape(s)
    private fun el(name: String, body: String = "", attributes: String = "") = if (body.isEmpty()) "<$name$attributes/>" else "<$name$attributes>$body</$name>"
    private fun idAttr(id: String): String { if (id.isEmpty() || id.length > 256) throw LocationError("a trigger needs a TriggerId"); return " TriggerId=\"${esc(id)}\"" }

    private fun pointXml(p: Point): String {
        var ext = ""
        p.altitude?.let { ext += el("altitude", el("twobytes", it.toString()), " type=\"Normal\"") }
        p.horizontalAccuracy?.let { ext += el("horizontalaccuracy", el("onebyteunsignedhalfrange", it.toString()), " type=\"Normal\"") }
        p.verticalAccuracy?.let { ext += el("verticalaccuracy", el("onebyteunsignedhalfrange", it.toString()), " type=\"Normal\"") }
        return el("longitude", el("threebytes", p.longitude.toString()), " type=\"Normal\"") +
            el("latitude", el("threebytes", p.latitude.toString()), " type=\"Normal\"") + (if (ext.isEmpty()) "" else el("anyExt", ext))
    }

    private fun located(name: String, inner: String, value: String, inExt: Boolean = false): String {
        val v = el(inner, esc(value))
        return el(name, if (inExt) el("anyExt", v) else v, " type=\"Normal\"")
    }

    private fun requestedXml(r: Requested): String {
        var body = ""
        if (r.servingEcgi) body += "<ServingEcgi/>"
        if (r.neighbouringEcgi) body += "<NeighbouringEcgi/>"
        if (r.mbmsSa) body += "<MbmsSaId/>"
        if (r.mbsfn) body += "<MbsfnArea/>"
        if (r.coordinate) body += "<GeographicalCoordinate/>"
        body += el("minimumIntervalLength", r.minimumInterval.toString())
        var ext = ""
        if (r.servingNcgi) ext += "<ServingNcgi/>"
        if (r.neighbouringNcgi) ext += "<NeighbouringNcgi/>"
        if (r.mbsfsa) ext += "<NR5GMbsfsaArea/>"
        if (r.bearingSpeed) ext += "<R_BearingAndSpeed/>"
        return body + (if (ext.isEmpty()) "" else el("anyExt", ext))
    }

    /** Only what a client sends: a Report, or a Request. Configurations are written for the tests. */
    fun reportXml(report: Report, service: LocationService): String {
        val l = report.location
        var inner = report.triggers.joinToString("") { el("TriggerId", esc(it)) }
        if (report.reportingUri.isNotEmpty()) inner += el("${service.lower}-reporting-uri", esc(report.reportingUri))
        var current = ""
        l.servingEcgi?.let { Dimension.CELL.check(it); current += located("CurrentServingEcgi", "Ecgi", it) }
        l.neighbouringEcgi.forEach { Dimension.CELL.check(it); current += located("NeighbouringEcgi", "Ecgi", it) }
        l.mbmsSa?.let { current += located("MbmsSaId", "SaId", it) }
        l.mbsfn?.let { current += located("MbsfnArea", "MbsfnAreaId", it) }
        l.coordinate?.let { current += el("CurrentCoordinate", pointXml(it)) }
        var ext = ""
        l.timestamp?.let { ext += el("locTimestamp", esc(it)) }
        l.functionalAlias?.let { ext += el("FunctionalAlias", esc(it)) }
        l.servingNcgi?.let { Dimension.NR_CELL.check(it); ext += located("CurrentServingNcgi", "Ncgi", it, true) }
        l.neighbouringNcgi.forEach { Dimension.NR_CELL.check(it); ext += located("CL_NeighbouringNcgi", "Ncgi", it, true) }
        l.mbsfsa?.let { ext += located("CL_5GMbsfsaArea", "NR5GMbsfsaAreaId", it, true) }
        l.bearingSpeed?.let { ext += el("CL_BearingAndSpeed", el("BearingAndSpeed", it), " type=\"Normal\"") }
        if (ext.isNotEmpty()) current += el("anyExt", ext)
        inner += "<CurrentLocation>$current</CurrentLocation>"
        val attrs = (if (report.id.isEmpty()) "" else " ReportID=\"${esc(report.id)}\"") + " ReportType=\"${if (report.emergency) "Emergency" else "NonEmergency"}\""
        return document(el("Report", inner, attrs), service)
    }

    fun requestXml(request: Request, service: LocationService): String {
        if (request.id.isEmpty() || request.id.length > 256) throw LocationError("a request needs a RequestId")
        val inner = request.filter?.let { el("anyExt", el("LocationFilter", el("AreaIdentifier", esc(it.area)) + el("AreaIn", it.inside.toString()))) } ?: ""
        return document(el("Request", inner, " RequestId=\"${esc(request.id)}\"" + if (request.refresh) " refresh=\"true\"" else ""), service)
    }

    /** A configuration (for the tests, and as a server would write it). */
    fun configurationXml(c: Configuration, service: LocationService): String {
        var inner = ""
        c.nonEmergency?.let { inner += el("NonEmergencyLocationInformation", requestedXml(it)) }
        c.emergency?.let { inner += el("EmergencyLocationInformation", requestedXml(it)) }
        inner += "<TriggeringCriteria>${criteriaXml(c.triggers, service)}</TriggeringCriteria>"
        inner += "<anyExt><EmergencyTriggeringCriteria>${criteriaXml(c.emergencyTriggers, service)}</EmergencyTriggeringCriteria></anyExt>"
        return document(el("Configuration", inner, " ConfigScope=\"${if (c.full) "Full" else "Update"}\""), service)
    }

    private fun criteriaXml(triggers: List<Trigger>, service: LocationService): String {
        var body = ""
        fun radio(dims: List<Dimension>): String {
            var inner = ""; var ext = ""
            for (t in triggers.filter { it.dimension in dims && it.kind in setOf(Trigger.Kind.AnyChange, Trigger.Kind.Enter, Trigger.Kind.Exit) }) {
                val xml = when (t.kind) {
                    Trigger.Kind.AnyChange -> el(t.dimension.any, "", idAttr(t.id))
                    Trigger.Kind.Enter -> el(t.dimension.enter, esc(t.value), idAttr(t.id))
                    else -> el(t.dimension.exit, esc(t.value), idAttr(t.id))
                }
                if (t.dimension == Dimension.NR_CELL) ext += xml else inner += xml
            }
            if (inner.isEmpty() && ext.isEmpty()) return ""
            return el(dims.first().change, inner + if (ext.isEmpty()) "" else el("anyExt", ext))
        }
        body += radio(listOf(Dimension.CELL, Dimension.NR_CELL)) + radio(listOf(Dimension.TRACKING_AREA)) + radio(listOf(Dimension.PLMN))
        triggers.firstOrNull { it.kind == Trigger.Kind.Periodic }?.let { body += el("PeriodicReport", it.amount.toString(), idAttr(it.id)) }
        triggers.firstOrNull { it.kind == Trigger.Kind.Distance }?.let { body += el("TravelledDistance", it.amount.toString(), idAttr(it.id)) }
        val events = triggers.filter { it.kind == Trigger.Kind.Signalling }
        if (events.isNotEmpty()) body += el("${service.capitalised}SignallingEvent", events.joinToString("") { el(it.event.name, "", idAttr(it.id)) })
        var areas = ""
        triggers.firstOrNull { it.kind == Trigger.Kind.AnyArea }?.let { areas += el("AnyAreaChange", "", idAttr(it.id)) }
        triggers.firstOrNull { it.kind == Trigger.Kind.EnterArea }?.let { areas += el("EnterSpecificAreaType", areaXml(it.area), idAttr(it.id)) }
        triggers.firstOrNull { it.kind == Trigger.Kind.ExitArea }?.let { areas += el("ExitSpecificAreaType", areaXml(it.area), idAttr(it.id)) }
        if (areas.isNotEmpty()) body += el("GeographicalAreaChange", areas)
        val ext = radio(listOf(Dimension.RAT)) + radio(listOf(Dimension.TRACKING_AREA_5G))
        if (ext.isNotEmpty()) body += el("anyExt", ext)
        return body
    }

    private fun areaXml(a: Area): String {
        var body = if (a.polygon.isNotEmpty()) el("PolygonArea", a.polygon.joinToString("") { el("Corner", pointXml(it)) })
        else el("EllipsoidArcArea", el("Center", pointXml(a.center!!)) + el("Radius", a.radius.toString()) +
            el("OffsetAngle", a.offsetAngle.toString()) + el("IncludedAngle", a.includedAngle.toString()))
        var ext = ""
        if (a.innerBorder > 0) ext += el("InnerBorderWidth", a.innerBorder.toString())
        if (a.outerBorder > 0) ext += el("OuterBorderWidth", a.outerBorder.toString())
        if (ext.isNotEmpty()) body += el("anyExt", ext)
        return el("GeographicalArea", body)
    }

    private fun document(body: String, service: LocationService) =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><location-info xmlns=\"${service.namespace}\">$body</location-info>"

    // ---- parsing (unknown elements are ignored, F.3.3) ----

    private class Reader(val ns: String) {
        fun all(parent: Element?, name: String): List<Element> = if (parent == null) emptyList() else
            (0 until parent.childNodes.length).map { parent.childNodes.item(it) }.filterIsInstance<Element>()
                .filter { it.localName == name && it.namespaceURI == ns }
        fun one(parent: Element?, name: String, required: Boolean = false): Element? {
            val found = all(parent, name)
            if (found.size > 1) throw LocationError("duplicate <$name>")
            if (found.isEmpty() && required) throw LocationError("missing <$name>")
            return found.firstOrNull()
        }
        fun withExt(parent: Element?, name: String) = all(parent, name) + all(parent, "anyExt").flatMap { all(it, name) }
        fun oneExt(parent: Element?, name: String): Element? {
            val found = withExt(parent, name)
            if (found.size > 1) throw LocationError("duplicate <$name>")
            return found.firstOrNull()
        }
        fun text(e: Element) = e.textContent.trim()
        fun number(e: Element, min: Long, max: Long): Long = text(e).toLongOrNull()?.takeIf { it in min..max } ?: throw LocationError("invalid number '${text(e)}'")
        fun clear(e: Element) {
            val type = e.getAttribute("type")
            if (type == "Encrypted") throw LocationError("encrypted content must be opened first")
            if (type.isNotEmpty() && type != "Normal") throw LocationError("unknown protection type")
        }
        fun coded(parent: Element, name: String, inner: String, min: Long, max: Long): Long {
            val e = one(parent, name, true)!!; clear(e); return number(one(e, inner, true)!!, min, max)
        }
        fun id(e: Element): String = e.getAttribute("TriggerId").takeIf { it.isNotEmpty() && it.length <= 256 } ?: throw LocationError("a trigger needs a TriggerId")
        fun point(e: Element): Point {
            var p = Point(coded(e, "latitude", "threebytes", 0, 0xffffff).toInt(), coded(e, "longitude", "threebytes", 0, 0xffffff).toInt())
            for (ext in all(e, "anyExt")) {
                if (one(ext, "altitude") != null) p = p.copy(altitude = coded(ext, "altitude", "twobytes", -32768, 32767).toInt())
                if (one(ext, "horizontalaccuracy") != null) p = p.copy(horizontalAccuracy = coded(ext, "horizontalaccuracy", "onebyteunsignedhalfrange", 0, 127).toInt())
                if (one(ext, "verticalaccuracy") != null) p = p.copy(verticalAccuracy = coded(ext, "verticalaccuracy", "onebyteunsignedhalfrange", 0, 127).toInt())
            }
            return p
        }
        fun area(specific: Element): Area {
            val g = one(specific, "GeographicalArea", true)!!
            var a = Area()
            one(g, "PolygonArea")?.let { poly ->
                a = a.copy(polygon = all(poly, "Corner").map(::point))
                if (a.polygon.size !in 3..15) throw LocationError("a polygon has 3 to 15 corners")
            }
            one(g, "EllipsoidArcArea")?.let { arc ->
                if (a.polygon.isNotEmpty()) throw LocationError("an area is a polygon or an arc")
                a = a.copy(center = point(one(arc, "Center", true)!!), radius = number(one(arc, "Radius", true)!!, 0, 0xffffffffL),
                    offsetAngle = number(one(arc, "OffsetAngle", true)!!, 0, 255).toInt(), includedAngle = number(one(arc, "IncludedAngle", true)!!, 0, 255).toInt())
            }
            if (a.polygon.isEmpty() && a.center == null) throw LocationError("an area needs a polygon or an arc")
            oneExt(g, "InnerBorderWidth")?.let { a = a.copy(innerBorder = number(it, 0, 127).toInt()) }
            oneExt(g, "OuterBorderWidth")?.let { a = a.copy(outerBorder = number(it, 0, 127).toInt()) }
            return a
        }
        fun criteria(c: Element): List<Trigger> {
            val out = mutableListOf<Trigger>()
            fun radio(change: Element?, dims: List<Dimension>) {
                if (change == null) return
                for (d in dims) {
                    if (d == dims.first()) withExt(change, d.any).forEach { out += Trigger(Trigger.Kind.AnyChange, id(it), d) }
                    if (d.enter.isEmpty()) continue
                    withExt(change, d.enter).forEach { out += Trigger(Trigger.Kind.Enter, id(it), d, text(it).also(d::check)) }
                    withExt(change, d.exit).forEach { out += Trigger(Trigger.Kind.Exit, id(it), d, text(it).also(d::check)) }
                }
            }
            radio(one(c, "CellChange"), listOf(Dimension.CELL, Dimension.NR_CELL))
            radio(one(c, "TrackingAreaChange"), listOf(Dimension.TRACKING_AREA))
            radio(one(c, "PlmnChange"), listOf(Dimension.PLMN))
            radio(one(c, "MbmsSaChange"), listOf(Dimension.MBMS_SA))
            radio(one(c, "MbsfnAreaChange"), listOf(Dimension.MBSFN))
            radio(oneExt(c, "RatTypeChange"), listOf(Dimension.RAT))
            radio(oneExt(c, "NR5GMbsfsaAreaChange"), listOf(Dimension.MBSFSA_5G))
            radio(oneExt(c, "NR5GTrackingAreaChange"), listOf(Dimension.TRACKING_AREA_5G))
            one(c, "PeriodicReport")?.let { out += Trigger(Trigger.Kind.Periodic, id(it), amount = number(it, 1, 86400L * 366)) }
            one(c, "TravelledDistance")?.let { out += Trigger(Trigger.Kind.Distance, id(it), amount = number(it, 1, 40_000_000)) }
            val events = one(c, "McpttSignallingEvent") ?: one(c, "McdataSignallingEvent") ?: one(c, "McvideoSignallingEvent")
            if (events != null) for (e in Event.entries) oneExt(events, e.name)?.let { out += Trigger(Trigger.Kind.Signalling, id(it), event = e) }
            one(c, "GeographicalAreaChange")?.let { g ->
                one(g, "AnyAreaChange")?.let { out += Trigger(Trigger.Kind.AnyArea, id(it)) }
                one(g, "EnterSpecificAreaType")?.let { out += Trigger(Trigger.Kind.EnterArea, id(it), area = area(it)) }
                one(g, "ExitSpecificAreaType")?.let { out += Trigger(Trigger.Kind.ExitArea, id(it), area = area(it)) }
            }
            // The schema names it AddaptiveTrigger, the semantics AdaptiveTrigger.
            (oneExt(c, "AddaptiveTrigger") ?: oneExt(c, "AdaptiveTrigger"))?.let { a ->
                out += Trigger(Trigger.Kind.Adaptive, id(a), adaptive = Adaptive(
                    minPeriod = one(a, "MinPeriod")?.let { number(it, 0, 86400L * 366) } ?: 0,
                    persistence = one(a, "PersistencePeriod")?.let { number(it, 0, 86400L * 366) } ?: 0,
                    additional = one(a, "AdditionalTime")?.let { number(it, 1, 86400L * 366) } ?: 1,
                    minDistance = one(a, "MinDistance")?.let { number(it, 1, 40_000_000) }))
            }
            if (out.map { it.id }.toSet().size != out.size) throw LocationError("a TriggerId is used twice")
            return out
        }
        fun requested(e: Element) = Requested(one(e, "ServingEcgi") != null, all(e, "NeighbouringEcgi").isNotEmpty(), one(e, "MbmsSaId") != null,
            one(e, "MbsfnArea") != null, one(e, "GeographicalCoordinate") != null, oneExt(e, "ServingNcgi") != null,
            withExt(e, "NeighbouringNcgi").isNotEmpty(), oneExt(e, "NR5GMbsfsaArea") != null, oneExt(e, "R_BearingAndSpeed") != null,
            number(one(e, "minimumIntervalLength", true)!!, 1, 86400L * 366))
        fun located(e: Element, inner: String): String { clear(e); return text(oneExt(e, inner) ?: throw LocationError("missing <$inner>")) }
        fun current(e: Element) = Current(
            servingEcgi = one(e, "CurrentServingEcgi")?.let { located(it, "Ecgi") },
            neighbouringEcgi = all(e, "NeighbouringEcgi").map { located(it, "Ecgi") },
            mbmsSa = one(e, "MbmsSaId")?.let { located(it, "SaId") },
            mbsfn = one(e, "MbsfnArea")?.let { located(it, "MbsfnAreaId") },
            coordinate = one(e, "CurrentCoordinate")?.let(::point),
            servingNcgi = oneExt(e, "CurrentServingNcgi")?.let { located(it, "Ncgi") },
            neighbouringNcgi = withExt(e, "CL_NeighbouringNcgi").map { located(it, "Ncgi") },
            mbsfsa = oneExt(e, "CL_5GMbsfsaArea")?.let { located(it, "NR5GMbsfsaAreaId") },
            bearingSpeed = oneExt(e, "CL_BearingAndSpeed")?.let { text(one(it, "BearingAndSpeed", true)!!) },
            timestamp = oneExt(e, "locTimestamp")?.let(::text),
            functionalAlias = oneExt(e, "FunctionalAlias")?.let(::text))
    }

    /** A Configuration, Request or Report, and the service whose namespace it uses. */
    fun parse(xml: String): Pair<Any, LocationService> {
        if (xml.isEmpty() || xml.length > 65536) throw LocationError("empty or oversized body")
        if ("<!DOCTYPE" in xml || "<!ENTITY" in xml) throw LocationError("DTD and entities are unsupported")
        val root = try { Xml.parse(xml) } catch (e: Exception) { throw LocationError("invalid XML document") }
        val service = LocationService.entries.firstOrNull { root.localName == "location-info" && root.namespaceURI == it.namespace }
            ?: throw LocationError("root is not a location-info element")
        val r = Reader(service.namespace)
        val configuration = r.one(root, "Configuration"); val request = r.one(root, "Request"); val report = r.one(root, "Report")
        if (listOfNotNull(configuration, request, report).size != 1) throw LocationError("exactly one of Configuration, Request or Report")
        configuration?.let { c ->
            val scope = c.getAttribute("ConfigScope")
            if (scope.isNotEmpty() && scope != "Full" && scope != "Update") throw LocationError("unknown ConfigScope")
            return Configuration(scope != "Update", r.one(c, "NonEmergencyLocationInformation")?.let(r::requested),
                r.one(c, "EmergencyLocationInformation")?.let(r::requested), r.one(c, "TriggeringCriteria")?.let(r::criteria) ?: emptyList(),
                r.oneExt(c, "EmergencyTriggeringCriteria")?.let(r::criteria) ?: emptyList()) to service
        }
        request?.let { q ->
            val id = q.getAttribute("RequestId").ifEmpty { q.getAttribute("RequestID") }
            if (id.isEmpty() || id.length > 256) throw LocationError("a request needs a RequestId")
            val refresh = q.getAttribute("refresh")
            val filter = r.oneExt(q, "LocationFilter")?.let { f ->
                Filter(r.text(r.one(f, "AreaIdentifier", true)!!), r.text(r.one(f, "AreaIn", true)!!).let { it == "true" || it == "1" })
            }
            return Request(id, refresh == "true" || refresh == "1", filter) to service
        }
        val p = report!!
        val type = p.getAttribute("ReportType")
        if (type != "Emergency" && type != "NonEmergency") throw LocationError("ReportType is Emergency or NonEmergency")
        return Report(p.getAttribute("ReportID"), type == "Emergency", r.all(p, "TriggerId").map(r::text),
            r.one(p, "${service.lower}-reporting-uri")?.let(r::text) ?: "", r.current(r.one(p, "CurrentLocation", true)!!)) to service
    }
}
