package com.comet.opik.infrastructure.auth;

import com.comet.opik.infrastructure.CipxTokenValidationConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the ingest-only allowlist, which is the whole authorization a device token gets: the caller is
 * resolved from the validation response rather than from the react service, so no path or permission check
 * happens anywhere else. The rejections are the point of this class — without them a device token would reach
 * every {@code /v1/private/*} endpoint, reads and deletes included.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CipxTokenValidationService")
class CipxTokenValidationServiceTest {

    private static final String TOKEN = CipxTokenUtils.ACCESS_PREFIX + "Zm9vYmFy";
    private static final String WORKSPACE_NAME = "__ai_spend_acme__";
    private static final String WORKSPACE_ID = "6f0a1c2d-1111-4b2c-8d3e-4f5a6b7c8d9e";
    private static final String DEVICE_ID = "8f4b2c1e-0000-4a1b-9c3d-2e5f6a7b8c9d";
    private static final String TRACE_ID = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d";

    @Mock
    private Client client;

    @Mock
    private CacheService cacheService;

    private final RequestContext requestContext = new RequestContext();

    private CipxTokenValidationService service;

    @BeforeEach
    void setUp() {
        var config = new OpikConfiguration();
        config.setCipxTokenValidation(CipxTokenValidationConfig.builder()
                .enabled(true)
                .url("http://cost-api:8000")
                .serviceToken("service-token")
                .build());
        service = new CipxTokenValidationService(client, config, cacheService, () -> requestContext);
    }

    @ParameterizedTest
    @MethodSource
    @DisplayName("accepts a device token on the endpoints the cipx shipper calls")
    void acceptsIngestEndpoints(String method, String path) {
        // Served from cache, so the accepted case needs no validator: what is under test is the allowlist.
        when(cacheService.resolveApiKeyUserAndWorkspaceIdFromCache(TOKEN, WORKSPACE_NAME, List.of()))
                .thenReturn(Optional.of(CacheService.AuthCredentials.builder()
                        .userName("cipx-device-" + DEVICE_ID)
                        .workspaceId(WORKSPACE_ID)
                        .workspaceName(WORKSPACE_NAME)
                        .quotas(List.of())
                        .permissions(List.of())
                        .deviceId(DEVICE_ID)
                        .build()));

        service.authenticate(TOKEN, WORKSPACE_NAME, contextInfo(method, path));

        assertThat(requestContext.getWorkspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(requestContext.getWorkspaceName()).isEqualTo(WORKSPACE_NAME);
        assertThat(requestContext.getCipxDeviceId()).isEqualTo(DEVICE_ID);
        // A device token names a machine, not a Comet user: no quota and no permission is resolved for it.
        assertThat(requestContext.getQuotas()).isEmpty();
        assertThat(requestContext.getPermissions()).isEmpty();
        verifyNoInteractions(client);
    }

    static Stream<Arguments> acceptsIngestEndpoints() {
        return Stream.of(
                arguments(named("span batch create", "POST"), "/v1/private/spans/batch"),
                arguments(named("span batch update", "PATCH"), "/v1/private/spans/batch"),
                arguments(named("trace create", "POST"), "/v1/private/traces"),
                arguments(named("trace update", "PATCH"), "/v1/private/traces/" + TRACE_ID));
    }

    @ParameterizedTest
    @MethodSource
    @DisplayName("rejects a device token everywhere else with 403, before validating it")
    void rejectsEverythingElse(String method, String path) {
        assertThatThrownBy(() -> service.authenticate(TOKEN, WORKSPACE_NAME, contextInfo(method, path)))
                .isInstanceOf(ClientErrorException.class)
                .satisfies(rejected -> assertThat(((ClientErrorException) rejected).getResponse().getStatus())
                        .isEqualTo(Response.Status.FORBIDDEN.getStatusCode()));

        // Rejected before any lookup, so a non-ingest path cannot even tell whether the token is valid.
        verifyNoInteractions(cacheService, client);
        assertThat(requestContext.getWorkspaceId()).isNull();
        assertThat(requestContext.getCipxDeviceId()).isNull();
    }

    static Stream<Arguments> rejectsEverythingElse() {
        return Stream.of(
                arguments(named("read traces", "GET"), "/v1/private/traces"),
                arguments(named("read spans", "GET"), "/v1/private/spans"),
                arguments(named("read one trace", "GET"), "/v1/private/traces/" + TRACE_ID),
                // POST is allowed on other paths, so the pairing has to bite, not the method alone.
                arguments(named("search traces", "POST"), "/v1/private/traces/search"),
                arguments(named("read the span batch path", "GET"), "/v1/private/spans/batch"),
                arguments(named("delete a project", "DELETE"), "/v1/private/projects/" + WORKSPACE_ID),
                arguments(named("delete traces", "POST"), "/v1/private/traces/delete"));
    }

    private ContextInfoHolder contextInfo(String method, String path) {
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getRequestUri()).thenReturn(URI.create("http://localhost:8080" + path));
        return ContextInfoHolder.builder()
                .uriInfo(uriInfo)
                .method(method)
                .requiredPermissions(List.of())
                .build();
    }
}
