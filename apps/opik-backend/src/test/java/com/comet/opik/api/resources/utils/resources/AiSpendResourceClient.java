package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.metrics.WorkspaceMetricsSummaryResponse;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.api.spend.SpendBreakdownResponse;
import com.comet.opik.api.spend.SpendCompositionResponse;
import com.comet.opik.api.spend.SpendMetricRequest;
import com.comet.opik.api.spend.SpendRecommendationsResponse;
import com.comet.opik.api.spend.SpendUserPage;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.hc.core5.http.HttpStatus;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
public class AiSpendResourceClient {

    private static final String RESOURCE_PATH = "%s/v1/private/ai-spend";

    private final ClientSupport client;
    private final String baseURI;

    public WorkspaceMetricsSummaryResponse getSummary(SpendMetricRequest request, String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("/summary")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(WorkspaceMetricsSummaryResponse.class);
        }
    }

    public SpendCompositionResponse getComposition(SpendMetricRequest request, String apiKey, String workspaceName) {
        return getComposition(request, apiKey, workspaceName, HttpStatus.SC_OK);
    }

    public SpendCompositionResponse getComposition(SpendMetricRequest request, String apiKey, String workspaceName,
            int expectedStatus) {
        try (var response = callComposition(request, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(expectedStatus);
            return expectedStatus == HttpStatus.SC_OK ? response.readEntity(SpendCompositionResponse.class) : null;
        }
    }

    private Response callComposition(SpendMetricRequest request, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("/composition")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request));
    }

    public SpendBreakdownResponse getBreakdown(String laneKey, SpendMetricRequest request, String apiKey,
            String workspaceName) {
        return getBreakdown(laneKey, request, apiKey, workspaceName, HttpStatus.SC_OK);
    }

    public SpendBreakdownResponse getBreakdown(String laneKey, SpendMetricRequest request, String apiKey,
            String workspaceName, int expectedStatus) {
        try (var response = callBreakdown(laneKey, request, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(expectedStatus);
            return expectedStatus == HttpStatus.SC_OK ? response.readEntity(SpendBreakdownResponse.class) : null;
        }
    }

    private Response callBreakdown(String laneKey, SpendMetricRequest request, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("/composition").path(laneKey).path("breakdown")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request));
    }

    public SpendUserPage getUsers(SpendMetricRequest request, int page, int size, String apiKey,
            String workspaceName) {
        return getUsers(request, page, size, null, apiKey, workspaceName);
    }

    public SpendUserPage getUsers(SpendMetricRequest request, int page, int size, List<SortingField> sortingFields,
            String apiKey, String workspaceName) {
        return getUsers(request, page, size, sortingFields, apiKey, workspaceName, HttpStatus.SC_OK);
    }

    public SpendUserPage getUsers(SpendMetricRequest request, int page, int size, List<SortingField> sortingFields,
            String apiKey, String workspaceName, int expectedStatus) {
        try (var response = callUsers(request, page, size, sortingFields, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(expectedStatus);
            return expectedStatus == HttpStatus.SC_OK ? response.readEntity(SpendUserPage.class) : null;
        }
    }

    private Response callUsers(SpendMetricRequest request, int page, int size, List<SortingField> sortingFields,
            String apiKey, String workspaceName) {
        var target = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("/users")
                .queryParam("page", page)
                .queryParam("size", size);
        if (CollectionUtils.isNotEmpty(sortingFields)) {
            target = target.queryParam("sorting",
                    URLEncoder.encode(JsonUtils.writeValueAsString(sortingFields), StandardCharsets.UTF_8));
        }
        return target
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request));
    }

    public SpendRecommendationsResponse getRecommendations(SpendMetricRequest request, String apiKey,
            String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("/recommendations")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(SpendRecommendationsResponse.class);
        }
    }
}
