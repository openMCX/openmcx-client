//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.client

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openmcx.client.body.McService
import org.openmcx.client.location.LocationRuleEngine
import org.openmcx.client.location.Point
import org.openmcx.client.location.ProfileRules
import org.openmcx.client.location.RuleActions

/** What the location rules of one service hold, for the UI and the debug endpoint. */
data class LocationRulesView(
    val loaded: Boolean = false,
    val aliases: List<String> = emptyList(),
    val rules: Int = 0,
    val locked: Set<String> = emptySet(),
    val aliasesByLocation: Set<String> = emptySet(),
    val ruleAffiliated: Set<String> = emptySet(),
    val areaLeft: Set<String> = emptySet(),
    val groupAreas: Set<String> = emptySet(),
    val notes: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * The location rules at the client, for each provisioned service, driven by the phone's position:
 * - location based functional alias status change (TS 24.379 §9A.2.1.4, TS 24.282 §22.2.1.4, TS 24.281 §20.2.1.4),
 *   through the functional alias status change procedure (§9A.2.1.2, §22.2.1.2, §20.2.1.2);
 * - rules based affiliation status change by the user profile's rules (TS 24.379 §9.2.1.7.2, TS 24.282 §8.2.6.2) and,
 *   for MCPTT, by the group documents' geographic areas (TS 24.379 §9.2.1.7.3), through the affiliation status change
 *   procedure (§9.2.1.2, TS 24.282 §8.2.2);
 * - the manual de-affiliation and alias deactivation a rule holds are suppressed (§9.2.1.2, TS 24.282 §8.2.2, §22.2.1.2).
 *
 * The rules come from the user profiles: the MCPTT one the client reads at login, the MCData and MCVideo ones read here
 * when the service is authorised (TS 24.484 §10.3.1A, §9.3.1A). As the Linux client, MCData and MCVideo evaluate no
 * group configuration rules (TS 24.282 §8.2.6.3).
 */
class LocationRuleProcedures(private val client: McxClient) {
    private val engines: Map<McService, LocationRuleEngine> = buildMap {
        if (client.config.mcptt != null) put(McService.MCPTT, LocationRuleEngine(groupAreasApply = true))
        if (client.config.mcdata != null) put(McService.MCDATA, LocationRuleEngine(groupAreasApply = false))
        if (client.config.mcvideo != null) put(McService.MCVIDEO, LocationRuleEngine(groupAreasApply = false))
    }
    private val errors = mutableMapOf<McService, String?>()
    private val loaded = mutableSetOf<McService>()
    private val notes = mutableMapOf<McService, List<String>>()
    private val lock = Mutex()
    private val _state = MutableStateFlow<Map<McService, LocationRulesView>>(emptyMap())
    val state: StateFlow<Map<McService, LocationRulesView>> = _state
    @Volatile var position: Point? = null
        private set
    @Volatile private var started = false
    /** The MCPTT user profile the rules were taken from (read again on its NOTIFY with `documents_on_notify`). */
    @Volatile private var loadedProfile: Any? = null

    fun engine(service: McService): LocationRuleEngine? = engines[service]

    private fun event(text: String) = client.event("location rules: $text")

    private fun authorised(service: McService) = when (service) {
        McService.MCPTT -> client.status.value.mcptt.authorised
        McService.MCDATA -> client.status.value.mcdata.authorised
        McService.MCVIDEO -> client.status.value.mcvideo.authorised
    }

    /** Follow each service's authorisation (rules read, or dropped) and its active functional aliases. */
    fun start() {
        if (started) return
        started = true
        for (service in engines.keys) {
            client.scope.launch {
                client.status.map { authorised(service) }.distinctUntilChanged().collect { up ->
                    lock.withLock { if (up) load(service) else { engines[service]?.reset(); loaded -= service; publish() } }
                    if (up) position?.let { evaluate(service, it) }
                }
            }
            client.scope.launch {
                var before = client.aliasesOf(service).value.active
                client.aliasesOf(service).map { it.active }.distinctUntilChanged().collect { after ->
                    val was = before
                    before = after
                    if (was == after) return@collect
                    // §9.2.1.7.2: rules with a functional alias criterion (TS 24.282 §8.2.6.2 "whenever a functional
                    // alias is activated or deactivated").
                    val actions = lock.withLock { engines[service]?.takeIf { authorised(service) }?.aliasesChanged(was, after) } ?: return@collect
                    execute(service, actions)
                }
            }
        }
    }

    /** The rules of [service]'s user profile, and the MCPTT group documents' areas. */
    private suspend fun load(service: McService) {
        val engine = engines[service] ?: return
        val rules = runCatching {
            when (service) {
                McService.MCPTT -> client.profile.also { loadedProfile = it }?.let { p -> p.locationRulesError?.let { throw IllegalStateException(it) }; p.locationRules } ?: ProfileRules()
                McService.MCDATA -> readProfile("mcdata", client.config.mcdata!!.userId)
                McService.MCVIDEO -> readProfile("mcvideo", client.config.mcvideo!!.userId)
            }
        }
        rules.onSuccess { engine.setRules(it); errors[service] = null }.onFailure { engine.setRules(ProfileRules()); errors[service] = it.message }
        if (service == McService.MCPTT) for ((group, document) in client.groupDocuments) engine.setGroupAreas(group, document.areas)
        loaded += service
        val r = engine.rules
        event("${service.name}: ${r.aliases.size} functional aliases with location criteria, ${r.rules.size} affiliation rules" +
            (errors[service]?.let { " ($it)" } ?: ""))
        publish()
    }

    private suspend fun readProfile(service: String, userId: String): ProfileRules {
        val token = client.tokens?.accessToken ?: throw IllegalStateException("no access token")
        return ProfileRules.parse(client.documents.serviceProfile(service, userId, client.config.mcptt?.profileIndex ?: 1, token))
    }

    /** The phone's position changed (its fix, or the one the debug endpoint sets): every service's rules in turn. */
    fun position(point: Point) {
        position = point
        client.scope.launch { for (service in engines.keys) evaluate(service, point) }
    }

    private suspend fun evaluate(service: McService, point: Point) {
        val engine = engines[service] ?: return
        val actions = lock.withLock {
            if (!authorised(service) || service !in loaded) return
            if (service == McService.MCPTT && client.profile !== loadedProfile) load(service)
            if (service == McService.MCPTT) groupsOfInterest().forEach { group -> ensureGroupAreas(engine, group) }
            val aliases = client.aliasesOf(service).value
            engine.position(point.lat, point.lon, aliases.standing, aliases.active,
                if (service == McService.MCPTT) groupsOfInterest() else emptyList())
        }
        execute(service, actions)
    }

    /** §9.2.1.7.3: the groups whose areas are checked: the groups this client wants affiliated. */
    private fun groupsOfInterest() = client.wantedGroups(McService.MCPTT)

    private suspend fun ensureGroupAreas(engine: LocationRuleEngine, group: String) {
        val known = client.groupDocuments[group]
        if (known != null) { engine.setGroupAreas(group, known.areas); return }
        val token = client.tokens?.accessToken ?: return
        runCatching { client.documents.group(group, token) }.onSuccess { engine.setGroupAreas(group, it.areas) }
    }

    private suspend fun execute(service: McService, actions: RuleActions) {
        if (actions.notes.isNotEmpty()) {
            actions.notes.forEach { event("${service.name}: $it") }
            notes[service] = (notes[service].orEmpty() + actions.notes).takeLast(10)
        }
        if (actions.activate.isNotEmpty()) runCatching { client.aliasRequest("activate", actions.activate, service) }
            .onFailure { event("${service.name}: functional alias activation ${actions.activate}: ${it.message}") }
        if (actions.deactivate.isNotEmpty()) runCatching { client.aliasRequest("deactivate", actions.deactivate, service) }
            .onFailure { event("${service.name}: functional alias deactivation ${actions.deactivate}: ${it.message}") }
        if (actions.affiliate.isNotEmpty() || actions.deaffiliate.isNotEmpty())
            runCatching { client.changeAffiliation(service, actions.affiliate, actions.deaffiliate) }
                .onFailure { event("${service.name}: affiliation change: ${it.message}") }
        publish()
    }

    /**
     * The user's affiliation (or de-affiliation) of [group]: TS 24.379 §9.2.1.2 suppresses a de-affiliation that a
     * fulfilled rule holds when the profile locks it (TS 24.282 §8.2.2 the same); §9.2.1.7.3 allows an affiliation only
     * within the group's permitted and mandatory areas.
     */
    suspend fun userAffiliation(service: McService, group: String, affiliate: Boolean) {
        val engine = engines[service]
        if (affiliate) engine?.takeIf { service == McService.MCPTT }?.manualAffiliationRefusal(group)?.let { throw IllegalStateException(it) }
        else engine?.manualDeaffiliationRefusal(group)?.let { throw IllegalStateException(it) }
        if (affiliate) client.changeAffiliation(service, listOf(group), emptyList()) else client.changeAffiliation(service, emptyList(), listOf(group))
        publish()
    }

    /** TS 24.282 §22.2.1.2 (TS 24.281 §20.2.1.2): a user's deactivation of an alias a location criterion holds is suppressed. */
    fun checkUserDeactivation(service: McService, aliases: List<String>) {
        val engine = engines[service] ?: return
        aliases.firstNotNullOfOrNull { engine.manualDeactivationRefusal(it) }?.let { throw IllegalStateException(it) }
    }

    private fun publish() {
        _state.value = engines.mapValues { (service, e) ->
            LocationRulesView(service in loaded, e.rules.aliases.map { it.alias }, e.rules.rules.size, e.rules.manualDeaffiliationLocked,
                e.aliasesByLocation.toSet(), e.ruleAffiliatedGroups.toSet(), e.areaLeftGroups.toSet(), e.groupAreas().keys,
                notes[service].orEmpty(), errors[service])
        }
    }
}
