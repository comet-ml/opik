package com.comet.opik.infrastructure.auth;

import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.mcpoauth.ValidatedToken;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    static Stream<Cookie> sessionTokens() {
        return Stream.of(null, new Cookie("sessionToken", "token"));
    }

    @ParameterizedTest
    @MethodSource("sessionTokens")
    void testAuthenticate__whenWorkspaceIsDefault__thenUseDefaultUser(Cookie sessionToken) {
        authService.authenticate(headers, sessionToken, infoHolder);

        verify(requestContext).setUserName(ProjectService.DEFAULT_USER);
    }

    @Test
    void testAuthenticate__whenWorkspaceIsNotDefault__thenFail() {
        Cookie sessionToken = new Cookie("sessionToken", "token");
        when(headers.getHeaderString(RequestContext.WORKSPACE_HEADER))
                .thenReturn("workspace");

        assertThatThrownBy(() -> authService.authenticate(headers, sessionToken, infoHolder))
                .isInstanceOf(ClientErrorException.class);
    }

    @Test
    void testListEligibleWorkspaces__returnsDefaultWorkspace() {
        List<WorkspaceInfo> result = authService.listEligibleWorkspaces(null);

        assertThat(result).isEqualTo(List.of(WorkspaceInfo.builder()
                .id(ProjectService.DEFAULT_WORKSPACE_ID)
                .name(ProjectService.DEFAULT_WORKSPACE_NAME)
                .build()));
    }

    @Test
    void testAuthorizeWorkspace__returnsDefaultUserWorkspace() {
        UserWorkspace result = authService.authorizeWorkspace(null, "ignored-workspace");

        assertThat(result).isEqualTo(UserWorkspace.builder()
                .userName(ProjectService.DEFAULT_USER)
                .workspaceId(ProjectService.DEFAULT_WORKSPACE_ID)
                .workspaceName(ProjectService.DEFAULT_WORKSPACE_NAME)
                .build());
    }

    @Test
    void testAuthorizeOAuth__setsTokenWorkspaceIntoContext() {
        ValidatedToken token = ValidatedToken.builder()
                .userName("oauth-user")
                .workspaceId("ws-id")
                .workspaceName("ws-name")
                .resource("resource")
                .build();

        authService.authorizeOAuth(token, infoHolder);

        verify(requestContext).setUserName(token.userName());
        verify(requestContext).setWorkspaceId(token.workspaceId());
        verify(requestContext).setWorkspaceName(token.workspaceName());
    }
}
