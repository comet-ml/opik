package com.comet.opik.api.resources.v1.events;

import com.comet.opik.domain.AgentInsightsJobService;
import com.comet.opik.domain.AgentInsightsMetrics;
import com.comet.opik.domain.AgentInsightsReportClient;
import com.comet.opik.domain.AgentInsightsReportMessage;
import com.comet.opik.infrastructure.AgentInsightsReportConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

/**
 * Consumes queued Agent Insights report triggers (published by {@code AgentInsightsReportPublisher}) and
 * performs the actual report call via {@link AgentInsightsReportClient}. The Redis consumer group caps
 * concurrent runs, so neither the manual {@code /trigger} endpoint nor the daily sweep blocks on the call.
 */
@Slf4j
@EagerSingleton
public class AgentInsightsReportSubscriber extends BaseRedisSubscriber<AgentInsightsReportMessage> {

    private static final String METRICS_NAMESPACE = "opik";
    private static final String METRICS_BASE_NAME = "agent_insights_report";

    private final AgentInsightsReportConfig config;
    private final ServiceTogglesConfig serviceToggles;
    private final AgentInsightsReportClient reportClient;
    private final AgentInsightsJobService jobService;

    @Inject
    public AgentInsightsReportSubscriber(
            @NonNull @Config("agentInsightsReport") AgentInsightsReportConfig config,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceToggles,
            @NonNull RedissonReactiveClient redisson,
            @NonNull AgentInsightsReportClient reportClient,
            @NonNull AgentInsightsJobService jobService) {
        super(config, redisson, AgentInsightsReportConfig.PAYLOAD_FIELD, METRICS_NAMESPACE, METRICS_BASE_NAME);
        this.config = config;
        this.serviceToggles = serviceToggles;
        this.reportClient = reportClient;
        this.jobService = jobService;
    }

    @Override
    public void start() {
        if (isDisabled()) {
            return;
        }
        log.info("Starting Agent Insights report subscriber with config: streamName='{}', consumerGroupName='{}', "
                + "batchSize='{}'", config.getStreamName(), config.getConsumerGroupName(),
                config.getConsumerBatchSize());
        super.start();
    }

    @Override
    public void stop() {
        if (isDisabled()) {
            return;
        }
        log.info("Stopping Agent Insights report subscriber");
        super.stop();
    }

    @Override
    protected Mono<Void> processEvent(@NonNull AgentInsightsReportMessage message) {
        log.info("Processing Agent Insights report trigger: reportId='{}', project='{}', workspace='{}'",
                message.reportId(), message.projectId(), message.workspaceId());

        // Default a null (legacy message queued before triggerSource existed) to the scheduled sweep.
        String triggerSource = message.triggerSource() != null
                ? message.triggerSource()
                : AgentInsightsMetrics.SCHEDULED;

        return Mono.fromRunnable(() -> reportClient.triggerAgentInsights(message.reportId(), message.projectId(),
                message.workspaceId(), message.periodStart(), message.periodEnd(), triggerSource))
                .subscribeOn(Schedulers.boundedElastic())
                .then()
                .doOnSuccess(unused -> {
                    // The base class records every message as success because processEvent swallows failures
                    // (at-most-once drop), so its processing-errors metric never fires for a failed trigger.
                    // This counter restores the success/failure outcome signal for the platform trigger call.
                    AgentInsightsMetrics.REPORTS_TRIGGERED.add(1, AgentInsightsMetrics.OUTCOME_SUCCESS);
                    log.info("Triggered Agent Insights report: reportId='{}', project='{}'",
                            message.reportId(), message.projectId());
                })
                .onErrorResume(throwable -> {
                    AgentInsightsMetrics.REPORTS_TRIGGERED.add(1, AgentInsightsMetrics.OUTCOME_FAILURE);
                    // At-most-once on purpose: report generation is a non-idempotent side effect (Ollie
                    // compute + a user-facing report), and the trigger isn't deduplicated downstream, so a
                    // redelivery would run the same report twice. We ack on failure (log + complete) rather
                    // than rethrow; the run is dropped (best-effort daily briefing) instead of duplicated.
                    // reportId is carried on the message as the idempotency key if downstream dedup is added.
                    log.error("Failed to trigger Agent Insights report, dropping reportId='{}', project='{}'",
                            message.reportId(), message.projectId(), throwable);
                    // Ollie never ran, so it can't report this itself: record "did not start" so the UI stops.
                    markDidNotStart(message, throwable);
                    return Mono.empty();
                });
    }

    private void markDidNotStart(AgentInsightsReportMessage message, Throwable throwable) {
        try {
            jobService.markRunFailed(message.workspaceId(), message.projectId(), "did_not_start",
                    throwable.getMessage());
        } catch (Exception e) {
            log.warn("Failed to record run failure for reportId='{}', project='{}'",
                    message.reportId(), message.projectId(), e);
        }
    }

    private boolean isDisabled() {
        if (!serviceToggles.isAgentInsightsEnabled()) {
            log.info("Agent Insights is disabled, skipping report subscriber lifecycle operation");
            return true;
        }
        return false;
    }
}
