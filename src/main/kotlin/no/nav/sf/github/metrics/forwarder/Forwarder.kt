package no.nav.sf.github.metrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText

import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.contentType

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException

/**
 * use instead of HttpStatusCode which limits the characters it can use in a
 * response.
 */
data class ForwardResponse(
    val status: HttpStatusCode,
    val reason: String,
)

/**
 * forwards data to the pushgateway defined as an environmental variable
 */
class Forwarder : IForwarder {

    val client = HttpClient(CIO)

    /**
     * forwards stats for a given job and an optionally specified instance.
     * often the instance is given in the data instead of the path.
     */
    override suspend fun forward(body: String?, jobname: String): ForwardResponse {
        // default to private network https://www.rfc-editor.org/rfc/rfc1918
        val root = System.getenv("PROMGATEADDRESS") ?: "http://172.17.0.3:9091"
        val url = "${root}/metrics/job/$jobname"
        try {
            val response: HttpResponse = client.post(url) {
                contentType(ContentType.Text.Plain)
                setBody(body + "\n") // metrics must end with a newline, just add it
            }
            logger.info("pushgateway responds ${response.status} (${response.bodyAsText()})")
            return when (response.status.value) {
                200, 400 -> ForwardResponse(response.status, response.bodyAsText())
                else -> {
                    logger.error("Unexpected response: ${response.status}: ${response.bodyAsText()}")
                    ForwardResponse(response.status, response.bodyAsText())
                }
            }
        } catch (ce: ConnectException) {
            logger.error("${ce.stackTraceToString()})")
            return ForwardResponse(
                HttpStatusCode(502, "Bad gateway"),
                "Failed to connect to pushgateway"
            )
        } catch (nrthe: NoRouteToHostException) {
            logger.error("${nrthe.stackTraceToString()})")
            return ForwardResponse(
                HttpStatusCode(502, "Bad gateway"),
                "No route to host"
            )
        } catch (se: SocketException) {
            logger.error("${se.stackTraceToString()})")
            return ForwardResponse(
                HttpStatusCode(502, "Bad gateway"),
                "Network is unreachable"
            )
        } catch (e: Exception) {
            logger.error("${e.stackTraceToString()})")
            return ForwardResponse(
                HttpStatusCode(500, "Internal server error"),
                "Internal server error"
            )
        }
    }
}
