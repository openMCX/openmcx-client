package org.openmcx.client.sip

/**
 * `;name=value` parameters in order, case-insensitive by name. A parameter without a
 * value (such as `lr`) maps to null.
 */
class Params(entries: List<Pair<String, String?>> = emptyList()) {
    private val list = entries.toMutableList()

    operator fun get(name: String): String? = list.firstOrNull { it.first.equals(name, true) }?.second
    fun has(name: String): Boolean = list.any { it.first.equals(name, true) }
    operator fun set(name: String, value: String?) {
        val index = list.indexOfFirst { it.first.equals(name, true) }
        if (index < 0) list += name to value else list[index] = name to value
    }
    fun remove(name: String) { list.removeAll { it.first.equals(name, true) } }
    fun copy() = Params(list.toList())
    val entries: List<Pair<String, String?>> get() = list.toList()

    override fun toString(): String = list.joinToString("") { (k, v) -> if (v == null) ";$k" else ";$k=$v" }

    companion object {
        /** Parse `;a=b;c` (the leading semicolon optional), respecting quoted values. */
        fun parse(text: String): Params {
            val params = Params()
            for (part in splitOutsideQuotes(text, ';')) {
                val item = part.trim()
                if (item.isEmpty()) continue
                val eq = item.indexOf('=')
                if (eq < 0) params[item] = null else params[item.substring(0, eq).trim()] = item.substring(eq + 1).trim()
            }
            return params
        }
    }
}

internal fun splitOutsideQuotes(text: String, separator: Char): List<String> {
    val parts = mutableListOf<String>()
    var quoted = false
    var start = 0
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        if (ch == '\\' && quoted) i++
        else if (ch == '"') quoted = !quoted
        else if (ch == separator && !quoted) { parts += text.substring(start, i); start = i + 1 }
        i++
    }
    parts += text.substring(start)
    return parts
}

/** A SIP or SIPS URI (RFC 3261 §19.1). Other schemes are kept whole in [host] with an empty [scheme] user part. */
data class SipUri(
    val scheme: String = "sip",
    val user: String? = null,
    val host: String,
    val port: Int? = null,
    val params: Params = Params(),
    val headers: String? = null,
) {
    /** `user@host` without port or parameters: the address-of-record form used for identity comparison. */
    val aor: String get() = if (user == null) "$scheme:$host" else "$scheme:$user@$host"

    fun withParam(name: String, value: String?): SipUri = copy(params = params.copy().also { it[name] = value })
    fun withoutParam(name: String): SipUri = copy(params = params.copy().also { it.remove(name) })

    override fun toString(): String {
        val sb = StringBuilder(scheme).append(':')
        if (user != null) sb.append(user).append('@')
        sb.append(host)
        if (port != null) sb.append(':').append(port)
        sb.append(params.toString())
        if (headers != null) sb.append('?').append(headers)
        return sb.toString()
    }

    companion object {
        fun parse(text: String): SipUri {
            val colon = text.indexOf(':')
            if (colon <= 0) throw SipParseError("bad URI: $text")
            val scheme = text.substring(0, colon).lowercase()
            var rest = text.substring(colon + 1)
            if (scheme != "sip" && scheme != "sips") return SipUri(scheme = scheme, host = rest)
            var headers: String? = null
            rest.indexOf('?').takeIf { it >= 0 }?.let { headers = rest.substring(it + 1); rest = rest.substring(0, it) }
            val at = rest.lastIndexOf('@')
            val user = if (at >= 0) rest.substring(0, at) else null
            rest = rest.substring(at + 1)
            val semi = rest.indexOf(';')
            val hostPort = if (semi >= 0) rest.substring(0, semi) else rest
            val params = if (semi >= 0) Params.parse(rest.substring(semi + 1)) else Params()
            val (host, port) = splitHostPort(hostPort)
            return SipUri(scheme, user, host, port, params, headers)
        }

        internal fun splitHostPort(text: String): Pair<String, Int?> {
            if (text.startsWith("[")) {
                val end = text.indexOf(']')
                val port = text.substring(end + 1).removePrefix(":").toIntOrNull()
                return text.substring(0, end + 1) to port
            }
            val colon = text.lastIndexOf(':')
            return if (colon >= 0) text.substring(0, colon) to text.substring(colon + 1).toIntOrNull()
            else text to null
        }
    }
}

/**
 * A name-addr or addr-spec with header parameters (From, To, Contact, Route, …).
 * [uri] is kept as text so non-SIP URIs (`urn:`, `tel:`) survive unchanged.
 */
data class NameAddr(val uri: String, val display: String? = null, val params: Params = Params()) {
    val tag: String? get() = params["tag"]
    val sipUri: SipUri get() = SipUri.parse(uri)

    fun withTag(tag: String) = copy(params = params.copy().also { it["tag"] = tag })
    fun withParam(name: String, value: String?) = copy(params = params.copy().also { it[name] = value })

    override fun toString(): String {
        val name = display?.let { "\"${it.replace("\"", "\\\"")}\" " } ?: ""
        return "$name<$uri>$params"
    }

    companion object {
        fun parse(text: String): NameAddr {
            val value = text.trim()
            val lt = indexOutsideQuotes(value, '<')
            if (lt >= 0) {
                val gt = value.indexOf('>', lt)
                if (gt < 0) throw SipParseError("bad name-addr: $text")
                val display = value.substring(0, lt).trim().removeSurrounding("\"").replace("\\\"", "\"").ifEmpty { null }
                return NameAddr(value.substring(lt + 1, gt), display, Params.parse(value.substring(gt + 1)))
            }
            // addr-spec: parameters after the URI belong to the header (RFC 3261 §20.10).
            val semi = value.indexOf(';')
            return if (semi < 0) NameAddr(value) else NameAddr(value.substring(0, semi), null, Params.parse(value.substring(semi + 1)))
        }

        private fun indexOutsideQuotes(text: String, target: Char): Int {
            var quoted = false
            text.forEachIndexed { i, ch ->
                if (ch == '"') quoted = !quoted
                else if (ch == target && !quoted) return i
            }
            return -1
        }
    }
}

/** A Via header value (RFC 3261 §20.42). */
data class Via(val transport: String, val host: String, val port: Int?, val params: Params) {
    val branch: String? get() = params["branch"]

    override fun toString(): String = "SIP/2.0/$transport $host${port?.let { ":$it" } ?: ""}$params"

    companion object {
        fun parse(text: String): Via {
            val value = text.trim()
            val slash = Regex("^SIP\\s*/\\s*2\\.0\\s*/\\s*([A-Za-z]+)\\s+", RegexOption.IGNORE_CASE).find(value)
                ?: throw SipParseError("bad Via: $text")
            val rest = value.substring(slash.range.last + 1)
            val semi = rest.indexOf(';')
            val sentBy = (if (semi >= 0) rest.substring(0, semi) else rest).trim()
            val (host, port) = SipUri.splitHostPort(sentBy)
            return Via(slash.groupValues[1].uppercase(), host, port, if (semi >= 0) Params.parse(rest.substring(semi + 1)) else Params())
        }
    }
}
