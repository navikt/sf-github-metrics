package no.nav.sf.github.metrics

/**
 * describes a type for a given name, typically counter or gauge.
 */
data class Type(val name: String, val type: String) {
    override fun toString() = "# TYPE $name $type"
    companion object {

        val regex = """# TYPE ([a-zA-Z0-9:_]+) ([a-z]+)""".toRegex()

        fun isValidLine(line: String): Boolean {
            return regex.matchEntire(line) != null
        }

        fun parseLine(line: String): Type {
            val matchResult = regex.matchEntire(line) ?: throw IllegalArgumentException("Line format is incorrect: $line")
            val (name, type) = matchResult.destructured
            return Type(name, type)
        }
    }
}
