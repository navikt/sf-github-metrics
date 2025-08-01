package no.nav.sf.github.metrics

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

import kotlinx.serialization.Serializable

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@Serializable
data class Payload(
    val metrics: String,
    val runner: String,
    val signature: String,
)

/**
 * updates the db and forwards, in that order.
 */
internal suspend fun updateAndForward(
    body: String,
    jobname: String,
    instance: String?,
    persistence: IPersistence,
    forwarder: IForwarder
): ForwardResponse {
    val newBody = persistence.updateStats(body, jobname, instance)
    return forwarder.forward(newBody, jobname)
}

/**
 * performs signature validation, updates database, and forwards metrics to a
 * pushgateway
 */
internal suspend fun validateAndUpdateAndForward(
    payload: Payload,
    jobname: String,
    instance: String? = null,
    runners: IRunners,
    persistence: IPersistence,
    forwarder: IForwarder
): ForwardResponse {
    val (metrics, runner, signature) = payload
    runners.get(runner)?.let {
        if (validator.isValid(metrics, it, signature)) {
            return updateAndForward(metrics, jobname, instance, persistence, forwarder)
        } else {
            logger.info("bad signature")
            return ForwardResponse(HttpStatusCode(401, "Unauthorized"), "Bad signature")
        }
    } ?: run {
        logger.info("unrecognised runner")
        return ForwardResponse(HttpStatusCode(401, "Unauthorized"), "Unrecognised runner")
    }
}

fun Application.module(
    runners: IRunners = Runners(),
    persistence: IPersistence = Persistence(),
    forwarder: IForwarder = Forwarder()
) {
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
            val response = validateAndUpdateAndForward(
                payload, jobname, instance,
                runners = runners,
                persistence = persistence,
                forwarder = forwarder
            )
            call.respondText(response.reason, status=response.status)
        }
        post("/measures/job/{jobname}") {
            val payload = call.receive<Payload>()
            val jobname = call.parameters.get("jobname")!!
            logger.info("got request to forward metrics from runner ${payload.runner} for job $jobname")
            val response = validateAndUpdateAndForward(
                payload, jobname,
                runners = runners,
                persistence = persistence,
                forwarder = forwarder
            )
            call.respondText(response.reason, status=response.status)
        }
    }
}

val logger: Logger = LoggerFactory.getLogger("Main")
val validator = MessageValidator()

fun main(args: Array<String>) {
    embeddedServer(Netty, port = 8080) {
        module()
    }.start(wait = true)
}
