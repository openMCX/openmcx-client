//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.client

import org.openmcx.client.body.RegInfo
import org.openmcx.client.sip.Connection
import org.openmcx.client.sip.Framed
import org.openmcx.client.sip.Headers
import org.openmcx.client.sip.SipRequest
import org.openmcx.client.sip.SipResponse
import org.openmcx.client.sip.StreamFramer
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * A SIP core on one flow: REGISTER 200 (Expires 3600), PUBLISH 200 with an ETag, SUBSCRIBE 200 (reg: Expires 3761,
 * presence: as asked, and for Expires 0 the final NOTIFY after the 200), anything else 200. It records the client's
 * requests and its answers to the core's NOTIFYs, and sends NOTIFYs on a recorded subscription.
 */
internal class FakeSipCore(val refusePublish: Boolean = false) : Connection {
    val requests = CopyOnWriteArrayList<SipRequest>()
    val answers = LinkedBlockingQueue<SipResponse>()
    private val toClient = LinkedBlockingQueue<ByteArray>()
    private val framer = StreamFramer()
    private val cseq = AtomicInteger(1)
    override val localHost = "127.0.0.1"
    override val localPort = 5061
    override val transport = "TLS"
    override val input: InputStream = object : InputStream() {
        private var current = ByteArray(0)
        private var at = 0
        override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 0xff }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (at == current.size) { current = toClient.take(); at = 0 }
            if (current.isEmpty()) { toClient.put(current); return -1 } // closed
            val count = minOf(length, current.size - at)
            System.arraycopy(current, at, buffer, offset, count)
            at += count
            return count
        }
    }
    override val output: OutputStream = object : OutputStream() {
        private val pending = ByteArrayOutputStream()
        override fun write(byte: Int) = pending.write(byte)
        override fun write(buffer: ByteArray, offset: Int, length: Int) = pending.write(buffer, offset, length)
        override fun flush() {
            val bytes = pending.toByteArray().also { pending.reset() }
            for (item in framer.feed(bytes, bytes.size)) {
                when (val message = (item as? Framed.Message)?.message) {
                    is SipResponse -> answers.put(message)
                    is SipRequest -> answer(message)
                    else -> {}
                }
            }
        }
    }

    private fun answer(request: SipRequest) {
        requests += request
        if (request.method == "ACK") return
        val headers = Headers()
        request.headers.all("Via").forEach { headers.add("Via", it) }
        headers.add("From", request.headers["From"]!!).add("To", request.headers["To"]!! + if (request.to.tag == null) ";tag=core" else "")
            .add("Call-ID", request.callId).add("CSeq", request.headers["CSeq"]!!)
        val event = request.headers["Event"]?.substringBefore(';')?.trim()
        when (request.method) {
            "REGISTER" -> headers.add("Expires", "3600")
            "PUBLISH" -> headers.add("SIP-ETag", "e${requests.size}").add("Expires", request.headers["Expires"] ?: "0")
            "SUBSCRIBE" -> headers.add("Expires", if (event == "reg") "3761" else request.headers["Expires"]!!)
        }
        if (refusePublish && request.method == "PUBLISH") { toClient.put(SipResponse(500, "Server Internal Error", headers).encode()); return }
        toClient.put(SipResponse(200, "OK", headers).encode())
        if (request.method == "SUBSCRIBE" && event == "presence" && request.headers["Expires"] == "0")
            notify(request, "presence", "terminated;reason=timeout", null, null)
    }

    /** The client's contact as it registered it. */
    fun contact(): String = requests.last { it.method == "REGISTER" }.headers["Contact"]!!.substringAfter('<').substringBefore('>')

    /** A NOTIFY in the dialog of [subscribe] (its first SUBSCRIBE, the core's tag "core"). */
    fun notify(subscribe: SipRequest, event: String, state: String, type: String?, body: String?, callId: String = subscribe.callId) {
        val headers = Headers()
            .add("Via", "SIP/2.0/TLS 127.0.0.1:5061;branch=z9hG4bKcore${cseq.get()}")
            .add("Max-Forwards", "70")
            .add("From", subscribe.headers["To"]!!.substringBefore(";tag") + ";tag=core")
            .add("To", subscribe.headers["From"]!!)
            .add("Call-ID", callId).add("CSeq", "${cseq.getAndIncrement()} NOTIFY")
            .add("Contact", "<sip:core@127.0.0.1:5061;transport=tls>")
            .add("Event", event).add("Subscription-State", state)
        if (type != null) headers.add("Content-Type", type)
        toClient.put(SipRequest("NOTIFY", subscribe.headers["Contact"]!!.substringAfter('<').substringBefore('>'), headers,
            body?.toByteArray() ?: ByteArray(0)).encode())
    }

    fun regSubscribe(): SipRequest = requests.first { it.method == "SUBSCRIBE" && it.headers["Event"] == "reg" }

    /** A reginfo NOTIFY for the client's own contact. */
    fun regNotify(version: Int, contactState: String, event: String, expires: Long? = null, registrationState: String = "active",
                  state: String = "active;expires=3761") =
        notify(regSubscribe(), "reg", state, RegInfo.CONTENT_TYPE,
            """<?xml version="1.0"?><reginfo xmlns="${RegInfo.NAMESPACE}" version="$version" state="full">
            <registration aor="sip:alice@mcx.test" id="a" state="$registrationState"><contact id="c" state="$contactState"
            event="$event"${expires?.let { " expires=\"$it\"" } ?: ""}><uri>${contact()}</uri></contact></registration></reginfo>""")

    fun answer(): SipResponse = answers.poll(5, java.util.concurrent.TimeUnit.SECONDS) ?: throw AssertionError("no answer")
    override fun close() { toClient.put(ByteArray(0)) }
}
