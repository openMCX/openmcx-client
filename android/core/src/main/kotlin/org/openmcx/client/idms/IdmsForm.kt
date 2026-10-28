//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.idms

/** One submitted control of an HTML form (HTML 4.01 §17.13.2): its name, its type (lower case) and its value. */
data class HtmlFormInput(val name: String, val type: String, val value: String)

/** HTML 4.01 §17.3: a form's action (as written, unresolved), its method (lower case) and its inputs. */
data class HtmlForm(val action: String, val method: String, val inputs: List<HtmlFormInput>)

/**
 * The user authentication form of an IdMS page (TS 24.482 §6.2.1, TS 37.579-1 table 5.3.2.3.2-5), read as the Linux
 * client reads it (openmcx-server src/security/http.cpp parse_html_form, html_form_fields, resolve_https_reference).
 * Strict: a page with more than one form, a control this client cannot fill or an unterminated tag is refused.
 */
object IdmsForm {
    private fun space(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C'

    private val named = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'")

    /** HTML 4.01 §5.3: the numeric character references and the five named ones decoded; any other "&" stays. */
    fun unescape(text: String): String {
        val out = StringBuilder()
        var at = 0
        while (at < text.length) {
            val semicolon = if (text[at] == '&') text.indexOf(';', at) else -1
            if (semicolon < 0 || semicolon - at > 10) {
                out.append(text[at++])
                continue
            }
            val name = text.substring(at + 1, semicolon)
            val plain = named[name]
            if (plain != null) {
                out.append(plain)
            } else if (name.length > 1 && name[0] == '#') {
                val hex = name[1] == 'x' || name[1] == 'X'
                val digits = name.substring(if (hex) 2 else 1)
                val code = digits.toIntOrNull(if (hex) 16 else 10)?.takeIf { digits.all { it.isLetterOrDigit() } }
                if (code == null || code == 0 || code > 0x10FFFF || code in 0xD800..0xDFFF)
                    throw IdmsError("invalid HTML character reference")
                out.appendCodePoint(code)
            } else {
                out.append(text[at++])
                continue
            }
            at = semicolon + 1
        }
        return out.toString()
    }

    /** HTML 4.01 §17: the one form of [html], or null when the page has none. */
    fun parse(html: String): HtmlForm? {
        var found: HtmlForm? = null
        val inputs = mutableListOf<HtmlFormInput>()
        var inside = false
        var at = html.indexOf('<')
        while (at >= 0) {
            if (html.startsWith("<!--", at)) {
                val end = html.indexOf("-->", at + 4)
                if (end < 0) throw IdmsError("unterminated HTML comment")
                at = html.indexOf('<', end + 3)
                continue
            }
            if (at + 1 >= html.length || !(html[at + 1].isLetter() || html[at + 1] == '/')) {
                at = html.indexOf('<', at + 1)
                continue
            }
            if (html[at + 1] == '/') {
                val end = html.indexOf('>', at)
                if (end < 0) throw IdmsError("unterminated HTML tag")
                if (html.substring(at + 2, end).trimEnd().takeWhile { !space(it) }.lowercase() == "form") inside = false
                at = html.indexOf('<', end + 1)
                continue
            }
            val (element, attributes, next) = tag(html, at)
            at = next
            if (element == "script" || element == "style") {
                // HTML 4.01 §6.2: script and style content is not markup.
                val end = html.lowercase().indexOf("</$element", at)
                if (end < 0) throw IdmsError("unterminated HTML $element")
                at = end
                continue
            }
            if (element == "form") {
                if (found != null || inside) throw IdmsError("more than one HTML form")
                found = HtmlForm(attributes["action"] ?: "", (attributes["method"] ?: "get").lowercase(), inputs)
                inside = true
            } else if (inside && (element == "select" || element == "textarea")) {
                throw IdmsError("unsupported HTML form control: $element")
            } else if (inside && element == "input") {
                val type = (attributes["type"] ?: "text").lowercase()
                if (type !in setOf("submit", "button", "reset", "image")) {
                    if (type !in setOf("text", "email", "password", "hidden", "checkbox", "radio"))
                        throw IdmsError("unsupported HTML input type: $type")
                    val name = attributes["name"] ?: ""
                    val choice = type == "checkbox" || type == "radio"
                    // HTML 4.01 §17.13.2: an unnamed, disabled or unchecked control is not submitted.
                    if (name.isNotEmpty() && "disabled" !in attributes && (!choice || "checked" in attributes))
                        inputs += HtmlFormInput(name, type, attributes["value"] ?: if (choice) "on" else "")
                }
            }
            at = html.indexOf('<', at)
        }
        if (inside) throw IdmsError("unterminated HTML form")
        return found
    }

    /**
     * Helper: the start tag at [start] (its "<"): the lower-case element name, its attributes (lower-case names,
     * decoded values; a repeated attribute keeps its first value, HTML 4.01 §3.2.2) and the index past its ">".
     */
    private fun tag(html: String, start: Int): Triple<String, Map<String, String>, Int> {
        var index = start + 1
        fun word(): String {
            val from = index
            while (index < html.length && !space(html[index]) && html[index] !in ">/=") index++
            return html.substring(from, index).lowercase()
        }
        fun skip() {
            while (index < html.length && (space(html[index]) || html[index] == '/')) index++
        }
        val element = word()
        val attributes = mutableMapOf<String, String>()
        skip()
        while (index < html.length && html[index] != '>') {
            val name = word()
            if (name.isEmpty()) throw IdmsError("invalid HTML attribute")
            var value = ""
            while (index < html.length && space(html[index])) index++
            if (index < html.length && html[index] == '=') {
                index++
                while (index < html.length && space(html[index])) index++
                if (index < html.length && (html[index] == '"' || html[index] == '\'')) {
                    val close = html.indexOf(html[index], index + 1)
                    if (close < 0) throw IdmsError("unterminated HTML attribute value")
                    value = unescape(html.substring(index + 1, close))
                    index = close + 1
                } else {
                    val from = index
                    while (index < html.length && !space(html[index]) && html[index] != '>') index++
                    value = unescape(html.substring(from, index))
                }
            }
            attributes.putIfAbsent(name, value)
            skip()
        }
        if (index >= html.length) throw IdmsError("unterminated HTML tag")
        return Triple(element, attributes, index + 1)
    }

    /**
     * HTML 4.01 §17.13.3 and TS 37.579-1 table 5.5.4.3-1 (condition USERAUTH): the hidden and checked inputs as they
     * are, the user name in the one text or email input, the password in the one password input.
     */
    fun fields(form: HtmlForm, user: String, password: String): Map<String, String> {
        val fields = linkedMapOf<String, String>()
        var users = 0
        var passwords = 0
        for (input in form.inputs) {
            if (input.name in fields) throw IdmsError("HTML form control name repeated: ${input.name}")
            fields[input.name] = when (input.type) {
                "password" -> password.also { passwords++ }
                "text", "email" -> user.also { users++ }
                else -> input.value
            }
        }
        if (users != 1 || passwords != 1) throw IdmsError("the IdMS form has no single user name and password input")
        return fields
    }

    /** RFC 3986 §5.2.2 (strict parser) for the references a form action uses; the base is an https URI. */
    fun resolve(base: String, reference: String): String {
        if (!base.startsWith("https://")) throw IdmsError("HTTPS base required")
        val target = reference.substringBefore('#')
        val authorityEnd = base.indexOfAny(charArrayOf('/', '?', '#'), 8).let { if (it < 0) base.length else it }
        val origin = base.substring(0, authorityEnd)
        val pathAndQuery = (if (authorityEnd == base.length) "/" else base.substring(authorityEnd)).substringBefore('#')
        val path = pathAndQuery.substringBefore('?')
        if (target.any { it in " \t\r\n\\" }) throw IdmsError("invalid form action")
        val colon = target.indexOf(':')
        val slashOrQuery = target.indexOfAny(charArrayOf('/', '?')).let { if (it < 0) Int.MAX_VALUE else it }
        val resolved = when {
            target.isEmpty() -> origin + pathAndQuery
            target.lowercase().startsWith("https://") -> target
            target.startsWith("//") -> "https:$target"
            colon >= 0 && colon < slashOrQuery -> throw IdmsError("form action of another scheme")
            target.startsWith("/") -> origin + target
            target.startsWith("?") -> origin + path + target
            else -> origin + path.substring(0, path.lastIndexOf('/') + 1) + target
        }
        // Dot segments (RFC 3986 §5.2.4) are refused rather than removed.
        val resolvedPath = resolved.substringBefore('?')
        var dot = resolvedPath.indexOf("/.", 8)
        while (dot >= 0) {
            val rest = resolvedPath.substring(dot + 1)
            if (rest == "." || rest == ".." || rest.startsWith("./") || rest.startsWith("../"))
                throw IdmsError("dot segment in form action")
            dot = resolvedPath.indexOf("/.", dot + 1)
        }
        return resolved
    }

    /**
     * RFC 6265 §5.4: the Cookie header field of the next request from the Set-Cookie fields of a response (the
     * name=value of each); empty when it set none. The IdMS may keep the authentication request in a cookie.
     */
    fun cookies(headers: Map<String, List<String>>): String =
        headers.filterKeys { it.equals("set-cookie", ignoreCase = true) }.values.flatten()
            .flatMap { it.split('\n') }.map { it.substringBefore(';') }
            .filter { '=' in it && it.none { c -> c == '\r' || c == '\n' } }.joinToString("; ")
}
