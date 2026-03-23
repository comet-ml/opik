package com.comet.opik.infrastructure.db;

import com.comet.opik.api.retention.RetentionLevel;

public class RetentionLevelMapper extends AbstractEnumColumnMapper<RetentionLevel> {
    public RetentionLevelMapper() {
        super(RetentionLevel::fromString, "retention_level");
    }
}
