package no.nav.sf.github.metrics

interface IPersistence {
    fun updateStats(body: String, job: String, instance: String?): String
}
