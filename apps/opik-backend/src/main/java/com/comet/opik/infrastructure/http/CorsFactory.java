package com.comet.opik.infrastructure.http;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.common.net.HttpHeaders;
import io.dropwizard.core.setup.Environment;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.server.handler.CrossOriginHandler;

import java.util.Set;

@Slf4j
public class CorsFactory {
    public static final String COMET_SDK_API_HEADER = "Comet-Sdk-Api";
    public static final String COMET_USERNAME_HEADER = "comet-username";
    public static final String COMET_REACT_VER_HEADER = "comet-react-ver";

    private static final String[] ALLOWED_HEADERS = new String[]{
            HttpHeaders.AUTHORIZATION,
            HttpHeaders.CONTENT_TYPE,
            HttpHeaders.ACCEPT,
            HttpHeaders.X_REQUESTED_WITH,
            HttpHeaders.ORIGIN,
            COMET_SDK_API_HEADER,
            COMET_USERNAME_HEADER,
            COMET_REACT_VER_HEADER,
            RequestContext.WORKSPACE_HEADER,
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

    public static void registerHandlerIfEnabled(OpikConfiguration config, Environment environment) {
        if (!config.getCors().isEnabled()) {
            log.info("CORS is disabled");
            return;
        }

        log.info("CORS is enabled");

        // Create CORS handler
        CrossOriginHandler corsHandler = new CrossOriginHandler();

        // Configure CORS parameters
        corsHandler.setAllowedOriginPatterns(Set.of("*"));
        corsHandler.setAllowedHeaders(Set.of(ALLOWED_HEADERS));
        corsHandler.setExposedHeaders(Set.of(HttpHeaders.LOCATION));
        corsHandler.setAllowedMethods(Set.of(ALLOWED_METHODS));
        corsHandler.setDeliverPreflightRequests(false); // Don't chain preflight requests (equivalent to CHAIN_PREFLIGHT_PARAM=false)

        // Wrap the existing application handler with CORS handler
        var currentHandler = environment.getApplicationContext().getHandler();
        corsHandler.setHandler(currentHandler);
        environment.getApplicationContext().setHandler(corsHandler);
    }
}
