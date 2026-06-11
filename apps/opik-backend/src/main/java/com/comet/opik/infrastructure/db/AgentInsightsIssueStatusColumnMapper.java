package com.comet.opik.infrastructure.db;

import com.comet.opik.api.AgentInsightsIssueStatus;
import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;

public class AgentInsightsIssueStatusColumnMapper implements ColumnMapper<AgentInsightsIssueStatus> {
    @Override
    public AgentInsightsIssueStatus map(ResultSet r, int columnNumber, StatementContext ctx) throws SQLException {
        String value = r.getString(columnNumber);
        return value == null ? null : AgentInsightsIssueStatus.fromString(value);
    }
}
