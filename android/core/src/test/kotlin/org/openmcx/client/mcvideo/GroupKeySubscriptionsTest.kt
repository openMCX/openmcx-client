//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcvideo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmcx.client.sip.Dialog
import org.openmcx.client.sip.NameAddr
import org.openmcx.client.sip.UaIdentity
import org.openmcx.client.sip.UserAgent

/** The GKTP subscriptions are kept with their dialogs, and ended in them at log-off (RFC 6665 §4.1.2.3). */
class GroupKeySubscriptionsTest {
    private fun dialog(callId: String) = Dialog(callId = callId, localTag = "l", remoteTag = "r",
        localUri = NameAddr("sip:alice.mcvideo@mcx.test"), remoteUri = NameAddr("sip:mcvideo@mcx.test"),
        remoteTarget = "sip:mcvideo@10.0.0.1", routeSet = listOf("<sip:pcscf@10.0.0.2;lr>"), localSeq = 1)

    @Test fun `a NOTIFY finds its group before and after the 2xx`() {
        val subs = GroupKeySubscriptions()
        subs.sent("c1", "sip:g1@mcx.test")
        assertEquals("sip:g1@mcx.test", subs.group("c1"))
        subs.accepted(dialog("c1"), 3600)
        assertEquals("sip:g1@mcx.test", subs.group("c1"))
        assertNull(subs.group("other"))
    }

    @Test fun `log-off takes every accepted dialog once and keeps the group for the final NOTIFY`() {
        val subs = GroupKeySubscriptions()
        subs.sent("c1", "sip:g1@mcx.test"); subs.accepted(dialog("c1"), 3600)
        subs.sent("c2", "sip:g2@mcx.test"); subs.accepted(dialog("c2"), 3600)
        subs.sent("c3", "sip:g3@mcx.test") // no 2xx yet: no dialog to end
        assertEquals(setOf("c1", "c2"), subs.takeDialogs().map { it.callId }.toSet())
        assertTrue(subs.takeDialogs().isEmpty())
        assertEquals("sip:g1@mcx.test", subs.group("c1"))
        subs.ended("c1")
        assertNull(subs.group("c1"))
    }

    @Test fun `a refused or terminated subscription leaves nothing to end`() {
        val subs = GroupKeySubscriptions()
        subs.sent("c1", "sip:g1@mcx.test"); subs.ended("c1")
        subs.accepted(dialog("c1"), 3600) // a late 2xx for a subscription already ended is not kept
        subs.sent("c2", "sip:g2@mcx.test"); subs.accepted(dialog("c2"), 3600); subs.ended("c2")
        assertTrue(subs.takeDialogs().isEmpty())
    }

    @Test fun `a refresh is due at nine tenths of the Expires granted, and taken once`() {
        var now = 1_000_000L
        val subs = GroupKeySubscriptions { now }
        subs.sent("c1", "sip:g1@mcx.test"); subs.accepted(dialog("c1"), 600)
        subs.sent("c2", "sip:g2@mcx.test"); subs.accepted(dialog("c2"), 3600)
        assertEquals(1_000_000L + 540_000L, subs.nextRefresh())
        now += 539_999L
        assertTrue(subs.due().isEmpty())
        now += 1L
        assertEquals(listOf("c1" to "sip:g1@mcx.test"), subs.due().map { it.first.callId to it.second })
        assertTrue(subs.due().isEmpty())
        assertEquals(1_000_000L + 3_240_000L, subs.nextRefresh())
        subs.accepted(dialog("c1"), 600) // the refresh's 2xx schedules the next one
        assertEquals(now + 540_000L, subs.nextRefresh())
        subs.ended("c1"); subs.takeDialogs()
        assertNull(subs.nextRefresh())
    }

    @Test fun `the granted duration is the Expires of the 2xx, else the RFC 5875 default asked for`() {
        val ok = org.openmcx.client.sip.SipResponse(200, "OK")
        assertEquals(3600L, GroupKeySubscriptions.granted(ok))
        ok.headers.add("Expires", "1800")
        assertEquals(1800L, GroupKeySubscriptions.granted(ok))
    }

    @Test fun `the re-SUBSCRIBE is in the dialog with the GKTP resource list and Expires 3600`() {
        val ua = UserAgent(CoroutineScope(Dispatchers.Unconfined),
            UaIdentity("sip:alice@mcx.test", "alice", "s", "mcx.test", "urn:uuid:1"))
        val request = GroupKeySubscriptions.refresh(ua, dialog("c1"), "sip:g1@mcx.test")
        assertEquals("SUBSCRIBE", request.method)
        assertEquals("c1", request.callId)
        assertEquals("sip:mcvideo@10.0.0.1", request.uri)
        assertEquals(2L, request.cseq.number)
        assertEquals("r", request.to.tag)
        assertEquals("xcap-diff", request.headers["Event"])
        assertEquals("3600", request.headers["Expires"])
        assertEquals("application/resource-lists+xml", request.headers["Content-Type"])
        assertEquals(org.openmcx.client.security.KmsDocuments.gktpResourceList("sip:g1@mcx.test"), String(request.body))
    }

    @Test fun `the SUBSCRIBE that ends a subscription is in its dialog with Expires 0`() {
        val ua = UserAgent(CoroutineScope(Dispatchers.Unconfined),
            UaIdentity("sip:alice@mcx.test", "alice", "s", "mcx.test", "urn:uuid:1"))
        val d = dialog("c1")
        val request = GroupKeySubscriptions.unsubscribe(ua, d)
        assertEquals("SUBSCRIBE", request.method)
        assertEquals("c1", request.callId)
        assertEquals("sip:mcvideo@10.0.0.1", request.uri)
        assertEquals(2L, request.cseq.number)
        assertEquals("xcap-diff", request.headers["Event"])
        assertEquals("application/xcap-diff+xml", request.headers["Accept"])
        assertEquals("0", request.headers["Expires"])
        assertEquals("<sip:pcscf@10.0.0.2;lr>", request.headers["Route"])
        assertEquals("r", request.to.tag)
    }
}
