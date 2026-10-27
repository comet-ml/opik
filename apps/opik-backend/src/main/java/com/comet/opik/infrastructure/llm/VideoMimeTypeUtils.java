package com.comet.opik.infrastructure.llm;

import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.net.DestinationGuard;
import com.comet.opik.infrastructure.net.DestinationGuardDns;
import com.comet.opik.infrastructure.net.DestinationGuardDnsResolver;
import com.comet.opik.infrastructure.net.DestinationGuardException;
import com.google.common.io.Files;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.client.ClientProperties;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.Set;

/**
 * Detects MIME types of video URLs.
 * Used when video URLs don't have file extensions that can be used for MIME type inference.
 */
@Slf4j
@Singleton
public class VideoMimeTypeUtils {

    private static final int HEAD_REQUEST_TIMEOUT_MS = 5000;

    /**
     * Common video file extensions that LangChain4j can detect automatically.
     */
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "webm", "ogg", "ogv", "avi", "mov", "wmv", "flv", "mkv", "m4v", "3gp", "3g2");

    private final @NonNull Client client;
    private final @NonNull DestinationGuard destinationGuard;

    @Inject
    public VideoMimeTypeUtils(@NonNull Client client,
            @NonNull @Config("llmProviderClient") LlmProviderClientConfig config) {
        this.client = client;
        this.destinationGuard = DestinationGuard.builder()
                .mode(config.getVideoUrlDestinationGuard())
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
    public String detectMimeTypeFromHttpHead(String url) {
        try {
            var uri = URI.create(url);
            destinationGuard.validate(url);

            log.debug("Making HEAD request to detect MIME type for URL without extension: '{}'",
                    loggable(url));

            String contentType;
            try (var response = client.target(uri)
                    .request()
                    .property(ClientProperties.FOLLOW_REDIRECTS, false)
                    .property(DestinationGuardDnsResolver.PROPERTY, destinationGuard)
                    .property(ClientProperties.CONNECT_TIMEOUT, HEAD_REQUEST_TIMEOUT_MS)
                    .property(ClientProperties.READ_TIMEOUT, HEAD_REQUEST_TIMEOUT_MS)
                    .head()) {
                // redirects aren't followed, so a 3xx carries the redirect page's type, not the video's
                if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
                    return null;
                }
                contentType = response.getHeaderString(HttpHeaders.CONTENT_TYPE);
            }

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
            if (DestinationGuardDns.isRefused(e)) {
                log.warn("Skipped MIME type detection for a refused URL: '{}'", loggable(url));
                return null;
            }
            // the message can echo the full URL, credentials included (URI.create does), so only the type is logged
            log.error("Failed to detect MIME type for URL: '{}', error type '{}'", loggable(url),
                    e.getClass().getSimpleName());
        }
        return null;
    }
}
