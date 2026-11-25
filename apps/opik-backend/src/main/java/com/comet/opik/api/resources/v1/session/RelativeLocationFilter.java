package com.comet.opik.api.resources.v1.session;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import jakarta.ws.rs.core.HttpHeaders;

import java.io.IOException;
import java.net.URI;
import java.util.Optional;

/**
 * Writes the {@code Location} header of the {@link RedirectResource} responses as a path, so the browser resolves it
 * against the URL it opened. Jersey makes every relative {@code Location} absolute with the scheme and host the backend
 * sees, which behind a proxy aren't the ones the user opened. Jersey does that right before writing the headers, so
 * this keeps only the path and query at the servlet level, as the header is written.
 */
public class RelativeLocationFilter implements Filter {

    public static final String URL_PATTERN = "/v1/session/redirect/*";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (response instanceof HttpServletResponse httpResponse) {
            chain.doFilter(request, new RelativeLocationResponse(httpResponse));
            return;
        }
        chain.doFilter(request, response);
    }

    static String relativeLocation(String location) {
        URI uri = URI.create(location);
        // One leading slash only: "//x" would be read by the browser as host x
        String path = "/" + Optional.ofNullable(uri.getRawPath()).orElse("").replaceFirst("^/+", "");
        String query = Optional.ofNullable(uri.getRawQuery()).map(value -> "?" + value).orElse("");
        return path + query;
    }

    private static class RelativeLocationResponse extends HttpServletResponseWrapper {

        RelativeLocationResponse(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void setHeader(String name, String value) {
            super.setHeader(name, isLocation(name, value) ? relativeLocation(value) : value);
        }

        @Override
        public void addHeader(String name, String value) {
            super.addHeader(name, isLocation(name, value) ? relativeLocation(value) : value);
        }

        private static boolean isLocation(String name, String value) {
            return value != null && HttpHeaders.LOCATION.equalsIgnoreCase(name);
        }
    }
}
