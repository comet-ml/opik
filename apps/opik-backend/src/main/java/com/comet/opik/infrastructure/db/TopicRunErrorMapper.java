package com.comet.opik.infrastructure.db;

import com.comet.opik.domain.topics.TopicProject;

public class TopicRunErrorMapper extends AbstractEnumColumnMapper<TopicProject.RunError> {
    public TopicRunErrorMapper() {
        super(TopicProject.RunError::fromString, "topic_run_error");
    }
}
