package com.comet.opik.domain.mcpoauth;

import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OAuth Client Service Test")
class OAuthClientServiceTest {

    private static final String CLIENT_ID = "test-client";
    private static final String REDIRECT_URI = "http://localhost:1234/cb";

    @Mock
    private OAuthClientStrategy strategy;
    @InjectMocks
    private OAuthClientService service;

    private McpOAuthClient mockClient(String... redirectUris) {
        McpOAuthClient client = McpOAuthClient.builder()
                .id(CLIENT_ID)
                .name("Test Client")
                .redirectUris(Set.of(redirectUris))
                .build();
        when(strategy.supports(CLIENT_ID)).thenReturn(true);
        when(strategy.resolve(CLIENT_ID)).thenReturn(Optional.of(client));
        return client;
    }

    @Test
    @DisplayName("resolveForRedirect: unknown client_id throws 400 invalid_client")
    void resolveForRedirect_unknownClient_throwsBadRequest() {
        when(strategy.supports(CLIENT_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.resolveForRedirect(CLIENT_ID, REDIRECT_URI))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(OAuthConstants.ERROR_INVALID_CLIENT);
    }

    @Test
    @DisplayName("resolveForRedirect: redirect_uri with fragment throws 400 (RFC 6749 §3.1.2)")
    void resolveForRedirect_redirectWithFragment_throwsBadRequest() {
        mockClient(REDIRECT_URI);

        assertThatThrownBy(() -> service.resolveForRedirect(CLIENT_ID, REDIRECT_URI + "#app"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("invalid redirect_uri");
    }

    @Test
    @DisplayName("resolveForRedirect: null redirect_uri throws 400")
    void resolveForRedirect_nullRedirect_throwsBadRequest() {
        mockClient(REDIRECT_URI);

        assertThatThrownBy(() -> service.resolveForRedirect(CLIENT_ID, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("invalid redirect_uri");
    }

    @Test
    @DisplayName("resolveForRedirect: unregistered redirect_uri throws 400")
    void resolveForRedirect_unregisteredRedirect_throwsBadRequest() {
        mockClient("http://localhost:1234/other");

        assertThatThrownBy(() -> service.resolveForRedirect(CLIENT_ID, REDIRECT_URI))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("invalid redirect_uri");
    }

    @Test
    @DisplayName("resolveForRedirect: loopback redirect matches on any port (RFC 8252 §7.3)")
    void resolveForRedirect_loopbackDifferentPort_matches() {
        McpOAuthClient expected = mockClient("http://127.0.0.1:1111/cb");

        McpOAuthClient client = service.resolveForRedirect(CLIENT_ID, "http://127.0.0.1:54321/cb");

        assertThat(client).isEqualTo(expected);
    }
}
