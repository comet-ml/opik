package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.view.View;
import com.comet.opik.api.view.ViewCreate;
import com.comet.opik.api.view.ViewCurrentVersion;
import com.comet.opik.api.view.ViewUpdate;
import com.comet.opik.api.view.ViewVersion;
import com.comet.opik.api.view.ViewVersionCreate;
import com.comet.opik.api.view.ViewVersionPage;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.apache.http.HttpStatus;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.util.UUID;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
public class ViewsResourceClient {

    private static final String RESOURCE_PATH = "%s/v1/private/views";

    private final ClientSupport client;
    private final String baseURI;

    public Invocation.Builder request(String pathSuffix, String apiKey, String workspaceName) {
        return withAuth(client.target(RESOURCE_PATH.formatted(baseURI) + pathSuffix), apiKey, workspaceName);
    }

    public View create(ViewCreate request, String apiKey, String workspaceName) {
        try (var response = callCreate(request, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);
            var view = response.readEntity(View.class);
            assertThat(response.getLocation().getPath()).endsWith("/v1/private/views/" + view.id());
            return view;
        }
    }

    public Response callCreate(Object request, String apiKey, String workspaceName) {
        return withAuth(viewsTarget(), apiKey, workspaceName)
                .post(Entity.json(request));
    }

    public ViewVersion createVersion(UUID id, ViewVersionCreate request, String apiKey, String workspaceName) {
        try (var response = callCreateVersion(id, request, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);
            var version = response.readEntity(ViewVersion.class);
            assertThat(response.getLocation().getPath())
                    .endsWith("/v1/private/views/%s/versions/%d".formatted(id, version.version()));
            return version;
        }
    }

    public Response callCreateVersion(UUID id, ViewVersionCreate request, String apiKey, String workspaceName) {
        return withAuth(versionsTarget(id), apiKey, workspaceName)
                .post(Entity.json(request));
    }

    public View get(UUID id, String apiKey, String workspaceName) {
        try (var response = callGet(id, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(View.class);
        }
    }

    public Response callGet(UUID id, String apiKey, String workspaceName) {
        return withAuth(viewTarget(id), apiKey, workspaceName)
                .get();
    }

    public ViewVersion getVersion(UUID id, int version, String apiKey, String workspaceName) {
        try (var response = callGetVersion(id, version, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(ViewVersion.class);
        }
    }

    public Response callGetVersion(UUID id, int version, String apiKey, String workspaceName) {
        return withAuth(versionsTarget(id).path(String.valueOf(version)), apiKey, workspaceName)
                .get();
    }

    public ViewVersionPage getVersions(UUID id, String apiKey, String workspaceName) {
        return getVersions(id, null, null, apiKey, workspaceName);
    }

    public ViewVersionPage getVersions(UUID id, Integer page, Integer size, String apiKey, String workspaceName) {
        try (var response = callGetVersions(id, page, size, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(ViewVersionPage.class);
        }
    }

    public Response callGetVersions(UUID id, Integer page, Integer size, String apiKey, String workspaceName) {
        var target = versionsTarget(id);
        if (page != null) {
            target = target.queryParam("page", page);
        }
        if (size != null) {
            target = target.queryParam("size", size);
        }
        return withAuth(target, apiKey, workspaceName)
                .get();
    }

    public View setCurrent(UUID id, int version, String apiKey, String workspaceName) {
        try (var response = callSetCurrent(id, version, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(View.class);
        }
    }

    public Response callSetCurrent(UUID id, int version, String apiKey, String workspaceName) {
        return withAuth(viewTarget(id).path("current"), apiKey, workspaceName)
                .put(Entity.json(ViewCurrentVersion.builder().version(version).build()));
    }

    public View update(UUID id, ViewUpdate update, String apiKey, String workspaceName) {
        try (var response = callUpdate(id, update, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(View.class);
        }
    }

    public Response callUpdate(UUID id, ViewUpdate update, String apiKey, String workspaceName) {
        return withAuth(viewTarget(id), apiKey, workspaceName)
                .method("PATCH", Entity.json(update));
    }

    public void delete(UUID id, String apiKey, String workspaceName) {
        try (var response = callDelete(id, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }

    public Response callDelete(UUID id, String apiKey, String workspaceName) {
        return withAuth(viewTarget(id), apiKey, workspaceName)
                .delete();
    }

    public void deleteVersion(UUID id, int version, String apiKey, String workspaceName) {
        try (var response = callDeleteVersion(id, version, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }

    public Response callDeleteVersion(UUID id, int version, String apiKey, String workspaceName) {
        return withAuth(versionsTarget(id).path(String.valueOf(version)), apiKey, workspaceName)
                .delete();
    }

    private WebTarget viewsTarget() {
        return client.target(RESOURCE_PATH.formatted(baseURI));
    }

    private WebTarget viewTarget(UUID id) {
        return viewsTarget().path(id.toString());
    }

    private WebTarget versionsTarget(UUID id) {
        return viewTarget(id).path("versions");
    }

    private Invocation.Builder withAuth(WebTarget target, String apiKey, String workspaceName) {
        return target.request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName);
    }
}
