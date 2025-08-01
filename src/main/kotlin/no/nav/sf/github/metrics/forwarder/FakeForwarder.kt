package no.nav.sf.github.metrics

import io.ktor.http.HttpStatusCode

class FakeForwarder : IForwarder {

    /**
     * just returns a happy 200 response
     */
    override suspend fun forward(body: String?, jobname: String): ForwardResponse {
        return ForwardResponse(HttpStatusCode(200, "alright"), "great")
    }
}
