package com.comet.opik.infrastructure.auth;

import com.comet.opik.domain.ProjectService;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private RequestContext requestContext;

    @Mock
    private HttpHeaders headers;

    private final static String PATH = "some-path";

    private AuthServiceImpl authService = new AuthServiceImpl(() -> requestContext);

    @Test
    void testAuthenticate__whenCookieAndHeaderNotPresent__thenUseDefault() {
        // Given
        Cookie sessionToken = null;

        // When
        authService.authenticate(headers, sessionToken, PATH);

        // Then
        verify(requestContext).setUserName(ProjectService.DEFAULT_USER);
    }

    @Test
    void testAuthenticate__whenCookieAndHeaderPresent__thenUseHeader() {
        // Given
        Cookie sessionToken = new Cookie("sessionToken", "token");

        // When
        authService.authenticate(headers, sessionToken, PATH);

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
                () -> authService.authenticate(headers, sessionToken, PATH));
    }
}
