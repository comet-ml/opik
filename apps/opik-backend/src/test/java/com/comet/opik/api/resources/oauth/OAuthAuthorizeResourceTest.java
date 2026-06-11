package com.comet.opik.api.resources.oauth;

import com.comet.opik.domain.mcpoauth.CreateOAuthCodeCommand;
import com.comet.opik.domain.mcpoauth.McpOAuthClient;
import com.comet.opik.domain.mcpoauth.McpOAuthService;
import com.comet.opik.domain.mcpoauth.OAuthClientService;
import com.comet.opik.infrastructure.McpOAuthConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.AuthService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.auth.UserWorkspace;
import com.comet.opik.infrastructure.auth.WorkspaceInfo;
import com.comet.opik.utils.JsonUtils;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import org.glassfish.jersey.client.ClientProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.AUTHORIZE_PATH;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.CODE_CHALLENGE_METHOD_S256;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.CSRF_COOKIE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_INVALID_REQUEST;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_INVALID_TARGET;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_UNSUPPORTED_RESPONSE_TYPE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CLIENT_ID;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CODE_CHALLENGE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CODE_CHALLENGE_METHOD;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_REDIRECT_URI;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_RESOURCE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_RESPONSE_TYPE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_STATE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.RESPONSE_TYPE_CODE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(DropwizardExtensionsSupport.class)
@DisplayName("OAuth Authorize Resource Test")
class OAuthAuthorizeResourceTest {

    private static final String CLIENT_ID = "test-client";
    private static final String REDIRECT_URI = "http://localhost:1234/cb";
    private static final String BASE_OPIK_URL = "https://www.comet.com/opik";
    private static final String RESOURCE_URI = BASE_OPIK_URL + "/api/v1/mcp";
    private static final String CODE_CHALLENGE = "abc123challenge";
    private static final String STATE = "state-xyz";
    private static final String CSRF = "csrf-token-abc";
    private static final String CONTEXT_PATH = AUTHORIZE_PATH + "/context";

    private static final OAuthClientService clientService = mock(OAuthClientService.class);
    private static final AuthService authService = mock(AuthService.class);
    private static final McpOAuthService mcpOAuthService = mock(McpOAuthService.class);
    private static final OpikConfiguration opikConfig = new OpikConfiguration();

    private static final ResourceExtension EXT = ResourceExtension.builder()
            .setMapper(JsonUtils.getMapper())
            .addResource(new OAuthAuthorizeResource(
                    new OAuthAuthorizationService(clientService, authService, mcpOAuthService, opikConfig),
                    opikConfig))
            .build();

    @BeforeEach
    void setUp() {
        reset(clientService, authService, mcpOAuthService);
        opikConfig.setMcpOAuth(McpOAuthConfig.builder()
                .enabled(true)
                .baseUrl(BASE_OPIK_URL)
                .mcpResourceUri(RESOURCE_URI)
                .build());
    }

    private McpOAuthClient validClient() {
        return McpOAuthClient.builder()
                .id(CLIENT_ID)
                .name("Test Client")
                .redirectUris(Set.of(REDIRECT_URI))
                .build();
    }

    private void mockClientResolution() {
        when(clientService.resolveForRedirect(CLIENT_ID, REDIRECT_URI)).thenReturn(validClient());
    }

    private ConsentRequest buildConsent() {
        return ConsentRequest.builder()
                .clientId(CLIENT_ID)
                .redirectUri(REDIRECT_URI)
                .codeChallenge(CODE_CHALLENGE)
                .codeChallengeMethod(CODE_CHALLENGE_METHOD_S256)
                .resource(RESOURCE_URI)
                .state(STATE)
                .workspaceId("ws-1")
                .workspaceName("default")
                .csrf(CSRF)
                .build();
    }

    @Test
    @DisplayName("GET /authorize: valid request redirects to consent UI with PKCE+state preserved")
    void authorize_validRequest_redirectsToConsent() {
        mockClientResolution();
        when(authService.listEligibleWorkspaces(any()))
                .thenReturn(List.of(WorkspaceInfo.builder().id("ws-1").name("default").build()));

        try (Response response = EXT.target(AUTHORIZE_PATH)
                .property(ClientProperties.FOLLOW_REDIRECTS, false)
                .queryParam(PARAM_CLIENT_ID, CLIENT_ID)
                .queryParam(PARAM_REDIRECT_URI, REDIRECT_URI)
                .queryParam(PARAM_RESPONSE_TYPE, RESPONSE_TYPE_CODE)
                .queryParam(PARAM_CODE_CHALLENGE, CODE_CHALLENGE)
                .queryParam(PARAM_CODE_CHALLENGE_METHOD, CODE_CHALLENGE_METHOD_S256)
                .queryParam(PARAM_RESOURCE, RESOURCE_URI)
                .queryParam(PARAM_STATE, STATE)
                .request()
                .cookie(RequestContext.SESSION_COOKIE, "sess")
                .get()) {

            assertThat(response.getStatus()).isEqualTo(Response.Status.FOUND.getStatusCode());
            assertThat(response.getLocation().toString())
                    .startsWith(BASE_OPIK_URL + "/oauth/consent?")
                    .contains("client_id=" + CLIENT_ID)
                    .contains("code_challenge=" + CODE_CHALLENGE)
                    .contains("code_challenge_method=" + CODE_CHALLENGE_METHOD_S256)
                    .contains("state=" + STATE);
        }
    }

    static Stream<Arguments> errorRedirectArgs() {
        return Stream.of(
                arguments("token", CODE_CHALLENGE, RESOURCE_URI, ERROR_UNSUPPORTED_RESPONSE_TYPE),
                arguments(RESPONSE_TYPE_CODE, " ", RESOURCE_URI, ERROR_INVALID_REQUEST),
                arguments(RESPONSE_TYPE_CODE, CODE_CHALLENGE, "https://attacker.example.com/api/v1/mcp",
                        ERROR_INVALID_TARGET));
    }

    @ParameterizedTest
    @MethodSource("errorRedirectArgs")
    @DisplayName("GET /authorize: invalid request redirects to client redirect_uri with the matching error")
    void authorize_invalidRequest_redirectsWithError(String responseType, String codeChallenge, String resource,
            String expectedError) {
        mockClientResolution();

        try (Response response = EXT.target(AUTHORIZE_PATH)
                .property(ClientProperties.FOLLOW_REDIRECTS, false)
                .queryParam(PARAM_CLIENT_ID, CLIENT_ID)
                .queryParam(PARAM_REDIRECT_URI, REDIRECT_URI)
                .queryParam(PARAM_RESPONSE_TYPE, responseType)
                .queryParam(PARAM_CODE_CHALLENGE, codeChallenge)
                .queryParam(PARAM_CODE_CHALLENGE_METHOD, CODE_CHALLENGE_METHOD_S256)
                .queryParam(PARAM_RESOURCE, resource)
                .queryParam(PARAM_STATE, STATE)
                .request()
                .get()) {

            assertThat(response.getStatus()).isEqualTo(Response.Status.FOUND.getStatusCode());
            assertThat(response.getLocation().toString())
                    .startsWith(REDIRECT_URI)
                    .contains("error=" + expectedError);
        }
    }

    @Test
    @DisplayName("GET /authorize: session check failure redirects to login with return_to")
    void authorize_noSession_redirectsToLogin() {
        mockClientResolution();
        when(authService.listEligibleWorkspaces(any())).thenThrow(new ClientErrorException(401));

        try (Response response = EXT.target(AUTHORIZE_PATH)
                .property(ClientProperties.FOLLOW_REDIRECTS, false)
                .queryParam(PARAM_CLIENT_ID, CLIENT_ID)
                .queryParam(PARAM_REDIRECT_URI, REDIRECT_URI)
                .queryParam(PARAM_RESPONSE_TYPE, RESPONSE_TYPE_CODE)
                .queryParam(PARAM_CODE_CHALLENGE, CODE_CHALLENGE)
                .queryParam(PARAM_CODE_CHALLENGE_METHOD, CODE_CHALLENGE_METHOD_S256)
                .queryParam(PARAM_RESOURCE, RESOURCE_URI)
                .queryParam(PARAM_STATE, STATE)
                .request()
                .get()) {

            String location = response.getLocation().toString();
            assertThat(location).startsWith(BASE_OPIK_URL + "/login?returnTo=");
            String returnTo = URLDecoder.decode(
                    location.substring(location.indexOf("returnTo=") + "returnTo=".length()),
                    StandardCharsets.UTF_8);
            assertThat(returnTo)
                    .startsWith(BASE_OPIK_URL + "/oauth/authorize?")
                    .contains("client_id=" + CLIENT_ID);
        }
    }

    @Test
    @DisplayName("GET /authorize/context: secure cookie flag tracks baseUrl scheme (https → true)")
    void context_httpsBaseUrl_setsSecureCookieTrue() {
        mockClientResolution();
        when(authService.listEligibleWorkspaces(any()))
                .thenReturn(List.of(WorkspaceInfo.builder().id("ws-1").name("default").build()));

        try (Response response = EXT.target(CONTEXT_PATH)
                .queryParam(PARAM_CLIENT_ID, CLIENT_ID)
                .queryParam(PARAM_REDIRECT_URI, REDIRECT_URI)
                .request()
                .cookie(RequestContext.SESSION_COOKIE, "sess")
                .get()) {

            NewCookie csrfCookie = response.getCookies().get(CSRF_COOKIE);
            assertThat(csrfCookie).isNotNull();
            assertThat(csrfCookie.isSecure()).isTrue();
            assertThat(csrfCookie.isHttpOnly()).isTrue();
        }
    }

    @Test
    @DisplayName("GET /authorize/context: secure cookie flag tracks baseUrl scheme (http → false)")
    void context_httpBaseUrl_setsSecureCookieFalse() {
        opikConfig.getMcpOAuth().setBaseUrl("http://localhost:5173");
        mockClientResolution();
        when(authService.listEligibleWorkspaces(any()))
                .thenReturn(List.of(WorkspaceInfo.builder().id("ws-1").name("default").build()));

        try (Response response = EXT.target(CONTEXT_PATH)
                .queryParam(PARAM_CLIENT_ID, CLIENT_ID)
                .queryParam(PARAM_REDIRECT_URI, REDIRECT_URI)
                .request()
                .cookie(RequestContext.SESSION_COOKIE, "sess")
                .get()) {

            assertThat(response.getCookies().get(CSRF_COOKIE).isSecure()).isFalse();
        }
    }

    @Test
    @DisplayName("POST /authorize (consent): valid request mints code and returns redirect URL with state")
    void consent_validRequest_mintsCode() {
        mockClientResolution();
        when(authService.authorizeWorkspace(any(), any()))
                .thenReturn(UserWorkspace.builder().userName("admin").workspaceId("ws-1").workspaceName("default")
                        .build());
        String randomCode = "auth-code-xyz";
        when(mcpOAuthService.createAuthorizationCode(any(CreateOAuthCodeCommand.class))).thenReturn(randomCode);

        try (Response response = EXT.target(AUTHORIZE_PATH)
                .request()
                .cookie(CSRF_COOKIE, CSRF)
                .cookie(RequestContext.SESSION_COOKIE, "sess")
                .post(Entity.json(buildConsent()))) {

            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
            assertThat(response.readEntity(ConsentResponse.class).redirectTo())
                    .startsWith(REDIRECT_URI)
                    .contains("code=" + randomCode)
                    .contains("state=" + STATE);
        }
    }

    @Test
    @DisplayName("POST /authorize (consent): missing CSRF cookie throws 403")
    void consent_missingCsrf_throwsForbidden() {
        try (Response response = EXT.target(AUTHORIZE_PATH)
                .request()
                .post(Entity.json(buildConsent()))) {

            assertThat(response.getStatus()).isEqualTo(Response.Status.FORBIDDEN.getStatusCode());
        }
        verify(mcpOAuthService, never()).createAuthorizationCode(any(CreateOAuthCodeCommand.class));
    }

    @Test
    @DisplayName("POST /authorize (consent): passes workspace from request body to code mint")
    void consent_propagatesWorkspaceToCodeCommand() {
        mockClientResolution();
        UserWorkspace authorizedWorkspace = UserWorkspace.builder()
                .userName("alice").workspaceId("ws-prod").workspaceName("production").build();
        when(authService.authorizeWorkspace(any(), any())).thenReturn(authorizedWorkspace);
        when(mcpOAuthService.createAuthorizationCode(any(CreateOAuthCodeCommand.class))).thenReturn("code");

        try (Response response = EXT.target(AUTHORIZE_PATH)
                .request()
                .cookie(CSRF_COOKIE, CSRF)
                .cookie(RequestContext.SESSION_COOKIE, "sess")
                .post(Entity.json(buildConsent()))) {

            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
        }

        ArgumentCaptor<CreateOAuthCodeCommand> captor = ArgumentCaptor.forClass(CreateOAuthCodeCommand.class);
        verify(mcpOAuthService).createAuthorizationCode(captor.capture());
        CreateOAuthCodeCommand cmd = captor.getValue();
        assertThat(cmd.userName()).isEqualTo(authorizedWorkspace.userName());
        assertThat(cmd.workspaceName()).isEqualTo(authorizedWorkspace.workspaceName());
        assertThat(cmd.workspaceId()).isEqualTo(authorizedWorkspace.workspaceId());
        assertThat(cmd.codeChallenge()).isEqualTo(CODE_CHALLENGE);
    }
}
