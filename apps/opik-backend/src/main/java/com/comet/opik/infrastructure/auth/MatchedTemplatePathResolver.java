package com.comet.opik.infrastructure.auth;

import jakarta.ws.rs.core.UriInfo;
import lombok.experimental.UtilityClass;
import org.apache.commons.collections4.CollectionUtils;
import org.glassfish.jersey.server.ExtendedUriInfo;
import org.glassfish.jersey.uri.UriTemplate;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@UtilityClass
public class MatchedTemplatePathResolver {

    private static final Pattern REPEATED_SLASHES = Pattern.compile("/{2,}");

    public Optional<String> resolve(UriInfo uriInfo) {
        if (!(uriInfo instanceof ExtendedUriInfo extendedUriInfo)) {
            return Optional.empty();
        }
        List<UriTemplate> templates = extendedUriInfo.getMatchedTemplates();
        if (CollectionUtils.isEmpty(templates)) {
            return Optional.empty();
        }
        var templatePath = new StringBuilder();
        for (int i = templates.size() - 1; i >= 0; i--) {
            templatePath.append(templates.get(i).getTemplate());
        }
        return Optional.of(REPEATED_SLASHES.matcher(templatePath).replaceAll("/"));
    }
}
