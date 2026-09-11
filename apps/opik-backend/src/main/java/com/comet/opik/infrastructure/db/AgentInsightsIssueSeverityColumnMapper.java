package com.comet.opik.infrastructure.db;

import com.comet.opik.api.AgentInsightsIssueSeverity;
import jakarta.ws.rs.WebApplicationException;

public class AgentInsightsIssueSeverityColumnMapper extends AbstractEnumColumnMapper<AgentInsightsIssueSeverity> {
    public AgentInsightsIssueSeverityColumnMapper() {
        super(value -> {
            try {
                return AgentInsightsIssueSeverity.fromString(value);
            } catch (WebApplicationException e) {
                throw new IllegalArgumentException(e);
            }
        }, "agent_insights_issue_severity");
    }
}
