package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.connect.ActivateRequest;
import com.comet.opik.api.connect.CreateSessionRequest;
import com.comet.opik.api.connect.CreateSessionResponse;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.apache.http.HttpStatus;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.net.URI;
import java.util.UUID;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
public class OpikConnectResourceClient {

    private static final String RESOURCE_PATH = "%s/v1/private/opik-connect";

    private final ClientSupport client;
    private final String baseURI;

    public CreateSessionResponse createSession(CreateSessionRequest request, String apiKey, String workspaceName) {
        try (Response response = callCreateSession(request, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);
            return response.readEntity(CreateSessionResponse.class);
        }
    }

    public Response callCreateSession(CreateSessionRequest request, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("sessions")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request));
    }

    public UUID activate(UUID sessionId, ActivateRequest request, String apiKey, String workspaceName) {
        try (Response response = callActivate(sessionId, request, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);
            String location = response.getHeaderString("Location");
            assertThat(location).isNotBlank();
            String path = URI.create(location).getPath();
            String[] segments = path.split("/");
            return UUID.fromString(segments[segments.length - 1]);
        }
    }

    public Response callActivate(UUID sessionId, ActivateRequest request, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("sessions")
                .path(sessionId.toString())
                .path("activate")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request));
    }
}
