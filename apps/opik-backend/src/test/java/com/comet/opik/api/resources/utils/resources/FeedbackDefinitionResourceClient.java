package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.BatchDelete;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;

@RequiredArgsConstructor
public class FeedbackDefinitionResourceClient {

    private static final String URL_TEMPLATE = "%s/v1/private/feedback-definitions";

    private final ClientSupport client;
    private final String baseURI;

    public Response deleteFeedbackDefinition(UUID id, String apiKey, String workspaceName) {
        return client.target(URL_TEMPLATE.formatted(baseURI))
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .delete();
    }

    public Response deleteBatchFeedbackDefinition(Set<UUID> idsToDelete, String apiKey, String workspaceName) {
        return client.target(URL_TEMPLATE.formatted(baseURI))
                .path("delete")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(new BatchDelete(new HashSet<>(idsToDelete))));
    }

}
