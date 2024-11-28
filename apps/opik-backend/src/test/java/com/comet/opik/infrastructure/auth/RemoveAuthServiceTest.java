package com.comet.opik.infrastructure.auth;

import com.comet.opik.domain.DummyLockService;
import com.comet.opik.infrastructure.AuthenticationConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.HttpHeaders;
import lombok.Setter;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class RemoveAuthServiceTest {
    private static RemoteAuthTestServer server;

    private static class RemoteAuthTestServer {
        public static int port;

        private static HttpServer server;
        @Setter
        @Nullable private static String responsePayload;
        @Setter
        private static int responseCode = HttpStatus.SC_OK;

        public static void run() throws IOException {
            port = getAvailablePort();
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/auth", new AuthHandler());
            server.setExecutor(null);
            server.start();
        }

        public static void reset() {
            responsePayload = null;
            responseCode = 200;
        }

        public static void stop() {
            server.stop(0);
            reset();
        }

        public static String getServerUrl() {
            return "http://localhost:" + port;
        }

        static class AuthHandler implements HttpHandler {
            @Override
            public void handle(HttpExchange t) throws IOException {
                t.getRequestURI();

                if (responsePayload == null) {
                    t.sendResponseHeaders(500, 0);
                    OutputStream os = t.getResponseBody();
                    os.close();
                } else {
                    t.getResponseHeaders().set("Content-Type", "application/json");
                    t.sendResponseHeaders(responseCode, responsePayload.length());
                    OutputStream os = t.getResponseBody();
                    os.write(responsePayload.getBytes());
                    os.close();
                }
            }
        }

        public static int getAvailablePort() throws IOException {
            try (ServerSocket serverSocket = new ServerSocket(0)) {
                return serverSocket.getLocalPort();
            }
        }
    }

    @BeforeAll
    static void beforeAll() throws IOException {
        RemoteAuthTestServer.run();
    }

    @AfterAll
    static void afterAll() {
        RemoteAuthTestServer.stop();
    }

    @Test
    void testAuthSuccessful() throws JsonProcessingException {
        var workspaceId = UUID.randomUUID();
        var user = RandomStringUtils.randomAlphabetic(10);
        var workspaceName = RandomStringUtils.randomAlphabetic(10);
        var apiKey = RandomStringUtils.randomAlphabetic(10);

        RemoteAuthTestServer.setResponseCode(HttpStatus.SC_OK);
        String resPayload = new ObjectMapper()
                .writeValueAsString(new RemoteAuthService.AuthResponse(user, workspaceId.toString()));
        RemoteAuthTestServer.setResponsePayload(resPayload);
        RequestContext requestContext = new RequestContext();
        HttpHeaders headersMock = Mockito.mock(HttpHeaders.class);

        Mockito.when(headersMock.getHeaderString(RequestContext.WORKSPACE_HEADER)).thenReturn(workspaceName);
        Mockito.when(headersMock.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn(apiKey);

        var service = new RemoteAuthService(ClientBuilder.newClient(),
                new AuthenticationConfig.UrlConfig(RemoteAuthTestServer.getServerUrl() + "/auth"),
                new AuthenticationConfig.UrlConfig(RemoteAuthTestServer.getServerUrl()),
                () -> requestContext, new NoopCacheService(), new DummyLockService());

        service.authenticate(headersMock, null, "/priv/something");

        assertThat(requestContext.getWorkspaceId()).isEqualTo(workspaceId.toString());
        assertThat(requestContext.getUserName()).isEqualTo(user);
        assertThat(requestContext.getApiKey()).isEqualTo(apiKey);
    }
}
