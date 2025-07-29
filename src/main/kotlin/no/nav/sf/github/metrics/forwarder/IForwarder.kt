package no.nav.sf.github.metrics

interface IForwarder {
    suspend fun forward(body: String?, jobname: String): ForwardResponse
}
