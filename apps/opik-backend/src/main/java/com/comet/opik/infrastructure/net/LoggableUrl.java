package com.comet.opik.infrastructure.net;

import jakarta.annotation.Nullable;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;

/** The form of a user-supplied URL that may be logged. */
@UtilityClass
public class LoggableUrl {

    private static final int MAX_LENGTH = 50;

    /**
     * The URL's scheme, host and port, capped at 50 characters. Its userinfo, path, query and fragment are left out, as
     * they can carry credentials: a webhook's token is in its path, a signed URL's token in its path or query. A missing
     * URL is a placeholder, so logging a failure never fails itself.
     */
    public static String of(@Nullable String url) {
        if (url == null) {
            return "<no URL>";
        }
        try {
            var uri = new URI(url);
            var port = uri.getPort() == -1 ? "" : ":%d".formatted(uri.getPort());
            var safe = "%s://%s%s".formatted(uri.getScheme(), uri.getHost(), port);
            return StringUtils.left(safe, MAX_LENGTH);
        } catch (URISyntaxException exception) {
            return "<invalid URL>";
        }
    }
}
