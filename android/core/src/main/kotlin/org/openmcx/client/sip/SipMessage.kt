package org.openmcx.client.sip

/** RFC 3261 §7.3.3 compact header forms, and the full names they stand for. */
private val COMPACT = mapOf(
    "i" to "Call-ID", "m" to "Contact", "e" to "Content-Encoding", "l" to "Content-Length",
    "c" to "Content-Type", "f" to "From", "s" to "Subject", "k" to "Supported", "t" to "To",
    "v" to "Via", "o" to "Event", "u" to "Allow-Events", "r" to "Refer-To", "b" to "Referred-By",
    "x" to "Session-Expires", "a" to "Accept-Contact", "j" to "Reject-Contact", "d" to "Request-Disposition",
)

/** The canonical form of a header name: the full name for a compact form, else as given. */
fun canonicalHeaderName(name: String): String = COMPACT[name.lowercase()] ?: name

/**
 * An ordered, case-insensitive SIP header list. A header that appears several times, or
 * carries a comma-separated list, is kept as the lines it arrived in.
 */
class Headers(entries: List<Pair<String, String>> = emptyList()) : Iterable<Pair<String, String>> {
    private val list = entries.map { canonicalHeaderName(it.first) to it.second }.toMutableList()

    /** The first line of [name], if any. */
    operator fun get(name: String): String? = list.firstOrNull { it.first.equals(canonicalHeaderName(name), true) }?.second

    /** Every line of [name], in order. */
    fun all(name: String): List<String> = list.filter { it.first.equals(canonicalHeaderName(name), true) }.map { it.second }

    /** Every value of [name], splitting each line at its top-level commas (RFC 3261 §7.3.1). */
    fun values(name: String): List<String> = all(name).flatMap { splitList(it) }

    fun contains(name: String): Boolean = get(name) != null

    fun add(name: String, value: String): Headers = apply { list += canonicalHeaderName(name) to value }

    /** Insert [name] before the existing lines (for Via and Route). */
    fun prepend(name: String, value: String): Headers = apply { list.add(0, canonicalHeaderName(name) to value) }

    /** Replace every line of [name] with one line, at the position of the first. */
    fun set(name: String, value: String): Headers = apply {
        val key = canonicalHeaderName(name)
        val index = list.indexOfFirst { it.first.equals(key, true) }
        remove(key)
        if (index < 0) list += key to value else list.add(index.coerceAtMost(list.size), key to value)
    }

    fun remove(name: String): Headers = apply { list.removeAll { it.first.equals(canonicalHeaderName(name), true) } }

    /** Remove the first value of [name] (the topmost Via or Route). */
    fun removeFirstValue(name: String): Headers = apply {
        val key = canonicalHeaderName(name)
        val index = list.indexOfFirst { it.first.equals(key, true) }
        if (index < 0) return@apply
        val rest = splitList(list[index].second).drop(1)
        if (rest.isEmpty()) list.removeAt(index) else list[index] = key to rest.joinToString(", ")
    }

    fun copy(): Headers = Headers(list.toList())

    override fun iterator(): Iterator<Pair<String, String>> = list.toList().iterator()

    companion object {
        /** Split a header value at commas outside quotes and angle brackets. */
        fun splitList(value: String): List<String> {
            val parts = mutableListOf<String>()
            var depth = 0
            var quoted = false
            var start = 0
            var i = 0
            while (i < value.length) {
                when (value[i]) {
                    '\\' -> if (quoted) i++
                    '"' -> quoted = !quoted
                    '<' -> if (!quoted) depth++
                    '>' -> if (!quoted && depth > 0) depth--
                    ',' -> if (!quoted && depth == 0) {
                        parts += value.substring(start, i).trim(); start = i + 1
                    }
                }
                i++
            }
            parts += value.substring(start).trim()
            return parts.filter { it.isNotEmpty() }
        }
    }
}

/** A SIP request or response (RFC 3261 §7). */
sealed class SipMessage(val headers: Headers, val body: ByteArray) {
    val callId: String get() = headers["Call-ID"] ?: throw SipParseError("no Call-ID")
    val cseq: CSeq get() = CSeq.parse(headers["CSeq"] ?: throw SipParseError("no CSeq"))
    val from: NameAddr get() = NameAddr.parse(headers["From"] ?: throw SipParseError("no From"))
    val to: NameAddr get() = NameAddr.parse(headers["To"] ?: throw SipParseError("no To"))
    val topVia: Via get() = Via.parse(headers.values("Via").firstOrNull() ?: throw SipParseError("no Via"))
    val contentType: String? get() = headers["Content-Type"]

    abstract fun startLine(): String

    fun encode(): ByteArray {
        val text = StringBuilder(startLine()).append("\r\n")
        for ((name, value) in headers) {
            if (!name.equals("Content-Length", true)) text.append(name).append(": ").append(value).append("\r\n")
        }
        text.append("Content-Length: ").append(body.size).append("\r\n\r\n")
        return text.toString().toByteArray(Charsets.UTF_8) + body
    }

    override fun toString(): String = String(encode(), Charsets.UTF_8)
}

class SipRequest(
    val method: String,
    val uri: String,
    headers: Headers = Headers(),
    body: ByteArray = ByteArray(0),
) : SipMessage(headers, body) {
    override fun startLine() = "$method $uri SIP/2.0"
}

class SipResponse(
    val status: Int,
    val reason: String,
    headers: Headers = Headers(),
    body: ByteArray = ByteArray(0),
) : SipMessage(headers, body) {
    override fun startLine() = "SIP/2.0 $status $reason"
    val isProvisional get() = status in 100..199
    val isSuccess get() = status in 200..299
    val isFinal get() = status >= 200
}

class SipParseError(message: String) : Exception(message)

data class CSeq(val number: Long, val method: String) {
    override fun toString() = "$number $method"

    companion object {
        fun parse(value: String): CSeq {
            val parts = value.trim().split(Regex("\\s+"))
            if (parts.size != 2) throw SipParseError("bad CSeq: $value")
            return CSeq(parts[0].toLongOrNull() ?: throw SipParseError("bad CSeq: $value"), parts[1])
        }
    }
}

/** Standard reason phrases for the responses this client sends. */
fun reasonPhrase(status: Int): String = when (status) {
    100 -> "Trying"; 180 -> "Ringing"; 183 -> "Session Progress"
    200 -> "OK"; 202 -> "Accepted"
    400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
    415 -> "Unsupported Media Type"; 420 -> "Bad Extension"; 480 -> "Temporarily Unavailable"
    481 -> "Call/Transaction Does Not Exist"; 486 -> "Busy Here"; 487 -> "Request Terminated"
    488 -> "Not Acceptable Here"; 491 -> "Request Pending"; 500 -> "Server Internal Error"
    503 -> "Service Unavailable"; 603 -> "Decline"
    else -> "Unknown"
}
