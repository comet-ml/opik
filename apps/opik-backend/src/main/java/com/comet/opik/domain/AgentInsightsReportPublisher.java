package com.comet.opik.domain;

import com.comet.opik.infrastructure.AgentInsightsReportConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.redis.RedisStreamUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes Agent Insights report-trigger requests onto a Redis stream so the report subscriber can
 * execute them with bounded concurrency. Both the manual {@code /trigger} endpoint and the daily cron
 * enqueue through here, so the actual (potentially slow) report call never runs on a request or sweep
 * thread, and the consumer group caps the in-flight fan-out.
 */
@Slf4j
@Singleton
public class AgentInsightsReportPublisher {

    private final @NonNull RedissonReactiveClient redisson;
    private final @NonNull AgentInsightsReportConfig config;
    private final @NonNull ServiceTogglesConfig serviceToggles;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull AgentInsightsRunGuidanceService runGuidanceService;

    @Inject
    public AgentInsightsReportPublisher(@NonNull RedissonReactiveClient redisson,
            @NonNull @Config("agentInsightsReport") AgentInsightsReportConfig config,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceToggles,
            @NonNull IdGenerator idGenerator,
            @NonNull AgentInsightsRunGuidanceService runGuidanceService) {
        this.redisson = redisson;
        this.config = config;
        this.serviceToggles = serviceToggles;
        this.idGenerator = idGenerator;
        this.runGuidanceService = runGuidanceService;
    }

    /**
     * Enqueues a report-trigger request and returns the generated report id once the message is on the
     * stream, or completes empty when publishing is disabled.
     */
    public Mono<String> enqueue(@NonNull UUID projectId, @NonNull String workspaceId,
            @NonNull Instant periodStart, @NonNull Instant periodEnd, @NonNull String triggerSource) {

        if (!serviceToggles.isAgentInsightsActive()) {
            log.debug("Agent Insights is disabled, ignoring trigger for project '{}'", projectId);
            return Mono.empty();
        }

        String reportId = idGenerator.generateId().toString();

        return Mono.defer(() -> {
            // Read at enqueue so every run source (manual, scheduled, auto-first-run) carries the current guidance.
            // Blocking JDBC, hence inside defer on the bounded-elastic scheduler.
            var runGuidance = runGuidanceService.find(workspaceId, projectId);
            Integer guidanceVersion = runGuidance.map(AgentInsightsJobDAO.RunGuidance::guidanceVersion).orElse(null);
            var message = AgentInsightsReportMessage.builder()
                    .reportId(reportId)
                    .projectId(projectId)
                    .workspaceId(workspaceId)
                    .periodStart(periodStart)
                    .periodEnd(periodEnd)
                    .triggerSource(triggerSource)
                    .guidance(runGuidance.map(AgentInsightsJobDAO.RunGuidance::guidance).orElse(null))
                    .guidanceVersion(guidanceVersion)
                    .build();

            // DEBUG: the daily sweep enqueues one per enabled project, so keep INFO for lifecycle events only.
            log.debug("Publishing Agent Insights report trigger: reportId='{}', project='{}', workspace='{}'",
                    reportId, projectId, workspaceId);

            RStreamReactive<String, AgentInsightsReportMessage> stream = redisson.getStream(
                    config.getStreamName(), config.getCodec());

            return stream.add(RedisStreamUtils.buildAddArgs(
                    AgentInsightsReportConfig.PAYLOAD_FIELD, message, config))
                    .publishOn(Schedulers.boundedElastic())
                    .map(streamMessageId -> {
                        markEnqueued(workspaceId, projectId, guidanceVersion, reportId);
                        return reportId;
                    })
                    .doOnError(throwable -> log.error(
                            "Failed to publish Agent Insights report trigger: reportId='{}', project='{}'",
                            reportId, projectId, throwable));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Best-effort: the run is already queued, so failing here would report a run that did start as one that did
     * not. A missed stamp only leaves the "guidance changed" callout less precise.
     */
    private void markEnqueued(String workspaceId, UUID projectId, Integer guidanceVersion, String reportId) {
        try {
            runGuidanceService.markEnqueued(workspaceId, projectId, guidanceVersion);
        } catch (RuntimeException e) {
            log.warn("Failed to record the guidance version of a queued Agent Insights run, reportId '{}', "
                    + "project '{}'", reportId, projectId, e);
        }
    }
}
