package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.events.TracesDeleted;
import com.comet.opik.domain.CommentDAO;
import com.comet.opik.domain.CommentService;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.ailabels.AiLabelStatusDAO;
import com.comet.opik.domain.ailabels.AiLabelsDAO;
import com.comet.opik.domain.attachment.AttachmentService;
import com.comet.opik.domain.topics.TopicVectorsDAO;
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

    private final @NonNull FeedbackScoreService feedbackScoreService;
    private final @NonNull CommentService commentService;
    private final @NonNull AttachmentService attachmentService;
    private final @NonNull SpanService spanService;
    private final @NonNull AiLabelsDAO aiLabelsDAO;
    private final @NonNull AiLabelStatusDAO aiLabelStatusDAO;
    private final @NonNull TopicVectorsDAO topicVectorsDAO;

    /**
     * Handles the TracesDeleted event by asynchronously deleting related entities.
     * This includes feedback scores, comments, attachments, ai-labels, topic vectors and spans associated with the deleted traces.
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

        processTraceDeletion(traceIds, projectId, workspaceId)
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
     * @param projectId the owning project the event resolved; required, so the span cascade is never workspace-wide
     * @param workspaceId the event's workspace, for the logs only; the deletes read it from the reactive context
     * @return a Mono that completes when all related entities have been deleted
     */
    private Mono<Void> processTraceDeletion(Set<UUID> traceIds, @NonNull UUID projectId, String workspaceId) {
        log.info("Starting deletion of related entities for traces, count '{}'", traceIds.size());

        return feedbackScoreService.deleteByTraceIds(traceIds, projectId)
                .then(Mono.defer(() -> commentService.deleteByEntityIds(CommentDAO.EntityType.TRACE, traceIds,
                        projectId)))
                .then(Mono.defer(() -> attachmentService.deleteByEntityIds(TRACE, traceIds, projectId)))
                // Topic vectors hold the summary text, so they go with the trace too. Before ai-labels and on their own,
                // so neither delete's failure can skip the other; neither ever blocks the span cascade.
                .then(Mono.defer(() -> topicVectorsDAO.deleteByTraceIds(traceIds, projectId)
                        .onErrorResume(error -> {
                            log.warn("Failed to delete topic vectors for deleted traces, workspace '{}', project '{}', "
                                    + "count '{}'; they stay until a retention delete", workspaceId, projectId,
                                    traceIds.size(),
                                    error);
                            return Mono.empty();
                        })))
                // Unconditional, even with ai-labels off: labels hold trace-derived text and must not outlive the trace.
                // A failure there must not skip the span cascade; retention removes any rows left behind.
                .then(Mono.defer(() -> aiLabelsDAO.deleteByTraceIds(traceIds, projectId)
                        .then(aiLabelStatusDAO.deleteByTraceIds(traceIds, projectId))
                        .onErrorResume(error -> {
                            log.error("Failed to delete ai-labels for deleted traces, count '{}'", traceIds.size(),
                                    error);
                            return Mono.empty();
                        })))
                .then(Mono.defer(() -> spanService.deleteByTraceIds(traceIds, projectId)));
    }
}
