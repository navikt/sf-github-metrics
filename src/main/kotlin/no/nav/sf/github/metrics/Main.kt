package no.nav.sf.github.metrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

import java.net.ConnectException
import java.net.NoRouteToHostException

import kotlinx.serialization.Serializable

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * use instead of HttpStatusCode which limits the characters it can use in a
 * response.
 */
data class ForwardResponse(
    val status: HttpStatusCode,
    val reason: String,
)

/**
 * forwards stats for a given job and an optionally specified instance.
 * often the instance is given in the data instead of the path.
 */
suspend fun forward(body: String?, jobname: String): ForwardResponse {
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
    } catch (e: Exception) {
        logger.error("${e.stackTraceToString()})")
        return ForwardResponse(
            HttpStatusCode(500, "Internal server error"),
            "Internal server error"
        )
    }
}

/**
 * updates the db and forwards, in that order.
 */
internal suspend fun updateAndForward(
    body: String,
    jobname: String,
    instance: String?,
    persistence: IPersistence = Persistence()
): ForwardResponse {
    val newBody = persistence.updateStats(body, jobname, instance)
    return forward(newBody, jobname)
}

@Serializable
data class Payload(
    val metrics: String,
    val runner: String,
    val signature: String,
)

/**
 * performs signature validation, updates database, and forwards metrics to a
 * pushgateway
 */
private suspend fun validateAndUpdateAndForward(
    payload: Payload,
    jobname: String,
    instance: String? = null
): ForwardResponse {
    val (metrics, runner, signature) = payload
    Runners.publicKeys.get(runner)?.let {
        if (validator.isValid(metrics, it, signature)) {
            return updateAndForward(metrics, jobname, instance)
        } else {
            logger.info("bad signature")
            return ForwardResponse(HttpStatusCode(401, "Unauthorized"), "Bad signature")
        }
    } ?: run {
        logger.info("unrecognised runner")
        return ForwardResponse(HttpStatusCode(401, "Unauthorized"), "Unrecognised runner")
    }
}

fun Application.module() {
    install(ContentNegotiation) {
        json()
    }
    routing {
        get("/isAlive") {
            call.respondText("Alive!")
        }
        get("/isReady") {
            call.respondText("Ready!")
        }
        post("/measures/job/{jobname}/instance/{instance}") {
            val payload = call.receive<Payload>()
            val jobname = call.parameters.get("jobname")!!
            val instance = call.parameters.get("instance")!!
            logger.info("got request to forward metrics from runner ${payload.runner} on instance $instance for job $jobname")
            val response = validateAndUpdateAndForward(payload, jobname, instance)
            call.respondText(response.reason, status=response.status)
        }
        post("/measures/job/{jobname}") {
            val payload = call.receive<Payload>()
            val jobname = call.parameters.get("jobname")!!
            logger.info("got request to forward metrics from runner ${payload.runner} for job $jobname")
            val response = validateAndUpdateAndForward(payload, jobname)
            call.respondText(response.reason, status=response.status)
        }
    }
}

val logger: Logger = LoggerFactory.getLogger("Main")
val validator = MessageValidator()
val client = HttpClient(CIO)

fun main(args: Array<String>): Unit = EngineMain.main(args)
