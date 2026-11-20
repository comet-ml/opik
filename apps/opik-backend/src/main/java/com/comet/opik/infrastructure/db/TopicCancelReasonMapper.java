package com.comet.opik.infrastructure.db;

import com.comet.opik.domain.topics.TopicProject;

public class TopicCancelReasonMapper extends AbstractEnumColumnMapper<TopicProject.CancelReason> {
    public TopicCancelReasonMapper() {
        super(TopicProject.CancelReason::fromString, "topic_cancel_reason");
    }
}
