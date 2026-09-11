package com.comet.opik.domain;

import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.api.WorkspaceUserPermissions;
import com.comet.opik.api.resources.utils.TestHttpClientUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.infrastructure.AuthenticationConfig;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.client.Client;
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

import java.util.List;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemoteWorkspacePermissionsServiceTest {
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
    void testGetPermissionsSuccessful() throws JsonProcessingException {
        var userName = "user-" + RandomStringUtils.secure().nextAlphanumeric(20);
        var workspaceName = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(20);
        var apiKey = "apiKey" + RandomStringUtils.secure().nextAlphanumeric(20);

        var expectedPermissions = List.of(
                WorkspaceUserPermissions.Permission.builder()
                        .permissionName("workspace_management")
                        .permissionValue("true")
                        .build(),
                WorkspaceUserPermissions.Permission.builder()
                        .permissionName("invite_users_to_workspace")
                        .permissionValue("false")
                        .build());

        var expectedResponse = WorkspaceUserPermissions.builder()
                .userName(userName)
                .workspaceName(workspaceName)
                .permissions(expectedPermissions)
                .build();

        WIRE_MOCK.server().stubFor(post("/opik/workspace-permissions")
                .willReturn(okJson(new ObjectMapper().writeValueAsString(expectedResponse))));

        var service = getService();
        var result = service.getPermissions(apiKey, workspaceName);

        assertThat(result.userName()).isEqualTo(userName);
        assertThat(result.workspaceName()).isEqualTo(workspaceName);
        assertThat(result.permissions()).hasSize(2);
        assertThat(result.permissions().get(0).permissionName()).isEqualTo("workspace_management");
        assertThat(result.permissions().get(0).permissionValue()).isEqualTo("true");
        assertThat(result.permissions().get(1).permissionName()).isEqualTo("invite_users_to_workspace");
        assertThat(result.permissions().get(1).permissionValue()).isEqualTo("false");
    }

    @Test
    void testGetPermissionsSuccessfulEmptyPermissions() throws JsonProcessingException {
        var userName = "user-" + RandomStringUtils.secure().nextAlphanumeric(20);
        var workspaceName = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(20);
        var apiKey = "apiKey" + RandomStringUtils.secure().nextAlphanumeric(20);

        var expectedResponse = WorkspaceUserPermissions.builder()
                .userName(userName)
                .workspaceName(workspaceName)
                .permissions(List.of())
                .build();

        WIRE_MOCK.server().stubFor(post("/opik/workspace-permissions")
                .willReturn(okJson(new ObjectMapper().writeValueAsString(expectedResponse))));

        var service = getService();
        var result = service.getPermissions(apiKey, workspaceName);

        assertThat(result.userName()).isEqualTo(userName);
        assertThat(result.workspaceName()).isEqualTo(workspaceName);
        assertThat(result.permissions()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource
    void testGetPermissionsError(int remoteStatusCode, Class<? extends Exception> expected) {
        var workspaceName = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(20);
        var apiKey = "apiKey" + RandomStringUtils.secure().nextAlphanumeric(20);

        WIRE_MOCK.server().stubFor(post("/opik/workspace-permissions")
                .willReturn(aResponse().withStatus(remoteStatusCode)
                        .withHeader("Content-Type", "application/json")
                        .withJsonBody(JsonUtils.readTree(
                                new ReactServiceErrorResponse("test error message", remoteStatusCode)))));

        assertThatThrownBy(() -> getService().getPermissions(apiKey, workspaceName))
                .isInstanceOf(expected);
    }

    private static Stream<Arguments> testGetPermissionsError() {
        return Stream.of(
                arguments(HttpStatus.SC_UNAUTHORIZED, ClientErrorException.class),
                arguments(HttpStatus.SC_BAD_REQUEST, ClientErrorException.class),
                arguments(HttpStatus.SC_SERVER_ERROR, InternalServerErrorException.class));
    }

    private RemoteWorkspacePermissionsService getService() {
        return new RemoteWorkspacePermissionsService(client,
                new AuthenticationConfig.UrlConfig(WIRE_MOCK.server().url("")));
    }
}
