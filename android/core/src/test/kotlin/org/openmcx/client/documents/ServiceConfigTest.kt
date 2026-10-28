//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** TS 24.484 §8.4.2.1 and §10.4.2.1: the Resource-Priority values of the service configuration documents (RFC 8101). */
class ServiceConfigTest {
    private fun rp(element: String, space: String, level: String) =
        "<$element><resource-priority-namespace>$space</resource-priority-namespace>" +
            "<resource-priority-priority>$level</resource-priority-priority></$element>"

    private val three = rp("emergency-resource-priority", "mcpttp", "15") +
        rp("imminent-peril-resource-priority", "mcpttp", "10") + rp("normal-resource-priority", "mcpttq", "0")

    @Test
    fun mcpttAndMcdataDocuments() {
        val mcptt = ServiceConfig.resourcePriorities(
            "<service-configuration-info xmlns=\"urn:3gpp:ns:mcpttServiceConfig:1.0\"><service-configuration-params domain=\"x\">" +
                "<on-network>$three</on-network></service-configuration-params></service-configuration-info>")
        assertEquals(mapOf("emergency" to "mcpttp.15", "imminent" to "mcpttp.10", "normal" to "mcpttq.0"), mcptt)
        val mcdata = ServiceConfig.resourcePriorities(
            "<service-configuration-info xmlns=\"urn:3gpp:ns:mcdataServiceConfig:1.0\"><service-configuration-params domain=\"x\">" +
                "<on-network><anyExt>$three</anyExt></on-network></service-configuration-params></service-configuration-info>")
        assertEquals("mcpttp.15", mcdata["emergency"])
    }

    @Test
    fun valuesOutsideRfc8101AreRefused() {
        for ((space, level) in listOf("mcptt" to "15", "mcpttp" to "16", "mcpttp" to "07")) {
            assertThrows(DocumentError::class.java) {
                ServiceConfig.resourcePriorities(
                    "<service-configuration-info xmlns=\"urn:3gpp:ns:mcpttServiceConfig:1.0\"><service-configuration-params domain=\"x\">" +
                        "<on-network>${rp("emergency-resource-priority", space, level)}${rp("imminent-peril-resource-priority", "mcpttp", "10")}" +
                        "${rp("normal-resource-priority", "mcpttp", "0")}</on-network></service-configuration-params></service-configuration-info>")
            }
        }
    }

    /** TS 24.484 §8.4.2.1: `<floor-control-queue>` in `<on-network>`: the server queues floor requests. */
    @Test
    fun floorControlQueue() {
        val doc = { queue: String -> "<service-configuration-info xmlns=\"urn:3gpp:ns:mcpttServiceConfig:1.0\">" +
            "<service-configuration-params domain=\"x\"><on-network>$queue$three</on-network></service-configuration-params>" +
            "</service-configuration-info>" }
        assertEquals(true, ServiceConfig.floorQueue(doc("<floor-control-queue><depth>3</depth></floor-control-queue>")))
        assertEquals(false, ServiceConfig.floorQueue(doc("")))
    }

    /** TS 24.379 §6.6.2.3.1 and §6.6.3.3.1: `<signalling-protection>`; an absent element means enabled. */
    @Test
    fun signallingProtection() {
        val doc = { inner: String -> "<service-configuration-info xmlns=\"urn:3gpp:ns:mcpttServiceConfig:1.0\">" +
            "<service-configuration-params domain=\"x\"><on-network>$inner$three</on-network></service-configuration-params>" +
            "</service-configuration-info>" }
        assertEquals(true to true, ServiceConfig.signallingProtection(doc("")))
        assertEquals(false to false, ServiceConfig.signallingProtection(doc("<signalling-protection>" +
            "<confidentiality-protection>false</confidentiality-protection><integrity-protection>0</integrity-protection>" +
            "</signalling-protection>")))
        assertEquals(false to true, ServiceConfig.signallingProtection(doc("<signalling-protection>" +
            "<confidentiality-protection>false</confidentiality-protection></signalling-protection>")))
        assertThrows(DocumentError::class.java) {
            ServiceConfig.signallingProtection(doc("<signalling-protection><integrity-protection>yes</integrity-protection>" +
                "</signalling-protection>"))
        }
    }
}
