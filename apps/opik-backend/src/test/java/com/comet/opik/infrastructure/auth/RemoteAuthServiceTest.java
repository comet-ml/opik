package com.comet.opik.infrastructure.auth;

import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.api.resources.utils.TestHttpClientUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.infrastructure.AuthenticationConfig;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.HttpHeaders;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.ReactServiceErrorResponse.MISSING_API_KEY;
import static com.comet.opik.api.ReactServiceErrorResponse.MISSING_WORKSPACE;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemoteAuthServiceTest {
    private Client client;

    private static final WireMockUtils.WireMockRuntime WIRE_MOCK = WireMockUtils.startWireMock();

    @BeforeAll
    void setUpAll() {
        client = TestHttpClientUtils.client();
        WIRE_MOCK.server().start();
    }

    @AfterAll
    void tearDownAll() {
        WIRE_MOCK.server().stop();
    }

    @AfterEach
    void afterEach() {
        WIRE_MOCK.server().resetAll();
    }

    @Test
    void testAuthSuccessful() throws JsonProcessingException {
        var workspaceId = UUID.randomUUID();
        var user = "user-" + RandomStringUtils.secure().nextAlphanumeric(20);
        var workspaceName = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(20);
        var apiKey = "apiKey" + RandomStringUtils.secure().nextAlphanumeric(20);
        WIRE_MOCK.server().stubFor(post("/auth")
                .willReturn(okJson(new ObjectMapper()
                        .writeValueAsString(RemoteAuthService.AuthResponse.builder()
                                .user(user)
                                .workspaceId(workspaceId.toString())
                                .workspaceName(workspaceName)
                                .build()))));

        var requestContext = new RequestContext();
        var service = getService(requestContext);
        service.authenticate(getHeadersMock(workspaceName, apiKey), null, "/priv/something");

        assertThat(requestContext.getWorkspaceId()).isEqualTo(workspaceId.toString());
        assertThat(requestContext.getUserName()).isEqualTo(user);
        assertThat(requestContext.getApiKey()).isEqualTo(apiKey);
        assertThat(requestContext.getWorkspaceName()).isEqualTo(workspaceName);
    }

    @ParameterizedTest
    @MethodSource
    void testUnauthorized(int remoteAuthStatusCode, Class<? extends Exception> expected) {
        var workspaceName = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(20);
        var apiKey = "apiKey" + RandomStringUtils.secure().nextAlphanumeric(20);
        WIRE_MOCK.server().stubFor(post("/auth")
                .willReturn(aResponse().withStatus(remoteAuthStatusCode)
                        .withHeader("Content-Type", "application/json")
                        .withJsonBody(JsonUtils.readTree(
                                new ReactServiceErrorResponse("test error message",
                                        remoteAuthStatusCode)))));

        assertThatThrownBy(() -> getService(new RequestContext()).authenticate(
                getHeadersMock(workspaceName, apiKey), null, "/priv/something"))
                .isInstanceOf(expected);
    }

    private static Stream<Arguments> testUnauthorized() {
        return Stream.of(
                arguments(HttpStatus.SC_UNAUTHORIZED, ClientErrorException.class),
                arguments(HttpStatus.SC_FORBIDDEN, ClientErrorException.class),
                arguments(HttpStatus.SC_SERVER_ERROR, InternalServerErrorException.class));
    }

    @Test
    void testAuthNoWorkspace() {
        var apiKey = "apiKey" + RandomStringUtils.secure().nextAlphanumeric(20);
        WIRE_MOCK.server().stubFor(post("/auth").willReturn(ok()));

        assertThatThrownBy(() -> getService(new RequestContext()).authenticate(
                getHeadersMock("", apiKey), null, "/priv/something"))
                .isInstanceOf(ClientErrorException.class)
                .hasMessageContaining(MISSING_WORKSPACE);
    }

    @Test
    void testAuthNoApiKey() {
        var workspaceName = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(20);
        WIRE_MOCK.server().stubFor(post("/auth").willReturn(ok()));

        assertThatThrownBy(() -> getService(new RequestContext()).authenticate(
                getHeadersMock(workspaceName, ""), null, "/priv/something"))
                .isInstanceOf(ClientErrorException.class)
                .hasMessage(MISSING_API_KEY);
    }

    private RemoteAuthService getService(RequestContext requestContext) {
        return new RemoteAuthService(client,
                new AuthenticationConfig.UrlConfig(WIRE_MOCK.server().url("/auth")),
                new AuthenticationConfig.UrlConfig(WIRE_MOCK.server().url("/")),
                () -> requestContext, new NoopCacheService());
    }

    private HttpHeaders getHeadersMock(String workspaceName, String apiKey) {
        var headersMock = Mockito.mock(HttpHeaders.class);
        Mockito.when(headersMock.getHeaderString(RequestContext.WORKSPACE_HEADER)).thenReturn(workspaceName);
        Mockito.when(headersMock.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn(apiKey);
        return headersMock;
    }
}
