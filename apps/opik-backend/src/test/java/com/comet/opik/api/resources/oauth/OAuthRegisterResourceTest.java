package com.comet.opik.api.resources.oauth;

import com.comet.opik.domain.mcpoauth.ClientRegistrationRequest;
import com.comet.opik.domain.mcpoauth.McpOAuthClient;
import com.comet.opik.domain.mcpoauth.OAuthClientService;
import com.comet.opik.infrastructure.McpOAuthConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.ratelimit.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

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

    @Mock
    private OAuthClientService clientService;
    @Mock
    private RateLimitService rateLimitService;
    @Mock
    private OpikConfiguration opikConfig;
    @Mock
    private HttpServletRequest httpRequest;

    @InjectMocks
    private OAuthRegisterResource resource;

    @BeforeEach
    void setUp() {
        lenient().when(opikConfig.getMcpOAuth()).thenReturn(new McpOAuthConfig());
        lenient().when(httpRequest.getRemoteAddr()).thenReturn("10.0.0.1");
        lenient().when(rateLimitService.isLimitExceeded(anyLong(), anyString(), any()))
                .thenReturn(Mono.just(false));
    }

    @Test
    @DisplayName("register: returns 201 with Location header pointing at admin endpoint per RFC 7591 §3.2.1")
    void register_returns201WithLocationHeader() {
        String clientId = "client-123";
        McpOAuthClient minted = McpOAuthClient.builder()
                .clientId(clientId)
                .name("Test Client")
                .redirectUris(Set.of("http://example.com/cb"))
                .build();
        when(clientService.register(any(ClientRegistrationRequest.class))).thenReturn(minted);

        Response response = resource.register(ClientRegistrationRequest.builder()
                .clientName("Test Client")
                .redirectUris(Set.of("http://example.com/cb"))
                .build(), httpRequest);

        assertThat(response.getStatus()).isEqualTo(Response.Status.CREATED.getStatusCode());
        assertThat(response.getLocation().getPath()).isEqualTo("/admin/mcp-oauth-clients/" + clientId);
        ClientRegistrationResponse body = (ClientRegistrationResponse) response.getEntity();
        assertThat(body.clientId()).isEqualTo(clientId);
        assertThat(body.clientName()).isEqualTo("Test Client");
    }

    @Test
    @DisplayName("register: response body omits clientIdIssuedAt (not surfaced by the client model)")
    void register_omitsIssuedAtWhenCreatedAtNull() {
        McpOAuthClient minted = McpOAuthClient.builder()
                .clientId("client-456")
                .name("No-Timestamp Client")
                .redirectUris(Set.of("http://example.com/cb"))
                .build();
        when(clientService.register(any(ClientRegistrationRequest.class))).thenReturn(minted);

        Response response = resource.register(ClientRegistrationRequest.builder()
                .clientName("No-Timestamp Client")
                .redirectUris(Set.of("http://example.com/cb"))
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
                .redirectUris(Set.of("http://example.com/cb"))
                .build(), httpRequest);

        assertThat(response.getStatus()).isEqualTo(Response.Status.TOO_MANY_REQUESTS.getStatusCode());
        verify(clientService, never()).register(any());
    }

    @Test
    @DisplayName("register: rate limit bucket keys on first X-Forwarded-For hop when present")
    void register_usesForwardedForAsRateLimitKey() {
        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.7, 10.0.0.1");
        when(rateLimitService.isLimitExceeded(anyLong(), anyString(), any()))
                .thenReturn(Mono.just(true));

        resource.register(ClientRegistrationRequest.builder()
                .clientName("Client")
                .redirectUris(Set.of("http://example.com/cb"))
                .build(), httpRequest);

        verify(rateLimitService).isLimitExceeded(anyLong(), eq("mcp_oauth_register:203.0.113.7"), any());
    }
}
