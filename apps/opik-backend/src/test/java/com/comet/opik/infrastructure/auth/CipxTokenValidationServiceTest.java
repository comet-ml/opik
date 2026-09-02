package com.comet.opik.infrastructure.auth;

import com.comet.opik.infrastructure.CipxTokenValidationConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    // The prefix stays literal: it is what triggers the branch. Only the secret part is data.
    private static final String TOKEN = CipxTokenUtils.ACCESS_PREFIX + RandomStringUtils.secure()
            .nextAlphanumeric(32);
    private static final String TOKEN_CACHE_KEY = "cipx-sha256:" + DigestUtils.sha256Hex(TOKEN);
    // Likewise the __ai_spend_ fence, which is the contract shape of a device's bound workspace.
    private static final String WORKSPACE_NAME = "__ai_spend_" + RandomStringUtils.secure().nextAlphanumeric(8)
            + "__";
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String PROJECT_ID = UUID.randomUUID().toString();
    private static final String DEVICE_ID = UUID.randomUUID().toString();
    // What the validator really returns as the user name: the device's MDM-provisioned address, not a
    // Comet username. The cipx-device-<id> form is a fallback cost-api owns and tests.
    private static final String MDM_EMAIL = "dev-" + UUID.randomUUID() + "@acme.com";
    // UUID-shaped deliberately: the allowlist's trace-update pattern matches only a UUID path segment.
    private static final String TRACE_ID = UUID.randomUUID().toString();

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
                .url("http://ai-cost-backend")
                .build());
        service = new CipxTokenValidationService(client, config, cacheService, () -> requestContext);
    }

    @ParameterizedTest
    @MethodSource
    @DisplayName("accepts a device token on the endpoints the cipx shipper calls")
    void acceptsIngestEndpoints(String method, String path) {
        // Served from cache, so the accepted case needs no validator: what is under test is the allowlist.
        // The cache key carries no workspace: a device's workspace is derived from its enrollment.
        when(cacheService.resolveApiKeyUserAndWorkspaceIdFromCache(TOKEN_CACHE_KEY, "", List.of()))
                .thenReturn(Optional.of(CacheService.AuthCredentials.builder()
                        .userName(MDM_EMAIL)
                        .workspaceId(WORKSPACE_ID)
                        .workspaceName(WORKSPACE_NAME)
                        .quotas(List.of())
                        .permissions(List.of())
                        .deviceId(DEVICE_ID)
                        .build()));

        service.authenticate(TOKEN, contextInfo(method, path));

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
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    @DisplayName("rejects a successful validation response without a device id")
    void rejectsValidationResponseWithoutDeviceId(String deviceId) {
        WebTarget target = mock(WebTarget.class);
        Invocation.Builder request = mock(Invocation.Builder.class);
        Response response = mock(Response.class);
        when(cacheService.resolveApiKeyUserAndWorkspaceIdFromCache(TOKEN_CACHE_KEY, "", List.of()))
                .thenReturn(Optional.empty());
        when(client.target(URI.create("http://ai-cost-backend/v1/internal/cipx-device-tokens/validate")))
                .thenReturn(target);
        when(target.request()).thenReturn(request);
        when(request.accept(MediaType.APPLICATION_JSON)).thenReturn(request);
        when(request.post(any())).thenReturn(response);
        when(response.getStatus()).thenReturn(Response.Status.OK.getStatusCode());
        when(response.getStatusInfo()).thenReturn(Response.Status.OK);
        when(response.readEntity(ValidatedCipxToken.class)).thenReturn(ValidatedCipxToken.builder()
                .userName(MDM_EMAIL)
                .workspaceId(WORKSPACE_ID)
                .workspaceName(WORKSPACE_NAME)
                .deviceId(deviceId)
                .build());

        assertThatThrownBy(() -> service.authenticate(TOKEN, contextInfo("POST", "/v1/private/traces")))
                .isInstanceOf(ClientErrorException.class)
                .satisfies(rejected -> assertThat(((ClientErrorException) rejected).getResponse().getStatus())
                        .isEqualTo(Response.Status.UNAUTHORIZED.getStatusCode()));

        verify(cacheService, never()).cache(any(), any(), any(), any());
        assertThat(requestContext.getWorkspaceId()).isNull();
        assertThat(requestContext.getCipxDeviceId()).isNull();
    }

    @ParameterizedTest
    @MethodSource
    @DisplayName("rejects a device token everywhere else with 403, before validating it")
    void rejectsEverythingElse(String method, String path) {
        assertThatThrownBy(() -> service.authenticate(TOKEN, contextInfo(method, path)))
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
                arguments(named("delete a project", "DELETE"), "/v1/private/projects/" + PROJECT_ID),
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
