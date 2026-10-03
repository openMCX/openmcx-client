package org.openmcx.client.app.attachments

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.openmcx.client.app.data.AttachmentStore
import org.openmcx.client.mcdata.FdDirection
import org.openmcx.client.mcdata.FdRecord
import org.openmcx.client.mcdata.FdService
import org.openmcx.client.mcdata.FdState
import org.openmcx.client.mcdata.FileMetadata
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.UUID

/** Staging, the record store and viewer grants, in the app's real private storage. */
@RunWith(AndroidJUnit4::class)
class AttachmentFilesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scratch = File(context.cacheDir, "attachment-test-${UUID.randomUUID()}")

    @After fun cleanUp() { scratch.deleteRecursively() }

    /** A provider stream of [size] bytes that never says how long it is. */
    private fun stream(size: Long) = object : InputStream() {
        var left = size
        override fun read() = if (left-- > 0) 7 else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = minOf(len.toLong(), left).toInt(); b.fill(7, off, off + n); left -= n; return n
        }
    }

    @Test fun unknownAndFalseProviderSizesAreBounded() = runBlocking {
        for (declared in listOf(null, 10L)) {
            try {
                AttachmentFiles.stage(scratch, "big.bin", "application/octet-stream", declared, stream(200_001), 200_000)
                fail("staged over the limit with declared size $declared")
            } catch (e: AttachmentError) { assertTrue(e.message!!.contains("limit")) }
        }
        // A declared size over the limit fails before reading.
        try {
            AttachmentFiles.stage(scratch, "big.bin", "application/octet-stream", 5_000_000, stream(1), 200_000); fail()
        } catch (e: AttachmentError) {}
        // A declared size larger than the free space fails without allocating it.
        try {
            AttachmentFiles.stage(scratch, "huge.bin", "application/octet-stream", scratch.usableSpace + 1, stream(1), null); fail()
        } catch (e: AttachmentError) { assertTrue(e.message!!.contains("storage")) }
        assertTrue(scratch.listFiles().orEmpty().isEmpty())
        // Exactly the limit is allowed.
        val (file, metadata) = AttachmentFiles.stage(scratch, "ok.bin", "application/octet-stream", null, stream(200_000), 200_000)
        assertEquals(200_000L, file.length()); assertEquals(200_000L, metadata.size)
        assertEquals(FdService.sha256(file), metadata.sha256)
    }

    @Test fun unicodeAndTraversalNamesStayPrivate() = runBlocking {
        val (file, metadata) = AttachmentFiles.stage(scratch, "../../shared_prefs/Ünïcode\u0007 report.pdf", "application/pdf", 3,
            ByteArrayInputStream(byteArrayOf(1, 2, 3)), null)
        assertEquals(scratch.canonicalPath, file.parentFile!!.canonicalPath)
        assertEquals("Ünïcode  report.pdf", metadata.name)
        assertEquals("application/pdf", AttachmentFiles.mediaType("application/pdf", "x"))
        assertEquals("image/jpeg", AttachmentFiles.mediaType("not a type", "photo.JPG"))
        assertEquals("application/octet-stream", AttachmentFiles.mediaType(null, "noextension"))
    }

    @Test fun recordsAndCompletedFilesSurviveReopen() {
        val store = AttachmentStore(File(scratch, "records"))
        val record = FdRecord(UUID.randomUUID().toString(), UUID.randomUUID().toString(), FdDirection.INCOMING, "sip:bob@mcx.test",
            "sip:ops@mcx.test", FileMetadata("a.pdf", "application/pdf", 3, "ab".repeat(32)), url = "https://h/content/mcdata/files/a/a.pdf",
            state = FdState.OFFERED)
        store.put(record)
        store.put(record.copy(state = FdState.COMPLETED, localPath = "/x"))
        val reopened = AttachmentStore(File(scratch, "records")).load()
        assertEquals(listOf(record.copy(state = FdState.COMPLETED, localPath = "/x")), reopened)
    }

    @Test fun viewerGetsOnlyCompletedAttachmentReadGrant() {
        val directory = File(AttachmentFiles.fdDirectory(context), "files/test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val file = File(directory, "photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val record = FdRecord(UUID.randomUUID().toString(), UUID.randomUUID().toString(), FdDirection.INCOMING, "sip:bob@mcx.test",
                null, FileMetadata("photo.jpg", "image/jpeg", 3), localPath = file.path, state = FdState.DOWNLOADING)
            assertNull(AttachmentFiles.viewIntent(context, record))
            val intent = AttachmentFiles.viewIntent(context, record.copy(state = FdState.COMPLETED))
            assertNotNull(intent)
            assertEquals(Intent.ACTION_VIEW, intent!!.action)
            assertEquals("image/jpeg", intent.type)
            assertEquals("content", intent.data!!.scheme)
            assertEquals(context.packageName + AttachmentFiles.AUTHORITY_SUFFIX, intent.data!!.authority)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            // The provider serves the file's bytes to whoever holds the grant.
            assertEquals(listOf<Byte>(1, 2, 3), context.contentResolver.openInputStream(intent.data!!)!!.use { it.readBytes() }.toList())
        } finally { directory.deleteRecursively() }
    }
}
