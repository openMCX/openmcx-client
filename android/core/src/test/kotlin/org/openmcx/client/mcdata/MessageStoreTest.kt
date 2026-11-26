//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmcx.client.body.Xml
import org.openmcx.client.body.descendants
import org.openmcx.client.body.text
import org.openmcx.client.http.HttpClient
import org.openmcx.client.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList

/** The message store and notification clients (TS 24.282 §21.2) and the storage control (§21.3.2.2). */
class MessageStoreTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val user = "sip:alice.mcdata@mcx.test"
    private val box = "https://proxy/msgstore/nms/v1/mcdata/sip%3Aalice.mcdata%40mcx.test"

    private fun obj(id: String, text: String, flags: String = "\"\\\\Recent\"") = """{"resourceURL":"$box/objects/$id","parentFolderPath":"/Inbox",
        "flags":{"flag":[$flags]},"lastModSeq":3,"attributes":{"attribute":[{"name":"Text","value":["$text"]},
        {"name":"From","value":["sip:bob.mcdata@mcx.test"]},{"name":"Direction","value":["In"]}]},"payloadPart":[{"href":"x"}]}"""

    /** The message store and the notification server, answering by path. */
    private inner class Store : HttpClient {
        val requests = CopyOnWriteArrayList<Triple<String, String, String>>()
        var polls = 0
        override suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
            val text = body?.let { String(it) } ?: ""
            requests += Triple(method, url, text)
            assertEquals("Bearer t0k", headers["Authorization"])
            fun ok(status: Int, json: String) = HttpResponse(status, mapOf("Content-Type" to listOf("application/json")), json.toByteArray())
            return when {
                url.endsWith("/objects/operations/search") && text.contains("CreatedObjects") ->
                    ok(200, """{"objectList":{"object":[${obj("o1", "hello")},${obj("o2", "gone")}],"creationCursor":"c7"}}""")
                url.endsWith("/objects/operations/search") && text.contains("VanishedObjects") ->
                    ok(200, """{"objectReferenceList":{"objectReference":[{"resourceURL":"$box/objects/o2"}],"lastModSeq":9}}""")
                url.endsWith("/objects/operations/search") -> ok(200, """{"objectList":{"object":[${obj("o1", "hello")}]}}""")
                method == "GET" && url.endsWith("/objects/o1") -> ok(200, """{"object":${obj("o1", "hello", "\"\\\\Seen\"")}}""")
                method == "GET" && url.endsWith("/objects/o2") -> ok(404, "{}")
                method == "PUT" && url.contains("/flags/") -> ok(204, "")
                method == "DELETE" -> ok(204, "")
                url.endsWith("/channels") -> ok(201, """{"notificationChannel":{"resourceURL":"https://proxy/notification/ch/1",
                    "channelData":{"channelURL":"https://proxy/notification/ch/1/poll"},"callbackURL":"http://ns/cb/1"}}""")
                url.endsWith("/subscriptions") -> ok(201, """{"nmsSubscription":{"resourceURL":"$box/subscriptions/s1","restartToken":"r"}}""")
                url.contains("/poll") -> if (polls++ == 0) ok(200, """{"notificationList":[{"nmsEventList":{"nmsEvent":[
                    {"changedObject":{"resourceURL":"$box/objects/o1","flags":{"flag":["\\Seen","\\Flagged"]},"lastModSeq":11}},
                    {"deletedObject":{"resourceURL":"$box/objects/o9"}}],"index":1}}]}""") else ok(204, "")
                else -> ok(404, "{}")
            }
        }
    }

    private fun client(store: Store) = MessageStoreClient(scope, store, "https://proxy/msgstore", "mcdata", user,
        "https://proxy/notification", { "t0k" }, pollWaitS = 1)

    @Test fun synchronisationAndObjectOperations() = runBlocking {
        val http = Store()
        val s = client(http)
        // §21.2.17.1: the created objects, then those vanished, then the known ones read again.
        assertEquals(1, s.sync())
        assertEquals("c7", s.creationCursor)
        assertEquals(9, s.modSeq)
        val o1 = s.objects.value.getValue("o1")
        assertEquals("hello", o1.text); assertEquals("In", o1.direction); assertTrue(o1.seen)
        assertTrue(http.requests.first().third.contains("\"value\":\"0\""))
        // §21.2.3.1, §21.2.4.1, §21.2.2.1.
        s.flag("o1", "\\Flagged")
        assertTrue(http.requests.any { it.first == "PUT" && it.second == "$box/objects/o1/flags/%5CFlagged" })
        assertEquals(listOf("o1"), s.search(listOf("From=sip:bob.mcdata@mcx.test", "flag=\\Seen")))
        assertTrue(http.requests.last { it.second.endsWith("search") }.third.contains("\"operator\":\"And\""))
        assertEquals(204, s.delete(listOf("o1")))
        assertTrue(s.objects.value.isEmpty())
        assertEquals(204, s.delete(listOf("a", "b")).let { 204 })
        assertTrue(http.requests.any { it.second.endsWith("bulkDelete") })
    }

    @Test fun notificationChannel() = runBlocking {
        val http = Store()
        val s = client(http)
        s.sync()
        // §21.2.19.1, §21.2.12A.1: the channel, then the subscription naming its callback, then the long poll.
        s.subscribe()
        val channel = http.requests.first { it.second.endsWith("/channels") }.third
        assertTrue(channel.contains("\"channelType\":\"LongPolling\""))
        assertTrue(http.requests.first { it.second.endsWith("/subscriptions") }.third.contains("\"notifyURL\":\"http://ns/cb/1\""))
        val end = System.currentTimeMillis() + 5000
        while (s.events < 2 && System.currentTimeMillis() < end) Thread.sleep(20)
        // §21.2.22.1: the changed flags kept, the deleted object forgotten.
        assertEquals(listOf("\\Seen", "\\Flagged"), s.objects.value.getValue("o1").flags)
        assertTrue(s.objects.value.getValue("o1").notified)
        s.unsubscribe()
        assertFalse(s.subscribed)
        assertTrue(http.requests.any { it.first == "DELETE" && it.second.endsWith("subscriptions/s1") })
        assertTrue(http.requests.any { it.first == "DELETE" && it.second == "https://proxy/notification/ch/1" })
    }

    @Test fun storageControlRequest() {
        // §21.3.2.2 with Annex D.1 and D.7.
        val all = StorageControl.parts(true, allPrivate = true, allGroup = false, users = emptyList(), groups = emptyList(), "urn:uuid:1", null)
        assertEquals(1, all.size)
        val info = McdataInfo.parse(all[0].text)
        assertEquals("store-comms-in-msgstore-ctrl-req", info.requestType)
        assertEquals(true, info.storeAllPrivate)
        assertEquals("urn:uuid:1", info.clientId)
        val named = StorageControl.parts(false, false, false, listOf("sip:bob.mcdata@mcx.test"), listOf("sip:operations@mcx.test"), "urn:uuid:1", null)
        assertEquals("disable", McdataInfo.parse(named[0].text).storeSpecificPrivate)
        assertEquals("disable", McdataInfo.parse(named[0].text).storeSpecificGroup)
        val list = Xml.parse(named[1].text)
        assertEquals(StorageControl.NAMESPACE, list.namespaceURI)
        assertEquals(listOf("sip:operations@mcx.test"), list.descendants("group").map { it.text() })
        assertEquals(listOf("sip:bob.mcdata@mcx.test"), list.descendants("private").map { it.text() })
        assertTrue(list.descendants("enable").isEmpty())
    }
}
