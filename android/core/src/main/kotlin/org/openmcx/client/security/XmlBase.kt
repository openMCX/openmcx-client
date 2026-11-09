//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.security

/**
 * The "join-URI-References" function of W3C Canonical XML 1.1 (2008) clause 2.4, for the xml:base fix-up: RFC 3986
 * clauses 5.2.1, 5.2.2 (with 5.2.3) and 5.2.4, then 5.3, "with the following modifications":
 * - 5.2.1: "The scheme component is not required in the base URI"; "Replace a trailing ".." segment with "../" segment
 *   before processing."
 * - 5.2.4: "Keep leading "../" segments"; "Replace multiple consecutive "/" characters with a single "/" character.";
 *   "Append a "/" character to a trailing ".." segment".
 * - 5.2.2: "After parsing R set R.fragment = null".
 * The clause's examples: "abc/" and "../" give "", "../" and "../" give "../../", ".." and ".." give "../../".
 */
object XmlBase {
    private class Parts(val scheme: String?, val authority: String?, val path: String, val query: String?)

    /** RFC 3986 appendix B. */
    private val REFERENCE = Regex("^(([^:/?#]+):)?(//([^/?#]*))?([^?#]*)(\\?([^#]*))?(#(.*))?$")

    private fun parse(text: String): Parts {
        val m = REFERENCE.matchEntire(text) ?: throw KeyError("invalid xml:base")
        fun group(i: Int) = m.groups[i]?.value
        return Parts(group(2), if (group(3) != null) group(4) ?: "" else null, group(5) ?: "", if (group(6) != null) group(7) ?: "" else null)
    }

    private fun trailingDotDot(path: String) = path == ".." || path.endsWith("/..")

    /** RFC 3986 clause 5.2.4 with the Canonical XML 1.1 modifications. */
    internal fun removeDotSegments(input: String): String {
        var path = input.replace(Regex("/{2,}"), "/")
        if (trailingDotDot(path)) path += "/"
        val absolute = path.startsWith("/")
        val segments = path.removePrefix("/").split('/')
        val out = ArrayList<String>()
        var trailingSlash = false
        for ((i, s) in segments.withIndex()) {
            val last = i == segments.size - 1
            when (s) {
                "." -> trailingSlash = last
                ".." -> {
                    if (out.isNotEmpty() && out.last() != "..") out.removeAt(out.size - 1)
                    else if (!absolute) out += ".."
                    trailingSlash = last
                }
                "" -> trailingSlash = last && out.isNotEmpty()
                else -> { out += s; trailingSlash = false }
            }
        }
        val body = out.joinToString("/")
        return (if (absolute) "/" else "") + body + (if (trailingSlash && body.isNotEmpty()) "/" else "")
    }

    /** RFC 3986 clause 5.2.3. */
    private fun merge(base: Parts, path: String): String = when {
        base.authority != null && base.path.isEmpty() -> "/$path"
        else -> base.path.substring(0, base.path.lastIndexOf('/') + 1) + path
    }

    /** xml:base [reference] (the inner value) joined with xml:base [base] (the outer one). */
    fun join(base: String, reference: String): String {
        val b = parse(base).let { if (trailingDotDot(it.path)) Parts(it.scheme, it.authority, it.path + "/", it.query) else it }
        val r = parse(reference) // R.fragment is not kept
        val scheme: String?
        val authority: String?
        val path: String
        val query: String?
        if (r.scheme != null) {
            scheme = r.scheme; authority = r.authority; path = removeDotSegments(r.path); query = r.query
        } else {
            scheme = b.scheme
            if (r.authority != null) {
                authority = r.authority; path = removeDotSegments(r.path); query = r.query
            } else {
                authority = b.authority
                if (r.path.isEmpty()) {
                    path = b.path; query = r.query ?: b.query
                } else {
                    path = removeDotSegments(if (r.path.startsWith("/")) r.path else merge(b, r.path)); query = r.query
                }
            }
        }
        // RFC 3986 clause 5.3 (no fragment).
        val out = StringBuilder()
        if (scheme != null) out.append(scheme).append(':')
        if (authority != null) out.append("//").append(authority)
        out.append(path)
        if (query != null) out.append('?').append(query)
        return out.toString()
    }
}
