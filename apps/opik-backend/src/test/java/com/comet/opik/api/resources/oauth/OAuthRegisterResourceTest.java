package com.comet.opik.api.resources.oauth;

import com.comet.opik.domain.mcpoauth.ClientRegistrationRequest;
import com.comet.opik.domain.mcpoauth.McpOAuthClient;
import com.comet.opik.domain.mcpoauth.OAuthClientService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OAuth Register Resource Test")
class OAuthRegisterResourceTest {

    @Mock
    private OAuthClientService clientService;

    @InjectMocks
    private OAuthRegisterResource resource;

    @Test
    @DisplayName("register: returns 201 with Location header pointing at admin endpoint per RFC 7591 §3.2.1")
    void register_returns201WithLocationHeader() {
        String clientId = "client-123";
        McpOAuthClient minted = McpOAuthClient.builder()
                .clientId(clientId)
                .name("Test Client")
                .redirectUris(Set.of("http://example.com/cb"))
                .createdAt(Instant.parse("2026-06-01T00:00:00Z"))
                .build();
        when(clientService.register(any(ClientRegistrationRequest.class))).thenReturn(minted);

        Response response = resource.register(ClientRegistrationRequest.builder()
                .clientName("Test Client")
                .redirectUris(Set.of("http://example.com/cb"))
                .build());

        assertThat(response.getStatus()).isEqualTo(Response.Status.CREATED.getStatusCode());
        assertThat(response.getLocation().getPath()).isEqualTo("/admin/mcp-oauth-clients/" + clientId);
        ClientRegistrationResponse body = (ClientRegistrationResponse) response.getEntity();
        assertThat(body.clientId()).isEqualTo(clientId);
        assertThat(body.clientName()).isEqualTo("Test Client");
    }

    @Test
    @DisplayName("register: response body omits clientIdIssuedAt when createdAt is null")
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
                .build());

        ClientRegistrationResponse body = (ClientRegistrationResponse) response.getEntity();
        assertThat(body.clientIdIssuedAt()).isNull();
    }

}
