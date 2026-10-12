//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

import kotlinx.serialization.json.*

data class FdConfig(
    val contentServer: String? = null, val serviceMaxBytes: Long? = null,
    /** The one-to-one MaxData1To1 policy; group policy is separate. */
    val userMaxBytes: Long? = null, val deviceMaxBytes: Long? = null,
    val tdu2Seconds: Int = 30, val transferTimeoutMs: Int = 15_000,
) {
    init {
        require(listOfNotNull(serviceMaxBytes, userMaxBytes, deviceMaxBytes).all { it >= 0 }) { "negative FD limit" }
        require(tdu2Seconds > 0 && transferTimeoutMs > 0) { "FD timers must be positive" }
    }
    companion object {
        fun parse(value: JsonObject?): FdConfig {
            if (value == null) return FdConfig()
            fun number(key: String): Long? = value[key]?.let { it.jsonPrimitive.longOrNull ?: error("invalid FD $key") }
            fun timer(key: String, default: Int) = number(key)?.let {
                require(it in 1..Int.MAX_VALUE.toLong()) { "invalid FD $key" }; it.toInt()
            } ?: default
            return FdConfig(value["content_server"]?.jsonPrimitive?.content,
                number("service_max_bytes"), number("user_max_bytes"), number("device_max_bytes"),
                timer("tdu2_seconds", 30), timer("transfer_timeout_ms", 15_000))
        }
    }
}

data class GroupFdPolicy(val allowed: Boolean, val supported: Boolean, val maxBytes: Long?) {
    init { require(maxBytes == null || maxBytes >= 0) { "negative group FD limit" } }
}

object FdPolicy {
    fun effectiveLimit(config: FdConfig, group: GroupFdPolicy?): Long? =
        listOfNotNull(config.serviceMaxBytes, config.deviceMaxBytes,
            if (group == null) config.userMaxBytes else group.maxBytes).filter { it > 0 }.minOrNull()

    fun requireAllowed(config: FdConfig, group: GroupFdPolicy?, size: Long) {
        require(size >= 0) { "invalid file size" }
        require(group == null || group.allowed && group.supported) { "file distribution is disabled for this group" }
        val limit = effectiveLimit(config, group)
        require(limit == null || size <= limit) { "file exceeds the allowed size of $limit bytes" }
    }
}
