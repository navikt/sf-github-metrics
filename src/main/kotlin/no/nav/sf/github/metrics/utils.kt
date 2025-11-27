@file:Suppress("ktlint:standard:filename")

package no.nav.sf.github.metrics

import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

val currentDateTime: String
    get() =
        ZonedDateTime
            .now(ZoneId.of("Europe/Oslo"))
            .format(DateTimeFormatter.ISO_DATE_TIME)
