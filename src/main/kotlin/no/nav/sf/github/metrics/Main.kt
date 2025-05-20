package no.nav.sf.github.metrics

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

import io.ktor.util.reflect.typeInfo

import java.net.ConnectException
import java.net.NoRouteToHostException

import kotlinx.serialization.Serializable

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * forwards stats for a given job and an optionally specified instance.
 * often the instance is given in the data instead of the path.
 */
suspend fun forward(body: String?, jobname: String, instance: String? = null): HttpStatusCode {
    // default to private network https://www.rfc-editor.org/rfc/rfc1918
    val root = System.getenv("PROMGATEADDRESS") ?: "http://172.17.0.3:9091"
    val url = instance?.let {
        "${root}/metrics/job/$jobname/instance/$it"
    } ?: "${root}/metrics/job/$jobname"

    try {
        val response: HttpResponse = client.post(url) {
            contentType(ContentType.Text.Plain)
            setBody(body + "\n") // metrics must end with a newline, just add it
        }
        return HttpStatusCode(200, "OK")
    } catch (ce: ConnectException) {
        logger.error("${ce.stackTraceToString()})")
        return HttpStatusCode(502, "Failed to connect to pushgateway")
    } catch (nrthe: NoRouteToHostException) {
        logger.error("${nrthe.stackTraceToString()})")
        return HttpStatusCode(502, "No route to host")
    }
}

@Serializable
data class Payload(
    val metrics: String,
    val runner: String,
    val signature: String,
)

val logger: Logger = LoggerFactory.getLogger("Main")
val validator = MessageValidator()
val client = HttpClient(CIO)

/**
 * performs signature validation and forwards metrics to a pushgateway
 */
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
        //route("/measures/job/{jobname}/instance/{instance}") {
        //    post {
        //        val body: String? = call.receiveNullable(typeInfo<String>())
        //        val jobname = call.parameters.get("jobname")!!
        //        val instance = call.parameters.get("instance")!!
        //        val response = forward(body, jobname, instance)
        //        call.respondText(response.description, status=response)
        //    }
        //}
        post("/measures/job/{jobname}") {
            val (metrics, runner, signature) = call.receive<Payload>()
            val jobname = call.parameters.get("jobname")!!
            Runners.publicKeys.get(runner)?.let {
                if (validator.isValid(metrics, it, signature)) {
                    val response = forward(metrics, jobname)
                    call.respondText(response.description, status=response)
                } else {
                    call.respondText("Bad signature", status=HttpStatusCode(401, "Bad signature"))
                }
            } ?: call.respondText("Unrecognised runner", status=HttpStatusCode(401, "Unrecognised runner"))
        }
    }
}

fun main(args: Array<String>): Unit = EngineMain.main(args)
