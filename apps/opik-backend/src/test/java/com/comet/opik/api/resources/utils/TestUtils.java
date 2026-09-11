package com.comet.opik.api.resources.utils;

import com.comet.opik.utils.JsonUtils;
import lombok.experimental.UtilityClass;
import org.apache.commons.collections4.CollectionUtils;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;

@UtilityClass
public class TestUtils {

    public static UUID getIdFromLocation(URI location) {
        return UUID.fromString(location.getPath().substring(location.getPath().lastIndexOf('/') + 1));
    }

    /**
     * Converts a list to JSON and encodes it for use as a URL query parameter.
     *
     * <p>This method handles URL encoding with special attention to space character encoding.
     * URLEncoder.encode() uses '+' for spaces (application/x-www-form-urlencoded format per RFC 1738),
     * but Jersey's @QueryParam uses URI decoding (RFC 3986) which expects '%20' for spaces.
     * We replace '+' with '%20' to ensure spaces are properly decoded by Jersey.
     *
     * @param filters the list to encode (filters, sorting fields, etc.)
     * @return URL-encoded JSON string, or null if list is empty
     */
    public static String toURLEncodedQueryParam(List<?> filters) {
        if (CollectionUtils.isEmpty(filters)) {
            return null;
        }
        return URLEncoder.encode(JsonUtils.writeValueAsString(filters), StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    public static String getBaseUrl(ClientSupport client) {
        return "http://localhost:%d".formatted(client.getPort());
    }

    /**
     * Waits for the specified duration.
     * Prefer this over {@code Thread.sleep()} or {@code Mono.delay().block()} for waiting in tests.
     *
     * @param millis duration to wait in milliseconds
     */
    public static void waitForMillis(long millis) {
        await().pollDelay(millis, TimeUnit.MILLISECONDS)
                .until(() -> true);
    }
}
