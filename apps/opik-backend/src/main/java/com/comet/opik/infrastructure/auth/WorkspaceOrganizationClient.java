package com.comet.opik.infrastructure.auth;

import com.comet.opik.infrastructure.AuthenticationConfig;
import com.comet.opik.infrastructure.RetriableHttpClient;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
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

    record Found(String organizationId, boolean isOrganizationAdmin) implements Lookup {
    }

    record NoOrganization() implements Lookup {
    }

    record Denied() implements Lookup {
    }

    static final class UnexpectedResponseException extends RuntimeException {
        UnexpectedResponseException(int status) {
            super("Unexpected status while resolving the workspace organization: " + status);
        }
    }

    record OrganizationRequest(String workspaceName) {
    }

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
                .body(Entity.json(new OrganizationRequest(workspaceName)))
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
                    : new Found(organization.organizationId(), organization.isOrganizationAdmin());
        }
        // A Comet release without the endpoint answers 404 too, and it knows no organization either.
        if (response.getStatus() == Response.Status.NOT_FOUND.getStatusCode()) {
            return new NoOrganization();
        }
        if (response.getStatus() == Response.Status.UNAUTHORIZED.getStatusCode()
                || response.getStatus() == Response.Status.FORBIDDEN.getStatusCode()) {
            return new Denied();
        }
        log.warn("Comet answered '{}' while resolving the workspace organization", response.getStatus());
        throw new UnexpectedResponseException(response.getStatus());
    }
}
