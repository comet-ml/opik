package com.comet.opik.infrastructure.auth;

import com.comet.opik.domain.ProjectService;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

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
}
