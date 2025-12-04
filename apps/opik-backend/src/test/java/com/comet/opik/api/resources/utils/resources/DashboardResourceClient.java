package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.Dashboard;
import com.comet.opik.api.Dashboard.DashboardPage;
import com.comet.opik.api.DashboardUpdate;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.apache.http.HttpStatus;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
public class DashboardResourceClient {

    private static final String RESOURCE_PATH = "%s/v1/private/dashboards";

    private final ClientSupport client;
    private final String baseURI;

    public UUID create(String apiKey, String workspaceName) {
        var dashboard = createPartialDashboard().build();
        return create(dashboard, apiKey, workspaceName);
    }

    public UUID create(Dashboard dashboard, String apiKey, String workspaceName) {
        try (var response = callCreate(dashboard, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);
            return TestUtils.getIdFromLocation(response.getLocation());
        }
    }

    public Response callCreate(Dashboard dashboard, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(dashboard));
    }

    public Dashboard createAndGet(String apiKey, String workspaceName) {
        var dashboard = createPartialDashboard().build();
        return createAndGet(dashboard, apiKey, workspaceName);
    }

    public Dashboard createAndGet(Dashboard dashboard, String apiKey, String workspaceName) {
        var id = create(dashboard, apiKey, workspaceName);
        return get(id, apiKey, workspaceName, HttpStatus.SC_OK);
    }

    public Dashboard get(UUID id, String apiKey, String workspaceName, int expectedStatus) {
        try (var response = callGet(id, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(expectedStatus);
            if (expectedStatus == HttpStatus.SC_OK) {
                return response.readEntity(Dashboard.class);
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

    public DashboardPage find(String apiKey, String workspaceName, int page, int size, String name,
            int expectedStatus) {
        return find(apiKey, workspaceName, page, size, name, null, expectedStatus);
    }

    public DashboardPage find(String apiKey, String workspaceName, int page, int size, String name,
            String sorting, int expectedStatus) {
        var target = client.target(RESOURCE_PATH.formatted(baseURI))
                .queryParam("page", page)
                .queryParam("size", size);

        if (name != null) {
            target = target.queryParam("name", name);
        }

        if (sorting != null) {
            target = target.queryParam("sorting", URLEncoder.encode(sorting, StandardCharsets.UTF_8));
        }

        try (var response = target.request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {
            assertThat(response.getStatus()).isEqualTo(expectedStatus);
            if (expectedStatus == HttpStatus.SC_OK) {
                return response.readEntity(DashboardPage.class);
            }
            return null;
        }
    }

    public void update(UUID id, DashboardUpdate update, String apiKey, String workspaceName, int expectedStatus) {
        try (var response = callUpdate(id, update, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(expectedStatus);
        }
    }

    public Dashboard updateAndGet(UUID id, DashboardUpdate update, String apiKey, String workspaceName) {
        try (var response = callUpdate(id, update, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(Dashboard.class);
        }
    }

    public Response callUpdate(UUID id, DashboardUpdate update, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .method("PATCH", Entity.json(update));
    }

    public void delete(UUID id, String apiKey, String workspaceName) {
        delete(id, apiKey, workspaceName, HttpStatus.SC_NO_CONTENT);
    }

    public void delete(UUID id, String apiKey, String workspaceName, int expectedStatus) {
        try (var response = callDelete(id, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(expectedStatus);
        }
    }

    public Response callDelete(UUID id, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .delete();
    }

    public void batchDelete(Set<UUID> ids, String apiKey, String workspaceName) {
        batchDelete(ids, apiKey, workspaceName, HttpStatus.SC_NO_CONTENT);
    }

    public void batchDelete(Set<UUID> ids, String apiKey, String workspaceName, int expectedStatus) {
        var batchDelete = com.comet.opik.api.BatchDelete.builder()
                .ids(ids)
                .build();
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("delete-batch")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(batchDelete))) {
            assertThat(response.getStatus()).isEqualTo(expectedStatus);
        }
    }

    public Dashboard.DashboardBuilder createPartialDashboard() {
        return Dashboard.builder()
                .name(UUID.randomUUID().toString())
                .description("Test dashboard description")
                .config(createValidConfig());
    }

    public JsonNode createValidConfig() {
        String configJson = """
                {
                    "version": 1,
                    "layout": {
                        "type": "grid",
                        "columns": 24,
                        "rowHeight": 10
                    },
                    "filters": {
                        "dateRange": {
                            "preset": "last_7_days"
                        }
                    },
                    "widgets": [
                        {
                            "id": "widget-1",
                            "type": "chart",
                            "title": "Latency p95",
                            "position": {"x": 0, "y": 0, "w": 6, "h": 8},
                            "data": {
                                "source": "traces",
                                "aggregation": {"metric": "latency_ms", "op": "p95"}
                            }
                        }
                    ]
                }
                """;
        return JsonUtils.getJsonNodeFromString(configJson);
    }

    public JsonNode createInvalidConfigTooLarge() {
        // Create a config that exceeds 256KB
        StringBuilder largeConfig = new StringBuilder("{\"version\":1,\"data\":\"");
        for (int i = 0; i < 300000; i++) {
            largeConfig.append("x");
        }
        largeConfig.append("\"}");
        return JsonUtils.getJsonNodeFromString(largeConfig.toString());
    }

    public JsonNode createInvalidConfigTooManyWidgets() {
        StringBuilder config = new StringBuilder("{\"version\":1,\"widgets\":[");
        for (int i = 0; i < 101; i++) {
            if (i > 0) {
                config.append(",");
            }
            config.append(String.format(
                    "{\"id\":\"widget-%d\",\"type\":\"chart\",\"position\":{\"x\":0,\"y\":0,\"w\":1,\"h\":1}}",
                    i));
        }
        config.append("]}");
        return JsonUtils.getJsonNodeFromString(config.toString());
    }
}
