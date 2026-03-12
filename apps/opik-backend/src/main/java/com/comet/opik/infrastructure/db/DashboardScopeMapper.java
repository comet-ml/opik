package com.comet.opik.infrastructure.db;

import com.comet.opik.api.DashboardScope;

public class DashboardScopeMapper extends AbstractEnumColumnMapper<DashboardScope> {
    public DashboardScopeMapper() {
        super(DashboardScope::fromString, "dashboard_scope");
    }
}
