package com.comet.opik.infrastructure.redaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RedactionRequestFilterTest {

    @ParameterizedTest(name = "{0} -> covered={1}")
    @MethodSource
    @DisplayName("redaction covers the authenticated paths that carry stored content, and nothing else")
    void coversPath(String path, boolean expected) {
        assertThat(RedactionRequestFilter.coversPath(path)).isEqualTo(expected);
    }

    static Stream<Arguments> coversPath() {
        return Stream.of(
                Arguments.of("/v1/private/traces", true),
                Arguments.of("/v1/private/traces/search", true),
                Arguments.of("/v1/private/spans", true),
                Arguments.of("/v1/private/datasets/items/stream", true),
                // The route that made the previous private-only predicate a bypass: caller-supplied SQL
                // returning stored trace content.
                Arguments.of("/v1/internal/analytics-queries/projects/01a0", true),
                // Unauthenticated, so there is no caller to decide about — and its rows carry a per-user
                // identifier the platform's usage attribution depends on.
                Arguments.of("/v1/internal/usage/workspace-trace-counts", false),
                Arguments.of("/v1/internal/usage/bi-traces", false),
                // A redirect carries nothing worth rewriting.
                Arguments.of("/v1/session/redirect", false),
                // Outside the versioned API: these return tokens and metadata, and the rule set includes
                // patterns for bearer tokens and JWTs that would destroy them.
                Arguments.of("/oauth/register", false),
                Arguments.of("/.well-known/oauth-authorization-server", false),
                Arguments.of("/is-alive/ping", false),
                Arguments.of("/openapi.json", false));
    }
}
