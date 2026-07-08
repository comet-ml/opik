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

    @Inject
    public AgentInsightsReportPublisher(@NonNull RedissonReactiveClient redisson,
            @NonNull @Config("agentInsightsReport") AgentInsightsReportConfig config,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceToggles,
            @NonNull IdGenerator idGenerator) {
        this.redisson = redisson;
        this.config = config;
        this.serviceToggles = serviceToggles;
        this.idGenerator = idGenerator;
    }

    /**
     * Enqueues a report-trigger request and returns the generated report id once the message is on the
     * stream, or completes empty when publishing is disabled.
     */
    public Mono<String> enqueue(@NonNull UUID projectId, @NonNull String workspaceId,
            @NonNull Instant periodStart, @NonNull Instant periodEnd, @NonNull String triggerSource) {

        if (!serviceToggles.isAgentInsightsEnabled()) {
            log.debug("Agent Insights is disabled, ignoring trigger for project '{}'", projectId);
            return Mono.empty();
        }

        String reportId = idGenerator.generateId().toString();
        var message = AgentInsightsReportMessage.builder()
                .reportId(reportId)
                .projectId(projectId)
                .workspaceId(workspaceId)
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .triggerSource(triggerSource)
                .build();

        // DEBUG: the daily sweep enqueues one per enabled project, so keep INFO for lifecycle events only.
        log.debug("Publishing Agent Insights report trigger: reportId='{}', project='{}', workspace='{}'",
                reportId, projectId, workspaceId);

        return Mono.defer(() -> {
            RStreamReactive<String, AgentInsightsReportMessage> stream = redisson.getStream(
                    config.getStreamName(), config.getCodec());

            return stream.add(RedisStreamUtils.buildAddArgs(
                    AgentInsightsReportConfig.PAYLOAD_FIELD, message, config))
                    .map(streamMessageId -> reportId)
                    .doOnError(throwable -> log.error(
                            "Failed to publish Agent Insights report trigger: reportId='{}', project='{}'",
                            reportId, projectId, throwable));
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
