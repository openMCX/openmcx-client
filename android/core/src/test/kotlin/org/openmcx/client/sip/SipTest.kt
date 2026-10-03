package org.openmcx.client.sip

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SipTest {
    private val invite = (
        "INVITE sip:bob@biloxi.com SIP/2.0\r\n" +
            "Via: SIP/2.0/TLS pc33.atlanta.com;branch=z9hG4bK776asdhds, SIP/2.0/TLS proxy;branch=z9hG4bKx\r\n" +
            "Max-Forwards: 70\r\n" +
            "To: Bob <sip:bob@biloxi.com>\r\n" +
            "f: \"Alice, A.\" <sip:alice@atlanta.com>;tag=1928301774\r\n" +
            "i: a84b4c76e66710@pc33.atlanta.com\r\n" +
            "CSeq: 314159 INVITE\r\n" +
            "Contact: <sip:alice@pc33.atlanta.com;transport=tls>;+sip.instance=\"<urn:uuid:00000000-0000-1000-8000-000A95A0E128>\"\r\n" +
            "Subject: folded\r\n  continuation\r\n" +
            "Content-Type: application/sdp\r\n" +
            "Content-Length: 4\r\n\r\nv=0\n"
        ).toByteArray()

    @Test
    fun parsesRequest() {
        val m = parseSipMessage(invite) as SipRequest
        assertEquals("INVITE", m.method)
        assertEquals("sip:bob@biloxi.com", m.uri)
        assertEquals("a84b4c76e66710@pc33.atlanta.com", m.callId)
        assertEquals(CSeq(314159, "INVITE"), m.cseq)
        assertEquals("Alice, A.", m.from.display)
        assertEquals("1928301774", m.from.tag)
        assertNull(m.to.tag)
        assertEquals(2, m.headers.values("Via").size)
        assertEquals("z9hG4bK776asdhds", m.topVia.branch)
        assertEquals("TLS", m.topVia.transport)
        assertEquals("folded continuation", m.headers["Subject"])
        assertEquals("<urn:uuid:00000000-0000-1000-8000-000A95A0E128>",
            NameAddr.parse(m.headers["Contact"]!!).params["+sip.instance"]!!.removeSurrounding("\""))
        assertArrayEquals("v=0\n".toByteArray(), m.body)
    }

    @Test
    fun roundTripsThroughEncode() {
        val m = parseSipMessage(invite)
        val again = parseSipMessage(m.encode())
        assertEquals(m.startLine(), again.startLine())
        assertEquals(m.headers.toList(), again.headers.toList())
        assertArrayEquals(m.body, again.body)
    }

    @Test
    fun framesSplitAndKeepalives() {
        val framer = StreamFramer()
        val stream = "\r\n".toByteArray() + invite + "\r\n\r\n".toByteArray() + invite
        val out = mutableListOf<Framed>()
        for (chunk in stream.toList().chunked(7)) out += framer.feed(chunk.toByteArray())
        assertEquals(listOf("pong", "msg", "ping", "msg"), out.map {
            when (it) { is Framed.Message -> "msg"; is Framed.Keepalive -> if (it.ping) "ping" else "pong" }
        })
    }

    @Test
    fun framesBodyWithCrlf() {
        val body = "a\r\n\r\nb".toByteArray()
        val msg = "MESSAGE sip:x@y SIP/2.0\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body
        val out = StreamFramer().feed(msg)
        assertEquals(1, out.size)
        assertArrayEquals(body, (out[0] as Framed.Message).message.body)
    }

    @Test
    fun splitsListsOutsideQuotesAndBrackets() {
        assertEquals(listOf("\"a,b\" <sip:x;p=1,2>", "<sip:y>"), Headers.splitList("\"a,b\" <sip:x;p=1,2>, <sip:y>"))
    }

    @Test
    fun parsesUris() {
        val u = SipUri.parse("sip:alice@10.0.0.1:5061;transport=tls;ob?Subject=x")
        assertEquals("alice", u.user); assertEquals("10.0.0.1", u.host); assertEquals(5061, u.port)
        assertEquals("tls", u.params["transport"]); assertTrue(u.params.has("ob")); assertEquals("Subject=x", u.headers)
        assertEquals("sip:alice@10.0.0.1:5061;transport=tls;ob?Subject=x", u.toString())
        assertEquals("sip:mcptt@mcx.test", SipUri.parse("sip:mcptt@mcx.test;lr").aor)
        val v6 = SipUri.parse("sip:[2001:db8::1]:5060")
        assertEquals("[2001:db8::1]", v6.host); assertEquals(5060, v6.port)
    }

    @Test
    fun addrSpecParamsBelongToTheHeader() {
        val a = NameAddr.parse("sip:bob@biloxi.com;tag=abc")
        assertEquals("sip:bob@biloxi.com", a.uri); assertEquals("abc", a.tag)
    }

    /** RFC 2617 §3.5 (MD5, qop=auth). */
    @Test
    fun digestRfc2617() {
        val c = DigestChallenge.parse("Digest realm=\"testrealm@host.com\", qop=\"auth,auth-int\", " +
            "nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"")
        val header = DigestAuth("Mufasa", "Circle Of Life").authorization(c, "GET", "/dir/index.html", cnonce = "0a4f113b")
        val fields = DigestChallenge.authParams(header.removePrefix("Digest "))
        assertEquals("6629fae49393a05397450978507c4ef1", fields["response"])
        assertEquals("00000001", fields["nc"])
    }

    /** RFC 7616 §3.9.1 (SHA-256, qop=auth). */
    @Test
    fun digestRfc7616Sha256() {
        val c = DigestChallenge.parse("Digest realm=\"http-auth@example.org\", qop=\"auth, auth-int\", algorithm=SHA-256, " +
            "nonce=\"7ypf/xlj9XXwfDPEoM4URrv/xwf94BcCAzFZH4GiTo0v\", opaque=\"FQhe/qaU925kfnzjCev0ciny7QMkPqMAFRtzCUYo5tdS\"")
        val header = DigestAuth("Mufasa", "Circle of Life").authorization(c, "GET", "/dir/index.html",
            cnonce = "f2/wE4q74E6zIJEtWaHKaf5wv/H5QzzpXusqGemxURZJ")
        assertEquals("753927fa0e85d155564e2e272a28d1802ca10daf4496794697cf8db5856cb6c1",
            DigestChallenge.authParams(header.removePrefix("Digest "))["response"])
    }

    @Test
    fun removesTopValueOnly() {
        val h = Headers().add("Route", "<sip:a;lr>, <sip:b;lr>").add("Route", "<sip:c;lr>")
        h.removeFirstValue("Route")
        assertEquals(listOf("<sip:b;lr>", "<sip:c;lr>"), h.values("Route"))
    }
}
