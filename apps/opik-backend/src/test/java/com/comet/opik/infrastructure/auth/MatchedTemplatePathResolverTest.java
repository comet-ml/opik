package com.comet.opik.infrastructure.auth;

import jakarta.ws.rs.core.UriInfo;
import org.glassfish.jersey.server.ExtendedUriInfo;
import org.glassfish.jersey.uri.PathTemplate;
import org.glassfish.jersey.uri.UriTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("MatchedTemplatePathResolver")
class MatchedTemplatePathResolverTest {

    static Stream<Arguments> matchedTemplates() {
        return Stream.of(
                arguments(templates("/v1/private/projects"), "/v1/private/projects"),
                arguments(templates("/v1/private/projects", "/{id}"), "/v1/private/projects/{id}"),
                arguments(templates("/v1/private/datasets", "/{id}", "/items/{itemId}"),
                        "/v1/private/datasets/{id}/items/{itemId}"),
                arguments(templates("/v1/private/projects", "stats/"), "/v1/private/projects/stats/"),
                arguments(templates("/v1/private/", "/toggles"), "/v1/private/toggles"),
                arguments(templates("/v1/private/projects", "/"), "/v1/private/projects/"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("matchedTemplates")
    @DisplayName("joins the templates in matching order with single slashes, keeping template variables")
    void resolve(List<UriTemplate> matched, String expected) {
        assertThat(MatchedTemplatePathResolver.resolve(uriInfo(matched))).contains(expected);
    }

    @Test
    @DisplayName("resolves nothing when no template matched")
    void noTemplates() {
        assertThat(MatchedTemplatePathResolver.resolve(uriInfo(List.of()))).isEmpty();
    }

    @Test
    @DisplayName("resolves nothing for a uri info without matching information")
    void plainUriInfo() {
        assertThat(MatchedTemplatePathResolver.resolve(mock(UriInfo.class))).isEmpty();
    }

    private static List<UriTemplate> templates(String... templatesInMatchingOrder) {
        return Stream.of(templatesInMatchingOrder)
                .map(template -> (UriTemplate) new PathTemplate(template))
                .toList()
                .reversed();
    }

    private static ExtendedUriInfo uriInfo(List<UriTemplate> matched) {
        ExtendedUriInfo uriInfo = mock(ExtendedUriInfo.class);
        when(uriInfo.getMatchedTemplates()).thenReturn(matched);
        return uriInfo;
    }
}
