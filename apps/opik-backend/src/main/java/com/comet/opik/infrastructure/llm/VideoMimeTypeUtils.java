package com.comet.opik.infrastructure.llm;

import com.google.common.io.Files;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

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
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

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
            log.debug("Invalid URL format: '{}'", url.substring(0, Math.min(50, url.length())));
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
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                return null;
            }

            log.debug("Making HEAD request to detect MIME type for URL without extension: '{}'",
                    url.substring(0, Math.min(50, url.length())));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofMillis(HEAD_REQUEST_TIMEOUT_MS))
                    .build();

            HttpResponse<Void> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.discarding());

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
                        url.substring(0, Math.min(50, url.length())));
                return contentType;
            }
        } catch (Exception e) {
            log.error("Failed to detect MIME type for URL: '{}', error: '{}'",
                    url.substring(0, Math.min(50, url.length())), e.getMessage());
        }
        return null;
    }
}
