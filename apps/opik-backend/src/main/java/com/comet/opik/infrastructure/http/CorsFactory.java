package com.comet.opik.infrastructure.http;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.google.common.net.HttpHeaders;
import io.dropwizard.core.setup.Environment;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterRegistration;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.servlets.CrossOriginFilter;

import java.util.EnumSet;

@Slf4j
public class CorsFactory {
    public static final String COMET_WORKSPACE_REQUEST_HEADER = "comet-workspace";
    private static final String CORS_PATH_FILTER = "/*";

    private static final String[] ALLOWED_HEADERS = new String[]{
            HttpHeaders.AUTHORIZATION,
            HttpHeaders.CONTENT_TYPE,
            HttpHeaders.ACCEPT,
            HttpHeaders.X_REQUESTED_WITH,
            HttpHeaders.ORIGIN,
            "Comet-Sdk-Api",
            "comet-username",
            "comet-react-ver",
            COMET_WORKSPACE_REQUEST_HEADER,
    };

    private static final String[] ALLOWED_METHODS = new String[]{
            HttpMethod.OPTIONS.toString(),
            HttpMethod.GET.toString(),
            HttpMethod.PUT.toString(),
            HttpMethod.POST.toString(),
            HttpMethod.DELETE.toString(),
            HttpMethod.HEAD.toString(),
            HttpMethod.PATCH.toString(),
    };

    public static void registerFilterIfEnabled(OpikConfiguration config, Environment environment) {
        if (!config.getCors().isEnabled()) {
            log.info("CORS is disabled");
            return;
        }

        log.info("CORS is enabled");

        final FilterRegistration.Dynamic cors = environment.servlets().addFilter("CORS", CrossOriginFilter.class);

        // CORS parameters
        cors.setInitParameter("allowedOrigins", "*");
        cors.setInitParameter("allowedHeaders", String.join(",", ALLOWED_HEADERS));
        cors.setInitParameter("allowedMethods", String.join(",", ALLOWED_METHODS));
        cors.setInitParameter(CrossOriginFilter.CHAIN_PREFLIGHT_PARAM, Boolean.FALSE.toString());

        // URL mappings
        cors.addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), false, CORS_PATH_FILTER);
    }
}
