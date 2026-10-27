package com.comet.opik.infrastructure.llm;

import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.customllm.ProviderRequestRefusedException;
import com.comet.opik.infrastructure.net.DestinationGuard;
import com.comet.opik.infrastructure.net.DestinationGuardDns;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URLConnection;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.comet.opik.infrastructure.llm.customllm.ProviderRequestRefusedException.Reason.MEDIA_URL;
import static com.comet.opik.infrastructure.llm.customllm.ProviderRequestRefusedException.Reason.MEDIA_URL_DOWNLOAD;

/**
 * The Google SDKs download some media URLs themselves, with no destination policy. So that no SDK ever opens such a
 * connection, whichever URLs a given SDK version happens to fetch, every media URL is downloaded here through the
 * destination guard and handed over as inline data. Only URLs that never reach the network from here pass through.
 */
@Slf4j
@UtilityClass
public class RemoteMediaInliner {

    // the inline data limit of a Gemini request
    static final int MAX_BYTES = 20 * 1024 * 1024;

    // Cloud Storage objects are read by Google, and data: URIs carry their own bytes; any other scheme is downloaded
    // here or refused. Compared exactly, as the SDKs do: a differently cased scheme takes the restrictive path.
    private static final Set<String> PASS_THROUGH_SCHEMES = Set.of("gs", "data");

    /**
     * Strict until {@link #setConfig(LlmProviderClientConfig)} runs, so an unconfigured caller takes the restrictive
     * path.
     */
    private static volatile OkHttpClient httpClient = httpClient(DestinationGuard.Mode.STRICT);

    /**
     * Called once at startup: some callers are MapStruct singletons or models built outside Guice.
     */
    public static void setConfig(@NonNull LlmProviderClientConfig config) {
        httpClient = httpClient(config.getVideoUrlDestinationGuard());
    }

    // a redirect is a failed download, so the only destination is the URL that was checked
    private static OkHttpClient httpClient(DestinationGuard.Mode mode) {
        return DestinationGuardDns.clientBuilder(DestinationGuard.builder()
                .mode(mode)
                .scheme(DestinationGuard.Scheme.PLAINTEXT_OR_TLS)
                .build())
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(Duration.ofSeconds(5))
                .callTimeout(Duration.ofSeconds(30))
                .build();
    }

    public static List<ChatMessage> inline(@NonNull List<ChatMessage> messages) {
        return messages.stream()
                .map(message -> message instanceof UserMessage userMessage
                        && userMessage.contents().stream().anyMatch(RemoteMediaInliner::isDownloaded)
                                ? userMessage.toBuilder()
                                        .contents(userMessage.contents().stream()
                                                .map(content -> isDownloaded(content) ? inline(content) : content)
                                                .toList())
                                        .build()
                                : message)
                .toList();
    }

    private static boolean isDownloaded(Content content) {
        return url(content).filter(url -> !PASS_THROUGH_SCHEMES.contains(url.getScheme())).isPresent();
    }

    private static Optional<URI> url(Content content) {
        return switch (content.type()) {
            case TEXT -> Optional.empty();
            case IMAGE -> Optional.ofNullable(((ImageContent) content).image().url());
            case AUDIO -> Optional.ofNullable(((AudioContent) content).audio().url());
            case VIDEO -> Optional.ofNullable(((VideoContent) content).video().url());
            case PDF -> Optional.ofNullable(((PdfFileContent) content).pdfFile().url());
        };
    }

    private static Content inline(Content content) {
        var url = url(content).orElseThrow().toString();
        return switch (content.type()) {
            case TEXT -> content;
            case IMAGE -> {
                var image = (ImageContent) content;
                var media = download(url, image.image().mimeType());
                yield ImageContent.from(media.base64Data(), media.mimeType(), image.detailLevel());
            }
            case AUDIO -> {
                var media = download(url, ((AudioContent) content).audio().mimeType());
                yield AudioContent.from(media.base64Data(), media.mimeType());
            }
            case VIDEO -> {
                var media = download(url, ((VideoContent) content).video().mimeType());
                yield VideoContent.from(media.base64Data(), media.mimeType());
            }
            case PDF -> {
                var media = download(url, ((PdfFileContent) content).pdfFile().mimeType());
                yield PdfFileContent.from(media.base64Data(), media.mimeType());
            }
        };
    }

    private record Media(String base64Data, String mimeType) {
    }

    private static Media download(String url, String declaredMimeType) {
        // null for anything but an http or https URL
        var httpUrl = HttpUrl.parse(url);
        if (httpUrl == null) {
            log.warn("Refused media URL with an unsupported scheme: '{}'", VideoMimeTypeUtils.loggable(url));
            throw new ProviderRequestRefusedException(MEDIA_URL);
        }
        try (var response = httpClient.newCall(new Request.Builder().url(httpUrl).build()).execute()) {
            var body = response.body();
            if (!response.isSuccessful() || body == null || body.contentLength() > MAX_BYTES) {
                log.warn("Media URL download failed, status '{}', length '{}': '{}'", response.code(),
                        body == null ? null : body.contentLength(), VideoMimeTypeUtils.loggable(url));
                throw new ProviderRequestRefusedException(MEDIA_URL_DOWNLOAD);
            }
            // the length header can be absent or wrong, so the read itself is capped
            var bytes = body.byteStream().readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                log.warn("Media URL download failed, larger than '{}' bytes: '{}'", MAX_BYTES,
                        VideoMimeTypeUtils.loggable(url));
                throw new ProviderRequestRefusedException(MEDIA_URL_DOWNLOAD);
            }
            return new Media(Base64.getEncoder().encodeToString(bytes),
                    mimeType(declaredMimeType, response.header("Content-Type"), response.request().url()));
        } catch (IOException exception) {
            if (DestinationGuardDns.isRefused(exception)) {
                // no exception detail: it describes a user-supplied URL and how our network resolves it
                log.warn("Refused media URL: '{}'", VideoMimeTypeUtils.loggable(url));
                throw new ProviderRequestRefusedException(MEDIA_URL);
            }
            // the message can echo the full URL, credentials included, so only the type is logged
            log.warn("Media URL download failed, error type '{}': '{}'", exception.getClass().getSimpleName(),
                    VideoMimeTypeUtils.loggable(url));
            throw new ProviderRequestRefusedException(MEDIA_URL_DOWNLOAD);
        }
    }

    // the order the SDKs use, with the response's own type ahead of a guess from the extension
    private static String mimeType(String declaredMimeType, String contentType, HttpUrl finalUrl) {
        return Optional.ofNullable(declaredMimeType)
                .or(() -> Optional.ofNullable(contentType)
                        .map(type -> StringUtils.substringBefore(type, ';').strip())
                        .filter(StringUtils::isNotBlank))
                .orElseGet(() -> URLConnection.guessContentTypeFromName(finalUrl.encodedPath()));
    }
}
