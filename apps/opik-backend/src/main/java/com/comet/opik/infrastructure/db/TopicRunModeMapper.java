package com.comet.opik.infrastructure.db;

import com.comet.opik.domain.topics.TopicProject;

public class TopicRunModeMapper extends AbstractEnumColumnMapper<TopicProject.RunMode> {
    public TopicRunModeMapper() {
        super(TopicProject.RunMode::fromString, "topic_run_mode");
    }
}
