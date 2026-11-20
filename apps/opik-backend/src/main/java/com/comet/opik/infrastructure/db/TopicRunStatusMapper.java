package com.comet.opik.infrastructure.db;

import com.comet.opik.domain.topics.TopicProject;

public class TopicRunStatusMapper extends AbstractEnumColumnMapper<TopicProject.RunStatus> {
    public TopicRunStatusMapper() {
        super(TopicProject.RunStatus::fromString, "topic_run_status");
    }
}
