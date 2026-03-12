package com.comet.opik.infrastructure.db;

import com.comet.opik.api.DashboardType;

public class DashboardTypeMapper extends AbstractEnumColumnMapper<DashboardType> {
    public DashboardTypeMapper() {
        super(DashboardType::fromString, "dashboard_type");
    }
}
