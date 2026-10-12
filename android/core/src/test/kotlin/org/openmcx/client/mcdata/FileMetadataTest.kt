//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

import org.junit.Assert.*
import org.junit.Test

class FileMetadataTest {
    @Test fun metadataPreservesNameSizeHashAndAvailability() {
        val hash = "a".repeat(64)
        val fixture = "file-selector:name:\"résumé.pdf\" type:application/pdf size:100000000 hash:sha-256:" +
            "AA:".repeat(31) + "AA\r\nfile-availability:Mon, 21 Sep 2026 14:13:20 +0000"
        val metadata = FileMetadata("résumé.pdf", "application/pdf", 100_000_000, hash, 1_790_000_000)
        assertEquals(fixture, metadata.encode())
        assertEquals(metadata, FileMetadata.parse(fixture))
    }

    @Test fun optionalHashAndAvailabilityRemainAbsent() {
        val value = FileMetadata.parse("file-selector:name:\"clip.mp4\" type:video/mp4 size:42")
        assertEquals(42L, value.size)
        assertNull(value.sha256)
        assertNull(value.availableUntil)
    }

    @Test fun rejectsMalformedAndInjectedMetadata() {
        for (size in listOf("-1", "9223372036854775808", "foo")) {
            assertThrows(McdataCodecError::class.java) { FileMetadata.parse("file-selector:name:\"x\" type:text/plain size:$size") }
        }
        assertThrows(McdataCodecError::class.java) { FileMetadata.parse("file-selector:name:\"x\" type:text/plain size:1 hash:sha-256:AA") }
        assertThrows(IllegalArgumentException::class.java) { FileMetadata("a\r\nInjected: x", "text/plain", 1, null, null).encode() }
    }
}
