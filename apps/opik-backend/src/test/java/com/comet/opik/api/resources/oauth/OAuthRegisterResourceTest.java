package com.comet.opik.api.resources.oauth;

import com.comet.opik.domain.mcpoauth.ClientRegistrationRequest;
import com.comet.opik.domain.mcpoauth.McpOAuthClient;
import com.comet.opik.domain.mcpoauth.OAuthClientService;
import com.comet.opik.domain.mcpoauth.OAuthConstants;
import com.comet.opik.infrastructure.McpOAuthConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.ratelimit.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OAuth Register Resource Test")
class OAuthRegisterResourceTest {

    private static final String REDIRECT_URI = "http://example.com/cb";
    private static final Set<String> REDIRECT_URIS = Set.of(REDIRECT_URI);
    private static final long REMAINING_TTL_MILLIS = 60_000L;

    @Mock
    private OAuthClientService clientService;
    @Mock
    private RateLimitService rateLimitService;
    @Mock
    private OpikConfiguration opikConfig;
    @Mock
    private HttpServletRequest httpRequest;

    private OAuthRegisterResource resource;

    @BeforeEach
    void setUp() {
        McpOAuthConfig mcpOAuthConfig = new McpOAuthConfig();
        mcpOAuthConfig.setRegistrationRateLimit(10);
        mcpOAuthConfig.setRegistrationRateLimitDuration(Duration.ofMinutes(1));
        when(opikConfig.getMcpOAuth()).thenReturn(mcpOAuthConfig);

        resource = new OAuthRegisterResource(clientService, rateLimitService, opikConfig);

        lenient().when(httpRequest.getRemoteAddr()).thenReturn("10.0.0.1");
        lenient().when(rateLimitService.isLimitExceeded(anyLong(), anyString(), any()))
                .thenReturn(Mono.just(false));
        lenient().when(rateLimitService.getRemainingTTL(anyString(), any()))
                .thenReturn(Mono.just(REMAINING_TTL_MILLIS));
    }

    @Test
    @DisplayName("register: returns 201 with Location header pointing at admin endpoint per RFC 7591 §3.2.1")
    void register_returns201WithLocationHeader() {
        String clientId = "client-123";
        String clientName = "Test Client";
        McpOAuthClient minted = McpOAuthClient.builder()
                .id(clientId)
                .name(clientName)
                .redirectUris(REDIRECT_URIS)
                .build();
        when(clientService.register(any(ClientRegistrationRequest.class))).thenReturn(minted);

        Response response = resource.register(ClientRegistrationRequest.builder()
                .clientName(clientName)
                .redirectUris(REDIRECT_URIS)
                .build(), httpRequest);

        assertThat(response.getStatus()).isEqualTo(Response.Status.CREATED.getStatusCode());
        assertThat(response.getLocation().getPath())
                .isEqualTo(OAuthConstants.CLIENT_CONFIG_PATH_PREFIX + clientId);

        ClientRegistrationResponse body = (ClientRegistrationResponse) response.getEntity();
        ClientRegistrationResponse expected = ClientRegistrationResponse.builder()
                .clientId(clientId)
                .clientName(clientName)
                .redirectUris(REDIRECT_URIS)
                .tokenEndpointAuthMethod(OAuthConstants.AUTH_METHOD_NONE)
                .grantTypes(OAuthConstants.DEFAULT_GRANT_TYPES)
                .responseTypes(OAuthConstants.DEFAULT_RESPONSE_TYPES)
                .build();
        assertThat(body).usingRecursiveComparison().isEqualTo(expected);
    }

    @Test
    @DisplayName("register: response body always omits clientIdIssuedAt (not surfaced by the client model)")
    void register_clientIdIssuedAtAlwaysOmitted() {
        String clientName = "No-Timestamp Client";
        McpOAuthClient minted = McpOAuthClient.builder()
                .id("client-456")
                .name(clientName)
                .redirectUris(REDIRECT_URIS)
                .build();
        when(clientService.register(any(ClientRegistrationRequest.class))).thenReturn(minted);

        Response response = resource.register(ClientRegistrationRequest.builder()
                .clientName(clientName)
                .redirectUris(REDIRECT_URIS)
                .build(), httpRequest);

        ClientRegistrationResponse body = (ClientRegistrationResponse) response.getEntity();
        assertThat(body.clientIdIssuedAt()).isNull();
    }

    @Test
    @DisplayName("register: per-IP rate limit exceeded returns 429 without registering")
    void register_rateLimitExceeded_returns429() {
        when(rateLimitService.isLimitExceeded(anyLong(), anyString(), any()))
                .thenReturn(Mono.just(true));

        Response response = resource.register(ClientRegistrationRequest.builder()
                .clientName("Spammy Client")
                .redirectUris(REDIRECT_URIS)
                .build(), httpRequest);

        assertThat(response.getStatus()).isEqualTo(Response.Status.TOO_MANY_REQUESTS.getStatusCode());
        assertThat(response.getHeaderString("Retry-After"))
                .isEqualTo(String.valueOf(Duration.ofMillis(REMAINING_TTL_MILLIS).toSeconds()));
        OAuthError error = (OAuthError) response.getEntity();
        assertThat(error.error()).isEqualTo(OAuthConstants.ERROR_TOO_MANY_REQUESTS);
        assertThat(error.errorDescription()).isEqualTo(OAuthConstants.ERROR_DESC_REGISTRATION_RATE_LIMIT);
        verify(clientService, never()).register(any());
    }

    @Test
    @DisplayName("register: rate limit bucket keys on last (nginx-appended) X-Forwarded-For hop, not the spoofable first")
    void register_usesLastForwardedForHopAsRateLimitKey() {
        String lastHopIp = "203.0.113.7";
        when(httpRequest.getHeader(OAuthConstants.X_FORWARDED_FOR_HEADER)).thenReturn("1.2.3.4, " + lastHopIp);
        when(rateLimitService.isLimitExceeded(anyLong(), anyString(), any()))
                .thenReturn(Mono.just(true));

        resource.register(ClientRegistrationRequest.builder()
                .clientName("Client")
                .redirectUris(REDIRECT_URIS)
                .build(), httpRequest);

        verify(rateLimitService).isLimitExceeded(anyLong(),
                eq(OAuthConstants.RATE_LIMIT_BUCKET.formatted(lastHopIp)), any());
    }
}
