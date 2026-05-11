package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.Environment;
import com.comet.opik.api.EnvironmentUpdate;
import com.comet.opik.api.resources.utils.TestUtils;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;

@RequiredArgsConstructor
public class EnvironmentsResourceClient {

    private static final String URL_TEMPLATE = "%s/v1/private/environments";

    private final ClientSupport client;
    private final String baseURI;

    public UUID createEnvironment(Environment environment, String apiKey, String workspaceName) {
        try (var response = callCreate(environment, apiKey, workspaceName)) {
            if (response.getStatusInfo().getStatusCode() != 201) {
                throw new IllegalStateException(
                        "Unexpected status creating environment: " + response.getStatusInfo().getStatusCode());
            }
            return TestUtils.getIdFromLocation(response.getLocation());
        }
    }

    public Response callCreate(Environment environment, String apiKey, String workspaceName) {
        return client.target(URL_TEMPLATE.formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(environment));
    }

    public Response callGet(UUID id, String apiKey, String workspaceName) {
        return client.target(URL_TEMPLATE.formatted(baseURI))
                .path(id.toString())
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();
    }

    public Response callFind(String apiKey, String workspaceName) {
        return client.target(URL_TEMPLATE.formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();
    }

    public Response callUpdate(UUID id, EnvironmentUpdate update, String apiKey, String workspaceName) {
        return client.target(URL_TEMPLATE.formatted(baseURI))
                .path(id.toString())
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .method("PATCH", Entity.json(update));
    }

    public Response callBatchDelete(Set<UUID> ids, String apiKey, String workspaceName) {
        return callBatchDelete(new BatchDelete(ids), apiKey, workspaceName);
    }

    public Response callBatchDelete(BatchDelete batchDelete, String apiKey, String workspaceName) {
        return client.target(URL_TEMPLATE.formatted(baseURI))
                .path("delete")
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(batchDelete));
    }
}
