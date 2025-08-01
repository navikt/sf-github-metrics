package no.nav.sf.github.metrics

/**
 * for testing. only temporarily persistent.
 */
class FakePersistence(): IPersistence {

    val types = mutableMapOf<String, String>()
    val stats = mutableMapOf<String, MutableMap<String, Double>>()

    /**
     * hides implementation detail when getting stored entries.
     * just used for testing for now, consider putting it in IPersistence if we
     * want to actually use it for some other reason.
     */
    fun getEntry(name: String, tagString: String): Double {
        return stats.get(name)!!.get(tagString)!!
    }

    /**
     * just parses the body sent, saves types and stats, and returns the body.
     */
    override fun updateStats(body: String, job: String, instance: String?): String {
        if ("""[a-zA-Z0-9_]+""".toRegex().matchEntire(job) == null) {
            throw JobNameException("Invalid job name: $job\nPlease only use alphanumeric characters.")
        }
        val (types, entries, unparseable) = Persistence.getTypesAndEntriesAndUnparseable(body, instance)
        unparseable.forEach {
            logger.warn("Encountered unparseable line:\n\t$it")
        }
        val names = entries.map { it.name }.distinct()
        types.forEach {
            this.types[it.name] = it.type
        }
        entries.forEach {
            stats.getOrPut(it.name) { mutableMapOf() }[it.tagString] = it.value
        }
        logger.info("Stored ${names.size} names, ${this.types.size} types, and ${stats.values.sumBy { it.size }} stat entries.")
        return body
    }
}
