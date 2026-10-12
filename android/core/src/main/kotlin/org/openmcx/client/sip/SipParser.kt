//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.sip

import java.io.ByteArrayOutputStream

/** Parse one complete SIP message (RFC 3261 §7), with its body. */
fun parseSipMessage(data: ByteArray): SipMessage {
    val end = indexOf(data, HEADER_END, 0)
    if (end < 0) throw SipParseError("no end of headers")
    val head = String(data, 0, end, Charsets.UTF_8)
    val body = data.copyOfRange(end + 4, data.size)
    return parseHead(head, body)
}

private val HEADER_END = "\r\n\r\n".toByteArray()

private fun parseHead(head: String, body: ByteArray): SipMessage {
    // Unfold continuation lines (RFC 3261 §7.3.1).
    val lines = head.split("\r\n").fold(mutableListOf<String>()) { acc, line ->
        if ((line.startsWith(" ") || line.startsWith("\t")) && acc.isNotEmpty()) acc[acc.lastIndex] += " " + line.trim()
        else acc += line
        acc
    }
    val start = lines.firstOrNull() ?: throw SipParseError("empty message")
    val headers = Headers()
    for (line in lines.drop(1)) {
        if (line.isEmpty()) continue
        val colon = line.indexOf(':')
        if (colon <= 0) throw SipParseError("bad header line: $line")
        headers.add(line.substring(0, colon).trim(), line.substring(colon + 1).trim())
    }
    val declared = headers["Content-Length"]?.trim()?.toIntOrNull()
    val content = if (declared != null && declared <= body.size) body.copyOf(declared) else body
    return if (start.startsWith("SIP/2.0 ")) {
        val parts = start.split(" ", limit = 3)
        val status = parts.getOrNull(1)?.toIntOrNull() ?: throw SipParseError("bad status line: $start")
        SipResponse(status, parts.getOrElse(2) { "" }, headers, content)
    } else {
        val parts = start.split(" ")
        if (parts.size != 3 || parts[2] != "SIP/2.0") throw SipParseError("bad request line: $start")
        SipRequest(parts[0], parts[1], headers, content)
    }
}

/** What a stream framer yields: a message, or a keepalive seen between messages. */
sealed interface Framed {
    data class Message(val message: SipMessage) : Framed
    /** A single CRLF pong (RFC 5626 §4.4.1), or a double-CRLF ping from the peer. */
    data class Keepalive(val ping: Boolean) : Framed
}

/**
 * Frames SIP messages on a stream transport (RFC 3261 §18.3): headers end at CRLFCRLF,
 * Content-Length is mandatory and gives the body length. CRLFs between messages are
 * RFC 5626 keepalives.
 */
class StreamFramer(private val maxMessage: Int = 1 shl 20) {
    private val buffer = ByteArrayOutputStream()

    fun feed(data: ByteArray, length: Int = data.size): List<Framed> {
        buffer.write(data, 0, length)
        val out = mutableListOf<Framed>()
        var bytes = buffer.toByteArray()
        var offset = 0
        while (offset < bytes.size) {
            // Keepalives: a double CRLF is a ping, a single CRLF a pong. A peer writes either in one piece.
            if (bytes[offset] == CR || bytes[offset] == LF) {
                if (startsWith(bytes, offset, PING)) { out += Framed.Keepalive(ping = true); offset += 4; continue }
                if (startsWith(bytes, offset, PONG)) { out += Framed.Keepalive(ping = false); offset += 2; continue }
                if (bytes[offset] == CR && offset + 1 == bytes.size) break
                offset++
                continue
            }
            val end = indexOf(bytes, HEADER_END, offset)
            if (end < 0) {
                if (bytes.size - offset > maxMessage) throw SipParseError("header section too long")
                break
            }
            val head = String(bytes, offset, end - offset, Charsets.UTF_8)
            val length = Regex("(?im)^(?:content-length|l)\\s*:\\s*(\\d+)\\s*$").find(head)?.groupValues?.get(1)?.toInt()
                ?: throw SipParseError("no Content-Length on a stream transport")
            if (length > maxMessage) throw SipParseError("body too long: $length")
            val bodyStart = end + 4
            if (bytes.size - bodyStart < length) break
            out += Framed.Message(parseHead(head, bytes.copyOfRange(bodyStart, bodyStart + length)))
            offset = bodyStart + length
        }
        if (offset > 0) {
            bytes = bytes.copyOfRange(offset, bytes.size)
            buffer.reset()
            buffer.write(bytes)
        }
        return out
    }

    private companion object {
        const val CR = '\r'.code.toByte()
        const val LF = '\n'.code.toByte()
        val PING = "\r\n\r\n".toByteArray()
        val PONG = "\r\n".toByteArray()
    }
}

private fun startsWith(data: ByteArray, offset: Int, prefix: ByteArray): Boolean =
    offset + prefix.size <= data.size && prefix.indices.all { data[offset + it] == prefix[it] }

internal fun indexOf(data: ByteArray, pattern: ByteArray, from: Int): Int {
    outer@ for (i in from..data.size - pattern.size) {
        for (j in pattern.indices) if (data[i + j] != pattern[j]) continue@outer
        return i
    }
    return -1
}
