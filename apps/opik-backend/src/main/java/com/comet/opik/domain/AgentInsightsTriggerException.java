package com.comet.opik.domain;

import com.comet.opik.api.AgentInsightsJob;
import lombok.Getter;
import lombok.NonNull;

/**
 * An Agent Insights trigger that was refused by the platform, carrying the {@link AgentInsightsJob.FailureReason}
 * to record for the run.
 *
 * <p>The daily report's equivalent classification hands the reason back through a {@code Consumer<String>},
 * because {@code OrchestratorClient} triggers asynchronously and has no return path. This client is
 * synchronous and its caller drives the failure metric and the at-most-once drop off the reactive error
 * channel, so the reason travels on the exception instead. The principle is the same either way: the client
 * names the reason, and the caller only records it.
 */
@Getter
public class AgentInsightsTriggerException extends IllegalStateException {

    private final String reason;

    public AgentInsightsTriggerException(@NonNull String reason, String message) {
        super(message);
        this.reason = reason;
    }
}
