package com.comet.opik.infrastructure.db;

import com.comet.opik.api.WorkspaceConfiguration;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;

public class WorkspaceConfigurationRowMapper implements RowMapper<WorkspaceConfiguration> {
    @Override
    public WorkspaceConfiguration map(ResultSet rs, StatementContext ctx) throws SQLException {
        Long timeoutSeconds = rs.getObject("timeoutToMarkThreadAsInactive", Long.class);
        Duration timeout = timeoutSeconds != null ? Duration.ofSeconds(timeoutSeconds) : null;
        return WorkspaceConfiguration.builder()
                .timeoutToMarkThreadAsInactive(timeout)
                .build();
    }
}
