package no.nav.sf.github.metrics

/**
 * structure for each tag. allows them to easily be sorted by key and thus
 * having a single representation for a unique set of key/values.
 */
data class Tag(val key: String, val value: String) {
    init {
        require ("""[a-zA-Z0-9_]+""".toRegex().matches(key)) {
            "Invalid tag key: $key. Use only alphanumeric characters and underscores."
        }
    }
    override fun toString() = "$key=\"$value\""
}
