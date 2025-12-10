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

    public static String toURLEncodedQueryParam(List<?> filters) {
        return CollectionUtils.isEmpty(filters)
                ? null
                : URLEncoder.encode(JsonUtils.writeValueAsString(filters), StandardCharsets.UTF_8);
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
