package no.nav.sf.github.metrics

/**
 * implementing classes must return a public key from an index
 */
interface IRunners {
    fun get(index: String): String?
}
