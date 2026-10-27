package com.comet.opik.infrastructure.net;

import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

/** The form of a user-supplied URL that may be logged. */
@UtilityClass
public class LoggableUrl {

    private static final int MAX_LENGTH = 50;

    /**
     * The URL's scheme, host, port and path, capped at 50 characters. Its userinfo, query and fragment are left out,
     * as they can carry credentials such as signed-URL tokens.
     */
    public static String of(String url) {
        try {
            var uri = new URI(url);
            var port = uri.getPort() == -1 ? "" : ":%d".formatted(uri.getPort());
            var safe = "%s://%s%s%s".formatted(uri.getScheme(), uri.getHost(), port,
                    Objects.toString(uri.getRawPath(), ""));
            return StringUtils.left(safe, MAX_LENGTH);
        } catch (URISyntaxException exception) {
            return "<invalid URL>";
        }
    }
}
