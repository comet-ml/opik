package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.domain.evaluators.OnlineScorePublisher;
import com.comet.opik.infrastructure.auth.RequestContext;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;

import java.util.List;

/**
 * Shared enqueue wiring for the (non-reactive) online-scoring samplers. The trace and span samplers run on the
 * EventBus thread, not inside a reactive chain, so they can't compose {@link OnlineScorePublisher#enqueueMessage}
 * themselves — they fire-and-forget. This centralizes the subscription, the workspace context seeding (so the
 * publisher's enqueue metric is labelled) and the error logging so the two samplers don't each duplicate it.
 */
@UtilityClass
class OnlineScoringSamplerSupport {

    /**
     * Subscribes the reactive enqueue, seeding the reactive context with the workspace (id + name, falling back
     * to the id when the name is blank) and logging any failure. Fire-and-forget.
     */
    static void publishSampled(OnlineScorePublisher publisher, Logger log, List<?> messages,
            AutomationRuleEvaluatorType type, String workspaceId, String workspaceName) {
        publisher.enqueueMessage(messages, type)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.WORKSPACE_NAME, StringUtils.defaultIfBlank(workspaceName, workspaceId)))
                .subscribe(unused -> {
                }, error -> log.error("Error enqueueing '{}' sampled online-scoring messages for evaluator='{}' "
                        + "workspaceId='{}' workspaceName='{}'", messages.size(), type, workspaceId, workspaceName,
                        error));
    }
}
