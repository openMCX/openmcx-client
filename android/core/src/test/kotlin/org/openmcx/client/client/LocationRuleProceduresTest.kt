//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmcx.client.alert.EmergencyAlerts
import org.openmcx.client.body.Affiliation
import org.openmcx.client.body.McService
import org.openmcx.client.body.Multipart
import org.openmcx.client.http.HttpClient
import org.openmcx.client.http.HttpResponse
import org.openmcx.client.idms.Tokens
import org.openmcx.client.location.LocationService
import org.openmcx.client.location.Point
import org.openmcx.client.location.encodeLatitude
import org.openmcx.client.location.encodeLongitude
import org.openmcx.client.mcptt.FunctionalAliasPidf
import org.openmcx.client.mcptt.McpttInfo
import org.openmcx.client.sip.SipRequest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * On the wire of a fake SIP core: the group geographic area notice (TS 24.379 §12.1.1.4) and the user profile's location
 * rules (§9A.2.1.4, §9.2.1.7.2, §9.2.1.2) driven by the position.
 */
class LocationRuleProceduresTest {
    private val alice = "sip:alice.mcptt@mcx.test"
    private val group = "sip:operations@mcx.test"
    private val alias = "sip:patrol@mcx.test"

    private fun corner(lat: Double, lon: Double) = "<Corner><Longitude>${encodeLongitude(lon)}</Longitude><Latitude>${encodeLatitude(lat)}</Latitude></Corner>"
    private val area = "<PolygonArea>" + corner(45.0005, 9.999) + corner(45.0005, 10.001) + corner(45.0013, 10.001) + corner(45.0013, 9.999) + "</PolygonArea>"
    private val inside = Point.at(45.0009, 10.0)
    private val outside = Point.at(45.0018, 10.0)

    private val profile = "<mcptt-user-profile xmlns=\"urn:3gpp:mcptt:user-profile:1.0\" XUI-URI=\"$alice\" user-profile-index=\"1\">" +
        "<Common index=\"1\"><MCPTTUserID><uri-entry>$alice</uri-entry></MCPTTUserID></Common>" +
        "<OnNetwork index=\"1\"><MCPTTGroupInfo><entry index=\"1\"><uri-entry>$group</uri-entry><anyExt>" +
        "<RulesForAffiliation><ListOfLocationCriteria><ExitSpecificArea>$area</ExitSpecificArea></ListOfLocationCriteria></RulesForAffiliation>" +
        "<RulesForDeaffiliation><ListOfLocationCriteria><EnterSpecificArea>$area</EnterSpecificArea></ListOfLocationCriteria></RulesForDeaffiliation>" +
        "<manual-deaffiliation-not-allowed-if-affiliation-rules-are-met>true</manual-deaffiliation-not-allowed-if-affiliation-rules-are-met>" +
        "</anyExt></entry></MCPTTGroupInfo><anyExt><FunctionalAliasList><entry index=\"1\"><uri-entry>$alias</uri-entry><anyExt>" +
        "<LocationCriteriaForActivation><EnterSpecificArea>$area</EnterSpecificArea></LocationCriteriaForActivation>" +
        "<LocationCriteriaForDeactivation><ExitSpecificArea>$area</ExitSpecificArea></LocationCriteriaForDeactivation>" +
        "</anyExt></entry></FunctionalAliasList></anyExt></OnNetwork></mcptt-user-profile>"

    private fun config() = ClientConfig.parse("""{
        "name": "client-a", "domain": "mcx.test", "client_id": "urn:uuid:1",
        "sip": {"public_user_id": "sip:alice@mcx.test", "private_user_id": "alice", "password": "s",
                "proxy": "sip-core", "proxy_port": 5061, "transport": "TLS", "register_expires": 3600},
        "idms": {"issuer": "https://idms", "client_id": "mcx-client", "redirect_uri": "http://127.0.0.1:8903/callback"},
        "cms": "https://cms", "gms": "https://gms",
        "mcptt": {"user_id": "$alice", "psi": "sip:mcptt@mcx.test", "groups": [],
                  "answer_mode": "automatic", "session_expires": 1800, "manual_answer_timeout": 20,
                  "subscribe_configuration": false, "subscribe_group_documents": false},
        "media": {"port_range": [20000, 20400], "amr_wb_mode": 2}}""")

    private val http = object : HttpClient {
        override suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?) =
            if ("mcptt-user-profile" in url) HttpResponse(200, emptyMap(), profile.toByteArray()) else HttpResponse(404, emptyMap(), ByteArray(0))
    }

    private fun tokens() = Tokens(accessToken = "AT", idToken = "ID", expiresAt = System.currentTimeMillis() / 1000 + 3600,
        mcpttId = alice, mcdataId = null, mcvideoId = null, sipUri = null, clientId = null, refreshToken = "RT", nonce = "n")

    private val log = EventLog()

    private fun waitFor(what: String, seconds: Int = 10, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + seconds * 1000L
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what; events: " + log.last(15).joinToString(" | "))
            Thread.sleep(50)
        }
    }

    private fun start(cores: MutableList<FakeSipCore>): McxClient {
        val client = McxClient(CoroutineScope(SupervisorJob() + Dispatchers.Default), config(), http,
            connector = { _, _ -> FakeSipCore().also { cores += it } }, log = log)
        client.tokens = tokens()
        client.profile = org.openmcx.client.documents.UserProfile.parse(profile) // read at login, which the tokens skip
        client.serviceProtection = mapOf("mcptt" to (false to false))
        client.handlers += EmergencyAlerts(client, LocationService.MCPTT, "sip:mcptt@mcx.test", alice)
        client.start()
        // The profile's group is affiliated at start-up: the core confirms it.
        waitFor("the start-up affiliation") { cores.isNotEmpty() && cores[0].affiliationPublishes().isNotEmpty() }
        cores[0].affiliated(listOf(group))
        waitFor("ready") { client.status.value.phase == "ready" }
        return client
    }

    private fun parts(request: SipRequest) = Multipart.parse(request.contentType, request.body)
    private fun part(request: SipRequest, type: String) = parts(request).firstOrNull { it.mediaType == type }?.text
    private fun FakeSipCore.affiliationPublishes() =
        requests.filter { it.method == "PUBLISH" && it.headers["Event"] == "presence" && part(it, Affiliation.CONTENT_TYPE)?.contains("xmlns:fa") == false }
    private fun FakeSipCore.aliasPublishes() =
        requests.filter { it.method == "PUBLISH" && part(it, FunctionalAliasPidf.CONTENT_TYPE)?.contains("xmlns:fa") == true }
    private fun FakeSipCore.ownSubscription() = requests.first { it.method == "SUBSCRIBE" && it.headers["Event"] == "presence" }
    private fun FakeSipCore.affiliated(groups: List<String>) = notify(ownSubscription(), "presence", "active;expires=3600", Affiliation.CONTENT_TYPE,
        """<?xml version="1.0"?><presence xmlns="${Affiliation.PIDF}" xmlns:mcpttPI10="urn:3gpp:ns:mcpttPresInfo:1.0" entity="$alice">
        <tuple id="urn:uuid:1"><status>""" + groups.joinToString("") { "<mcpttPI10:affiliation group=\"$it\" status=\"affiliated\"/>" } +
        "</status></tuple></presence>")
    private fun listed(publish: SipRequest) = runCatching { Affiliation.parseAll(part(publish, Affiliation.CONTENT_TYPE)!!)["urn:uuid:1"].orEmpty().keys }
        .getOrDefault(emptySet())

    /** §12.1.1.4: the notice's <group-geo-area-ind> de-affiliates from, then affiliates to, the <associated-group-id> group. */
    @Test fun groupGeographicAreaNotice() = runBlocking {
        val cores = CopyOnWriteArrayList<FakeSipCore>()
        val client = start(cores)
        val core = cores[0]
        val before = core.affiliationPublishes().size
        core.message(McpttInfo.CONTENT_TYPE, McpttInfo(requestUri = alice, associatedGroupId = group, groupGeoArea = false).encode())
        assertEquals(200, core.answer().status)
        waitFor("the de-affiliation PUBLISH") { core.affiliationPublishes().size == before + 1 }
        assertTrue(group !in listed(core.affiliationPublishes().last()))
        core.affiliated(emptyList())
        waitFor("de-affiliated") { !Affiliation.counts(client.status.value.mcptt.affiliations[group]) }
        core.message(McpttInfo.CONTENT_TYPE, McpttInfo(requestUri = alice, associatedGroupId = group, groupGeoArea = true).encode())
        assertEquals(200, core.answer().status)
        waitFor("the affiliation PUBLISH") { core.affiliationPublishes().size == before + 2 }
        assertEquals(setOf(group), listed(core.affiliationPublishes().last()))
        core.affiliated(listOf(group))
        waitFor("affiliated") { Affiliation.counts(client.status.value.mcptt.affiliations[group]) }
        client.stop()
    }

    /** §9A.2.1.4 and §9.2.1.7.2: crossing the area activates and deactivates the alias, de-affiliates and affiliates. */
    @Test fun profileRulesByPosition() = runBlocking {
        val cores = CopyOnWriteArrayList<FakeSipCore>()
        val client = start(cores)
        val core = cores[0]
        val rules = client.locationRules
        waitFor("rules loaded") { rules.state.value[McService.MCPTT]?.loaded == true && rules.state.value[McService.MCPTT]?.rules == 2 }
        val publishes = core.affiliationPublishes().size
        val aliasPublishes = core.aliasPublishes().size
        rules.position(outside)
        Thread.sleep(300)
        assertEquals("the first position only places", publishes, core.affiliationPublishes().size)
        // Entering: the alias is activated (§9A.2.1.2 PUBLISH) and the group left (the de-affiliation rule).
        rules.position(inside)
        waitFor("alias activation") { core.aliasPublishes().size == aliasPublishes + 1 }
        assertTrue(alias in part(core.aliasPublishes().last(), FunctionalAliasPidf.CONTENT_TYPE)!!)
        waitFor("rule de-affiliation") { core.affiliationPublishes().size == publishes + 1 }
        assertTrue(group !in listed(core.affiliationPublishes().last()))
        core.affiliated(emptyList())
        waitFor("de-affiliated") { !Affiliation.counts(client.status.value.mcptt.affiliations[group]) }
        assertEquals(setOf(alias), rules.state.value[McService.MCPTT]!!.aliasesByLocation)
        // Leaving: the affiliation rule (exit) affiliates again.
        rules.position(outside)
        waitFor("rule affiliation") { core.affiliationPublishes().size == publishes + 2 }
        assertEquals(setOf(group), listed(core.affiliationPublishes().last()))
        core.affiliated(listOf(group))
        waitFor("held by the rule") { group in rules.state.value[McService.MCPTT]!!.ruleAffiliated }
        // §9.2.1.2: the user's de-affiliation is suppressed while the rule holds the group.
        assertTrue(runCatching { rules.userAffiliation(McService.MCPTT, group, affiliate = false) }.exceptionOrNull()?.message!!.contains("not allowed"))
        client.stop()
    }
}
