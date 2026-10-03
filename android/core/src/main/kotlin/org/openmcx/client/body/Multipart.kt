package org.openmcx.client.body

import org.openmcx.client.sip.indexOf
import java.security.SecureRandom

/** One body part (RFC 2046 §5.1): its headers and content. */
data class Part(val contentType: String, val content: ByteArray, val headers: List<Pair<String, String>> = emptyList()) {
    /** The media type without parameters, lower case. */
    val mediaType: String get() = contentType.substringBefore(';').trim().lowercase()
    val text: String get() = String(content, Charsets.UTF_8)

    override fun equals(other: Any?) = other is Part && contentType == other.contentType &&
        content.contentEquals(other.content) && headers == other.headers
    override fun hashCode() = contentType.hashCode() * 31 + content.contentHashCode()
}

/** multipart/mixed bodies (RFC 2046 §5.1), as SIP carries them (RFC 5621). */
object Multipart {
    /** Encode [parts]; returns the Content-Type (with boundary) and the body. */
    fun encode(parts: List<Part>, boundary: String = newBoundary()): Pair<String, ByteArray> {
        val out = java.io.ByteArrayOutputStream()
        for (part in parts) {
            out.write("--$boundary\r\nContent-Type: ${part.contentType}\r\n".toByteArray())
            for ((k, v) in part.headers) out.write("$k: $v\r\n".toByteArray())
            out.write("\r\n".toByteArray())
            out.write(part.content)
            out.write("\r\n".toByteArray())
        }
        out.write("--$boundary--\r\n".toByteArray())
        return "multipart/mixed; boundary=$boundary" to out.toByteArray()
    }

    /** The parts of a body with [contentType]; a non-multipart body is one part. */
    fun parse(contentType: String?, body: ByteArray): List<Part> {
        if (contentType == null) return if (body.isEmpty()) emptyList() else listOf(Part("application/octet-stream", body))
        if (!contentType.trim().lowercase().startsWith("multipart/")) return listOf(Part(contentType.trim(), body))
        val boundary = Regex("boundary\\s*=\\s*(\"[^\"]+\"|[^;\\s]+)", RegexOption.IGNORE_CASE).find(contentType)
            ?.groupValues?.get(1)?.removeSurrounding("\"") ?: throw IllegalArgumentException("multipart without boundary")
        val delimiter = "--$boundary".toByteArray()
        val parts = mutableListOf<Part>()
        var start = indexOf(body, delimiter, 0)
        while (start >= 0) {
            var cursor = start + delimiter.size
            if (cursor + 1 < body.size && body[cursor] == '-'.code.toByte() && body[cursor + 1] == '-'.code.toByte()) break
            cursor = skipLine(body, cursor)
            val next = indexOf(body, "\r\n--$boundary".toByteArray(), cursor)
            val end = if (next >= 0) next else body.size
            parts += parsePart(body.copyOfRange(cursor, end))
            start = if (next >= 0) next + 2 else -1
        }
        return parts
    }

    private fun parsePart(data: ByteArray): Part {
        val split = indexOf(data, "\r\n\r\n".toByteArray(), 0)
        val (head, content) = if (split >= 0) String(data, 0, split, Charsets.UTF_8) to data.copyOfRange(split + 4, data.size)
        else if (data.size >= 2 && data[0] == '\r'.code.toByte() && data[1] == '\n'.code.toByte()) "" to data.copyOfRange(2, data.size)
        else String(data, Charsets.UTF_8) to ByteArray(0)
        var type = "text/plain"
        val headers = mutableListOf<Pair<String, String>>()
        for (line in head.split("\r\n")) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            if (name.equals("Content-Type", true)) type = value else headers += name to value
        }
        return Part(type, content, headers)
    }

    private fun skipLine(data: ByteArray, from: Int): Int {
        var i = from
        while (i < data.size && data[i] != '\n'.code.toByte()) i++
        return (i + 1).coerceAtMost(data.size)
    }

    private fun newBoundary(): String = "mcx" + ByteArray(12).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
}
