package no.nav.sf.github.metrics

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*

import io.ktor.util.reflect.typeInfo

val logger: Logger = LoggerFactory.getLogger("Main")

/**
 * forward stats for a given job and an optionally specified instance
 * often the instance is given in the data instead of the path
 */
suspend fun forward(body: String?, jobname: String, instance: String? = null): HttpStatusCode {
    val client = HttpClient(CIO)
    val root = System.getenv("PROMGATEADDRESS") ?: "http://172.17.0.3:9091" // default to private network https://www.rfc-editor.org/rfc/rfc1918
    val url = instance?.let { "${root}/metrics/job/$jobname/instance/$it" } ?: "${root}/metrics/job/$jobname"

    try {
        val response: HttpResponse = client.post(url) {
            contentType(ContentType.Text.Plain)
            setBody(body)
        }
        return HttpStatusCode(200, "success")
    } catch (e: Exception) {
        logger.error("$e (${e.stackTraceToString()})")
        return HttpStatusCode(502, "failed to forward log data")
    }
}

fun Application.module() {
    routing {
        get("/isAlive") {
            call.respondText("yup")
        }
        get("/isReady") {
            call.respondText("fine")
        }
        route("/metrics/job/{jobname}/instance/{instance}") {
            post {
                val body: String? = call.receiveNullable(typeInfo<String>())
                val jobname = call.parameters.get("jobname")!!
                val instance = call.parameters.get("instance")!!
                val response = forward(body, jobname, instance)
                call.respondText(response.description, status=response)
            }
        }
        route("/metrics/job/{jobname}") {
            post {
                val body: String? = call.receiveNullable(typeInfo<String>())
                val jobname = call.parameters.get("jobname")!!
                val response = forward(body, jobname)
                call.respondText(response.description, status=response)
            }
        }
    }
}

fun main(args: Array<String>): Unit = EngineMain.main(args)
