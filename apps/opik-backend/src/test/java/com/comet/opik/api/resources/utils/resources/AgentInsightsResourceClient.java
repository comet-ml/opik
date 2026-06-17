package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.AgentInsightsIssue;
import com.comet.opik.api.AgentInsightsIssueStatus;
import com.comet.opik.api.AgentInsightsIssueUpdate;
import com.comet.opik.api.AgentInsightsIssueWithDetails;
import com.comet.opik.api.AgentInsightsReport;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.utils.JsonUtils;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpStatus;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

public class AgentInsightsResourceClient {

    private static final String INTERNAL_ISSUES_PATH = "%s/v1/private/agent-insights/issues";
    private static final String ISSUES_PATH = "%s/v1/private/agent-insights/issues";
    private static final String ISSUE_BY_ID_PATH = ISSUES_PATH + "/%s";

    private final ClientSupport client;
    private final String baseURI;

    public AgentInsightsResourceClient(ClientSupport client) {
        this.client = client;
        this.baseURI = TestUtils.getBaseUrl(client);
    }

    public void reportIssues(AgentInsightsReport report, String apiKey, String workspaceName, int expectedStatus) {
        try (var actualResponse = reportIssuesWithResponse(report, apiKey, workspaceName)) {
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
        }
    }

    public Response reportIssuesWithResponse(AgentInsightsReport report, String apiKey, String workspaceName) {
        return client.target(INTERNAL_ISSUES_PATH.formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(report));
    }

    public AgentInsightsIssue.AgentInsightsIssuePage findIssues(UUID projectId, LocalDate fromDate, LocalDate toDate,
            AgentInsightsIssueStatus status, List<SortingField> sorting, Integer page, Integer size,
            String apiKey, String workspaceName, int expectedStatus) {
        try (var actualResponse = findIssuesWithResponse(projectId,
                fromDate == null ? null : fromDate.toString(),
                toDate == null ? null : toDate.toString(),
                status == null ? null : status.getValue(),
                CollectionUtils.isEmpty(sorting) ? null : JsonUtils.writeValueAsString(sorting),
                page, size, apiKey, workspaceName)) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);

            if (expectedStatus == HttpStatus.SC_OK) {
                return actualResponse.readEntity(AgentInsightsIssue.AgentInsightsIssuePage.class);
            }

            return null;
        }
    }

    public Response findIssuesWithResponse(UUID projectId, String fromDate, String toDate, String status,
            String sorting, Integer page, Integer size, String apiKey, String workspaceName) {
        WebTarget target = client.target(ISSUES_PATH.formatted(baseURI));

        if (projectId != null) {
            target = target.queryParam("project_id", projectId);
        }
        if (fromDate != null) {
            target = target.queryParam("from_date", fromDate);
        }
        if (toDate != null) {
            target = target.queryParam("to_date", toDate);
        }
        if (status != null) {
            target = target.queryParam("status", status);
        }
        if (sorting != null) {
            target = target.queryParam("sorting", URLEncoder.encode(sorting, StandardCharsets.UTF_8));
        }
        if (page != null) {
            target = target.queryParam("page", page);
        }
        if (size != null) {
            target = target.queryParam("size", size);
        }

        return target.request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();
    }

    public AgentInsightsIssueWithDetails getIssue(UUID issueId, UUID projectId, LocalDate fromDate, LocalDate toDate,
            String apiKey, String workspaceName, int expectedStatus) {
        WebTarget target = client.target(ISSUE_BY_ID_PATH.formatted(baseURI, issueId))
                .queryParam("project_id", projectId);
        if (fromDate != null) {
            target = target.queryParam("from_date", fromDate);
        }
        if (toDate != null) {
            target = target.queryParam("to_date", toDate);
        }
        try (var actualResponse = target.request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);

            if (expectedStatus == HttpStatus.SC_OK) {
                return actualResponse.readEntity(AgentInsightsIssueWithDetails.class);
            }

            return null;
        }
    }

    public void updateStatus(UUID issueId, AgentInsightsIssueUpdate update, String apiKey, String workspaceName,
            int expectedStatus) {
        try (var actualResponse = client.target(ISSUE_BY_ID_PATH.formatted(baseURI, issueId))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .method("PATCH", Entity.json(update))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
        }
    }

    public Response updateStatusWithResponse(UUID issueId, String body, String apiKey, String workspaceName) {
        return client.target(ISSUE_BY_ID_PATH.formatted(baseURI, issueId))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .method("PATCH", Entity.entity(body, ContentType.APPLICATION_JSON.toString()));
    }
}
