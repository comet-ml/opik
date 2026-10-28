package com.comet.opik.infrastructure.auth;

import lombok.NonNull;

/**
 * The credential the platform authenticated a caller with, so that a later platform call can be made on the caller's
 * behalf: the API key, the browser session token or, for OAuth, the user name. Only {@link RemoteAuthService} sets
 * it. The value is a secret and stays out of {@link #toString()}.
 */
public record PlatformCredential(@NonNull Type type, @NonNull String value) {

    public enum Type {
        API_KEY,
        SESSION,
        OAUTH_USER
    }

    @Override
    public String toString() {
        return "PlatformCredential[type=%s]".formatted(type);
    }
}
