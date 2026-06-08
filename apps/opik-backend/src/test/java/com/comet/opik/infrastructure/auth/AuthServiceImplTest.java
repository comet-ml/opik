package com.comet.opik.infrastructure.auth;

import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.mcpoauth.ValidatedToken;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private RequestContext requestContext;

    @Mock
    private HttpHeaders headers;

    private UriInfo uriInfo = Mockito.mock(UriInfo.class);

    private AuthServiceImpl authService = new AuthServiceImpl(() -> requestContext);
    private ContextInfoHolder infoHolder = ContextInfoHolder.builder()
            .uriInfo(uriInfo)
            .method("GET")
            .build();

    @Test
    void testAuthenticate__whenCookieAndHeaderNotPresent__thenUseDefault() {
        // Given
        Cookie sessionToken = null;

        // When
        authService.authenticate(headers, sessionToken, infoHolder);

        // Then
        verify(requestContext).setUserName(ProjectService.DEFAULT_USER);
    }

    @Test
    void testAuthenticate__whenCookieAndHeaderPresent__thenUseHeader() {
        // Given
        Cookie sessionToken = new Cookie("sessionToken", "token");

        // When
        authService.authenticate(headers, sessionToken, infoHolder);

        // Then
        verify(requestContext).setUserName(ProjectService.DEFAULT_USER);
    }

    @Test
    void testAuthenticate__whenWorkspaceIsNotDefault__thenFail() {
        // Given
        Cookie sessionToken = new Cookie("sessionToken", "token");

        // When
        when(headers.getHeaderString(RequestContext.WORKSPACE_HEADER))
                .thenReturn("workspace");

        Assertions.assertThrows(
                jakarta.ws.rs.ClientErrorException.class,
                () -> authService.authenticate(headers, sessionToken, infoHolder));
    }

    @Test
    void testListEligibleWorkspaces__returnsDefaultWorkspace() {
        // When
        List<WorkspaceInfo> result = authService.listEligibleWorkspaces(null);

        // Then
        Assertions.assertEquals(
                List.of(WorkspaceInfo.builder()
                        .id(ProjectService.DEFAULT_WORKSPACE_ID)
                        .name(ProjectService.DEFAULT_WORKSPACE_NAME)
                        .build()),
                result);
    }

    @Test
    void testAuthorizeWorkspace__returnsDefaultUserWorkspace() {
        // When
        UserWorkspace result = authService.authorizeWorkspace(null, "ignored-workspace");

        // Then
        Assertions.assertEquals(
                UserWorkspace.builder()
                        .userName(ProjectService.DEFAULT_USER)
                        .workspaceId(ProjectService.DEFAULT_WORKSPACE_ID)
                        .workspaceName(ProjectService.DEFAULT_WORKSPACE_NAME)
                        .build(),
                result);
    }

    @Test
    void testAuthorizeOAuth__setsTokenWorkspaceIntoContext() {
        // Given
        ValidatedToken token = ValidatedToken.builder()
                .userName("oauth-user")
                .workspaceId("ws-id")
                .workspaceName("ws-name")
                .resource("resource")
                .build();

        // When
        authService.authorizeOAuth(token, infoHolder);

        // Then
        verify(requestContext).setUserName("oauth-user");
        verify(requestContext).setWorkspaceId("ws-id");
        verify(requestContext).setWorkspaceName("ws-name");
    }
}
