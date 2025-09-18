package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.AnnotationQueueUpdate;
import lombok.experimental.UtilityClass;

@UtilityClass
public class AnnotationQueueUtils {
    public static AnnotationQueue applyUpdate(AnnotationQueueUpdate updateRequest, AnnotationQueue existingQueue) {
        return existingQueue.toBuilder()
                .name(updateRequest.name() != null ? updateRequest.name() : existingQueue.name())
                .description(
                        updateRequest.description() != null ? updateRequest.description() : existingQueue.description())
                .instructions(updateRequest.instructions() != null
                        ? updateRequest.instructions()
                        : existingQueue.instructions())
                .commentsEnabled(updateRequest.commentsEnabled() != null
                        ? updateRequest.commentsEnabled()
                        : existingQueue.commentsEnabled())
                .feedbackDefinitionNames(updateRequest.feedbackDefinitionNames() != null
                        ? updateRequest.feedbackDefinitionNames()
                        : existingQueue.feedbackDefinitionNames())
                .build();
    }
}
