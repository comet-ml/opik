package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.retention.RetentionPeriod;
import com.comet.opik.api.retention.RetentionRule;
import com.comet.opik.api.retention.RetentionRule.RetentionRulePage;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.apache.http.HttpStatus;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.util.UUID;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
public class RetentionRuleResourceClient {

    private static final String RESOURCE_PATH = "%s/v1/private/retention/rules";

    private final ClientSupport client;
    private final String baseURI;

    public UUID create(RetentionRule rule, String apiKey, String workspaceName) {
        try (var response = callCreate(rule, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);
            return TestUtils.getIdFromLocation(response.getLocation());
        }
    }

    public Response callCreate(RetentionRule rule, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(rule));
    }

    public RetentionRule createAndGet(RetentionRule rule, String apiKey, String workspaceName) {
        var id = create(rule, apiKey, workspaceName);
        return get(id, apiKey, workspaceName, HttpStatus.SC_OK);
    }

    public RetentionRule get(UUID id, String apiKey, String workspaceName, int expectedStatus) {
        try (var response = callGet(id, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(expectedStatus);
            if (expectedStatus == HttpStatus.SC_OK) {
                return response.readEntity(RetentionRule.class);
            }
            return null;
        }
    }

    public Response callGet(UUID id, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();
    }

    public RetentionRulePage find(String apiKey, String workspaceName, int page, int size,
            boolean includeInactive, int expectedStatus) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .queryParam("page", page)
                .queryParam("size", size)
                .queryParam("include_inactive", includeInactive)
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {
            assertThat(response.getStatus()).isEqualTo(expectedStatus);
            if (expectedStatus == HttpStatus.SC_OK) {
                return response.readEntity(RetentionRulePage.class);
            }
            return null;
        }
    }

    public void deactivate(UUID id, String apiKey, String workspaceName) {
        deactivate(id, apiKey, workspaceName, HttpStatus.SC_NO_CONTENT);
    }

    public void deactivate(UUID id, String apiKey, String workspaceName, int expectedStatus) {
        try (var response = callDeactivate(id, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(expectedStatus);
        }
    }

    public Response callDeactivate(UUID id, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .delete();
    }

    public RetentionRule.RetentionRuleBuilder buildWorkspaceRule(RetentionPeriod retention) {
        return RetentionRule.builder()
                .retention(retention);
    }

    public RetentionRule.RetentionRuleBuilder buildProjectRule(UUID projectId, RetentionPeriod retention) {
        return RetentionRule.builder()
                .projectId(projectId)
                .retention(retention);
    }

    public RetentionRule.RetentionRuleBuilder buildOrganizationRule(RetentionPeriod retention) {
        return RetentionRule.builder()
                .organizationLevel(true)
                .retention(retention);
    }
}
