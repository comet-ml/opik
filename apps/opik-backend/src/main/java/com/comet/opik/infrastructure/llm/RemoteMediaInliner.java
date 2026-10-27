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
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.client.ClientProperties;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.comet.opik.infrastructure.llm.customllm.ProviderRequestRefusedException.Reason.MEDIA_URL;
import static com.comet.opik.infrastructure.llm.customllm.ProviderRequestRefusedException.Reason.MEDIA_URL_DOWNLOAD;

/**
 * The Google SDKs download some media URLs themselves, with no destination policy. So that no SDK ever opens such a
 * connection, whichever URLs a given SDK version happens to fetch, every media URL is downloaded here through the
 * destination guard and handed over as inline data. Only URLs that never reach the network from here pass through.
 */
@Slf4j
public class RemoteMediaInliner {

    // Cloud Storage objects are read by Google, and data: URIs carry their own bytes; any other scheme is downloaded
    // here or refused. Compared exactly, as the SDKs do: a differently cased scheme takes the restrictive path.
    private static final Set<String> PASS_THROUGH_SCHEMES = Set.of("gs", "data");
    // checked in every guard mode: the relaxed one skips the destination check, never this
    private static final Set<String> DOWNLOAD_SCHEMES = Set.of("http", "https");

    private final @NonNull Client client;
    private final @NonNull DestinationGuard destinationGuard;
    private final int timeoutMillis;
    private final int maxBytes;

    public RemoteMediaInliner(@NonNull Client client, @NonNull LlmProviderClientConfig config) {
        this.client = client;
        this.destinationGuard = DestinationGuard.builder()
                .mode(config.getVideoUrlDestinationGuard())
                .scheme(DestinationGuard.Scheme.PLAINTEXT_OR_TLS)
                .build();
        this.timeoutMillis = Math.toIntExact(config.getMediaDownloadTimeout().toMilliseconds());
        this.maxBytes = Math.toIntExact(config.getMediaDownloadMaxSize().toBytes());
    }

    public List<ChatMessage> inline(@NonNull List<ChatMessage> messages) {
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

    private Content inline(Content content) {
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

    private Media download(String url, String declaredMimeType) {
        try {
            if (!DOWNLOAD_SCHEMES.contains(URI.create(url).getScheme())) {
                throw new DestinationGuardException("destination was refused, only http and https URLs are allowed");
            }
            destinationGuard.validate(url);
        } catch (DestinationGuardException exception) {
            // no exception detail: it describes a user-supplied URL and how our network resolves it
            log.warn("Refused media URL: '{}'", VideoMimeTypeUtils.loggable(url));
            throw new ProviderRequestRefusedException(MEDIA_URL);
        }
        // a redirect is a failed download, so the only destination is the one just checked
        var future = client.target(url)
                .request()
                .property(ClientProperties.FOLLOW_REDIRECTS, false)
                .property(ClientProperties.CONNECT_TIMEOUT, timeoutMillis)
                .property(ClientProperties.READ_TIMEOUT, timeoutMillis)
                .async()
                .get();
        // bounds the whole download, body included, where the timeouts above only bound each wait
        try (var response = future.get(timeoutMillis, TimeUnit.MILLISECONDS)) {
            return read(url, declaredMimeType, response);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ProviderRequestRefusedException(MEDIA_URL_DOWNLOAD);
        } catch (ExecutionException | TimeoutException | IOException | ProcessingException exception) {
            future.cancel(true);
            // the message can echo the full URL, credentials included, so only the type is logged
            log.warn("Media URL download failed, error type '{}': '{}'", exception.getClass().getSimpleName(),
                    VideoMimeTypeUtils.loggable(url));
            throw new ProviderRequestRefusedException(MEDIA_URL_DOWNLOAD);
        }
    }

    private Media read(String url, String declaredMimeType, Response response) throws IOException {
        if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL
                || response.getLength() > maxBytes) {
            log.warn("Media URL download failed, status '{}', length '{}': '{}'", response.getStatus(),
                    response.getLength(), VideoMimeTypeUtils.loggable(url));
            throw new ProviderRequestRefusedException(MEDIA_URL_DOWNLOAD);
        }
        // the length header can be absent or wrong, so the read itself is capped
        byte[] bytes;
        try (var body = response.readEntity(InputStream.class)) {
            bytes = body.readNBytes(maxBytes + 1);
        }
        if (bytes.length > maxBytes) {
            log.warn("Media URL download failed, larger than '{}' bytes: '{}'", maxBytes,
                    VideoMimeTypeUtils.loggable(url));
            throw new ProviderRequestRefusedException(MEDIA_URL_DOWNLOAD);
        }
        return new Media(Base64.getEncoder().encodeToString(bytes),
                mimeType(declaredMimeType, response.getHeaderString(HttpHeaders.CONTENT_TYPE), url));
    }

    // the order the SDKs use, with the response's own type ahead of a guess from the extension
    private static String mimeType(String declaredMimeType, String contentType, String url) {
        return Optional.ofNullable(declaredMimeType)
                .or(() -> Optional.ofNullable(contentType)
                        .map(type -> StringUtils.substringBefore(type, ';').strip())
                        .filter(StringUtils::isNotBlank))
                .orElseGet(() -> URLConnection.guessContentTypeFromName(URI.create(url).getPath()));
    }
}
