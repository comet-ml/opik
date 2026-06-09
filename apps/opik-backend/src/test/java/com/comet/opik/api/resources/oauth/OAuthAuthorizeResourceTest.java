package com.comet.opik.api.resources.oauth;

import com.comet.opik.domain.mcpoauth.CreateOAuthCodeCommand;
import com.comet.opik.domain.mcpoauth.McpOAuthClient;
import com.comet.opik.domain.mcpoauth.McpOAuthService;
import com.comet.opik.domain.mcpoauth.OAuthClientService;
import com.comet.opik.infrastructure.McpOAuthConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.AuthService;
import com.comet.opik.infrastructure.auth.UserWorkspace;
import com.comet.opik.infrastructure.auth.WorkspaceInfo;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.CODE_CHALLENGE_METHOD_S256;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.CSRF_COOKIE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_INVALID_REQUEST;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_INVALID_TARGET;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_UNSUPPORTED_RESPONSE_TYPE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.RESPONSE_TYPE_CODE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OAuth Authorize Resource Test")
class OAuthAuthorizeResourceTest {

    private static final String CLIENT_ID = "test-client";
    private static final String REDIRECT_URI = "http://localhost:1234/cb";
    private static final String RESOURCE_URI = "https://www.comet.com/opik/api/v1/mcp";
    private static final String CODE_CHALLENGE = "abc123challenge";
    private static final String STATE = "state-xyz";
    private static final String CSRF = "csrf-token-abc";
    private static final String SESSION_COOKIE_NAME = "sessionToken";

    @Mock
    private OAuthClientService clientService;
    @Mock
    private AuthService authService;
    @Mock
    private McpOAuthService mcpOAuthService;
    @Mock
    private OpikConfiguration opikConfig;
    @Mock
    private HttpHeaders headers;
    @Mock
    private UriInfo uriInfo;

    private OAuthAuthorizeResource resource;
    private McpOAuthConfig mcpConfig;

    @BeforeEach
    void setUp() {
        resource = new OAuthAuthorizeResource(clientService, authService, mcpOAuthService, opikConfig);
        mcpConfig = new McpOAuthConfig();
        mcpConfig.setEnabled(true);
        mcpConfig.setBaseUrl("https://www.comet.com/opik");
        mcpConfig.setMcpResourceUri(RESOURCE_URI);
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

    private void mockCookies(Map<String, Cookie> cookies) {
        when(headers.getCookies()).thenReturn(cookies);
    }

    @Test
    @DisplayName("GET /authorize: valid request redirects to consent UI with PKCE+state preserved")
    void authorize_validRequest_redirectsToConsent() {
        mockClientResolution();
        when(opikConfig.getMcpOAuth()).thenReturn(mcpConfig);
        mockCookies(Map.of(SESSION_COOKIE_NAME, new Cookie.Builder(SESSION_COOKIE_NAME).value("sess").build()));
        when(authService.listEligibleWorkspaces(any()))
                .thenReturn(List.of(WorkspaceInfo.builder().id("ws-1").name("default").build()));

        Response response = resource.authorize(CLIENT_ID, REDIRECT_URI, RESPONSE_TYPE_CODE, CODE_CHALLENGE,
                CODE_CHALLENGE_METHOD_S256,
                RESOURCE_URI, STATE, headers, uriInfo);

        assertThat(response.getStatus()).isEqualTo(Response.Status.FOUND.getStatusCode());
        URI location = response.getLocation();
        assertThat(location.toString())
                .startsWith("https://www.comet.com/opik/oauth/consent?")
                .contains("client_id=" + CLIENT_ID)
                .contains("code_challenge=" + CODE_CHALLENGE)
                .contains("code_challenge_method=" + CODE_CHALLENGE_METHOD_S256)
                .contains("state=" + STATE);
    }

    @Test
    @DisplayName("GET /authorize: invalid response_type redirects with unsupported_response_type")
    void authorize_invalidResponseType_redirectsWithError() {
        mockClientResolution();

        Response response = resource.authorize(CLIENT_ID, REDIRECT_URI, "token", CODE_CHALLENGE,
                CODE_CHALLENGE_METHOD_S256,
                RESOURCE_URI, STATE, headers, uriInfo);

        assertThat(response.getStatus()).isEqualTo(Response.Status.FOUND.getStatusCode());
        assertThat(response.getLocation().toString())
                .startsWith(REDIRECT_URI)
                .contains("error=" + ERROR_UNSUPPORTED_RESPONSE_TYPE);
    }

    @Test
    @DisplayName("GET /authorize: blank code_challenge redirects with invalid_request")
    void authorize_blankCodeChallenge_redirectsWithError() {
        mockClientResolution();

        Response response = resource.authorize(CLIENT_ID, REDIRECT_URI, RESPONSE_TYPE_CODE, "  ",
                CODE_CHALLENGE_METHOD_S256,
                RESOURCE_URI, STATE, headers, uriInfo);

        assertThat(response.getLocation().toString()).contains("error=" + ERROR_INVALID_REQUEST);
    }

    @Test
    @DisplayName("GET /authorize: mismatched resource redirects with invalid_target")
    void authorize_resourceMismatch_redirectsWithError() {
        mockClientResolution();
        when(opikConfig.getMcpOAuth()).thenReturn(mcpConfig);

        Response response = resource.authorize(CLIENT_ID, REDIRECT_URI, RESPONSE_TYPE_CODE, CODE_CHALLENGE,
                CODE_CHALLENGE_METHOD_S256,
                "https://attacker.example.com/api/v1/mcp", STATE, headers, uriInfo);

        assertThat(response.getLocation().toString()).contains("error=" + ERROR_INVALID_TARGET);
    }

    @Test
    @DisplayName("GET /authorize: session check failure redirects to login with return_to")
    void authorize_noSession_redirectsToLogin() {
        mockClientResolution();
        when(opikConfig.getMcpOAuth()).thenReturn(mcpConfig);
        mockCookies(new HashMap<>());
        when(authService.listEligibleWorkspaces(any())).thenThrow(new ClientErrorException(401));
        URI requestUri = URI.create("https://www.comet.com/opik/oauth/authorize?client_id=" + CLIENT_ID);
        when(uriInfo.getRequestUri()).thenReturn(requestUri);

        Response response = resource.authorize(CLIENT_ID, REDIRECT_URI, RESPONSE_TYPE_CODE, CODE_CHALLENGE,
                CODE_CHALLENGE_METHOD_S256,
                RESOURCE_URI, STATE, headers, uriInfo);

        String location = response.getLocation().toString();
        assertThat(location).startsWith("https://www.comet.com/opik/login?returnTo=");
        String returnTo = java.net.URLDecoder.decode(
                location.substring(location.indexOf("returnTo=") + "returnTo=".length()),
                java.nio.charset.StandardCharsets.UTF_8);
        // return_to must be the public authorize URL (with /opik prefix), not the nginx-internal path
        assertThat(returnTo).startsWith("https://www.comet.com/opik/oauth/authorize?");
        assertThat(returnTo).contains("client_id=" + CLIENT_ID);
    }

    @Test
    @DisplayName("GET /authorize/context: secure cookie flag tracks baseUrl scheme (https → true)")
    void context_httpsBaseUrl_setsSecureCookieTrue() {
        mockClientResolution();
        when(opikConfig.getMcpOAuth()).thenReturn(mcpConfig);
        mockCookies(Map.of(SESSION_COOKIE_NAME, new Cookie.Builder(SESSION_COOKIE_NAME).value("sess").build()));
        when(authService.listEligibleWorkspaces(any()))
                .thenReturn(List.of(WorkspaceInfo.builder().id("ws-1").name("default").build()));

        Response response = resource.context(CLIENT_ID, REDIRECT_URI, headers);

        NewCookie csrfCookie = response.getCookies().get(CSRF_COOKIE);
        assertThat(csrfCookie).isNotNull();
        assertThat(csrfCookie.isSecure()).isTrue();
        assertThat(csrfCookie.isHttpOnly()).isTrue();
    }

    @Test
    @DisplayName("GET /authorize/context: secure cookie flag tracks baseUrl scheme (http → false)")
    void context_httpBaseUrl_setsSecureCookieFalse() {
        mcpConfig.setBaseUrl("http://localhost:5173");
        mockClientResolution();
        when(opikConfig.getMcpOAuth()).thenReturn(mcpConfig);
        mockCookies(Map.of(SESSION_COOKIE_NAME, new Cookie.Builder(SESSION_COOKIE_NAME).value("sess").build()));
        when(authService.listEligibleWorkspaces(any()))
                .thenReturn(List.of(WorkspaceInfo.builder().id("ws-1").name("default").build()));

        Response response = resource.context(CLIENT_ID, REDIRECT_URI, headers);

        NewCookie csrfCookie = response.getCookies().get(CSRF_COOKIE);
        assertThat(csrfCookie.isSecure()).isFalse();
    }

    @Test
    @DisplayName("POST /authorize (consent): valid request mints code and returns redirect URL with state")
    void consent_validRequest_mintsCode() {
        mockClientResolution();
        when(opikConfig.getMcpOAuth()).thenReturn(mcpConfig);
        mockCookies(Map.of(
                CSRF_COOKIE, new Cookie.Builder(CSRF_COOKIE).value(CSRF).build(),
                SESSION_COOKIE_NAME, new Cookie.Builder(SESSION_COOKIE_NAME).value("sess").build()));
        when(authService.authorizeWorkspace(any(), any()))
                .thenReturn(UserWorkspace.builder().userName("admin").workspaceId("ws-1").workspaceName("default")
                        .build());
        when(mcpOAuthService.createAuthorizationCode(any(CreateOAuthCodeCommand.class))).thenReturn("auth-code-xyz");

        Response response = resource.consent(buildConsent(CODE_CHALLENGE), headers);

        assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
        ConsentResponse body = (ConsentResponse) response.getEntity();
        assertThat(body.redirectTo())
                .startsWith(REDIRECT_URI)
                .contains("code=auth-code-xyz")
                .contains("state=" + STATE);
    }

    @Test
    @DisplayName("POST /authorize (consent): missing CSRF cookie throws 403")
    void consent_missingCsrf_throwsForbidden() {
        mockCookies(new HashMap<>());

        assertThatThrownBy(() -> resource.consent(buildConsent(CODE_CHALLENGE), headers))
                .isInstanceOf(ForbiddenException.class);
        verify(mcpOAuthService, never()).createAuthorizationCode(any(CreateOAuthCodeCommand.class));
    }

    @Test
    @DisplayName("POST /authorize (consent): passes workspace from request body to code mint")
    void consent_propagatesWorkspaceToCodeCommand() {
        mockClientResolution();
        when(opikConfig.getMcpOAuth()).thenReturn(mcpConfig);
        mockCookies(Map.of(
                CSRF_COOKIE, new Cookie.Builder(CSRF_COOKIE).value(CSRF).build(),
                SESSION_COOKIE_NAME, new Cookie.Builder(SESSION_COOKIE_NAME).value("sess").build()));
        when(authService.authorizeWorkspace(any(), any()))
                .thenReturn(UserWorkspace.builder().userName("alice").workspaceId("ws-prod").workspaceName("production")
                        .build());
        when(mcpOAuthService.createAuthorizationCode(any(CreateOAuthCodeCommand.class))).thenReturn("code");

        resource.consent(buildConsent(CODE_CHALLENGE), headers);

        ArgumentCaptor<CreateOAuthCodeCommand> captor = ArgumentCaptor.forClass(CreateOAuthCodeCommand.class);
        verify(mcpOAuthService).createAuthorizationCode(captor.capture());
        CreateOAuthCodeCommand cmd = captor.getValue();
        assertThat(cmd.userName()).isEqualTo("alice");
        assertThat(cmd.workspaceName()).isEqualTo("production");
        assertThat(cmd.workspaceId()).isEqualTo("ws-prod");
        assertThat(cmd.codeChallenge()).isEqualTo(CODE_CHALLENGE);
    }

    private ConsentRequest buildConsent(String codeChallenge) {
        return ConsentRequest.builder()
                .clientId(CLIENT_ID)
                .redirectUri(REDIRECT_URI)
                .codeChallenge(codeChallenge)
                .codeChallengeMethod(CODE_CHALLENGE_METHOD_S256)
                .resource(RESOURCE_URI)
                .state(STATE)
                .workspaceId("ws-1")
                .workspaceName("default")
                .csrf(CSRF)
                .build();
    }
}
