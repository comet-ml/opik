package com.comet.opik.infrastructure.db;

import com.comet.opik.api.AgentInsightsIssueStatus;
import jakarta.ws.rs.WebApplicationException;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;

@Slf4j
public class AgentInsightsIssueStatusColumnMapper implements ColumnMapper<AgentInsightsIssueStatus> {

    @Override
    public AgentInsightsIssueStatus map(ResultSet r, int columnNumber, StatementContext ctx) throws SQLException {
        return parse(r.getString(columnNumber));
    }

    @Override
    public AgentInsightsIssueStatus map(ResultSet r, String columnLabel, StatementContext ctx) throws SQLException {
        return parse(r.getString(columnLabel));
    }

    private AgentInsightsIssueStatus parse(String value) {
        if (value == null) {
            return null;
        }

        // fromString throws BadRequestException to map invalid query params to 400; a bad value read
        // from the DB is a server-side integrity problem, not a client error, so swallow and log it.
        try {
            return AgentInsightsIssueStatus.fromString(value);
        } catch (WebApplicationException exception) {
            log.warn("Failed to parse agent_insights_issue_status: '{}'", value, exception);
            return null;
        }
    }
}
