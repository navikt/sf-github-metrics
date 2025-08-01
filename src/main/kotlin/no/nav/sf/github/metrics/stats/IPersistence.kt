package no.nav.sf.github.metrics

/**
 * implementing classes must have a method which takes a message body and
 * returns an updated message body
 */
interface IPersistence {
    fun updateStats(body: String, job: String, instance: String?): String
}
