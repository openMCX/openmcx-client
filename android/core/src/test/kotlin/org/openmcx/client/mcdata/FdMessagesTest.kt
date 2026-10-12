//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class FdMessagesTest {
    private val conversation = UUID.fromString("30313233-3435-3637-3839-3a3b3c3d3e3f")
    private val message = UUID.fromString("40414243-4445-4647-4849-4a4b4c4d4e4f")
    private val ids = (0x30..0x4f).map(Int::toByte).toByteArray()
    private val offerHeader = byteArrayOf(2, 0, 0, 0, 0, 7) + ids
    private val urlIe = byteArrayOf(0x78, 0, 0x12, 4) + "https://content/f".toByteArray()

    @Test fun httpOfferIsByteExact() {
        // TS 24.282 15.1.3 / server test_mcdata literal example.
        val fixture = offerHeader + byteArrayOf(0x91.toByte(), 0xa1.toByte()) + urlIe
        val offer = FdSignalling(7, conversation, message, "https://content/f", completionRequested = true, mandatoryDownload = true)
        assertArrayEquals(fixture, offer.encode())
        assertEquals(offer, FdSignalling.decode(fixture))
    }

    @Test fun allDispositionTypesAreByteExact() {
        listOf(FdNotificationType.ACCEPTED, FdNotificationType.REJECTED, FdNotificationType.COMPLETED, FdNotificationType.DEFERRED)
            .forEachIndexed { index, type ->
                val fixture = byteArrayOf(6, (index + 1).toByte(), 0, 0, 0, 0, 7) + ids +
                    byteArrayOf(0x51, 0, 7) + "sip:b@x".toByteArray()
                val notification = FdNotification(type, 7, conversation, message, sender = "sip:b@x")
                assertArrayEquals(fixture, notification.encode())
                assertEquals(notification, FdNotification.decode(fixture))
            }
    }

    @Test fun networkNotificationsAreByteExact() {
        listOf(FdNetworkNotificationType.EXPIRED, FdNetworkNotificationType.DELETED).forEachIndexed { index, type ->
            val fixture = byteArrayOf(9, (index + 1).toByte(), 0, 0, 0, 0, 7) + ids
            val notification = FdNetworkNotification(type, 7, conversation, message)
            assertArrayEquals(fixture, notification.encode())
            assertEquals(notification, FdNetworkNotification.decode(fixture))
        }
    }

    @Test fun rejectsTruncationDuplicateAndReservedIes() {
        val good = offerHeader + urlIe
        for (bad in listOf(offerHeader, good.dropLast(1).toByteArray(), good + urlIe,
            good + byteArrayOf(0x92.toByte()), good + byteArrayOf(0xa1.toByte(), 0xa1.toByte()),
            byteArrayOf(0x42) + good.drop(1).toByteArray(), offerHeader + byteArrayOf(0x78, 0, 2, 1, 65))) {
            assertThrows(McdataCodecError::class.java) { FdSignalling.decode(bad) }
        }
        assertThrows(McdataCodecError::class.java) { FdNotification.decode(byteArrayOf(6, 5, 0, 0, 0, 0, 7) + ids) }
        assertThrows(McdataCodecError::class.java) { FdNetworkNotification.decode(byteArrayOf(9, 3, 0, 0, 0, 0, 7) + ids) }
    }

    @Test fun discoveryResponseCarriesControllerPsi() {
        val xml = "<mcdatainfo xmlns=\"urn:3gpp:ns:mcdataInfo:1.0\"><mcdata-Params><request-type>msf-disc-res</request-type>" +
            "<mcdata-controller-psi><mcdataURI>https://proxy:8443/content</mcdataURI></mcdata-controller-psi></mcdata-Params></mcdatainfo>"
        assertEquals("https://proxy:8443/content", McdataInfo.parse(xml).controllerPsi)
        assertTrue(McdataInfo(requestType = "msf-disc-res", controllerPsi = "https://proxy:8443/content").encode().contains("<mcdata-controller-psi"))
    }
}
