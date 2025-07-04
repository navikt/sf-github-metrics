package no.nav.sf.github.metrics

class FakePersistence(): IPersistence {


    /**
     * separates lines from a message into their possible forms
     */
    private fun getTypesAndEntriesAndUnparseable(
        body: String,
        instance: String?
    ): Triple<List<Type>, List<Entry>, List<String>> {
        val lines = body.split("\n").map { it.trim() }
        val types = lines.filter { it.startsWith("# TYPE") }.map { Type.parseLine(it) }
        val entries = lines.filter { Entry.isValidLine(it) }.map { Entry.parseLine(instance, it) }
        val unparseable = lines.filter { !(
            it.startsWith("# TYPE") ||
            Entry.isValidLine(it) ||
            it == ""
        )}
        return Triple(types, entries, unparseable)
    }

    /**
     * just parses the body sent and returns it if no errors occur
     */
    override fun updateStats(body: String, job: String, instance: String?): String {
        if ("""[a-zA-Z0-9_]+""".toRegex().matchEntire(job) == null) {
            throw JobNameException("Invalid job name: $job\nPlease only use alphanumeric characters.")
        }
        val (types, entries, unparseable) = getTypesAndEntriesAndUnparseable(body, instance)
        unparseable.forEach {
            logger.warn("Encountered unparseable line:\n\t$it")
        }
        val names = entries.map { it.name }.distinct()
        logger.info("Upserted nothing. Got ${names.size} names")
        return body
    }
}
