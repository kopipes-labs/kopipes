package org.myorg.payments

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Hours when core checking is closed, such as `22:00-06:00@America/New_York`. */
class ClosedHours(private val from: LocalTime, private val to: LocalTime, private val zone: ZoneId) {

    fun isClosed(epochMillis: Long): Boolean {
        val time = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalTime()
        return if (from <= to) time >= from && time < to else time >= from || time < to
    }

    companion object {
        /** Blank: always open. */
        fun parse(spec: String?): ClosedHours? {
            if (spec.isNullOrBlank()) return null
            val (hours, zone) = spec.split("@", limit = 2).let { it[0] to ZoneId.of(it.getOrElse(1) { "UTC" }) }
            val (from, to) = hours.split("-", limit = 2).map { LocalTime.parse(it.trim()) }
            return ClosedHours(from, to, zone)
        }
    }
}
