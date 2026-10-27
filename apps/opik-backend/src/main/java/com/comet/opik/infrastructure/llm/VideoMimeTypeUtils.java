package com.comet.opik.infrastructure.llm;

import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.net.DestinationGuard;
import com.comet.opik.infrastructure.net.DestinationGuardException;
import com.google.common.io.Files;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

import static jakarta.ws.rs.core.Response.Status.Family.familyOf;

/**
 * Utility class for detecting MIME types of video URLs.
 * Used when video URLs don't have file extensions that can be used for MIME type inference.
 */
@Slf4j
@UtilityClass
public class VideoMimeTypeUtils {

    private static final int HEAD_REQUEST_TIMEOUT_MS = 5000;

    /**
     * Common video file extensions that LangChain4j can detect automatically.
     */
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "webm", "ogg", "ogv", "avi", "mov", "wmv", "flv", "mkv", "m4v", "3gp", "3g2");

    /**
     * Shared HTTP client for HEAD requests.
     * Thread-safe and reusable across all calls.
     */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(HEAD_REQUEST_TIMEOUT_MS))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /**
     * Strict until {@link #setConfig(LlmProviderClientConfig)} runs, so an unconfigured caller takes the restrictive
     * path.
     */
    private static volatile DestinationGuard destinationGuard = destinationGuard(DestinationGuard.Mode.STRICT);

    /**
     * Called once at startup: the mappers calling this utility are MapStruct singletons outside Guice.
     */
    public static void setConfig(@NonNull LlmProviderClientConfig config) {
        destinationGuard = destinationGuard(config.getVideoUrlDestinationGuard());
    }

    private static DestinationGuard destinationGuard(DestinationGuard.Mode mode) {
        return DestinationGuard.builder()
                .mode(mode)
                .scheme(DestinationGuard.Scheme.PLAINTEXT_OR_TLS)
                .build();
    }

    /**
     * The URL without userinfo, query or fragment, which can carry credentials such as signed-URL tokens, capped at
     * 50 characters.
     */
    public static String loggable(String url) {
        try {
            var uri = new URI(url);
            var path = Objects.toString(uri.getRawPath(), "");
            var safe = "%s://%s%s".formatted(uri.getScheme(), uri.getHost(), path);
            return safe.substring(0, Math.min(50, safe.length()));
        } catch (URISyntaxException e) {
            return "<invalid URL>";
        }
    }

    /**
     * Check if the URL has a recognizable video file extension.
     * If it does, LangChain4j will detect the MIME type automatically.
     *
     * @param url the video URL to check
     * @return true if the URL has a recognized video file extension
     */
    public static boolean hasVideoFileExtension(String url) {
        try {
            String path = URI.create(url).getPath();
            if (StringUtils.isBlank(path)) {
                return false;
            }
            String extension = Files.getFileExtension(path).toLowerCase();
            return VIDEO_EXTENSIONS.contains(extension);
        } catch (IllegalArgumentException e) {
            log.debug("Invalid URL format: '{}'", loggable(url));
            return false;
        }
    }

    /**
     * Detect MIME type by making an HTTP HEAD request to read the Content-Type header.
     * Only called for URLs without file extensions.
     *
     * @param url the video URL to check
     * @return the MIME type, or null if detection fails
     */
    public static String detectMimeTypeFromHttpHead(String url) {
        try {
            var uri = URI.create(url);
            destinationGuard.validate(url);

            log.debug("Making HEAD request to detect MIME type for URL without extension: '{}'",
                    loggable(url));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofMillis(HEAD_REQUEST_TIMEOUT_MS))
                    .build();

            HttpResponse<Void> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            // redirects aren't followed, so a 3xx carries the redirect page's type, not the video's
            if (familyOf(response.statusCode()) != Response.Status.Family.SUCCESSFUL) {
                return null;
            }

            String contentType = response.headers()
                    .firstValue("Content-Type")
                    .orElse(null);

            if (contentType != null && !contentType.isBlank()) {
                // Content-Type may include charset, e.g., "video/mp4; charset=utf-8"
                int semicolonIndex = contentType.indexOf(';');
                if (semicolonIndex > 0) {
                    contentType = contentType.substring(0, semicolonIndex).trim();
                }
                log.debug("Detected MIME type '{}' from HTTP HEAD: '{}'", contentType,
                        loggable(url));
                return contentType;
            }
        } catch (DestinationGuardException e) {
            log.warn("Skipped MIME type detection for a refused URL: '{}'", loggable(url));
        } catch (Exception e) {
            // the message can echo the full URL, credentials included (URI.create does), so only the type is logged
            log.error("Failed to detect MIME type for URL: '{}', error type '{}'", loggable(url),
                    e.getClass().getSimpleName());
        }
        return null;
    }
}
