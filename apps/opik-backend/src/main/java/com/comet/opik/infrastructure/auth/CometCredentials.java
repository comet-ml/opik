package com.comet.opik.infrastructure.auth;

import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.HttpHeaders;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.Value;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.OAUTH_USERNAME_HEADER;

@Value
@Builder(toBuilder = true)
class CometCredentials {

    @Getter
    @RequiredArgsConstructor
    enum Kind {
        API_KEY("workspace-organization"),
        SESSION("workspace-organization-session"),
        USERNAME("workspace-organization-by-username");

        private final @NonNull String organizationPath;
    }

    @NonNull Kind kind;

    @ToString.Exclude
    @NonNull String value;

    static CometCredentials apiKey(@NonNull String apiKey) {
        return builder().kind(Kind.API_KEY).value(apiKey).build();
    }

    static CometCredentials session(@NonNull String sessionToken) {
        return builder().kind(Kind.SESSION).value(sessionToken).build();
    }

    static CometCredentials username(@NonNull String userName) {
        return builder().kind(Kind.USERNAME).value(userName).build();
    }

    void applyTo(@NonNull Invocation.Builder builder) {
        switch (kind) {
            case API_KEY -> builder.header(HttpHeaders.AUTHORIZATION, value);
            case SESSION -> builder.cookie(RequestContext.SESSION_COOKIE, value);
            case USERNAME -> builder.header(OAUTH_USERNAME_HEADER, value);
        }
    }
}
