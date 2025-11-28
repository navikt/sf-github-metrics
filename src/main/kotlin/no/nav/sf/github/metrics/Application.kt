package no.nav.sf.github.metrics

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import mu.KotlinLogging
import no.nav.sf.github.metrics.token.AuthRouteBuilder
import no.nav.sf.github.metrics.token.DefaultTokenValidator
import no.nav.sf.github.metrics.token.MockTokenValidator
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Response
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
import kotlin.time.Duration.Companion.seconds

class Application {
    private val log = KotlinLogging.logger { }

    var recordEventsForGui = false

    // Gson instance
    val gson = Gson()
    val gsonPretty = GsonBuilder().setPrettyPrinting().create()!!

    val allEvents: MutableMap<String, MutableList<EventEntry>> = mutableMapOf()

    val local: Boolean = System.getenv(env_NAIS_CLUSTER_NAME) == null

    val tokenValidator = if (local) MockTokenValidator() else DefaultTokenValidator()

    val cluster = if (local) "local" else env(env_NAIS_CLUSTER_NAME)

    fun apiServer(port: Int): Http4kServer = api().asServer(Netty(port))

    fun api(): HttpHandler =
        routes(
            "/internal/isAlive" bind Method.GET to { Response(OK) },
            "/internal/isReady" bind Method.GET to { Response(OK) },
            "/internal/metrics" bind Method.GET to Metrics.metricsHttpHandler,
            "/internal/gui" bind Method.GET to guiHandler,
            "/internal/hello" bind Method.GET to { Response(OK).body("Hello") },
            "/internal/secrethello" authbind Method.GET to { Response(OK).body("Secret Hello!") },
            "/internal/start" bind Method.GET to {
                recordEventsForGui = true
                Response(OK)
            },
            "/internal/stop" bind Method.GET to {
                recordEventsForGui = false
                Response(OK)
            },
            "/internal/clear" bind Method.GET to {
                allEvents.clear()
                Response(OK)
            },
            "/internal/isRecording" bind Method.GET to { Response(OK).body(recordEventsForGui.toString()) },
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
        try {
            val body = request.bodyString()
//            val signatureHeader = request.header("x-hub-signature-256")
//            val secret = webhookSecret // already loaded from env()
//
//            val computedHash = "sha256=" + hmacSha256(secret, body)
//
//            val verified = computedHash == signatureHeader
//
//            if (verified) {
//                log.info("Received Webhook. Webhook signature VERIFIED!")
//            } else {
//                log.warn("Received Webhook. Webhook signature NOT verified- Expected: $computedHash - Got: $signatureHeader ")
//            }

            val payload = gson.fromJson(body, JsonObject::class.java)
            val eventType = request.header("x-github-event") ?: "unknown"

            val repoName = payload["repository"]?.asJsonObject?.get("name")?.asString ?: "unknown-repo"
            val actionValue = payload.get("action")?.asString ?: ""

            if (recordEventsForGui) {
                allEvents
                    .getOrPut(repoName) { mutableListOf() }
                    .add(EventEntry(currentDateTime, eventType, gsonPretty.toJson(payload), actionValue))
            }

            val workflowRun = payload["workflow_run"]?.asJsonObject

            if (
                eventType == "workflow_run" && // Correct event type
                actionValue == "completed" && // Finished run
                workflowRun != null
            ) {
                val branch = workflowRun["head_branch"]?.asString ?: "unknown-branch"
                val name = workflowRun["name"]?.asString ?: "unknown-name"
                val event = workflowRun["event"]?.asString ?: "unknown-event"
                val path = workflowRun["path"]?.asString ?: "unknown-path"
                val conclusion = workflowRun["conclusion"]?.asString ?: "unknown-conclusion"

                val created = Instant.parse(workflowRun["created_at"]?.asString!!)
                val started = Instant.parse(workflowRun["run_started_at"]?.asString!!)
                val ended = Instant.parse(workflowRun["updated_at"]?.asString!!)

                val durationWorkflow = Duration.between(started, ended).seconds
                val durationDelay = Duration.between(created, started).seconds // TODO if start delays is interesting to look at

                Metrics.observeWorkflowDuration(
                    repo = repoName,
                    branch = branch,
                    name = name,
                    event = event,
                    conclusion = conclusion,
                    path = path,
                    value = durationWorkflow.toDouble(),
                )

                log.info {
                    "Workflow conclusion detected: $repoName / $name ($branch) / $event / $path / $conclusion / duration: ${durationWorkflow.seconds} s"
                }
            }

            val workflowJob = payload["workflow_job"]?.asJsonObject

            if (
                eventType == "workflow_job" && // Correct event type
                actionValue == "completed" && // Finished run
                workflowJob != null
            ) {
                val branch = workflowJob["head_branch"]?.asString ?: "unknown-branch"
                val name = workflowJob["name"]?.asString ?: "unknown-name"
                val workflowName = workflowJob["workflow_name"]?.asString ?: "unknown-workflow-name"
                val conclusion = workflowJob["conclusion"]?.asString ?: "unknown-conclusion"

                val created = Instant.parse(workflowJob["created_at"]?.asString!!)
                val started = Instant.parse(workflowJob["started_at"]?.asString!!)
                val ended = Instant.parse(workflowJob["completed_at"]?.asString!!)

                val durationJob = Duration.between(started, ended).seconds
                val durationDelay = Duration.between(created, started).seconds // TODO if start delays is interesting to look at

                Metrics.observeJobDuration(
                    repo = repoName,
                    branch = branch,
                    name = name,
                    workflowName = workflowName,
                    conclusion = conclusion,
                    value = durationJob.toDouble(),
                )

                log.info {
                    "Job conclusion detected: $repoName / $workflowName ($branch) / $name / $conclusion / duration: ${durationJob.seconds} s"
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

    val guiHandler: HttpHandler = { _ ->
        val html =
            buildString {
                append("<html><head>")
                append(
                    """
                    <script>
                        let refreshTimer = null;
                    
                        async function checkRecordingStatus() {
                            const res = await fetch('/internal/isRecording');
                            const isRecording = (await res.text()).trim() === 'true';
                    
                            // Update lamp color
                            const lamp = document.getElementById('recordLamp');
                            lamp.style.background = isRecording ? '#00f04b' : '#a6acb5';
                    
                            // Start auto-refresh if recording AND no timer exists
                            if (isRecording && !refreshTimer) {
                                refreshTimer = setInterval(() => location.reload(), 1000);
                            }
                    
                            // Stop auto-refresh if no longer recording
                            if (!isRecording && refreshTimer) {
                                clearInterval(refreshTimer);
                                refreshTimer = null;
                            }
                        }
                    
                        // Runs once when page loads
                        window.onload = checkRecordingStatus;
                    
                        // Trigger fetch when buttons are clicked
                        function trigger(action) {
                            fetch('/internal/' + action).then(checkRecordingStatus);
                        }
                    </script>
                    """.trimIndent(),
                )
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
                    .pill-secondary {
                        display: inline-block;
                        padding: 2px 8px;
                        border-radius: 12px;
                        font-size: 12px;
                        color: #000;
                        margin-right: 6px;
                        font-weight: 600;
                        border: 1px solid #aaa; /* secondary look */
                        background: transparent;
                    }
                    .control-btn {
                        display: inline-block;
                        padding: 6px 14px;
                        border-radius: 14px;
                        font-size: 13px;
                     cursor: pointer;
                     margin-right: 10px;
                     border: none;
                    }
                    .start-btn { background: #c6f6d5; }
                    .stop-btn  { background: #fed7d7; }
                    .clear-btn { background: #bee3f8; }
                    details { margin-bottom: 10px; }
                    pre { background: #f4f4f4; padding: 10px; border-radius: 8px; overflow-x: auto;}
                    """.trimIndent(),
                )
                append(
                    """
                    .status-lamp {
                        width: 20px;
                        height: 20px;
                        border-radius: 50%;
                        border: #d8d8d8;
                        border-style: solid;
                        display: inline-block;
                        margin-right: 12px;
                        background: #00f04b; /* #a6acb5 ;default gray */
                    }
                    """.trimIndent(),
                )
                append("</style></head><body>")

                append("<h2>📦 Received Events</h2>")

                append(
                    """
                    <div style='margin-bottom:16px;'>
                        <div id='recordLamp' class='status-lamp'></div>
                        <button class='control-btn start-btn' onclick="fetch('/internal/start')">Start</button>
                        <button class='control-btn stop-btn'  onclick="fetch('/internal/stop')">Stop</button>
                        <button class='control-btn clear-btn' onclick="fetch('/internal/clear')">Clear</button>
                    </div>
                    """.trimIndent(),
                )

                if (allEvents.isEmpty()) {
                    append("<p>No events received yet.</p>")
                } else {
                    allEvents.forEach { (repoName, events) ->
                        append("<details class='repo'><summary><b>$repoName</b></summary>")

                        events.forEach { event ->
                            val pillColor =
                                when (event.type) {
                                    "workflow_run" -> "#d6e2ff"
                                    "workflow_job" -> "#c6f6d5"
                                    "check_suite", "check_run" -> "#eeeeee"
                                    "pull_request", "pull_request_review", "issues", "issue_comment" -> "#bee3f8"
                                    "commit_comment", "pull_request_review_comment" -> "#fefcbf"
                                    "discussion", "discussion_comment" -> "#d6e2ff"
                                    "deployment", "deployment_status" -> "#fcd5ce"
                                    "member", "membership", "team", "organization" -> "#fae1dd"
                                    "push", "create", "delete", "branch_protection_rule" -> "#fed7d7"
                                    "security_advisory", "code_scanning_alert" -> "#fbd38d"
                                    "status" -> "#fff5b1"
                                    else -> "#e2e8f0"
                                }

                            append("<details class='event'><summary>")
                            append("<span class='pill' style='background:$pillColor;'>${event.type}</span>")

                            // only render action pill if it exists
                            if (event.action != "") {
                                val actionColor =
                                    when (event.action) {
                                        "requested" -> "#ede600" // yellow
                                        "completed" -> "#00f04b" // green
                                        "in_progress" -> "#59c3ff" // blue
                                        "opened" -> "#fbb6ce" // pink
                                        "closed" -> "#fed7d7" // light red
                                        else -> "#a6acb5"
                                    }
                                append("<span class='pill-secondary' style='border:1px solid $actionColor;'>${event.action}</span>")
                            }

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
    val action: String? = null,
)
