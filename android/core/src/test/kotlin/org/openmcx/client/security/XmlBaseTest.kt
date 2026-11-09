//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.security

import org.junit.Assert.assertEquals
import org.junit.Test

/** The join-URI-References function of Canonical XML 1.1 clause 2.4 (XmlBase.join(outer, inner)). */
class XmlBaseTest {
    @Test fun examplesOfTheClause() {
        assertEquals("", XmlBase.join("abc/", "../"))
        assertEquals("../../", XmlBase.join("../", "../"))
        assertEquals("../../", XmlBase.join("..", ".."))
        // <a xml:base="foo/bar"><b xml:base=".."><c xml:base=".."><d xml:base="x">, b and c removed: "../../x".
        assertEquals("../../x", XmlBase.join("..", XmlBase.join("..", "x")))
    }

    @Test fun rfc3986WithTheModifications() {
        assertEquals("http://h/a/c/d", XmlBase.join("http://h/a/b", "c/d"))
        assertEquals("http://h/x", XmlBase.join("http://h/a/b/", "../../../x"))
        assertEquals("http://h/z", XmlBase.join("http://h", "z"))
        assertEquals("/a/c", XmlBase.join("/a/b/", "../c"))
        assertEquals("../y", XmlBase.join("x", "../y"))
        assertEquals("../../x", XmlBase.join("../..", "x"))
        assertEquals("a/b/c/", XmlBase.join("a/./b/", "./c/."))
        // Consecutive "/" become one.
        assertEquals("a/b/c", XmlBase.join("a//b/", "c"))
        // Empty reference, query only.
        assertEquals("a/b", XmlBase.join("a/b", ""))
        assertEquals("http://h/a?q", XmlBase.join("http://h/a?q", ""))
        assertEquals("a/b?n", XmlBase.join("a/b", "?n"))
        // R.fragment = null.
        assertEquals("a/b?q", XmlBase.join("a/b?q", "#f"))
        assertEquals("../c", XmlBase.join("a/b", "../../c#x"))
        // An absolute reference: its dot segments removed, the base ignored.
        assertEquals("http://o/p/q", XmlBase.join("a/", "http://o/p/./q"))
        assertEquals("http://o/q", XmlBase.join("http://h/a", "//o/p/../q"))
    }
}
