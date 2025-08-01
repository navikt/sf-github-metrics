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
         * creates distinct representation. name, sorted tags with mandatory
         * instance, and value.
         */
        fun parseLine(instance: String?, line: String): Entry {
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
                }
            }
            // always set instance. priority:
            // 1. already in tag
            // 2. from endpoint
            // 3. "default"
            val tagsWithInstance: List<Tag> = tags + if ("instance" in tags.map { it.key }) {
                listOf()
            } else if (instance != null) {
                listOf(Tag("instance", instance))
            } else {
                listOf(Tag("instance", "default"))
            }
            val sortedTags = tagsWithInstance.sortedBy { it.key }
            val doubleValue = value.toDouble()
            return Entry(name, sortedTags, doubleValue)
        }
    }
}
