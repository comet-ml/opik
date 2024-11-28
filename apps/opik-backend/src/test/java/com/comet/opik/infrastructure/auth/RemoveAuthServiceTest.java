package com.comet.opik.infrastructure.auth;

import com.comet.opik.domain.DummyLockService;
import com.comet.opik.infrastructure.AuthenticationConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.HttpHeaders;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RemoveAuthServiceTest {
    private RemoteAuthTestServer server;
    private Client client;

    @BeforeAll
    void setUpAll() throws IOException {
        this.server = new RemoteAuthTestServer();
        server.run();

        client = ClientBuilder.newClient();
    }

    @AfterAll
    void tearDownAll() {
        server.stop();
    }

    @AfterEach
    void afterEach() {
        server.reset();
    }

    @Test
    void testAuthSuccessful() throws JsonProcessingException {
        var workspaceId = UUID.randomUUID();
        var user = RandomStringUtils.randomAlphabetic(10);
        var workspaceName = RandomStringUtils.randomAlphabetic(10);
        var apiKey = RandomStringUtils.randomAlphabetic(10);

        server.setResponseCode(HttpStatus.SC_OK);
        server.setResponsePayload(new ObjectMapper()
                .writeValueAsString(new RemoteAuthService.AuthResponse(user, workspaceId.toString())));
        RequestContext requestContext = new RequestContext();

        var service = new RemoteAuthService(client,
                new AuthenticationConfig.UrlConfig(server.getServerUrl() + "/auth"),
                new AuthenticationConfig.UrlConfig(server.getServerUrl()),
                () -> requestContext, new NoopCacheService(), new DummyLockService());

        service.authenticate(getHeadersMock(workspaceName, apiKey), null, "/priv/something");

        assertThat(requestContext.getWorkspaceId()).isEqualTo(workspaceId.toString());
        assertThat(requestContext.getUserName()).isEqualTo(user);
        assertThat(requestContext.getApiKey()).isEqualTo(apiKey);
    }

    @ParameterizedTest
    @ValueSource(ints = {HttpStatus.SC_UNAUTHORIZED, HttpStatus.SC_FORBIDDEN, HttpStatus.SC_SERVER_ERROR})
    void testUnauthorized(int remoteAuthStatusCode) {
        var workspaceName = RandomStringUtils.randomAlphabetic(10);
        var apiKey = RandomStringUtils.randomAlphabetic(10);

        server.setResponseCode(remoteAuthStatusCode);
        RequestContext requestContext = new RequestContext();

        var service = new RemoteAuthService(client,
                new AuthenticationConfig.UrlConfig(server.getServerUrl() + "/auth"),
                new AuthenticationConfig.UrlConfig(server.getServerUrl()),
                () -> requestContext, new NoopCacheService(), new DummyLockService());

        assertThatThrownBy(() -> service.authenticate(
                getHeadersMock(workspaceName, apiKey), null, "/priv/something"))
                .isInstanceOf(ClientErrorException.class);
    }

    private HttpHeaders getHeadersMock(String workspaceName, String apiKey) {
        var headersMock = Mockito.mock(HttpHeaders.class);

        Mockito.when(headersMock.getHeaderString(RequestContext.WORKSPACE_HEADER)).thenReturn(workspaceName);
        Mockito.when(headersMock.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn(apiKey);

        return headersMock;
    }
}
