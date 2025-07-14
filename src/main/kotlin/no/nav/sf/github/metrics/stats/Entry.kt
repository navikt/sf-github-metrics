package no.nav.sf.github.metrics

/**
 * a metric (name + tags)'s value (value) for a given point in time.
 */
data class Entry(
    val name: String,
    val tags: List<Tag>,
    val value: Double
) {

    val tagString: String get() = tags.joinToString(",")
    override fun toString() = "$name{$tagString} $value"

    companion object {

        val regex = """([a-zA-Z0-9:_]+)\{([^}]*)} ([0-9.]+)""".toRegex()

        fun isValidLine(line: String): Boolean {
            return regex.matchEntire(line) != null
        }

        /**
         * create distinct representation
         */
        fun parseLine(line: String): Entry {
            val matchResult = regex.matchEntire(line)!!
            val (name, tagString, value) = matchResult.destructured
            // parse into Tag objects and sort by key in order to avoid
            // duplicates
            val tags: List<Tag> = if (tagString.trim() == "") {
                listOf()
            } else {
                tagString.split(",").map { it.trim() }.map {
                    val (key, value) = it.split("=")
                    Tag(key.trim(), value.trim().removeSurrounding("\""))
                }.sortedBy { it.key }  // Sort tags by key
            }
            val doubleValue = value.toDouble()
            return Entry(name, tags, doubleValue)
        }
    }
}
