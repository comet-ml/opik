package com.comet.opik.infrastructure.redaction;

import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.glassfish.jersey.server.ExtendedUriInfo;
import org.glassfish.jersey.uri.UriTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Nested
    @DisplayName("filter decides on the matched templates")
    class Filter {

        private final RedactionService redactionService = mock(RedactionService.class);
        private final RequestContext requestContext = new RequestContext();
        private final RedactionRequestFilter filter = new RedactionRequestFilter(redactionService,
                () -> requestContext);

        @Test
        @DisplayName("records the decision for a private resource")
        void privateResource() throws Exception {
            enabled(true);
            var context = requestContext("/v1/private/traces|/{id}");

            filter.filter(context);

            assertThat(requestContext.isRedactResponse()).isTrue();
            verify(context).setProperty(RedactionRequestFilter.REDACT_RESPONSE_PROPERTY, true);
        }

        @Test
        @DisplayName("records the decision for the analytics query executor")
        void analyticsQueries() throws Exception {
            enabled(true);
            var context = requestContext("/v1/internal/analytics-queries|/projects/{projectId}");

            filter.filter(context);

            assertThat(requestContext.isRedactResponse()).isTrue();
            verify(context).setProperty(RedactionRequestFilter.REDACT_RESPONSE_PROPERTY, true);
        }

        @Test
        @DisplayName("leaves an unauthenticated resource alone")
        void usageResource() throws Exception {
            enabled(true);
            var context = requestContext("/v1/internal/usage|/workspace-trace-counts");

            filter.filter(context);

            assertThat(requestContext.isRedactResponse()).isFalse();
            verify(context, never()).setProperty(any(), any());
        }

        @Test
        @DisplayName("records the decision when no template matched")
        void nothingMatched() throws Exception {
            enabled(true);
            var context = requestContext("");

            filter.filter(context);

            assertThat(requestContext.isRedactResponse()).isTrue();
            verify(context).setProperty(RedactionRequestFilter.REDACT_RESPONSE_PROPERTY, true);
        }

        @Test
        @DisplayName("does nothing while redaction is disabled")
        void disabled() throws Exception {
            enabled(false);
            var context = requestContext("/v1/private/traces");

            filter.filter(context);

            assertThat(requestContext.isRedactResponse()).isFalse();
            verify(context, never()).setProperty(any(), any());
            verify(context, never()).getUriInfo();
        }

        private void enabled(boolean enabled) {
            when(redactionService.isEnabled()).thenReturn(enabled);
            lenient().when(redactionService.shouldRedactFor(Set.of())).thenReturn(true);
        }

        private ContainerRequestContext requestContext(String templates) {
            List<UriTemplate> matched = templates.isEmpty()
                    ? List.of()
                    : Arrays.stream(templates.split("\\|")).map(UriTemplate::new).toList().reversed();
            ExtendedUriInfo uriInfo = mock(ExtendedUriInfo.class);
            lenient().when(uriInfo.getMatchedTemplates()).thenReturn(matched);
            ContainerRequestContext context = mock(ContainerRequestContext.class);
            lenient().when(context.getUriInfo()).thenReturn(uriInfo);
            return context;
        }
    }
}
