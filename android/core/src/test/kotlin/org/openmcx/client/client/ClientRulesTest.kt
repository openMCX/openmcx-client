//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmcx.client.mcptt.FloorIndicator

class ClientRulesTest {
    /** RFC 5373 §2: the answer mode is the token before any parameter, without case. */
    @Test fun answerModeIsComparedByToken() {
        assertEquals("auto", ClientRules.answerModeToken("Auto;require"))
        assertEquals("manual", ClientRules.answerModeToken(" manual ; require"))
        assertEquals("auto", ClientRules.answerModeToken("AUTO"))
        assertNull(ClientRules.answerModeToken(null))
        assertNull(ClientRules.answerModeToken(";require"))
    }

    /** TS 24.379 §11.1.1.2.1.2 steps 9-10: a forced call with parameters is answered automatically. */
    @Test fun commencementFromTheHeaderTokens() {
        assertFalse(ClientRules.manualCommencement(null, "Auto;require", "manual", true))
        assertTrue(ClientRules.manualCommencement("Manual;require", null, "automatic", true))
        assertFalse(ClientRules.manualCommencement("Auto;require", null, "automatic", true))
        assertTrue(ClientRules.manualCommencement("Auto;require", null, "manual", true))
        assertFalse(ClientRules.manualCommencement("Auto", null, "manual", false))
        assertTrue(ClientRules.manualCommencement(null, null, "manual", false))
        assertFalse(ClientRules.manualCommencement(null, null, "automatic", true))
    }

    /** RFC 3261 §20.33 for a 500 or 503 to the service authorisation, bounded to 1 s .. 600 s; the back-off otherwise. */
    @Test fun authorisationRetryAfter() {
        assertEquals(30_000, ClientRules.authorisationRetryDelayMs(503, "30", 2_000))
        assertEquals(30_000, ClientRules.authorisationRetryDelayMs(500, " 30 (busy);duration=10", 2_000))
        assertEquals(600_000, ClientRules.authorisationRetryDelayMs(503, "86400", 2_000))
        assertEquals(1_000, ClientRules.authorisationRetryDelayMs(500, "0", 2_000))
        assertEquals(2_000, ClientRules.authorisationRetryDelayMs(503, null, 2_000))
        assertEquals(2_000, ClientRules.authorisationRetryDelayMs(503, "soon", 2_000))
        assertEquals(4_000, ClientRules.authorisationRetryDelayMs(486, "30", 4_000))
        assertEquals(4_000, ClientRules.authorisationRetryDelayMs(null, "30", 4_000))
    }

    /** TS 24.380 §6.2.4.3.5 1 b) and table 8.2.3.15-2: the call type bit, and bit F with negotiated queueing. */
    @Test fun floorIndicatorByCallType() {
        assertEquals(FloorIndicator.NORMAL, ClientRules.floorIndicator(false, false, false, false))
        assertEquals(FloorIndicator.NORMAL or FloorIndicator.QUEUEING, ClientRules.floorIndicator(false, false, false, true))
        assertEquals(FloorIndicator.EMERGENCY, ClientRules.floorIndicator(false, true, false, false))
        assertEquals(FloorIndicator.EMERGENCY, ClientRules.floorIndicator(false, true, true, false))
        assertEquals(FloorIndicator.IMMINENT_PERIL or FloorIndicator.QUEUEING, ClientRules.floorIndicator(false, false, true, true))
        assertEquals(FloorIndicator.BROADCAST, ClientRules.floorIndicator(true, false, false, false))
        assertEquals(FloorIndicator.BROADCAST or FloorIndicator.EMERGENCY, ClientRules.floorIndicator(true, true, false, false))
    }

    /** TS 24.380 §14.4: an answer's parameter the offer lacked is discarded. */
    @Test fun answerParametersNotOfferedAreDiscarded() {
        assertTrue(ClientRules.answerParameterKept(answered = true, offered = true))
        assertFalse(ClientRules.answerParameterKept(answered = true, offered = false))
        assertFalse(ClientRules.answerParameterKept(answered = false, offered = true))
    }
}
