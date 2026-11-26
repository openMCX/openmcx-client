//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.msrp

import java.io.ByteArrayOutputStream
import java.security.SecureRandom

// MSRP framing for the MCData media plane: URIs, requests and responses, Byte-Range and the end-line.
//
// Specifications: RFC 4975 (MSRP) sections 6, 7 and 9; RFC 6135 (MSRP connection establishment); TS 24.582.

class MsrpError(message: String, val status: Int? = null) : Exception(message)

/** RFC 4975 §6: an `msrp` or `msrps` URI, `msrp[s]://host[:port]/session;transport`. */
data class MsrpUri(val host: String, val port: Int, val session: String, val transport: String = "tcp", val secure: Boolean = false) {
    fun text(): String = (if (secure) "msrps://" else "msrp://") + host + (if (port > 0) ":$port" else "") +
        (if (session.isNotEmpty()) "/$session" else "") + ";" + transport

    /** RFC 4975 §6.1: the session-id is what names this endpoint's session (the comparison used for To-Path). */
    fun sameSession(other: MsrpUri) = session == other.session

    override fun toString() = text()

    companion object {
        fun parse(text: String): MsrpUri {
            var rest = text.trim()
            val secure = when {
                rest.startsWith("msrps://", ignoreCase = true) -> { rest = rest.substring(8); true }
                rest.startsWith("msrp://", ignoreCase = true) -> { rest = rest.substring(7); false }
                else -> throw MsrpError("unsupported MSRP URI scheme: $text")
            }
            val semicolon = rest.lastIndexOf(';')
            if (semicolon < 0 || semicolon == rest.length - 1) throw MsrpError("MSRP URI without a transport: $text")
            val transport = rest.substring(semicolon + 1)
            rest = rest.substring(0, semicolon)
            val slash = rest.indexOf('/')
            val session = if (slash >= 0) rest.substring(slash + 1) else ""
            if (slash >= 0) rest = rest.substring(0, slash)
            var port = 0
            // An IPv6 literal is bracketed (RFC 3986 authority); the port follows the bracket.
            val colon = rest.lastIndexOf(':')
            if (colon > rest.lastIndexOf(']')) {
                port = rest.substring(colon + 1).toIntOrNull()?.takeIf { it in 0..65535 } ?: throw MsrpError("invalid MSRP URI port: $text")
                rest = rest.substring(0, colon)
            }
            if (rest.isEmpty()) throw MsrpError("MSRP URI without a host: $text")
            return MsrpUri(rest, port, session, transport, secure)
        }
    }
}

/** RFC 4975 §9: `Byte-Range: start-end/total`, 1-based and inclusive; null end or total is `*`. */
data class ByteRange(val start: Long, val end: Long?, val total: Long?) {
    fun text() = "$start-${end ?: "*"}/${total ?: "*"}"

    companion object {
        fun parse(value: String): ByteRange {
            val hyphen = value.indexOf('-'); val slash = value.indexOf('/')
            if (hyphen < 0 || slash < hyphen) throw MsrpError("malformed Byte-Range: $value")
            fun number(s: String): Long? = if (s.trim() == "*") null else s.trim().toLongOrNull()?.takeIf { it >= 0 }
                ?: throw MsrpError("malformed Byte-Range: $value")
            val start = number(value.substring(0, hyphen)) ?: throw MsrpError("Byte-Range without a start")
            if (start < 1) throw MsrpError("Byte-Range starts at 1")
            return ByteRange(start, number(value.substring(hyphen + 1, slash)), number(value.substring(slash + 1)))
        }
    }
}

/**
 * One MSRP request (SEND, REPORT) or response (RFC 4975 §7, §9). [continuation] is the end-line flag: `$` the message is
 * complete, `+` more chunks follow, `#` the message is aborted (§7.1.1).
 */
class MsrpMessage(
    val transaction: String,
    val method: String? = null,
    val status: Int = 0,
    val comment: String? = null,
    val toPath: List<MsrpUri>,
    val fromPath: List<MsrpUri>,
    val messageId: String? = null,
    val byteRange: ByteRange? = null,
    val successReport: String? = null,
    val failureReport: String? = null,
    /** The Status header field of a REPORT (RFC 4975 §9: `000 200 OK`). */
    val reportStatus: String? = null,
    val contentType: String? = null,
    val body: ByteArray = ByteArray(0),
    val continuation: Char = '$',
) {
    val isRequest get() = method != null
    val complete get() = continuation == '$'
    val aborted get() = continuation == '#'

    /** The status code of a REPORT's Status header field (`000 200 OK` is 200). */
    val reportCode: Int? get() = reportStatus?.trim()?.split(' ')?.getOrNull(1)?.toIntOrNull()

    fun encode(): ByteArray {
        if (transaction.isEmpty()) throw MsrpError("a message needs a transaction identifier")
        val head = StringBuilder("MSRP ").append(transaction).append(' ')
        if (method != null) head.append(method) else head.append(status).append(if (comment.isNullOrEmpty()) "" else " $comment")
        head.append("\r\nTo-Path: ").append(toPath.joinToString(" ") { it.text() })
            .append("\r\nFrom-Path: ").append(fromPath.joinToString(" ") { it.text() }).append("\r\n")
        messageId?.let { head.append("Message-ID: ").append(it).append("\r\n") }
        successReport?.let { head.append("Success-Report: ").append(it).append("\r\n") }
        failureReport?.let { head.append("Failure-Report: ").append(it).append("\r\n") }
        byteRange?.let { head.append("Byte-Range: ").append(it.text()).append("\r\n") }
        reportStatus?.let { head.append("Status: ").append(it).append("\r\n") }
        val out = ByteArrayOutputStream()
        // RFC 4975 §7.1.1: a SEND with a body names its Content-Type; the body is followed by CRLF before the end-line.
        if (body.isNotEmpty()) {
            if (contentType.isNullOrEmpty()) throw MsrpError("a body needs a content type")
            head.append("Content-Type: ").append(contentType).append("\r\n\r\n")
            out.write(head.toString().toByteArray(Charsets.UTF_8))
            out.write(body)
            out.write("\r\n".toByteArray())
        } else out.write(head.toString().toByteArray(Charsets.UTF_8))
        out.write("-------$transaction$continuation\r\n".toByteArray())
        return out.toByteArray()
    }

    fun response(status: Int, comment: String?, local: MsrpUri) = MsrpMessage(transaction, status = status, comment = comment,
        // RFC 4975 §7.2: a response's To-Path is the request's From-Path's first hop; its From-Path the local URI.
        toPath = listOf(fromPath.first()), fromPath = listOf(local))

    override fun toString() = if (isRequest) "MSRP $transaction $method ${messageId ?: ""} ${byteRange?.text() ?: ""}$continuation"
        else "MSRP $transaction $status ${comment ?: ""}"

    companion object {
        private val random = SecureRandom()
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

        /** RFC 4975 §9 `ident`: transaction, message and session identifiers, 12 random lower-case letters and digits. */
        fun newIdent(length: Int = 12): String = buildString { repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
    }
}

/**
 * The incremental reader of an MSRP byte stream (RFC 4975 §9): feed what arrives, take the complete messages. A message
 * whose end-line has not arrived stays buffered; one larger than [maxMessage] fails the stream.
 */
class MsrpDecoder(private val maxMessage: Int = 8 * 1024 * 1024) {
    private var buffer = ByteArray(16 * 1024)
    private var length = 0

    fun feed(data: ByteArray, count: Int = data.size): List<MsrpMessage> {
        if (length + count > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, length + count))
        System.arraycopy(data, 0, buffer, length, count)
        length += count
        val out = mutableListOf<MsrpMessage>()
        while (true) {
            // RFC 4975 §9: CRLF between messages is tolerated.
            var skip = 0
            while (skip + 1 < length && buffer[skip] == '\r'.code.toByte() && buffer[skip + 1] == '\n'.code.toByte()) skip += 2
            if (skip > 0) consume(skip)
            val used = decodeOne(out)
            if (used == 0) break
            consume(used)
        }
        if (length > maxMessage) throw MsrpError("MSRP message exceeds the accepted size")
        return out
    }

    private fun consume(count: Int) {
        System.arraycopy(buffer, count, buffer, 0, length - count)
        length -= count
    }

    private fun indexOf(pattern: ByteArray, from: Int): Int {
        outer@ for (i in from..length - pattern.size) {
            for (j in pattern.indices) if (buffer[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }

    private fun decodeOne(out: MutableList<MsrpMessage>): Int {
        val crlf = "\r\n".toByteArray()
        val startEnd = indexOf(crlf, 0)
        if (startEnd < 0) {
            if (length > 512) throw MsrpError("MSRP start line too long")
            return 0
        }
        val first = String(buffer, 0, startEnd, Charsets.UTF_8)
        if (!first.startsWith("MSRP ")) throw MsrpError("not an MSRP message: ${first.take(40)}")
        val fields = first.substring(5).split(' ', limit = 3)
        val transaction = fields[0]
        if (transaction.isEmpty() || transaction.length > 32 || fields.size < 2) throw MsrpError("malformed start line")
        val endLine = "\r\n-------$transaction".toByteArray()
        // The end-line of a message without a body follows its last header field's CRLF.
        val end = indexOf(endLine, startEnd)
        if (end < 0) return 0
        val flagAt = end + endLine.size
        if (flagAt >= length) return 0
        val flag = buffer[flagAt].toInt().toChar()
        if (flag != '$' && flag != '+' && flag != '#') throw MsrpError("invalid continuation flag")
        var used = flagAt + 1
        // The CRLF after the flag; one that has not arrived yet is skipped by the next feed.
        if (used + 2 <= length && buffer[used] == '\r'.code.toByte() && buffer[used + 1] == '\n'.code.toByte()) used += 2
        // Headers end at the first empty line; what follows (to the CRLF before the end-line) is the body.
        val headStart = startEnd + 2
        val blank = indexOf("\r\n\r\n".toByteArray(), startEnd).takeIf { it in 0 until end }
        val headerText = if (blank != null) String(buffer, headStart, blank + 2 - headStart, Charsets.UTF_8)
            else if (end > headStart) String(buffer, headStart, end - headStart, Charsets.UTF_8) else ""
        val body = if (blank != null) buffer.copyOfRange(blank + 4, end) else ByteArray(0)
        var to = emptyList<MsrpUri>(); var from = emptyList<MsrpUri>()
        var messageId: String? = null; var range: ByteRange? = null; var success: String? = null; var failure: String? = null
        var status: String? = null; var type: String? = null
        for (line in headerText.split("\r\n")) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val value = line.substring(colon + 1).trim()
            when (line.substring(0, colon).trim().lowercase()) {
                "to-path" -> to = value.split(' ').filter { it.isNotEmpty() }.map(MsrpUri::parse)
                "from-path" -> from = value.split(' ').filter { it.isNotEmpty() }.map(MsrpUri::parse)
                "message-id" -> messageId = value
                "byte-range" -> range = ByteRange.parse(value)
                "success-report" -> success = value
                "failure-report" -> failure = value
                "status" -> status = value
                "content-type" -> type = value
            }
        }
        if (to.isEmpty() || from.isEmpty()) throw MsrpError("a message needs To-Path and From-Path")
        val code = fields[1].toIntOrNull()
        val message = if (code != null) {
            if (code !in 100..999) throw MsrpError("invalid status code")
            MsrpMessage(transaction, status = code, comment = fields.getOrNull(2), toPath = to, fromPath = from)
        } else {
            val method = if (fields.size == 3) fields[1] + " " + fields[2] else fields[1]
            MsrpMessage(transaction, method = method.trim(), toPath = to, fromPath = from, messageId = messageId, byteRange = range,
                successReport = success, failureReport = failure, reportStatus = status, contentType = type, body = body,
                continuation = flag)
        }
        out += message
        return used
    }
}
