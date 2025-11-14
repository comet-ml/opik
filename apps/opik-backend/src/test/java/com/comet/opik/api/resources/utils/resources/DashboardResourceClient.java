package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.Dashboard;
import com.comet.opik.api.Dashboard.DashboardPage;
import com.comet.opik.api.DashboardUpdate;
import com.comet.opik.api.resources.utils.TestUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
public class DashboardResourceClient {

    private static final String RESOURCE_PATH = "%s/v1/private/workspaces/%s/dashboards";
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        return client.target(RESOURCE_PATH.formatted(baseURI, workspaceName))
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
        return client.target(RESOURCE_PATH.formatted(baseURI, workspaceName))
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();
    }

    public DashboardPage find(String apiKey, String workspaceName, int page, int size, String search,
            int expectedStatus) {
        try (var response = callFind(apiKey, workspaceName, page, size, search)) {
            assertThat(response.getStatus()).isEqualTo(expectedStatus);
            if (expectedStatus == HttpStatus.SC_OK) {
                return response.readEntity(DashboardPage.class);
            }
            return null;
        }
    }

    public Response callFind(String apiKey, String workspaceName, int page, int size, String search) {
        var target = client.target(RESOURCE_PATH.formatted(baseURI, workspaceName))
                .queryParam("page", page)
                .queryParam("size", size);

        if (search != null) {
            target = target.queryParam("search", search);
        }

        return target.request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();
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
        return client.target(RESOURCE_PATH.formatted(baseURI, workspaceName))
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .put(Entity.json(update));
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
        return client.target(RESOURCE_PATH.formatted(baseURI, workspaceName))
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .delete();
    }

    public Dashboard.DashboardBuilder createPartialDashboard() {
        return Dashboard.builder()
                .name(UUID.randomUUID().toString())
                .description("Test dashboard description")
                .config(createValidConfig());
    }

    public JsonNode createValidConfig() {
        try {
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
            return MAPPER.readTree(configJson);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create valid config", e);
        }
    }

    public JsonNode createInvalidConfigTooLarge() {
        try {
            // Create a config that exceeds 256KB
            StringBuilder largeConfig = new StringBuilder("{\"version\":1,\"data\":\"");
            for (int i = 0; i < 300000; i++) {
                largeConfig.append("x");
            }
            largeConfig.append("\"}");
            return MAPPER.readTree(largeConfig.toString());
        } catch (Exception e) {
            throw new RuntimeException("Failed to create invalid config", e);
        }
    }

    public JsonNode createInvalidConfigTooManyWidgets() {
        try {
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
            return MAPPER.readTree(config.toString());
        } catch (Exception e) {
            throw new RuntimeException("Failed to create invalid config", e);
        }
    }
}
