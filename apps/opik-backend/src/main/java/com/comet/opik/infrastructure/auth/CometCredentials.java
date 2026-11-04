package com.comet.opik.infrastructure.auth;

import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.HttpHeaders;
import lombok.NonNull;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.OAUTH_USERNAME_HEADER;

record CometCredentials(@NonNull Kind kind, @NonNull String value) {

    enum Kind {
        API_KEY("workspace-organization"),
        SESSION("workspace-organization-session"),
        USERNAME("workspace-organization-by-username");

        private final String organizationPath;

        Kind(String organizationPath) {
            this.organizationPath = organizationPath;
        }

        String organizationPath() {
            return organizationPath;
        }
    }

    static CometCredentials apiKey(String apiKey) {
        return new CometCredentials(Kind.API_KEY, apiKey);
    }

    static CometCredentials session(String sessionToken) {
        return new CometCredentials(Kind.SESSION, sessionToken);
    }

    static CometCredentials username(String userName) {
        return new CometCredentials(Kind.USERNAME, userName);
    }

    void applyTo(Invocation.Builder builder) {
        switch (kind) {
            case API_KEY -> builder.header(HttpHeaders.AUTHORIZATION, value);
            case SESSION -> builder.cookie(RequestContext.SESSION_COOKIE, value);
            case USERNAME -> builder.header(OAUTH_USERNAME_HEADER, value);
        }
    }

    @Override
    public String toString() {
        return "CometCredentials[kind=" + kind + "]";
    }
}
