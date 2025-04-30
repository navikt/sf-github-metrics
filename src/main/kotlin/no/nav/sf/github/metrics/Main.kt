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

fun Application.module() {
    routing {
        route("/metrics/job/{jobname}/instance/{instance}") {
            post {
                val body: String? = call.receiveNullable(typeInfo<String>())
                val jobname = call.parameters.get("jobname")
                val instance = call.parameters.get("instance")
                val client = HttpClient(CIO)
                val response: HttpResponse = client.post(System.getenv("PROMGATEADDRESS") ?: "http://172.17.0.3:9091/metrics/job/$jobname/instance/$instance") {
                    contentType(ContentType.Text.Plain)
                    setBody(body)
                }
                logger.info("${response}")
                call.respond("whatever\n")
            }
        }
        route("/metrics/job/{jobname}") {
            post {
                logger.info("${call.parameters.getAll("jobname")} got")
                call.respond(HttpStatusCode.OK, "something's ok!\n")
            }
        }
    }
}

fun main(args: Array<String>): Unit = EngineMain.main(args)
