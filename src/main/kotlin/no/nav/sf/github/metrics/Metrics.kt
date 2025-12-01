package no.nav.sf.github.metrics

import io.prometheus.client.CollectorRegistry
import io.prometheus.client.Counter
import io.prometheus.client.Gauge
import io.prometheus.client.Histogram
import io.prometheus.client.Summary
import io.prometheus.client.exporter.common.TextFormat
import io.prometheus.client.hotspot.DefaultExports
import mu.KotlinLogging
import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status
import java.io.StringWriter

object Metrics {
    private val log = KotlinLogging.logger { }

    val cRegistry: CollectorRegistry = CollectorRegistry.defaultRegistry

    val pushCount = registerLabelCounter("push_count", "repo")

    val latestJobDuration =
        registerLabelGauge(
            "latest_job_duration",
            "repo",
            "branch",
            "name",
            "workflow_name",
            "conclusion",
        )

    val jobsDurationSummary =
        registerLabelSummary(
            "job_duration_summary",
            "repo",
            "branch",
            "name",
            "workflow_name",
            "conclusion",
        )

    val latestWorkflowDuration =
        registerLabelGauge(
            "latest_workflow_duration",
            "repo",
            "branch",
            "name",
            "event",
            "path",
            "conclusion",
        )

    val workflowDurationSummary =
        registerLabelSummary(
            "workflow_duration_summary",
            "repo",
            "branch",
            "name",
            "event",
            "path",
            "conclusion",
        )

    fun observeJobDuration(
        repo: String,
        branch: String,
        name: String,
        workflowName: String,
        conclusion: String,
        value: Double,
    ) {
        jobsDurationSummary
            .labels(repo, branch, name, workflowName, conclusion)
            .observe(value)
        latestJobDuration
            .labels(repo, branch, name, workflowName, conclusion)
            .set(value)
    }

    fun observeWorkflowDuration(
        repo: String,
        branch: String,
        name: String,
        event: String,
        conclusion: String,
        path: String,
        value: Double,
    ) {
        workflowDurationSummary
            .labels(repo, branch, name, event, path, conclusion)
            .observe(value)
        latestWorkflowDuration
            .labels(repo, branch, name, event, path, conclusion)
            .set(value)
    }

    val apiCalls: Counter = registerLabelCounter("api_calls", "ingress")

    fun registerForwardedCallHistogram(name: String): Histogram =
        Histogram
            .build()
            .name(name)
            .help(name)
            .labelNames("targetApp", "tokenType", "status")
            .buckets(50.0, 100.0, 200.0, 300.0, 400.0, 500.0, 1000.0, 2000.0, 4000.0)
            .register()

    fun registerLabelSummary(
        name: String,
        vararg labels: String,
    ) = Summary
        .build()
        .name(name)
        .help(name)
        .labelNames(*labels)
        .register()

    fun registerLabelGauge(
        name: String,
        vararg labels: String,
    ) = Gauge
        .build()
        .name(name)
        .help(name)
        .labelNames(*labels)
        .register()

    fun registerLabelCounter(
        name: String,
        vararg labels: String,
    ) = Counter
        .build()
        .name(name)
        .help(name)
        .labelNames(*labels)
        .register()

    init {
        DefaultExports.initialize()
    }

    val metricsHttpHandler: HttpHandler = {
        try {
            val str = StringWriter()
            TextFormat.write004(str, CollectorRegistry.defaultRegistry.metricFamilySamples())
            val result = str.toString()
            if (result.isEmpty()) {
                Response(Status.NO_CONTENT)
            } else {
                Response(Status.OK).body(result)
            }
        } catch (e: Exception) {
            log.error { "/prometheus failed writing metrics - ${e.message}" }
            Response(Status.INTERNAL_SERVER_ERROR)
        }
    }
}
