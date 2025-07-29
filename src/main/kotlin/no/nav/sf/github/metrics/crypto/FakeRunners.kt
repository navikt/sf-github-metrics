package no.nav.sf.github.metrics

/**
 * allow setting keys at run time
 */
class FakeRunners: IRunners {
    val publicKeys: MutableMap<String, String> = mutableMapOf()
    override fun get(index: String) = publicKeys.get(index)
    fun set(index: String, key: String) {
        publicKeys[index] = key
    }
}
