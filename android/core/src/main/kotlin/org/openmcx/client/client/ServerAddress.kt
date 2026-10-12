//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.client

/**
 * The address the servers of a profile are reached at, which the user may change (a new DHCP lease, another network)
 * while the rest of the profile stays as provisioned. The profile's `endpoints` map each server (`sip-core:5061`) to
 * where it is reached (`192.0.2.10:5061`); the certificates name the servers, not their addresses, so TLS still
 * checks them against the profile's CA.
 */
object ServerAddress {
    private val IPV4 = Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$")
    private val HOSTNAME = Regex("^(?=.{1,253}$)[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$")

    /** An IPv4 address, or a host name (not something that looks like a broken IPv4 address). */
    fun valid(host: String) = IPV4.matches(host) || (HOSTNAME.matches(host) && !host.all { it.isDigit() || it == '.' })

    /** The one host every server of [config] is reached at; null when they differ or the profile has no endpoints. */
    fun of(config: ClientConfig): String? = config.endpoints.values.map { it.substringBeforeLast(':') }.distinct().singleOrNull()

    /**
     * [config] with every server reached at [host], each on its own port. A profile without endpoints (its servers
     * found by DNS) gets them for the SIP core and the IdMS.
     */
    fun apply(config: ClientConfig, host: String): ClientConfig {
        require(valid(host)) { "\"$host\" is not an IP address or host name" }
        val endpoints = config.endpoints.ifEmpty {
            val issuer = java.net.URI(config.idms.issuer)
            val idmsPort = if (issuer.port > 0) issuer.port else 443
            mapOf("${config.sip.proxy}:${config.sip.proxyPort}" to "${config.sip.proxy}:${config.sip.proxyPort}",
                "${issuer.host}:$idmsPort" to "${issuer.host}:$idmsPort")
        }
        return config.copy(endpoints = endpoints.mapValues { (_, target) -> "$host:${target.substringAfterLast(':')}" })
    }
}
