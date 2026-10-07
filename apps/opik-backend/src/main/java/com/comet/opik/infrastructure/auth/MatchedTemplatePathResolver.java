package com.comet.opik.infrastructure.auth;

import jakarta.ws.rs.core.UriInfo;
import lombok.experimental.UtilityClass;
import org.glassfish.jersey.server.ExtendedUriInfo;
import org.glassfish.jersey.uri.UriTemplate;

import java.util.List;
import java.util.regex.Pattern;

@UtilityClass
public class MatchedTemplatePathResolver {

    private static final Pattern REPEATED_SLASHES = Pattern.compile("/{2,}");

    public String resolve(UriInfo uriInfo) {
        if (!(uriInfo instanceof ExtendedUriInfo extendedUriInfo)) {
            return null;
        }
        List<UriTemplate> templates = extendedUriInfo.getMatchedTemplates();
        if (templates == null || templates.isEmpty()) {
            return null;
        }
        var templatePath = new StringBuilder();
        for (int i = templates.size() - 1; i >= 0; i--) {
            templatePath.append('/').append(templates.get(i).getTemplate());
        }
        return REPEATED_SLASHES.matcher(templatePath).replaceAll("/");
    }
}
