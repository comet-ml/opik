package com.comet.opik.infrastructure.auth;

import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.infrastructure.AuthenticationConfig;
import com.comet.opik.infrastructure.RetriableHttpClient;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.Builder;
import lombok.Getter;
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

    sealed interface Lookup permits Found, NoOrganization, Denied {
    }

    @Builder(toBuilder = true)
    record Found(@NonNull String organizationId, boolean isOrganizationAdmin) implements Lookup {
    }

    record NoOrganization() implements Lookup {
    }

    record Denied() implements Lookup {
    }

    @Getter
    static final class UnexpectedResponseException extends RuntimeException {
        private final int status;

        UnexpectedResponseException(int status) {
            super("Unexpected status while resolving the workspace organization: %d".formatted(status));
            this.status = status;
        }
    }

    @Builder(toBuilder = true)
    record OrganizationRequest(@NonNull String workspaceName) {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WorkspaceOrganization(String workspaceId, String organizationId, String organizationName,
            @JsonProperty("isOrganizationAdmin") boolean isOrganizationAdmin) {
    }

    private final @NonNull RetriableHttpClient retriableHttpClient;
    private final @NonNull AuthenticationConfig.UrlConfig reactServiceUrl;
    private final @NonNull Retry retryPolicy;
    private final Duration requestTimeout;

    Mono<Lookup> fetch(@NonNull CometCredentials credentials, @NonNull String workspaceName) {
        return retriableHttpClient.executePostWithRetry(RetriableHttpClient.Request.<Lookup>builder()
                .requestFunction(client -> client.target(URI.create(reactServiceUrl.url()))
                        .path("opik")
                        .path(credentials.kind().organizationPath()))
                .requestCustomizer(builder -> {
                    builder.accept(MediaType.APPLICATION_JSON)
                            .acceptEncoding("identity");
                    credentials.applyTo(builder);
                })
                .body(Entity.json(OrganizationRequest.builder().workspaceName(workspaceName).build()))
                .retryPolicy(retryPolicy)
                .readTimeout(requestTimeout == null || requestTimeout.isZero() ? null : requestTimeout)
                .responseFunction(WorkspaceOrganizationClient::toLookup)
                .build());
    }

    private static Lookup toLookup(Response response) {
        if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
            var organization = response.readEntity(WorkspaceOrganization.class);
            return organization == null || StringUtils.isBlank(organization.organizationId())
                    ? new NoOrganization()
                    : Found.builder()
                            .organizationId(organization.organizationId())
                            .isOrganizationAdmin(organization.isOrganizationAdmin())
                            .build();
        }
        // A Comet release without the endpoint answers 404 too, and it knows no organization either. Comet answers
        // 400 "No such workspace!" for a workspace name it doesn't know; any other 400 (a missing user name, say)
        // says nothing about the organization, so it isn't remembered as "none".
        if (response.getStatus() == Response.Status.NOT_FOUND.getStatusCode()
                || (response.getStatus() == Response.Status.BAD_REQUEST.getStatusCode()
                        && isUnknownWorkspace(response))) {
            return new NoOrganization();
        }
        if (response.getStatus() == Response.Status.UNAUTHORIZED.getStatusCode()
                || response.getStatus() == Response.Status.FORBIDDEN.getStatusCode()) {
            return new Denied();
        }
        log.warn("Comet answered '{}' while resolving the workspace organization", response.getStatus());
        throw new UnexpectedResponseException(response.getStatus());
    }

    private static boolean isUnknownWorkspace(Response response) {
        try {
            var error = response.readEntity(ReactServiceErrorResponse.class);
            return error != null && ReactServiceErrorResponse.NO_SUCH_WORKSPACE.equals(StringUtils.strip(error.msg()));
        } catch (ProcessingException unreadableBody) {
            log.warn("Comet answered '{}' with a body that isn't its error response", response.getStatus(),
                    unreadableBody);
            return false;
        }
    }
}
