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
suspend fun forward(body: String?, jobname: String, instance: String? = null): HttpResponse {
    val client = HttpClient(CIO)
    val root = System.getenv("PROMGATEADDRESS") ?: "http://172.17.0.3:9091" // default to private network https://www.rfc-editor.org/rfc/rfc1918
    val url = instance?.let { "${root}/metrics/job/$jobname/instance/$it" } ?: "${root}/metrics/job/$jobname"

    val response: HttpResponse = client.post(url) {
        contentType(ContentType.Text.Plain)
        setBody(body)
    }
    return response
}

fun Application.module() {
    routing {
        route("/metrics/job/{jobname}/instance/{instance}") {
            post {
                val body: String? = call.receiveNullable(typeInfo<String>())
                val jobname = call.parameters.get("jobname")!!
                val instance = call.parameters.get("instance")!!
                val response = forward(body, jobname, instance)
                logger.info("${response}")
                call.respond("whatever\n")
            }
        }
        route("/metrics/job/{jobname}") {
            post {
                val body: String? = call.receiveNullable(typeInfo<String>())
                val jobname = call.parameters.get("jobname")!!
                val response = forward(body, jobname)
                logger.info("${response}")
                call.respond("whomever\n")
            }
        }
    }
}

fun main(args: Array<String>): Unit = EngineMain.main(args)
