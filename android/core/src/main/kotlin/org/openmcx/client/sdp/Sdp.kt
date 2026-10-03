package org.openmcx.client.sdp

/** One media description (RFC 8866 §5.14) with its connection and attributes. */
data class MediaDescription(
    val media: String,
    val port: Int,
    val proto: String,
    val formats: List<String>,
    val connection: String? = null,
    val attributes: List<Pair<String, String?>> = emptyList(),
    val info: String? = null,
) {
    fun attribute(name: String): String? = attributes.firstOrNull { it.first == name }?.second
    fun attributes(name: String): List<String> = attributes.filter { it.first == name }.mapNotNull { it.second }
    fun has(name: String): Boolean = attributes.any { it.first == name }

    /** `a=rtpmap:<pt> <encoding>` entries: payload type to encoding (`AMR-WB/16000/1`). */
    val rtpmap: Map<Int, String> get() = attributes("rtpmap").mapNotNull { v ->
        val sp = v.indexOf(' ')
        if (sp < 0) null else v.substring(0, sp).toIntOrNull()?.let { it to v.substring(sp + 1).trim() }
    }.toMap()

    /** `a=fmtp:<format> <params>` for [format]. */
    fun fmtp(format: String): String? = attributes("fmtp").firstOrNull { it.substringBefore(' ') == format }?.substringAfter(' ', "")

    /** The RTCP port: `a=rtcp` (RFC 3605) or the next port up. */
    val rtcpPort: Int get() = attribute("rtcp")?.substringBefore(' ')?.trim()?.toIntOrNull() ?: (port + 1)

    /** send/receive direction (RFC 8866 §6.7), sendrecv by default. */
    val direction: String get() = attributes.map { it.first }.lastOrNull { it in DIRECTIONS } ?: "sendrecv"

    companion object {
        val DIRECTIONS = setOf("sendrecv", "sendonly", "recvonly", "inactive")
    }
}

/** A session description (RFC 8866). */
data class SessionDescription(
    val origin: String,
    val sessionName: String = "-",
    val connection: String? = null,
    val attributes: List<Pair<String, String?>> = emptyList(),
    val media: List<MediaDescription> = emptyList(),
) {
    /** The address a media stream is sent to: its own `c=` or the session's. */
    fun address(m: MediaDescription): String? = (m.connection ?: connection)?.split(' ')?.getOrNull(2)

    fun encode(): String = buildString {
        append("v=0\r\n")
        append("o=").append(origin).append("\r\n")
        append("s=").append(sessionName).append("\r\n")
        connection?.let { append("c=").append(it).append("\r\n") }
        append("t=0 0\r\n")
        attributes.forEach { append(attributeLine(it)) }
        for (m in media) {
            append("m=").append(m.media).append(' ').append(m.port).append(' ').append(m.proto)
            m.formats.forEach { append(' ').append(it) }
            append("\r\n")
            m.info?.let { append("i=").append(it).append("\r\n") }
            m.connection?.let { append("c=").append(it).append("\r\n") }
            m.attributes.forEach { append(attributeLine(it)) }
        }
    }

    private fun attributeLine(a: Pair<String, String?>) = if (a.second == null) "a=${a.first}\r\n" else "a=${a.first}:${a.second}\r\n"

    companion object {
        fun parse(text: String): SessionDescription {
            var origin = ""
            var name = "-"
            var connection: String? = null
            val attributes = mutableListOf<Pair<String, String?>>()
            val media = mutableListOf<MediaDescription>()
            var current: MediaDescription? = null
            fun flush() { current?.let { media += it }; current = null }
            for (raw in text.split('\n')) {
                val line = raw.trimEnd('\r')
                if (line.length < 2 || line[1] != '=') continue
                val value = line.substring(2)
                when (line[0]) {
                    'o' -> origin = value
                    's' -> name = value
                    'c' -> if (current != null) current = current!!.copy(connection = value) else connection = value
                    'i' -> current = current?.copy(info = value)
                    'a' -> {
                        val colon = value.indexOf(':')
                        val attr = if (colon < 0) value to null else value.substring(0, colon) to value.substring(colon + 1)
                        if (current != null) current = current!!.copy(attributes = current!!.attributes + attr) else attributes += attr
                    }
                    'm' -> {
                        flush()
                        val parts = value.split(' ').filter { it.isNotEmpty() }
                        if (parts.size >= 3) {
                            current = MediaDescription(parts[0], parts[1].substringBefore('/').toIntOrNull() ?: 0, parts[2], parts.drop(3))
                        }
                    }
                }
            }
            flush()
            return SessionDescription(origin, name, connection, attributes, media)
        }
    }
}
