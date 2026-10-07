package com.comet.opik.infrastructure.auth;

import jakarta.ws.rs.core.UriInfo;
import org.glassfish.jersey.server.ExtendedUriInfo;
import org.glassfish.jersey.uri.UriTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("MatchedTemplatePathResolver")
class MatchedTemplatePathResolverTest {

    @Test
    @DisplayName("uses the single matched template as is")
    void singleTemplate() {
        assertThat(resolve("/v1/private/projects")).isEqualTo("/v1/private/projects");
    }

    @Test
    @DisplayName("joins the templates in matching order, keeping template variables")
    void nestedTemplates() {
        assertThat(resolve("/{id}", "/v1/private/projects")).isEqualTo("/v1/private/projects/{id}");
        assertThat(resolve("/items/{itemId}", "/{id}", "/v1/private/datasets"))
                .isEqualTo("/v1/private/datasets/{id}/items/{itemId}");
    }

    @Test
    @DisplayName("collapses duplicate slashes between and around templates")
    void normalizesSlashes() {
        assertThat(resolve("stats/", "/v1/private/projects")).isEqualTo("/v1/private/projects/stats/");
        assertThat(resolve("/toggles", "/v1/private/")).isEqualTo("/v1/private/toggles");
    }

    @Test
    @DisplayName("resolves nothing when no template matched")
    void noTemplates() {
        assertThat(resolve()).isNull();
    }

    @Test
    @DisplayName("resolves nothing for a uri info without matching information")
    void plainUriInfo() {
        assertThat(MatchedTemplatePathResolver.resolve(mock(UriInfo.class))).isNull();
    }

    private static String resolve(String... templatesInReverseMatchingOrder) {
        List<UriTemplate> templates = java.util.Arrays.stream(templatesInReverseMatchingOrder)
                .map(UriTemplate::new)
                .toList();
        ExtendedUriInfo uriInfo = mock(ExtendedUriInfo.class);
        when(uriInfo.getMatchedTemplates()).thenReturn(templates);
        return MatchedTemplatePathResolver.resolve(uriInfo);
    }
}
