package com.comet.opik.infrastructure.db;

import com.comet.opik.api.retention.RetentionPeriod;

public class RetentionPeriodMapper extends AbstractEnumColumnMapper<RetentionPeriod> {
    public RetentionPeriodMapper() {
        super(RetentionPeriod::fromString, "retention_period");
    }
}
