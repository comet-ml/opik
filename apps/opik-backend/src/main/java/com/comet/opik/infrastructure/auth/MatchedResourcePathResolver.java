package com.comet.opik.infrastructure.auth;

import jakarta.ws.rs.core.UriInfo;
import lombok.experimental.UtilityClass;
import org.glassfish.jersey.server.ExtendedUriInfo;
import org.glassfish.jersey.uri.UriTemplate;

import java.util.List;

@UtilityClass
public class MatchedResourcePathResolver {

    public String resolve(UriInfo uriInfo) {
        if (!(uriInfo instanceof ExtendedUriInfo extendedUriInfo)) {
            return null;
        }
        List<UriTemplate> templates = extendedUriInfo.getMatchedTemplates();
        if (templates == null || templates.isEmpty()) {
            return null;
        }
        var path = new StringBuilder();
        for (int i = templates.size() - 1; i >= 0; i--) {
            path.append('/').append(templates.get(i).getTemplate());
        }
        return path.toString().replaceAll("/{2,}", "/");
    }
}
