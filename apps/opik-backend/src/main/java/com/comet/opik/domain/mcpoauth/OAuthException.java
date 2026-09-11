package com.comet.opik.domain.mcpoauth;

import jakarta.ws.rs.BadRequestException;
import lombok.Getter;

/**
 * Signals an OAuth protocol error (RFC 6749 §5.2). The {@link #getError() error} code is rendered into the
 * OAuth error envelope by {@code OAuthExceptionMapper}, so callers never need to assemble the error response
 * themselves. Extends {@link BadRequestException} so it carries 400 semantics if no mapper is in play.
 */
@Getter
public class OAuthException extends BadRequestException {

    private final String error;

    public OAuthException(String error) {
        super(error);
        this.error = error;
    }
}
