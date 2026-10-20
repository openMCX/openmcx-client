//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmcx.client.body.McService
import org.openmcx.client.mcdata.McdataInfo
import org.openmcx.client.mcvideo.McvideoInfo

class FunctionalAliasTest {
    private val dispatcher = "sip:dispatcher.alias@mcx.test"

    @Test
    fun theStatusChangeRequestNamesTheAliasesAndTheTakeOver() {
        // TS 24.379 §9A.2.1.2 step 6, syntax of §9A.3.1.2.
        val xml = FunctionalAliasPidf.request("sip:alice.mcptt@mcx.test", "urn:uuid:1", listOf(dispatcher), takeOver = true, publishId = "p1")
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?><presence xmlns=\"urn:ietf:params:xml:ns:pidf\" " +
            "xmlns:fa=\"urn:3gpp:ns:mcpttPresInfoFA:1.0\" entity=\"sip:alice.mcptt@mcx.test\"><fa:p-id-fa>p1</fa:p-id-fa>" +
            "<tuple id=\"urn:uuid:1\"><status><fa:functionalAlias functionalAliasID=\"$dispatcher\"/><fa:take-over>true</fa:take-over>" +
            "</status></tuple></presence>", xml)
        assertTrue("<fa:take-over>" !in FunctionalAliasPidf.request("sip:a@x", "c", listOf(dispatcher), takeOver = false))
    }

    @Test
    fun theStatusChangeListsTheWholeWantedSet() {
        // TS 24.379 §9A.2.1.2 step 6 a with §9A.2.2.2.3 steps 12 and 13: an activation adds to the set, a deactivation
        // lists the others, and Expires 0 only when none is left.
        val reserve = "sip:reserve.alias@mcx.test"
        assertEquals(setOf(dispatcher, reserve), FunctionalAliasRules.wanted(setOf(dispatcher), "activate", listOf(reserve)))
        assertEquals(setOf(dispatcher, reserve), FunctionalAliasRules.wanted(setOf(dispatcher), "takeover", listOf(reserve)))
        assertEquals(setOf(reserve), FunctionalAliasRules.wanted(setOf(dispatcher, reserve), "deactivate", listOf(dispatcher)))
        assertEquals("4294967295", FunctionalAliasRules.expires(setOf(reserve)))
        val none = FunctionalAliasRules.wanted(setOf(reserve), "deactivate", listOf(reserve))
        assertEquals("0", FunctionalAliasRules.expires(none))
        assertTrue("functionalAliasID" !in FunctionalAliasPidf.request("sip:a@x", "c", none.toList(), takeOver = false))
    }

    @Test
    fun theNextChangeStartsFromTheAliasesStanding() {
        // §9A.2.2.2.5 leaves out only "deactivated": an alias still "activating" is wanted (left out, step 12 b would
        // cancel it), one "deactivating" was given up (listed again, step 12 a ii would activate it again); the latter
        // stays usable until it ends.
        val reserve = "sip:reserve.alias@mcx.test"
        val commander = "sip:incident-commander.alias@mcx.test"
        val status = FunctionalAliasPidf.Status("sip:bob.mcptt@mcx.test", null, listOf(
            FunctionalAliasPidf.Entry(dispatcher, "activated"), FunctionalAliasPidf.Entry(reserve, "activating"),
            FunctionalAliasPidf.Entry(commander, "deactivating")))
        val state = FunctionalAliasRules.notified(FunctionalAliasState(), status, null, mutableSetOf()).state
        assertEquals(setOf(dispatcher, reserve), state.standing)
        assertEquals(setOf(dispatcher, commander), state.active)
        assertEquals(setOf(dispatcher), FunctionalAliasRules.wanted(state.standing, "deactivate", listOf(reserve)))
    }

    @Test
    fun readsTheServersStatusNotification() {
        // The server's per-user notification (openmcx-server functional_alias_status_xml).
        val status = FunctionalAliasPidf.parseStatus("<?xml version=\"1.0\"?><presence xmlns=\"urn:ietf:params:xml:ns:pidf\" " +
            "xmlns:fa=\"urn:3gpp:ns:mcpttPresInfoFA:1.0\" entity=\"sip:bob.mcptt@mcx.test\"><fa:p-id-fa>p2</fa:p-id-fa>" +
            "<tuple id=\"urn:uuid:2\"><status><fa:functionalAlias functionalAliasID=\"$dispatcher\" status=\"take-over-possible\"/>" +
            "<fa:functionalAlias functionalAliasID=\"sip:reserve.alias@mcx.test\" status=\"activated\"/></status></tuple></presence>")!!
        assertEquals("sip:bob.mcptt@mcx.test", status.entity)
        assertEquals("p2", status.publishId)
        assertEquals(listOf(FunctionalAliasPidf.Entry(dispatcher, "take-over-possible"),
            FunctionalAliasPidf.Entry("sip:reserve.alias@mcx.test", "activated")), status.entries)
    }

    @Test
    fun anEmptyNotificationClearsTheAliases() {
        val status = FunctionalAliasPidf.parseStatus("<?xml version=\"1.0\"?><presence xmlns=\"urn:ietf:params:xml:ns:pidf\" " +
            "xmlns:fa=\"urn:3gpp:ns:mcpttPresInfoFA:1.0\" entity=\"sip:bob.mcptt@mcx.test\"><tuple id=\"urn:uuid:2\"><status/></tuple></presence>")!!
        assertTrue(status.entries.isEmpty())
        assertNull(status.publishId)
    }

    @Test
    fun anAffiliationNotificationIsNotAnAliasOne() {
        assertNull(FunctionalAliasPidf.parseStatus("<?xml version=\"1.0\"?><presence xmlns=\"urn:ietf:params:xml:ns:pidf\" " +
            "xmlns:mcpttPI10=\"urn:3gpp:ns:mcpttPresInfo:1.0\" entity=\"sip:bob.mcptt@mcx.test\"><tuple id=\"c\"><status>" +
            "<mcpttPI10:affiliation group=\"sip:operations@mcx.test\" status=\"affiliated\"/></status></tuple></presence>"))
    }

    @Test
    fun theCallElementsGoInAnyExtAndBack() {
        // TS 24.379 Annex F.1.3: the presented alias, the called alias, the call to an alias and the request type.
        val xml = McpttInfo(sessionType = "private", clientId = "urn:uuid:1", functionalAlias = "sip:reserve.alias@mcx.test",
            callToFunctionalAlias = true).encode()
        assertTrue(xml.contains("<anyExt><functional-alias-URI type=\"Normal\"><mcpttURI>sip:reserve.alias@mcx.test</mcpttURI>" +
            "</functional-alias-URI><call-to-functional-alias-ind>true</call-to-functional-alias-ind></anyExt>"))
        val back = McpttInfo.parse(xml)
        assertEquals("sip:reserve.alias@mcx.test", back.functionalAlias)
        assertEquals(true, back.callToFunctionalAlias)
        // The server's 300 to a call to an alias: the selected MCPTT ID and the alias called.
        val redirect = McpttInfo.parse("<?xml version=\"1.0\"?><mcpttinfo xmlns=\"urn:3gpp:ns:mcpttInfo:1.0\"><mcptt-Params>" +
            "<mcptt-request-uri type=\"Normal\"><mcpttURI>sip:bob.mcptt@mcx.test</mcpttURI></mcptt-request-uri><anyExt>" +
            "<called-functional-alias-URI type=\"Normal\"><mcpttURI>$dispatcher</mcpttURI></called-functional-alias-URI>" +
            "</anyExt></mcptt-Params></mcpttinfo>")
        assertEquals("sip:bob.mcptt@mcx.test", redirect.requestUri)
        assertEquals(dispatcher, redirect.calledFunctionalAlias)
        assertTrue(McpttInfo(requestUri = "sip:a@x", requestType = "functional-alias-status-determination").encode()
            .contains("<request-type>functional-alias-status-determination</request-type>"))
    }

    private fun status(publishId: String?, vararg entries: Pair<String, String>) =
        FunctionalAliasPidf.Status("sip:bob.mcdata@mcx.test", publishId, entries.map { FunctionalAliasPidf.Entry(it.first, it.second) })

    @Test
    fun aTakeOverAskedBeforeTheOfferGoesAsAnActivationFirst() {
        // §9A.2.1.2 step 6 d, NOTE 4: <take-over> only for the aliases already offered.
        val (offered, others) = FunctionalAliasRules.splitTakeOver(listOf(dispatcher, "sip:medic.alias@mcx.test"), setOf(dispatcher))
        assertEquals(listOf(dispatcher), offered)
        assertEquals(listOf("sip:medic.alias@mcx.test"), others)
    }

    @Test
    fun mcdataActivationAnswered200TheOutcomeByNotify() {
        // TS 24.282 §22.2.1.2 and §22.2.1.3, the corrected shape (as MCPTT): the PUBLISH is answered 200 and the NOTIFY
        // carrying its <p-id-fa> gives each alias's outcome: activated, take-over-possible, or omitted (refused).
        val medic = "sip:medic.alias@mcx.test"
        val reserve = "sip:reserve.alias@mcx.test"
        val wanted = mutableSetOf(dispatcher)
        val change = FunctionalAliasRules.Change("activate", listOf(dispatcher, medic, reserve), "p9")
        val result = FunctionalAliasRules.notified(FunctionalAliasState(available = listOf(dispatcher, medic, reserve)),
            status("p9", medic to "activated", dispatcher to "take-over-possible"), change, wanted)
        assertEquals(setOf(medic), result.state.active)
        assertEquals(setOf(dispatcher), result.state.takeOverPossible)
        assertEquals(mapOf(dispatcher to "take-over-possible", medic to "activated", reserve to "refused"), result.state.lastOutcomes)
        // The take-over the user asked for follows once offered, and only once.
        assertEquals(listOf(dispatcher), result.takeOver)
        assertTrue(wanted.isEmpty())
    }

    @Test
    fun anotherChangesNotifyLeavesTheOutcomesAndTakeOversAlone() {
        val wanted = mutableSetOf(dispatcher)
        val before = FunctionalAliasState(lastOutcomes = mapOf(dispatcher to "activated"), inUse = dispatcher, active = setOf(dispatcher))
        // Taken over by another user: dispatcher is no longer active here, and no longer presented.
        val result = FunctionalAliasRules.notified(before, status("other", dispatcher to "take-over-possible"),
            FunctionalAliasRules.Change("activate", listOf(dispatcher), "mine"), wanted)
        assertTrue(result.state.active.isEmpty())
        assertNull(result.state.inUse)
        assertEquals(mapOf(dispatcher to "activated"), result.state.lastOutcomes)
        assertTrue(result.takeOver.isEmpty())
        assertEquals(setOf(dispatcher), wanted)
    }

    @Test
    fun aDeactivationOutcome() {
        val result = FunctionalAliasRules.notified(FunctionalAliasState(active = setOf(dispatcher)), status("p3"),
            FunctionalAliasRules.Change("deactivate", listOf(dispatcher), "p3"), mutableSetOf())
        assertEquals(mapOf(dispatcher to "deactivated"), result.state.lastOutcomes)
        assertTrue(result.state.active.isEmpty())
    }

    @Test
    fun theMcdataAndMcvideoDocumentsUseTheirNamespaces() {
        // TS 24.282 §22.3.1, TS 24.281 §20.3.1.
        assertTrue(FunctionalAliasPidf.request("sip:a.mcdata@x", "c", listOf(dispatcher), false, "p", McService.MCDATA)
            .contains("xmlns:fa=\"urn:3gpp:ns:mcdataPresInfoFA:1.0\""))
        val video = "<?xml version=\"1.0\"?><presence xmlns=\"urn:ietf:params:xml:ns:pidf\" xmlns:fa=\"urn:3gpp:ns:mcvideoPresInfoFA:1.0\" " +
            "entity=\"sip:a.mcvideo@x\"><tuple id=\"c\"><status><fa:functionalAlias functionalAliasID=\"$dispatcher\" status=\"activated\"/>" +
            "</status></tuple></presence>"
        assertEquals(1, FunctionalAliasPidf.parseStatus(video, McService.MCVIDEO)!!.entries.size)
        // An MCVideo notification is not an MCPTT one.
        assertNull(FunctionalAliasPidf.parseStatus(video, McService.MCPTT))
    }

    @Test
    fun theMcdataAndMcvideoInfoElements() {
        // TS 24.282 §22.2.1.3 step 2 b: <request-type> in <mcdata-Params>; §9.2.2.2.1 step 2b ii A: the SDS to an alias.
        val data = McdataInfo(requestUri = "sip:a.mcdata@x", requestType = "functional-alias-status-determination").encode()
        assertTrue(data.contains("<mcdata-Params><request-type>functional-alias-status-determination</request-type>"))
        val sds = McdataInfo(requestType = "one-to-one-sds", callToFunctionalAlias = true, functionalAlias = dispatcher).encode()
        assertTrue(sds.contains("<anyExt><call-to-functional-alias-ind>true</call-to-functional-alias-ind><functional-alias-URI type=\"Normal\">" +
            "<mcdataURI>$dispatcher</mcdataURI></functional-alias-URI></anyExt>"))
        assertEquals(true, McdataInfo.parse(sds).callToFunctionalAlias)
        assertEquals(dispatcher, McdataInfo.parse(sds).functionalAlias)
        // TS 24.281 §20.2.1.3 step 2: <req-type> in <anyExt>; the aliases of a call.
        val video = McvideoInfo(requestUri = "sip:a.mcvideo@x", reqType = "functional-alias-status-determination").encode()
        assertTrue(video.contains("<anyExt><req-type>functional-alias-status-determination</req-type></anyExt>"))
        val call = McvideoInfo.parse(McvideoInfo(sessionType = "private", functionalAlias = dispatcher, calledFunctionalAlias = "sip:b@x",
            callToFunctionalAlias = true).encode())
        assertEquals(dispatcher, call.functionalAlias)
        assertEquals("sip:b@x", call.calledFunctionalAlias)
        assertEquals(true, call.callToFunctionalAlias)
    }
}
