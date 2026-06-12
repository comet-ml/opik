package com.comet.opik.infrastructure.db;

import com.comet.opik.api.AgentInsightsIssueStatus;
import jakarta.ws.rs.WebApplicationException;

public class AgentInsightsIssueStatusColumnMapper extends AbstractEnumColumnMapper<AgentInsightsIssueStatus> {
    public AgentInsightsIssueStatusColumnMapper() {
        // fromString throws BadRequestException (WebApplicationException) for invalid values; convert to
        // IllegalArgumentException so the base class can swallow and log bad DB values without propagating.
        super(value -> {
            try {
                return AgentInsightsIssueStatus.fromString(value);
            } catch (WebApplicationException e) {
                throw new IllegalArgumentException(e);
            }
        }, "agent_insights_issue_status");
    }
}
