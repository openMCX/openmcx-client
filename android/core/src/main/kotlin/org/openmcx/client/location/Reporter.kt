//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.location

/**
 * The client side of location reporting (TS 24.379 §13.3, TS 24.282 §17.3, TS 24.281 §18.3), as OpenMCX's reference
 * client does it: the stored configuration and its triggers, what the UE observes (position, radio identities,
 * signalling events), and the minimumReportInterval timer. Times are milliseconds on a monotonic clock.
 */
class Reporter {
    var configuration = Configuration()
        private set
    var configured = false
        private set
    var emergency = false
    var position: Point? = null
        private set
    private var reportedPosition: Point? = null
    private val radio = mutableMapOf<Dimension, String>()
    private val fired = linkedSetOf<String>()
    private val areaState = mutableMapOf<String, Boolean>()
    private val periodic = mutableMapOf<String, Long>()
    private val detected = mutableMapOf<String, Long>()
    private var timerUntil = 0L
    private var origin = 0L

    val pending: Set<String> get() = fired.toSet()

    /** §13.3.2.1: replace (Full) or extend (Update) the stored configuration, set the triggers, start the timer. */
    @Synchronized fun configure(c: Configuration, now: Long) {
        if (c.full) {
            configuration = c
            areaState.clear(); periodic.clear(); detected.clear(); fired.clear()
        } else {
            // "Update": in addition to the previous one; a trigger with an existing TriggerId replaces it.
            fun merge(into: List<Trigger>, from: List<Trigger>): List<Trigger> {
                val out = into.toMutableList()
                for (t in from) {
                    val i = out.indexOfFirst { it.id == t.id }
                    if (i >= 0) out[i] = t else out += t
                    areaState.remove(t.id); periodic.remove(t.id); detected.remove(t.id)
                }
                return out
            }
            configuration = configuration.copy(full = true, nonEmergency = c.nonEmergency ?: configuration.nonEmergency,
                emergency = c.emergency ?: configuration.emergency, triggers = merge(configuration.triggers, c.triggers),
                emergencyTriggers = merge(configuration.emergencyTriggers, c.emergencyTriggers))
        }
        configured = !configuration.cancels
        if (!configured) { fired.clear(); return }
        timerUntil = now + (requested()?.minimumInterval ?: 0) * 1000
        origin = now
        reportedPosition = position
        evaluateAreas()
        active().filter { it.kind == Trigger.Kind.Signalling && it.event == Event.LocationConfigurationReceived }.forEach { fired += it.id }
    }

    private fun active() = if (emergency) configuration.emergencyTriggers else configuration.triggers

    private fun requested(): Requested? = if (emergency) configuration.emergency ?: configuration.nonEmergency
        else configuration.nonEmergency ?: configuration.emergency

    private fun evaluateAreas() {
        val p = position ?: return
        var changed = false
        for (t in active().filter { it.kind == Trigger.Kind.EnterArea || it.kind == Trigger.Kind.ExitArea }) {
            val d = depth(t.area, p.lat, p.lon)
            val previous = areaState[t.id]
            val now = when {
                previous == null -> d >= 0 // the first position fires nothing
                previous -> !(if (t.area.outerBorder > 0) d <= -t.area.outerBorder.toDouble() else d < 0)
                else -> if (t.area.innerBorder > 0) d >= t.area.innerBorder.toDouble() else d >= 0
            }
            if (previous != null && previous != now) {
                changed = true
                if ((t.kind == Trigger.Kind.EnterArea) == now) fired += t.id
            }
            areaState[t.id] = now
        }
        if (changed) active().filter { it.kind == Trigger.Kind.AnyArea }.forEach { fired += it.id }
    }

    /** Whether the last position is inside the area a TriggerId names. */
    @Synchronized fun insideArea(triggerId: String) = areaState[triggerId] == true

    @Synchronized fun position(point: Point) {
        position = point
        if (!configured) return
        val from = reportedPosition ?: point.also { reportedPosition = it }
        evaluateAreas()
        val moved = distance(from.lat, from.lon, point.lat, point.lon)
        active().filter { it.kind == Trigger.Kind.Distance && moved >= it.amount }.forEach { fired += it.id }
    }

    @Synchronized fun radio(dimension: Dimension, value: String) {
        val old = radio[dimension] ?: ""
        radio[dimension] = value
        if (!configured || old == value) return
        for (t in active()) {
            val same = t.dimension == dimension || (t.kind == Trigger.Kind.AnyChange && t.dimension == Dimension.CELL && dimension == Dimension.NR_CELL)
            if (!same) continue
            if ((t.kind == Trigger.Kind.AnyChange && old.isNotEmpty()) || (t.kind == Trigger.Kind.Enter && t.value == value) ||
                (t.kind == Trigger.Kind.Exit && old.isNotEmpty() && t.value == old)) fired += t.id
        }
    }

    @Synchronized fun signal(event: Event) {
        if (!configured) return
        active().filter { it.kind == Trigger.Kind.Signalling && it.event == event }.forEach { fired += it.id }
    }

    private fun build(now: Long): Report {
        val want = requested()
        fun has(d: Dimension) = !radio[d].isNullOrEmpty()
        // Without a configuration (a request only), everything known is reported.
        val location = Current(
            coordinate = position?.takeIf { want == null || want.coordinate },
            servingEcgi = radio[Dimension.CELL]?.takeIf { has(Dimension.CELL) && (want == null || want.servingEcgi) },
            servingNcgi = radio[Dimension.NR_CELL]?.takeIf { has(Dimension.NR_CELL) && (want == null || want.servingNcgi) },
            mbmsSa = radio[Dimension.MBMS_SA]?.takeIf { has(Dimension.MBMS_SA) && (want == null || want.mbmsSa) },
            mbsfn = radio[Dimension.MBSFN]?.takeIf { has(Dimension.MBSFN) && (want == null || want.mbsfn) },
            mbsfsa = radio[Dimension.MBSFSA_5G]?.takeIf { has(Dimension.MBSFSA_5G) && (want == null || want.mbsfsa) })
        val report = Report(emergency = emergency, triggers = fired.toList(), location = location)
        fired.clear()
        timerUntil = now + (want?.minimumInterval ?: 0) * 1000
        origin = now
        reportedPosition = position
        detected.clear()
        return report
    }

    /** §13.3.3.1: a request is answered at once, and resets the timer. */
    @Synchronized fun answer(requestId: String, now: Long): Report = build(now).copy(id = requestId)

    /** §13.3.4.1: a report due now, once the minimumReportInterval timer allows it. */
    @Synchronized fun poll(now: Long): Report? {
        if (!configured) return null
        for (t in active()) {
            if (t.kind == Trigger.Kind.Periodic) {
                val due = periodic.getOrPut(t.id) { now + t.amount * 1000 }
                if (now >= due) { fired += t.id; periodic[t.id] = now + t.amount * 1000 }
            } else if (t.kind == Trigger.Kind.Adaptive) {
                val a = t.adaptive
                val since = now - minOf(now, origin)
                val p = position; val r = reportedPosition
                if (a.minDistance != null && p != null && r != null && since >= a.minPeriod * 1000) {
                    if (distance(r.lat, r.lon, p.lat, p.lon) >= a.minDistance) {
                        val first = detected.getOrPut(t.id) { now }
                        if (now - first >= a.persistence * 1000) fired += t.id
                    } else detected.remove(t.id)
                }
                if (since >= (a.minPeriod + a.persistence + a.additional) * 1000) fired += t.id
            }
        }
        if (fired.isEmpty() || now < timerUntil) return null
        return build(now)
    }
}
