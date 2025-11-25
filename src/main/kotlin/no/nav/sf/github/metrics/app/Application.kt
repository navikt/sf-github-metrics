package no.nav.sf.github.metrics.app

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import mu.KotlinLogging
import no.nav.sf.github.metrics.app.token.AuthRouteBuilder
import no.nav.sf.github.metrics.app.token.DefaultTokenValidator
import no.nav.sf.github.metrics.app.token.MockTokenValidator
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.Status.Companion.INTERNAL_SERVER_ERROR
import org.http4k.core.Status.Companion.OK
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.server.Http4kServer
import org.http4k.server.Netty
import org.http4k.server.asServer
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class Application {
    private val log = KotlinLogging.logger { }

    // Gson instance
    val gson = Gson()
    val gsonPretty = GsonBuilder().setPrettyPrinting().create()

    // To handle parallel runs
    // Key: Run ID  | Value: Start time
    val workflowStartTimes = ConcurrentHashMap<Long, Instant>()

    val workflowMessages: MutableMap<Long, MutableList<String>> = mutableMapOf()

    val allEvents: MutableMap<String, MutableList<EventEntry>> = mutableMapOf()

    val local: Boolean = System.getenv(env_NAIS_CLUSTER_NAME) == null

    val tokenValidator = if (local) MockTokenValidator() else DefaultTokenValidator()

    val cluster = if (local) "local" else env(env_NAIS_CLUSTER_NAME)

    val webhookSecret = env(secret_WEBHOOK_SECRET)

    fun apiServer(port: Int): Http4kServer = api().asServer(Netty(port))

    fun api(): HttpHandler =
        routes(
            "/internal/isAlive" bind Method.GET to { Response(OK) },
            "/internal/isReady" bind Method.GET to { Response(OK) },
            "/internal/metrics" bind Method.GET to Metrics.metricsHttpHandler,
            "/internal/gui" bind Method.GET to guiHandler,
            "/internal/hello" bind Method.GET to { Response(OK).body("Hello") },
            "/internal/secrethello" authbind Method.GET to { Response(OK).body("Secret Hello") },
            "/webhook" bind Method.GET to { Response(OK).body("Up") },
            "/webhook" bind Method.POST to webhookHandler,
        )

    /**
     * authbind: a variant of bind that takes care of authentication with use of tokenValidator
     */
    infix fun String.authbind(method: Method) = AuthRouteBuilder(this, method, tokenValidator)

    fun start() {
        log.info { "Starting in cluster $cluster" }
        apiServer(8080).start()
    }

    val webhookHandler: HttpHandler = { request ->
        log.info("Receieved Webhook event")
        // log.info("Receieved Webhook. Body: ${request.bodyString()}")
        File("/tmp/latestWebhookCall").writeText("$currentDateTime\n" + request.toMessage())

        try {
            val body = request.bodyString()
//            val body =
//                request.body.stream.use { inputStream ->
//                    inputStream.readBytes().toString(Charsets.UTF_8)
//                }
            val signatureHeader = request.header("x-hub-signature-256")
            val secret = webhookSecret // already loaded from env()

            val computedHash = "sha256=" + hmacSha256(secret, body)

            val verified = computedHash == signatureHeader

            if (verified) {
                log.info("Received Webhook. Webhook signature VERIFIED! 🟢")
            } else {
                log.warn("Received Webhook. Webhook signature NOT verified 🔴 - Expected: $computedHash - Got: $signatureHeader ")
            }

            val payload = gson.fromJson(body, JsonObject::class.java)
            val eventType = request.header("x-github-event") ?: "unknown"

            // Repo name is ALWAYS present
            val repoName = payload["repository"]?.asJsonObject?.get("full_name")?.asString ?: "unknown-repo"

            // Try to find a timestamp (fallback to request received time)
            val timestamp =
                payload["created_at"]?.asString
                    ?: payload["updated_at"]?.asString
                    ?: payload["timestamp"]?.asString
                    ?: currentDateTime // fallback if none exists

            allEvents
                .getOrPut(repoName) { mutableListOf() }
                .add(EventEntry(timestamp, eventType, gsonPretty.toJson(payload))) // or your own pretty string

            log.info("Github header event type: $eventType")

            // Only handle workflow_run events
            if (eventType == "push") {
                File("/tmp/push_events").appendText(request.bodyString() + "\n\n")
            } else if (eventType == "workflow_job") {
                val jobRun = payload.getAsJsonObject("workflow_job")
                val runId = jobRun.get("run_id")?.takeIf { !it.isJsonNull }?.asLong
                if (runId != null) {
                    if (!workflowMessages.contains(runId)) workflowMessages[runId] = mutableListOf()
                    workflowMessages[runId]!!.add(gsonPretty.toJson(payload))
                }
            } else if (eventType == "workflow_run") {
                log.info("Workflow run event registered")
                File("/tmp/Workflow_run_events").appendText(request.bodyString() + "\n\n")
                val workflowRun = payload.getAsJsonObject("workflow_run")
                val runId = workflowRun.get("id")?.takeIf { !it.isJsonNull }?.asLong
                if (runId != null) {
                    if (!workflowMessages.contains(runId)) workflowMessages[runId] = mutableListOf()
                    workflowMessages[runId]!!.add(gsonPretty.toJson(payload))
                }
                val status = workflowRun.get("status")?.takeIf { !it.isJsonNull }?.asString // in_progress / completed
                val conclusion =
                    workflowRun.get("conclusion")?.takeIf { !it.isJsonNull }?.asString // success / failure / cancelled

                if (conclusion != null) {
                    val runStartedAt = Instant.parse(workflowRun.get("run_started_at")!!.asString)
                    val updatedAt = Instant.parse(workflowRun.get("updated_at")!!.asString)

                    // Track start time for parallel runs
                    if (status == "in_progress" && runId != null) {
                        workflowStartTimes[runId] = runStartedAt
                        log.info("Workflow run $runId started at $runStartedAt")
                    }

                    // When workflow ends, compute duration
                    if (status == "completed" && runId != null) {
                        val startTime = workflowStartTimes[runId] ?: runStartedAt // fallback
                        val durationSeconds = Duration.between(startTime, updatedAt).seconds

                        when (conclusion) {
                            "success" -> log.info("SUCCESS: Run $runId took $durationSeconds sec")
                            "failure" -> log.info("FAILED: Run $runId took $durationSeconds sec")
                            "cancelled" -> log.info("CANCELLED: Run $runId took $durationSeconds sec")
                            else -> log.warn("Run $runId completed with unknown conclusion: $conclusion")
                        }

                        // Clean up memory
                        workflowStartTimes.remove(runId)
                    }
                }
            }

            Response(OK)
        } catch (e: Exception) {
            log.error("Error handling webhook", e)
            Response(INTERNAL_SERVER_ERROR)
        }
    }

    fun hmacSha256(
        secret: String,
        message: String,
    ): String {
        val algorithm = "HmacSHA256"
        val mac = Mac.getInstance(algorithm)
        val secretKey = SecretKeySpec(secret.toByteArray(), algorithm)
        mac.init(secretKey)

        val hashBytes = mac.doFinal(message.toByteArray())

        // Convert to hex string
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    //    val guiHandler: HttpHandler = { _ ->
//        val html =
//            buildString {
//                append("<html><head>")
//                append(
//                    """
//            <style>
//                details { margin: 8px 0; }
//                summary { cursor: pointer; font-weight: bold; }
//                pre { background: #f4f4f4; padding: 8px; border-radius: 4px; }
//            </style>
//        """,
//                )
//                append("</head><body>")
//                append("<h1>Workflow Messages</h1>")
//
//                if (workflowMessages.isEmpty()) {
//                    append("<p>No messages received yet.</p>")
//                } else {
//                    workflowMessages.forEach { (id, messages) ->
//                        append("<details>")
//                        append("<summary>Workflow ID: $id (${messages.size} events)</summary>")
//
//                        messages.forEach { msg ->
//                            val type = if (msg.contains("workflow_job")) "Job" else "Run"
//                            append("<details style='margin-left:20px;'>")
//                            append("<summary>$type event</summary>")
//                            append("<pre>$msg</pre>")
//                            append("</details>")
//                        }
//
//                        append("</details>")
//                    }
//                }
//
//                append("</body></html>")
//            }
//
//        Response(OK).body(html).header("Content-Type", "text/html")
//    }

//    val guiHandler: HttpHandler = { _ ->
//
//        val html =
//            buildString {
//                append("<html><head>")
//                append("<style>")
//                append("details { margin-bottom: 10px; }")
//                append("pre { background: #f4f4f4; padding: 10px; border-radius: 5px; }")
//                append("</style>")
//                append("</head><body>")
//                append("<h1>Webhook Events Viewer</h1>")
//
//                allEvents.forEach { (repoName, events) ->
//                    append("<details><summary><b>$repoName</b> (${events.size} events)</summary>")
//
//                    events.forEach { ev ->
//                        append(
//                            """<details style="margin-left:20px">
//                    <summary><code>${ev.type}</code> | ${ev.timestamp}</summary>
//                    <pre>${ev.jsonPretty}</pre>
//                </details>""",
//                        )
//                    }
//
//                    append("</details>")
//                }
//
//                append("</body></html>")
//            }
//
//        Response(OK).body(html).header("Content-Type", "text/html")
//    }

    val guiHandler: HttpHandler = { _ ->
        val html =
            buildString {
                append("<html><head>")
                append("<style>")
                append(
                    """
                    body { font-family: Arial, sans-serif; padding: 20px; }
                    .repo { margin-bottom: 16px; }
                    .event { margin-left: 20px; margin-top: 8px; }
                    .pill {
                        display: inline-block;
                        padding: 2px 8px;
                        border-radius: 12px;
                        font-size: 12px;
                        color: #000;
                        margin-right: 6px;
                        font-weight: 600;
                    }
                    details { margin-bottom: 10px; }
                    pre { background: #f4f4f4; padding: 10px; border-radius: 8px; overflow-x: auto;}
                    """.trimIndent(),
                )
                append("</style></head><body>")

                append("<h2>📦 Received Events</h2>")

                if (allEvents.isEmpty()) {
                    append("<p>No events received yet.</p>")
                } else {
                    allEvents.forEach { (repoName, events) ->
                        append("<details class='repo'><summary><b>$repoName</b></summary>")

                        events.forEach { event ->
//                            val pillColor =
//                                when (event.type) {
//                                    "workflow_run" -> "#c6f6d5" // light green
//                                    "workflow_job" -> "#bee3f8" // light blue
//                                    "push" -> "#fed7d7" // light red
//                                    else -> "#e2e8f0" // light gray
//                                }

                            val pillColor =
                                when (event.type) {

                                    // 🛠 Workflow / CI (distinguished)
                                    "workflow_run" -> "#9ae6b4" // medium green
                                    "workflow_job" -> "#c6f6d5" // light green
                                    "check_suite", "check_run" -> "#d9f7e3" // extra pale green

                                    // 🐛 Issues / PRs
                                    "pull_request", "pull_request_review", "issues", "issue_comment" -> "#bee3f8"

                                    // 📝 Comments
                                    "commit_comment", "pull_request_review_comment" -> "#fefcbf"

                                    // 📣 Discussions
                                    "discussion", "discussion_comment" -> "#d6e2ff"

                                    // 📦 Deployments
                                    "deployment", "deployment_status" -> "#fcd5ce"

                                    // 👤 User / Org / Teams
                                    "member", "membership", "team", "organization" -> "#fae1dd"

                                    // 🧠 Repo / Code changes
                                    "push", "create", "delete", "branch_protection_rule" -> "#fed7d7"

                                    // ⚠ Security
                                    "security_advisory", "code_scanning_alert" -> "#fbd38d"

                                    // 🧪 Status
                                    "status" -> "#fff5b1"

                                    // ❓ Default
                                    else -> "#e2e8f0"
                                }

                            append("<details class='event'><summary>")
                            append("<span class='pill' style='background:$pillColor;'>${event.type}</span>")
                            append("${event.timestamp}</summary>")

                            append("<pre>${event.jsonPretty}</pre>")
                            append("</details>")
                        }

                        append("</details>")
                    }
                }

                append("</body></html>")
            }

        Response(OK)
            .header("Content-Type", "text/html; charset=utf-8")
            .body(html)
    }
}

data class EventEntry(
    val timestamp: String,
    val type: String,
    val jsonPretty: String,
)
