package com.comet.opik.domain;

import com.comet.opik.api.MediaUrlCheck;
import com.comet.opik.api.MediaUrlCheck.Result;
import com.comet.opik.api.MediaUrlCheck.Status;
import com.comet.opik.infrastructure.MediaUrlCheckConfig;
import com.comet.opik.infrastructure.net.DestinationGuard;
import com.comet.opik.infrastructure.net.DestinationGuardException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.HttpHeaders;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Singleton
public class MediaUrlCheckService {

    private static final int MAX_REDIRECTS = 5;
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final List<String> MEDIA_TYPE_PREFIXES = List.of("image/", "video/", "audio/");
    // object stores label uploads that had no declared type this way, and providers sniff the bytes anyway
    private static final Set<String> UNTYPED_BINARY_TYPES = Set.of("application/octet-stream", "binary/octet-stream");
    // some media hosts, Wikimedia among them, refuse the JDK's generic user agent
    private static final String USER_AGENT = "Opik-MediaUrlCheck/1.0 (+https://github.com/comet-ml/opik)";

    private final Duration perUrlTimeout;
    private final DestinationGuard destinationGuard;
    private final HttpClient httpClient;

    @Inject
    public MediaUrlCheckService(@NonNull @Config("mediaUrlCheck") MediaUrlCheckConfig config) {
        this(config, DestinationGuard.builder()
                .mode(config.getDestinationGuard())
                .scheme(DestinationGuard.Scheme.PLAINTEXT_OR_TLS)
                .build());
    }

    MediaUrlCheckService(@NonNull MediaUrlCheckConfig config, @NonNull DestinationGuard destinationGuard) {
        this.perUrlTimeout = Duration.ofMillis(config.getPerUrlTimeout().toMilliseconds());
        this.destinationGuard = destinationGuard;
        // redirects are followed by hand, so that every hop goes through the guard before we connect to it
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(config.getConnectTimeout().toMilliseconds()))
                .build();
    }

    public List<Result> check(@NonNull List<String> urls) {
        return Flux.fromIterable(urls)
                .flatMapSequential(url -> Mono.fromCallable(() -> checkUrl(url))
                        .subscribeOn(Schedulers.boundedElastic())
                        .timeout(perUrlTimeout, Mono.fromSupplier(() -> result(url, Status.TIMEOUT)))
                        .onErrorResume(throwable -> {
                            log.warn("Media URL check failed unexpectedly", throwable);
                            return Mono.just(result(url, Status.UNREACHABLE));
                        }),
                        MediaUrlCheck.MAX_URLS)
                .collectList()
                .block();
    }

    private Result checkUrl(String url) {
        long deadline = System.nanoTime() + perUrlTimeout.toNanos();
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException exception) {
            return result(url, Status.INVALID_URL);
        }
        if ("data".equalsIgnoreCase(uri.getScheme())) {
            return result(url, Status.SKIPPED);
        }

        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            if (!isHttp(uri)) {
                return result(url, Status.INVALID_URL);
            }
            try {
                destinationGuard.validate(uri.toString());
            } catch (DestinationGuardException exception) {
                return result(url, exception.getCause() instanceof UnknownHostException
                        ? Status.UNREACHABLE
                        : Status.BLOCKED);
            }

            var remaining = Duration.ofNanos(deadline - System.nanoTime());
            if (remaining.isNegative() || remaining.isZero()) {
                return result(url, Status.TIMEOUT);
            }

            HttpResponse<InputStream> response;
            try {
                response = httpClient.send(firstByteRequest(uri, remaining), HttpResponse.BodyHandlers.ofInputStream());
            } catch (HttpTimeoutException exception) {
                return result(url, Status.TIMEOUT);
            } catch (IOException | IllegalArgumentException exception) {
                log.debug("Media URL unreachable, host '{}'", uri.getHost(), exception);
                return result(url, Status.UNREACHABLE);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return result(url, Status.TIMEOUT);
            }
            // closed unread: a host that ignores the Range header would otherwise stream us the whole file
            closeQuietly(response.body());

            int httpStatus = response.statusCode();
            if (REDIRECT_STATUSES.contains(httpStatus)) {
                var next = redirectTarget(uri, response);
                if (next.isEmpty()) {
                    return result(url, Status.HTTP_ERROR, httpStatus, null);
                }
                uri = next.get();
                continue;
            }
            if (httpStatus < 200 || httpStatus >= 300) {
                return result(url, Status.HTTP_ERROR, httpStatus, null);
            }
            var contentType = contentTypeOf(response);
            return result(url, isMedia(contentType) ? Status.OK : Status.NOT_MEDIA, httpStatus, contentType);
        }
        return result(url, Status.TOO_MANY_REDIRECTS);
    }

    private static HttpRequest firstByteRequest(URI uri, Duration timeout) {
        // GET, not HEAD: presigned object-store URLs are signed for GET and answer HEAD with 403
        return HttpRequest.newBuilder(uri)
                .GET()
                .header("Range", "bytes=0-0")
                .header(HttpHeaders.USER_AGENT, USER_AGENT)
                .timeout(timeout)
                .build();
    }

    private static Optional<URI> redirectTarget(URI from, HttpResponse<?> response) {
        try {
            return response.headers().firstValue(HttpHeaders.LOCATION).map(from::resolve);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static boolean isHttp(URI uri) {
        return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && StringUtils.isNotBlank(uri.getHost());
    }

    private static String contentTypeOf(HttpResponse<?> response) {
        return response.headers().firstValue(HttpHeaders.CONTENT_TYPE)
                .map(value -> StringUtils.substringBefore(value, ";").trim().toLowerCase(Locale.ROOT))
                .filter(StringUtils::isNotBlank)
                .orElse(null);
    }

    private static boolean isMedia(String contentType) {
        return contentType == null
                || UNTYPED_BINARY_TYPES.contains(contentType)
                || MEDIA_TYPE_PREFIXES.stream().anyMatch(contentType::startsWith);
    }

    private static void closeQuietly(InputStream body) {
        try {
            body.close();
        } catch (IOException exception) {
            log.debug("Failed to close media URL check response body", exception);
        }
    }

    private static Result result(String url, Status status) {
        return result(url, status, null, null);
    }

    private static Result result(String url, Status status, Integer httpStatus, String contentType) {
        return Result.builder()
                .url(url)
                .status(status)
                .httpStatus(httpStatus)
                .contentType(contentType)
                .build();
    }
}
