package com.comet.opik.api.resources.oauth;

import com.comet.opik.domain.mcpoauth.OAuthException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.slf4j.Slf4j;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.CACHE_CONTROL_NO_STORE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.HEADER_PRAGMA;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PRAGMA_NO_CACHE;

/**
 * Renders an {@link OAuthException} as the RFC 6749 §5.2 error envelope: a {@code 400} carrying a JSON
 * {@link OAuthError} body and the §5.1 no-store cache headers. Centralising this here keeps the token
 * resource free of error-assembly and try-catch logic.
 */
@Slf4j
@Provider
public class OAuthExceptionMapper implements ExceptionMapper<OAuthException> {

    @Override
    public Response toResponse(OAuthException exception) {
        log.warn("MCP OAuth request rejected [error={}]", exception.getError());
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_NO_STORE)
                .header(HEADER_PRAGMA, PRAGMA_NO_CACHE)
                .entity(OAuthError.builder().error(exception.getError()).build())
                .build();
    }
}
