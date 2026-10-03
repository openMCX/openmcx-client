package org.openmcx.client.mcdata

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** RFC 5547 selector and RFC 5322 availability in the FD Metadata IE. */
data class FileMetadata(val name: String, val mediaType: String, val size: Long,
                        val sha256: String? = null, val availableUntil: Long? = null) {
    fun encode(): String {
        require(size >= 0 && name.isNotBlank() && name.none { it.isISOControl() }) { "invalid file metadata" }
        require(mediaType.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+"))) { "invalid MIME type" }
        require(sha256 == null || sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "invalid SHA-256" }
        val safeName = name.replace('"', '_').replace('\\', '_')
        return buildString {
            append("file-selector:name:\"$safeName\" type:$mediaType size:$size")
            sha256?.let { append(" hash:sha-256:"); append(it.chunked(2).joinToString(":").uppercase(Locale.ROOT)) }
            availableUntil?.let {
                require(it >= 0) { "negative availability" }
                append("\r\nfile-availability:"); append(date.format(Instant.ofEpochSecond(it)))
            }
        }
    }

    companion object {
        private val date = DateTimeFormatter.ofPattern("EEE, dd MMM uuuu HH:mm:ss Z", Locale.US).withZone(ZoneOffset.UTC)
        fun parse(value: String): FileMetadata {
            if (value.length > 65535) throw McdataCodecError("file metadata too long")
            val lines = value.split("\r\n")
            val selector = lines.singleOrNull { it.startsWith("file-selector:") } ?: throw McdataCodecError("missing file selector")
            val name = Regex("name:\"([^\"]+)\"").find(selector)?.groupValues?.get(1) ?: throw McdataCodecError("missing file name")
            val type = Regex("(?:^| )type:([^ ]+)").find(selector)?.groupValues?.get(1) ?: "application/octet-stream"
            val size = Regex("(?:^| )size:([^ ]+)").find(selector)?.groupValues?.get(1)?.toLongOrNull()
                ?: throw McdataCodecError("invalid file size")
            if (size < 0 || name.any { it.isISOControl() }) throw McdataCodecError("invalid file metadata")
            val hashValue = Regex("(?:^| )hash:sha-256:([^ ]+)").find(selector)?.groupValues?.get(1)
            val hash = hashValue?.replace(":", "")?.lowercase(Locale.ROOT)
            if (hash != null && !hash.matches(Regex("[a-f0-9]{64}"))) throw McdataCodecError("invalid SHA-256")
            val availability = lines.firstOrNull { it.startsWith("file-availability:") }?.substringAfter(':')?.let {
                it.toLongOrNull() ?: runCatching { OffsetDateTime.parse(it, date).toEpochSecond() }
                    .getOrElse { throw McdataCodecError("invalid file availability") }
            }
            if (availability != null && availability < 0) throw McdataCodecError("negative file availability")
            return FileMetadata(name, type, size, hash, availability)
        }
    }
}
