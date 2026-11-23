package com.comet.opik.infrastructure.auth;

import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.infrastructure.AuthenticationConfig;
import com.comet.opik.infrastructure.RetriableHttpClient;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.net.URI;
import java.time.Duration;

@Slf4j
@RequiredArgsConstructor
class WorkspaceOrganizationClient {

    static final String UNEXPECTED_RESPONSE = "Unexpected Comet response while resolving the workspace organization";

    sealed interface Lookup permits Found, NoOrganization, Denied {
    }

    @Builder(toBuilder = true)
    record Found(@NonNull String organizationId, boolean isOrganizationAdmin) implements Lookup {
    }

    record NoOrganization() implements Lookup {
    }

    record Denied() implements Lookup {
    }

    @Builder(toBuilder = true)
    record OrganizationRequest(@NonNull String workspaceName) {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WorkspaceOrganization(String workspaceId, String organizationId, String organizationName,
            boolean isOrganizationAdmin) {
    }

    private final @NonNull RetriableHttpClient retriableHttpClient;
    private final @NonNull AuthenticationConfig.UrlConfig reactServiceUrl;
    private final @NonNull Retry retryPolicy;
    private final @NonNull Duration requestTimeout;

    Mono<Lookup> fetch(@NonNull CometCredentials credentials, @NonNull String workspaceName) {
        return retriableHttpClient.executePostWithRetry(RetriableHttpClient.Request.<Lookup>builder()
                .requestFunction(client -> client.target(URI.create(reactServiceUrl.url()))
                        .path("opik")
                        .path(credentials.getKind().getOrganizationPath()))
                .requestCustomizer(builder -> {
                    builder.accept(MediaType.APPLICATION_JSON)
                            .acceptEncoding("identity");
                    credentials.applyTo(builder);
                })
                .body(Entity.json(OrganizationRequest.builder().workspaceName(workspaceName).build()))
                .retryPolicy(retryPolicy)
                .readTimeout(requestTimeout.isZero() ? null : requestTimeout)
                .responseFunction(WorkspaceOrganizationClient::toLookup)
                .build());
    }

    /**
     * A Comet release without the endpoint answers 404 too, and it knows no organization either. Comet answers 400
     * "No such workspace!" for a workspace name it doesn't know; any other 400 (a missing user name, say) says nothing
     * about the organization, so it isn't remembered as "none". Any other answer, a success without a JSON body
     * included, errors with {@link #UNEXPECTED_RESPONSE}, so nothing is remembered from it either.
     */
    private static Lookup toLookup(Response response) {
        if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
            if (!isJsonEntity(response)) {
                throw unexpectedResponse(response);
            }
            var organization = response.readEntity(WorkspaceOrganization.class);
            return organization == null || StringUtils.isBlank(organization.organizationId())
                    ? new NoOrganization()
                    : Found.builder()
                            .organizationId(organization.organizationId())
                            .isOrganizationAdmin(organization.isOrganizationAdmin())
                            .build();
        }
        if (response.getStatus() == Response.Status.NOT_FOUND.getStatusCode()
                || (response.getStatus() == Response.Status.BAD_REQUEST.getStatusCode()
                        && isUnknownWorkspace(response))) {
            return new NoOrganization();
        }
        if (response.getStatus() == Response.Status.UNAUTHORIZED.getStatusCode()
                || response.getStatus() == Response.Status.FORBIDDEN.getStatusCode()) {
            return new Denied();
        }
        throw unexpectedResponse(response);
    }

    private static boolean isUnknownWorkspace(Response response) {
        if (!isJsonEntity(response)) {
            return false;
        }
        try {
            var error = response.readEntity(ReactServiceErrorResponse.class);
            return error != null && ReactServiceErrorResponse.NO_SUCH_WORKSPACE.equals(StringUtils.strip(error.msg()));
        } catch (ProcessingException unreadableBody) {
            log.warn("Could not read the Comet error body, status '{}'", response.getStatus(), unreadableBody);
            return false;
        }
    }

    private static boolean isJsonEntity(Response response) {
        return response.hasEntity() && response.bufferEntity() && RemoteAuthService.isJson(response.getMediaType());
    }

    private static WebApplicationException unexpectedResponse(Response response) {
        return new WebApplicationException(UNEXPECTED_RESPONSE, response.getStatus());
    }
}
