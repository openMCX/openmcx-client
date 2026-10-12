//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app.attachments

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.openmcx.client.mcdata.FdRecord
import org.openmcx.client.mcdata.FdState
import org.openmcx.client.mcdata.FileMetadata
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

class AttachmentError(message: String) : Exception(message)

/** What the Messages picker offers, with the document picker's MIME filter for each. */
enum class AttachmentKind(val mimeTypes: Array<String>) {
    IMAGE(arrayOf("image/*")), VIDEO(arrayOf("video/*")), DOCUMENT(arrayOf("*/*"))
}

/**
 * Attachments in the app's private storage: picked content is copied (staged) before it is
 * sent, so a lost picker grant cannot lose it, and completed files are shown to viewers through
 * the FileProvider with a temporary read grant only.
 */
object AttachmentFiles {
    const val AUTHORITY_SUFFIX = ".attachments"
    /** Kept free beyond the file itself, so staging never fills the device. */
    private const val RESERVE_BYTES = 16L * 1024 * 1024

    fun root(context: Context) = File(context.filesDir, "attachments")
    fun fdDirectory(context: Context) = File(root(context), "fd")
    fun stagingDirectory(context: Context) = File(root(context), "staging")

    /** Copy [uri] into private storage within [maxBytes], hashing as it goes. */
    suspend fun stage(context: Context, uri: Uri, maxBytes: Long?): Pair<File, FileMetadata> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }?.let { name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                }
            }
        }
        val displayName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val type = mediaType(resolver.getType(uri), displayName)
        val input = resolver.openInputStream(uri) ?: throw AttachmentError("cannot read the selected file")
        input.use { stage(stagingDirectory(context), displayName, type, size, it, maxBytes) }
    }

    /**
     * Stage [input] as [name] in [directory]. The declared size is only a hint: the limit and
     * the free-space check apply to the bytes actually read. A failure leaves no file behind.
     */
    suspend fun stage(directory: File, name: String, type: String, declaredSize: Long?, input: InputStream,
                      maxBytes: Long?): Pair<File, FileMetadata> {
        if (declaredSize != null && declaredSize >= 0 && maxBytes != null && declaredSize > maxBytes)
            throw AttachmentError("the file is ${readableSize(declaredSize)}; the limit is ${readableSize(maxBytes)}")
        directory.mkdirs()
        val free = directory.usableSpace
        if (declaredSize != null && declaredSize + RESERVE_BYTES > free) throw AttachmentError("not enough storage for this file")
        val target = File(directory, UUID.randomUUID().toString())
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        try {
            target.outputStream().use { out ->
                val buffer = ByteArray(65536)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    copied += n
                    if (maxBytes != null && copied > maxBytes) throw AttachmentError("the file is larger than the limit of ${readableSize(maxBytes)}")
                    if (copied + RESERVE_BYTES > free) throw AttachmentError("not enough storage for this file")
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                }
            }
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        return target to FileMetadata(displayName(name), type, copied, hash)
    }

    /** A name the metadata can carry: no control characters or path, never blank. */
    fun displayName(name: String): String =
        name.substringAfterLast('/').map { if (it.isISOControl()) ' ' else it }.joinToString("").trim().take(200).ifEmpty { "file" }

    /** The provider's type if it is a valid MIME type, else one from the extension, else octet-stream. */
    fun mediaType(provided: String?, name: String): String {
        val valid = Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")
        provided?.lowercase()?.takeIf { valid.matches(it) }?.let { return it }
        val extension = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.takeIf { valid.matches(it) } ?: "application/octet-stream"
    }

    /** The file a record can open: a completed download, or a file this phone sent. */
    fun openable(record: FdRecord): File? {
        val path = record.localPath ?: return null
        if (record.incoming && record.state != FdState.COMPLETED) return null
        return File(path).takeIf { it.isFile }
    }

    /** An ACTION_VIEW intent for [record] with a temporary read grant, or null when it cannot be opened. */
    fun viewIntent(context: Context, record: FdRecord): Intent? {
        val file = openable(record) ?: return null
        val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file, record.metadata.name)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, record.metadata.mediaType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun readableSize(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
        bytes >= 1_000 -> "%.0f kB".format(bytes / 1e3)
        else -> "$bytes B"
    }
}
