package com.comet.opik.infrastructure.auth;

import com.comet.opik.domain.mcpoauth.McpOAuthService;
import com.comet.opik.infrastructure.CipxTokenValidationConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The CIPX branch of the filter, driven end to end with the real {@link CipxTokenValidationService}, to pin the
 * one property that is invisible from inside the service: the branch never reads {@code Comet-Workspace}, so a
 * device token authenticates without it and every header variant shares one cache entry. The API-key and
 * MCP OAuth branches still require the header and are not exercised here.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthFilter CIPX device token branch")
class AuthFilterCipxTokenTest {

    // The prefix stays literal: it is what triggers the branch. Only the secret part is data.
    private static final String TOKEN = CipxTokenUtils.ACCESS_PREFIX + RandomStringUtils.secure()
            .nextAlphanumeric(32);
    private static final String INGEST_PATH = "/v1/private/traces";
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    // The __ai_spend_ fence is the contract shape of a device's bound workspace; only the org part is data.
    private static final String BOUND_WORKSPACE = "__ai_spend_" + RandomStringUtils.secure().nextAlphanumeric(8)
            + "__";
    private static final String DEVICE_ID = UUID.randomUUID().toString();
    // What the validator really returns as the user name: the device's MDM-provisioned address, not a
    // Comet username. The cipx-device-<id> form is a fallback cost-api owns and tests.
    private static final String MDM_EMAIL = "dev-" + UUID.randomUUID() + "@acme.com";

    @Mock
    private AuthService authService;

    @Mock
    private McpOAuthService mcpOAuthService;

    @Mock
    private Client client;

    @Mock
    private CacheService cacheService;

    private final RequestContext requestContext = new RequestContext();

    private AuthFilter filter;

    @BeforeEach
    void setUp() {
        var config = new OpikConfiguration();
        config.setCipxTokenValidation(CipxTokenValidationConfig.builder()
                .enabled(true)
                .url("http://ai-cost-backend")
                .build());
        var cipxTokenValidationService = new CipxTokenValidationService(client, config, cacheService,
                () -> requestContext);
        filter = new AuthFilter(authService, mcpOAuthService, cipxTokenValidationService, config,
                () -> requestContext);
    }

    @Test
    @DisplayName("authenticates a device token with no Comet-Workspace header at all")
    void authenticatesWithoutTheWorkspaceHeader() throws IOException {
        cacheHit();
        var context = requestContext(null);

        filter.filter(context);

        // The workspace comes from the token's enrollment binding, not from anything the client sent.
        assertThat(requestContext.getWorkspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(requestContext.getWorkspaceName()).isEqualTo(BOUND_WORKSPACE);
        assertThat(requestContext.getCipxDeviceId()).isEqualTo(DEVICE_ID);
        verify(context, never()).getHeaderString(RequestContext.WORKSPACE_HEADER);
        // Neither the API-key nor the MCP OAuth branch runs for this credential.
        verifyNoInteractions(authService, mcpOAuthService);
    }

    @Test
    @DisplayName("two requests with different workspace headers share one cache entry")
    void differentWorkspaceHeadersShareOneCacheEntry() throws IOException {
        cacheHit();

        filter.filter(requestContext("__ai_spend_acme__"));
        filter.filter(requestContext("something-else-entirely"));

        // Same key both times, so a client varying the header cannot force a cold validate per variant.
        verify(cacheService, times(2)).resolveApiKeyUserAndWorkspaceIdFromCache(TOKEN, "", List.of());
        verify(cacheService, never()).cache(any(), any(), any(), any());
    }

    private void cacheHit() {
        when(cacheService.resolveApiKeyUserAndWorkspaceIdFromCache(TOKEN, "", List.of()))
                .thenReturn(Optional.of(CacheService.AuthCredentials.builder()
                        .userName(MDM_EMAIL)
                        .workspaceId(WORKSPACE_ID)
                        .workspaceName(BOUND_WORKSPACE)
                        .quotas(List.of())
                        .permissions(List.of())
                        .deviceId(DEVICE_ID)
                        .build()));
    }

    /**
     * @param workspaceHeader stubbed leniently on purpose: it is present on the request and must never be read.
     */
    private ContainerRequestContext requestContext(String workspaceHeader) {
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getRequestUri()).thenReturn(URI.create("http://localhost:8080" + INGEST_PATH));

        ContainerRequestContext context = mock(ContainerRequestContext.class);
        when(context.getCookies()).thenReturn(Map.of());
        when(context.getUriInfo()).thenReturn(uriInfo);
        when(context.getMethod()).thenReturn("POST");
        when(context.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn(TOKEN);
        when(context.getHeaders()).thenReturn(new MultivaluedHashMap<>());
        lenient().when(context.getHeaderString(RequestContext.WORKSPACE_HEADER)).thenReturn(workspaceHeader);
        return context;
    }
}
