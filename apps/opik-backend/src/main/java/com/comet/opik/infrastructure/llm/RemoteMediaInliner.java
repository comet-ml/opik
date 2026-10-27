package com.comet.opik.infrastructure.llm;

import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.customllm.ProviderRequestRefusedException;
import com.comet.opik.infrastructure.net.DestinationGuard;
import com.comet.opik.infrastructure.net.DestinationGuardException;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.net.URLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import static com.comet.opik.infrastructure.llm.customllm.ProviderRequestRefusedException.Reason.MEDIA_URL;
import static com.comet.opik.infrastructure.llm.customllm.ProviderRequestRefusedException.Reason.MEDIA_URL_DOWNLOAD;
import static jakarta.ws.rs.core.Response.Status.Family.familyOf;

/**
 * The Google SDKs download media URLs themselves, with a client that follows redirects and has no destination policy.
 * This downloads those URLs first, through the destination guard, and hands the SDK inline data instead, so it never
 * opens a connection of its own. Everything else is left for the provider to fetch.
 */
@Slf4j
@UtilityClass
public class RemoteMediaInliner {

    // the inline data limit of a Gemini request
    static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    // redirects are followed here, so each hop is checked before it is requested
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /**
     * Strict until {@link #setConfig(LlmProviderClientConfig)} runs, so an unconfigured caller takes the restrictive
     * path.
     */
    private static volatile DestinationGuard destinationGuard = destinationGuard(DestinationGuard.Mode.STRICT);

    /**
     * Called once at startup: some callers are MapStruct singletons or models built outside Guice.
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

    /** Gemini (Google AI) downloads http(s) image URLs and passes every other URL to the API as file data. */
    public static List<ChatMessage> inlineForGemini(@NonNull List<ChatMessage> messages) {
        return inline(messages, content -> content instanceof ImageContent image
                && isDownloadedByGemini(image.image().url()));
    }

    /** Vertex AI downloads every image, audio, video and PDF URL except a Cloud Storage {@code gs://} one. */
    public static List<ChatMessage> inlineForVertexAi(@NonNull List<ChatMessage> messages) {
        return inline(messages, content -> url(content)
                .filter(url -> !"gs".equals(url.getScheme()))
                .isPresent());
    }

    private static boolean isDownloadedByGemini(URI url) {
        return url != null && url.getScheme() != null && url.getScheme().startsWith("http");
    }

    private static List<ChatMessage> inline(List<ChatMessage> messages, Predicate<Content> downloadedBySdk) {
        return messages.stream()
                .map(message -> message instanceof UserMessage userMessage
                        && userMessage.contents().stream().anyMatch(downloadedBySdk)
                                ? userMessage.toBuilder()
                                        .contents(userMessage.contents().stream()
                                                .map(content -> downloadedBySdk.test(content)
                                                        ? inline(content)
                                                        : content)
                                                .toList())
                                        .build()
                                : message)
                .toList();
    }

    private static Optional<URI> url(Content content) {
        return Optional.ofNullable(switch (content) {
            case ImageContent image -> image.image().url();
            case AudioContent audio -> audio.audio().url();
            case VideoContent video -> video.video().url();
            case PdfFileContent pdf -> pdf.pdfFile().url();
            default -> null;
        });
    }

    private static Content inline(Content content) {
        var url = url(content).orElseThrow();
        return switch (content) {
            case ImageContent image -> {
                var media = download(url, image.image().mimeType());
                yield ImageContent.from(media.base64Data(), media.mimeType(), image.detailLevel());
            }
            case AudioContent audio -> {
                var media = download(url, audio.audio().mimeType());
                yield AudioContent.from(media.base64Data(), media.mimeType());
            }
            case VideoContent video -> {
                var media = download(url, video.video().mimeType());
                yield VideoContent.from(media.base64Data(), media.mimeType());
            }
            case PdfFileContent pdf -> {
                var media = download(url, pdf.pdfFile().mimeType());
                yield PdfFileContent.from(media.base64Data(), media.mimeType());
            }
            default -> content;
        };
    }

    private record Media(String base64Data, String mimeType) {
    }

    private static Media download(URI url, String declaredMimeType) {
        var current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            validate(current);
            var response = get(current);
            var status = familyOf(response.statusCode());
            if (status == Response.Status.Family.REDIRECTION) {
                current = redirectTarget(current, response);
                continue;
            }
            if (status != Response.Status.Family.SUCCESSFUL) {
                log.warn("Media URL download failed, status '{}': '{}'", response.statusCode(),
                        VideoMimeTypeUtils.loggable(url.toString()));
                throw new ProviderRequestRefusedException(MEDIA_URL_DOWNLOAD);
            }
            return new Media(Base64.getEncoder().encodeToString(response.body()),
                    mimeType(declaredMimeType, response, current));
        }
        log.warn("Media URL download failed, too many redirects: '{}'", VideoMimeTypeUtils.loggable(url.toString()));
        throw new ProviderRequestRefusedException(MEDIA_URL_DOWNLOAD);
    }

    private static void validate(URI url) {
        try {
            destinationGuard.validate(url.toString());
        } catch (DestinationGuardException exception) {
            // no exception detail: it describes a user-supplied URL and how our network resolves it
            log.warn("Refused media URL: '{}'", VideoMimeTypeUtils.loggable(url.toString()));
            throw new ProviderRequestRefusedException(MEDIA_URL);
        }
    }

    private static HttpResponse<byte[]> get(URI url) {
        var request = HttpRequest.newBuilder(url).timeout(TIMEOUT).GET().build();
        // the timeout above stops at the headers, this one also bounds reading the body
        var response = HTTP_CLIENT.sendAsync(request,
                HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MAX_BYTES));
        try {
            return response.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            response.cancel(true);
            Thread.currentThread().interrupt();
            throw new ProviderRequestRefusedException(MEDIA_URL_DOWNLOAD);
        } catch (ExecutionException | TimeoutException exception) {
            response.cancel(true);
            // the message can echo the full URL, credentials included, so only the type is logged
            var cause = exception instanceof ExecutionException ? exception.getCause() : exception;
            log.warn("Media URL download failed, error type '{}': '{}'", cause.getClass().getSimpleName(),
                    VideoMimeTypeUtils.loggable(url.toString()));
            throw new ProviderRequestRefusedException(MEDIA_URL_DOWNLOAD);
        }
    }

    private static URI redirectTarget(URI current, HttpResponse<?> response) {
        try {
            return current.resolve(response.headers().firstValue("Location").orElseThrow());
        } catch (RuntimeException exception) {
            log.warn("Media URL download failed, invalid redirect: '{}'",
                    VideoMimeTypeUtils.loggable(current.toString()));
            throw new ProviderRequestRefusedException(MEDIA_URL_DOWNLOAD);
        }
    }

    // the order the SDKs use, with the response's own type ahead of a guess from the extension
    private static String mimeType(String declaredMimeType, HttpResponse<?> response, URI url) {
        return Optional.ofNullable(declaredMimeType)
                .or(() -> response.headers().firstValue("Content-Type")
                        .map(contentType -> StringUtils.substringBefore(contentType, ';').strip())
                        .filter(StringUtils::isNotBlank))
                .orElseGet(() -> URLConnection.guessContentTypeFromName(url.getPath()));
    }
}
