package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.events.TracesDeleted;
import com.comet.opik.domain.CommentDAO;
import com.comet.opik.domain.EntityType;
import com.comet.opik.domain.FeedbackScoreDAO;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.attachment.AttachmentService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.attachment.EntityType.TRACE;

@EagerSingleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TraceDeletedListener {

    private final @NonNull FeedbackScoreDAO feedbackScoreDAO;
    private final @NonNull CommentDAO commentDAO;
    private final @NonNull AttachmentService attachmentService;
    private final @NonNull SpanService spanService;

    /**
     * Handles the TracesDeleted event by asynchronously deleting related entities.
     * This includes feedback scores, comments, attachments, and spans associated with the deleted traces.
     *
     * @param event the TracesDeleted event containing the trace IDs that were deleted
     */
    @Subscribe
    public void onTracesDeleted(@NonNull TracesDeleted event) {
        Set<UUID> traceIds = event.traceIds();
        String workspaceId = event.workspaceId();
        String userName = event.userName();
        UUID projectId = event.projectId();

        log.info(
                "Received TracesDeleted event for workspace: '{}', trace count: '{}'. Processing related entity deletion",
                workspaceId, traceIds.size());

        processTraceDeletion(traceIds, projectId)
                .doOnError(error -> {
                    log.error(
                            "Failed to process TracesDeleted event for workspace: '{}', trace count: '{}', error: '{}'",
                            workspaceId, traceIds.size(), error.getMessage());
                    log.error("Error processing trace related entity deletion", error);
                })
                .doOnSuccess(__ -> log.info(
                        "Successfully processed TracesDeleted event for workspace: '{}', trace count: '{}'",
                        workspaceId, traceIds.size()))
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, userName))
                .subscribe();
    }

    /**
     * Processes the deletion of all entities related to the traces.
     * This method handles the deletion in the correct order to maintain referential integrity.
     *
     * @param traceIds the set of trace IDs whose related entities should be deleted
     * @return a Mono that completes when all related entities have been deleted
     */
    private Mono<Void> processTraceDeletion(Set<UUID> traceIds, UUID projectId) {
        log.info("Starting deletion of related entities for traces, count '{}'", traceIds.size());

        return feedbackScoreDAO.deleteByEntityIds(EntityType.TRACE, traceIds, projectId)
                .then(Mono.defer(() -> commentDAO.deleteByEntityIds(CommentDAO.EntityType.TRACE, traceIds)))
                .then(Mono.defer(() -> attachmentService.deleteByEntityIds(TRACE, traceIds)))
                .then(Mono.defer(() -> spanService.deleteByTraceIds(traceIds, projectId)));
    }
}
