package com.comet.opik.infrastructure.redaction;

import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.glassfish.jersey.server.ExtendedUriInfo;
import org.glassfish.jersey.uri.PathTemplate;
import org.glassfish.jersey.uri.UriTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
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

        @Mock
        private RedactionService redactionService;

        private final RequestContext requestContext = new RequestContext();

        private RedactionRequestFilter filter;

        @BeforeEach
        void setUp() {
            filter = new RedactionRequestFilter(redactionService, () -> requestContext);
        }

        static Stream<Arguments> coveredResources() {
            return Stream.of(
                    templates("/v1/private/traces", "/{id}"),
                    templates("/v1/internal/analytics-queries", "/projects/{projectId}"),
                    List.<UriTemplate>of())
                    .flatMap(matched -> Stream.of(arguments(matched, true), arguments(matched, false)));
        }

        @ParameterizedTest
        @MethodSource("coveredResources")
        @DisplayName("records the decision for a covered resource, and for anything unmatched")
        void coveredResource(List<UriTemplate> matchedTemplates, boolean redact) throws Exception {
            when(redactionService.isEnabled()).thenReturn(true);
            when(redactionService.shouldRedactFor(Set.of())).thenReturn(redact);
            var context = requestContext(matchedTemplates);

            filter.filter(context);

            assertThat(requestContext.isRedactResponse()).isEqualTo(redact);
            verify(context).getUriInfo();
            verify(context).setProperty(RedactionRequestFilter.REDACT_RESPONSE_PROPERTY, redact);
            verifyNoMoreInteractions(context);
        }

        @Test
        @DisplayName("leaves an unauthenticated resource alone")
        void usageResource() throws Exception {
            when(redactionService.isEnabled()).thenReturn(true);
            var context = requestContext(templates("/v1/internal/usage", "/workspace-trace-counts"));

            filter.filter(context);

            assertThat(requestContext.isRedactResponse()).isFalse();
            verify(context).getUriInfo();
            verifyNoMoreInteractions(context);
            verify(redactionService).isEnabled();
            verifyNoMoreInteractions(redactionService);
        }

        @Test
        @DisplayName("does nothing while redaction is disabled")
        void disabled() throws Exception {
            when(redactionService.isEnabled()).thenReturn(false);
            var context = mock(ContainerRequestContext.class);

            filter.filter(context);

            assertThat(requestContext.isRedactResponse()).isFalse();
            verifyNoInteractions(context);
        }

        private static List<UriTemplate> templates(String... templatesInMatchingOrder) {
            return Stream.of(templatesInMatchingOrder)
                    .map(template -> (UriTemplate) new PathTemplate(template))
                    .toList()
                    .reversed();
        }

        private static ContainerRequestContext requestContext(List<UriTemplate> matchedTemplates) {
            ExtendedUriInfo uriInfo = mock(ExtendedUriInfo.class);
            when(uriInfo.getMatchedTemplates()).thenReturn(matchedTemplates);
            ContainerRequestContext context = mock(ContainerRequestContext.class);
            when(context.getUriInfo()).thenReturn(uriInfo);
            return context;
        }
    }
}
