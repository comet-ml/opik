package com.comet.opik.infrastructure.http;

import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.inject.Singleton;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

@Provider
@PreMatching
@Singleton
public class MatrixParameterRequestFilter implements ContainerRequestFilter {

    public static final String MATRIX_PARAMETERS_NOT_SUPPORTED_MESSAGE = "Matrix parameters are not supported in request paths";

    @Override
    public void filter(ContainerRequestContext context) {
        if (hasMatrixParameters(context.getUriInfo().getRequestUri().getRawPath())) {
            context.abortWith(Response.status(Response.Status.NOT_FOUND)
                    .entity(new ErrorMessage(Response.Status.NOT_FOUND.getStatusCode(),
                            MATRIX_PARAMETERS_NOT_SUPPORTED_MESSAGE))
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                    .build());
        }
    }

    static boolean hasMatrixParameters(String rawPath) {
        return rawPath != null && rawPath.indexOf(';') >= 0;
    }
}
