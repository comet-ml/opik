package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.AgentConfigCreate;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.domain.AgentBlueprint;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpStatus;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.util.UUID;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

public class AgentConfigsResourceClient {

    private static final String RESOURCE_PATH = "%s/v1/private/agent-configs";
    private static final String BLUEPRINTS_PATH = RESOURCE_PATH + "/blueprints";
    private static final String LATEST_BLUEPRINT_PATH = RESOURCE_PATH + "/blueprints/latest/projects/%s";
    private static final String BLUEPRINT_BY_ID_PATH = RESOURCE_PATH + "/blueprints/%s";
    private static final String BLUEPRINT_BY_ENV_PATH = RESOURCE_PATH + "/blueprints/environments/%s/projects/%s";
    private static final String DELTA_PATH = RESOURCE_PATH + "/blueprints/%s/deltas";
    private static final String ENVIRONMENTS_PATH = RESOURCE_PATH + "/blueprints/environments";
    private static final String HISTORY_PATH = RESOURCE_PATH + "/blueprints/history/projects/%s";

    private final ClientSupport client;
    private final String baseURI;

    public AgentConfigsResourceClient(ClientSupport client) {
        this.client = client;
        this.baseURI = TestUtils.getBaseUrl(client);
    }

    public UUID createAgentConfig(AgentConfigCreate request, String apiKey,
            String workspaceName, int expectedStatus) {
        try (var actualResponse = client.target(BLUEPRINTS_PATH.formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);

            if (expectedStatus == HttpStatus.SC_CREATED) {
                assertThat(actualResponse.getLocation()).isNotNull();

                return TestUtils.getIdFromLocation(actualResponse.getLocation());
            }

            return null;
        }
    }

    public Response createAgentConfigWithResponse(AgentConfigCreate request, String apiKey,
            String workspaceName) {
        return client.target(BLUEPRINTS_PATH.formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request));
    }

    public Response createAgentConfigWithResponse(String body, String apiKey, String workspaceName) {
        return client.target(BLUEPRINTS_PATH.formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.entity(body, ContentType.APPLICATION_JSON.toString()));
    }

    public AgentBlueprint getLatestBlueprint(UUID projectId, UUID maskId, String apiKey,
            String workspaceName, int expectedStatus) {
        var target = client.target(LATEST_BLUEPRINT_PATH.formatted(baseURI, projectId));

        if (maskId != null) {
            target = target.queryParam("mask_id", maskId);
        }

        try (var actualResponse = target
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);

            if (expectedStatus == HttpStatus.SC_OK) {
                return actualResponse.readEntity(AgentBlueprint.class);
            }

            return null;
        }
    }

    public AgentBlueprint getBlueprintById(UUID blueprintId, UUID maskId, String apiKey,
            String workspaceName, int expectedStatus) {
        var target = client.target(BLUEPRINT_BY_ID_PATH.formatted(baseURI, blueprintId));

        if (maskId != null) {
            target = target.queryParam("mask_id", maskId);
        }

        try (var actualResponse = target
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);

            if (expectedStatus == HttpStatus.SC_OK) {
                return actualResponse.readEntity(AgentBlueprint.class);
            }

            return null;
        }
    }

    public AgentBlueprint getBlueprintByEnv(String envName, UUID projectId, UUID maskId, String apiKey,
            String workspaceName, int expectedStatus) {
        var target = client.target(BLUEPRINT_BY_ENV_PATH.formatted(baseURI, envName, projectId));

        if (maskId != null) {
            target = target.queryParam("mask_id", maskId);
        }

        try (var actualResponse = target
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);

            if (expectedStatus == HttpStatus.SC_OK) {
                return actualResponse.readEntity(AgentBlueprint.class);
            }

            return null;
        }
    }

    public AgentBlueprint getDelta(UUID blueprintId, String apiKey, String workspaceName, int expectedStatus) {
        try (var actualResponse = client
                .target(DELTA_PATH.formatted(baseURI, blueprintId))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);

            if (expectedStatus == HttpStatus.SC_OK) {
                return actualResponse.readEntity(AgentBlueprint.class);
            }

            return null;
        }
    }

    public void createOrUpdateEnvs(com.comet.opik.api.AgentConfigEnvUpdate request, String apiKey,
            String workspaceName, int expectedStatus) {
        try (var actualResponse = client.target(ENVIRONMENTS_PATH.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
        }
    }

    public AgentBlueprint.BlueprintPage getHistory(UUID projectId, int page, int size,
            String apiKey, String workspaceName, int expectedStatus) {
        try (var actualResponse = client
                .target(HISTORY_PATH.formatted(baseURI, projectId))
                .queryParam("page", page)
                .queryParam("size", size)
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);

            if (expectedStatus == HttpStatus.SC_OK) {
                return actualResponse.readEntity(com.comet.opik.domain.AgentBlueprint.BlueprintPage.class);
            }

            return null;
        }
    }
}
