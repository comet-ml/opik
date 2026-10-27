package com.comet.opik.infrastructure.llm;

import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.customllm.ProviderRequestRefusedException;
import com.comet.opik.infrastructure.net.DestinationGuard;
import com.comet.opik.infrastructure.net.DestinationGuardDns;
import com.comet.opik.infrastructure.net.DestinationGuardDnsResolver;
import com.comet.opik.infrastructure.net.DestinationGuardException;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.client.ClientProperties;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
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
@Singleton
public class RemoteMediaInliner {

    /**
     * Cloud Storage objects are read by Google, and data: URIs carry their own bytes; any other scheme is downloaded
     * here or refused. Compared exactly, as the SDKs do: a differently cased scheme takes the restrictive path.
     */
    private static final Set<String> PASS_THROUGH_SCHEMES = Set.of("gs", "data");
    /** Checked in every guard mode: the relaxed one skips the destination check, never this. */
    private static final Set<String> DOWNLOAD_SCHEMES = Set.of("http", "https");
    /** Size of each read from a media body, in bytes. */
    private static final int READ_SIZE = 8192;
    /**
     * Largest buffer allocated up front from a declared length: the header is the media server's claim, so a bigger
     * body grows the buffer as it actually arrives.
     */
    private static final int MAX_INITIAL_BUFFER_SIZE = 1 << 20;

    private final Client client;
    private final DestinationGuard destinationGuard;
    private final int timeoutMillis;

    /** Most bytes downloaded for all the media of one {@link #inline(List)} call. */
    private final long maxBytes;

    @Inject
    public RemoteMediaInliner(@NonNull Client client,
            @NonNull @Config("llmProviderClient") LlmProviderClientConfig config) {
        this.client = client;
        this.destinationGuard = DestinationGuard.builder()
                .mode(config.getVideoUrlDestinationGuard())
                .scheme(config.getMediaUrlDestinationScheme())
                .build();
        this.timeoutMillis = Math.toIntExact(config.getMediaDownloadTimeout().toMilliseconds());
        this.maxBytes = config.getMediaDownloadMaxSize().toBytes();
    }

    /** Downloads every media URL in the messages, refusing them once their bytes together exceed the size limit. */
    public List<ChatMessage> inline(@NonNull List<ChatMessage> messages) {
        var budget = new Budget(maxBytes);
        return messages.stream()
                .map(message -> message instanceof UserMessage userMessage
                        && userMessage.contents().stream().anyMatch(RemoteMediaInliner::isDownloaded)
                                ? userMessage.toBuilder()
                                        .contents(userMessage.contents().stream()
                                                .map(content -> isDownloaded(content)
                                                        ? inline(content, budget)
                                                        : content)
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

    private Content inline(Content content, Budget budget) {
        var url = url(content).orElseThrow().toString();
        return switch (content.type()) {
            case TEXT -> content;
            case IMAGE -> {
                var image = (ImageContent) content;
                var media = download(url, image.image().mimeType(), budget);
                // only the source changes; Image has no toBuilder, so its other field is copied by hand
                yield ImageContent.from(Image.builder()
                        .base64Data(media.base64Data())
                        .mimeType(media.mimeType())
                        .revisedPrompt(image.image().revisedPrompt())
                        .build(), image.detailLevel());
            }
            case AUDIO -> {
                var media = download(url, ((AudioContent) content).audio().mimeType(), budget);
                yield AudioContent.from(media.base64Data(), media.mimeType());
            }
            case VIDEO -> {
                var media = download(url, ((VideoContent) content).video().mimeType(), budget);
                yield VideoContent.from(media.base64Data(), media.mimeType());
            }
            case PDF -> {
                var media = download(url, ((PdfFileContent) content).pdfFile().mimeType(), budget);
                yield PdfFileContent.from(media.base64Data(), media.mimeType());
            }
        };
    }

    @Builder(toBuilder = true)
    private record Media(String base64Data, String mimeType) {
    }

    /** The bytes the downloads of one request may still take. */
    @AllArgsConstructor
    private static class Budget {
        private long remaining;
    }

    private Media download(String url, String declaredMimeType, Budget budget) {
        try {
            if (!DOWNLOAD_SCHEMES.contains(URI.create(url).getScheme())) {
                throw new DestinationGuardException("destination was refused, only http and https URLs are allowed");
            }
            destinationGuard.validate(url);
        } catch (DestinationGuardException exception) {
            // no exception detail: it describes a user-supplied URL and how our network resolves it
            log.warn("Refused media URL, url '{}'", VideoMimeTypeUtils.loggable(url));
            throw new ProviderRequestRefusedException(MEDIA_URL);
        }
        // a redirect is a failed download, and the guard checks each address connected to, so the only destination
        // is the one just checked
        var future = client.target(url)
                .request()
                .property(ClientProperties.FOLLOW_REDIRECTS, false)
                .property(DestinationGuardDnsResolver.PROPERTY, destinationGuard)
                .property(ClientProperties.CONNECT_TIMEOUT, timeoutMillis)
                .property(ClientProperties.READ_TIMEOUT, timeoutMillis)
                .async()
                .get();
        // bounds the whole download, body included, where the timeouts above only bound each wait
        try (var response = future.get(timeoutMillis, TimeUnit.MILLISECONDS)) {
            return read(url, declaredMimeType, response, budget);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ProviderRequestRefusedException(MEDIA_URL_DOWNLOAD);
        } catch (ExecutionException | TimeoutException | IOException | ProcessingException exception) {
            future.cancel(true);
            if (DestinationGuardDns.isRefused(exception)) {
                log.warn("Refused media URL: '{}'", VideoMimeTypeUtils.loggable(url));
                throw new ProviderRequestRefusedException(MEDIA_URL);
            }
            // the message can echo the full URL, credentials included, so only the type is logged
            log.warn("Media URL download failed, error type '{}', url '{}'", exception.getClass().getSimpleName(),
                    VideoMimeTypeUtils.loggable(url));
            throw new ProviderRequestRefusedException(MEDIA_URL_DOWNLOAD);
        }
    }

    /**
     * The body base64 encoded as it is read, so the raw bytes are never held beside their encoding. The limit is
     * checked on the raw bytes, reading at most one byte past what the request may still take.
     */
    private Media read(String url, String declaredMimeType, Response response, Budget budget) throws IOException {
        if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL
                || response.getLength() > budget.remaining) {
            log.warn("Media URL download failed, status '{}', length '{}', url '{}'", response.getStatus(),
                    response.getLength(), VideoMimeTypeUtils.loggable(url));
            throw new ProviderRequestRefusedException(MEDIA_URL_DOWNLOAD);
        }
        // the length header can be absent or wrong, so the read itself is capped
        var encoded = new ByteArrayOutputStream(
                response.getLength() > 0 ? Math.min(base64Length(response.getLength()), MAX_INITIAL_BUFFER_SIZE) : 32);
        long total = 0;
        try (var body = response.readEntity(InputStream.class); var encoder = Base64.getEncoder().wrap(encoded)) {
            var chunk = new byte[READ_SIZE];
            int read;
            while ((read = body.read(chunk, 0, (int) Math.min(READ_SIZE, budget.remaining - total + 1))) != -1) {
                total += read;
                if (total > budget.remaining) {
                    log.warn("Media URL download failed, media larger than the size limit, max bytes '{}', url '{}'",
                            maxBytes, VideoMimeTypeUtils.loggable(url));
                    throw new ProviderRequestRefusedException(MEDIA_URL_DOWNLOAD);
                }
                encoder.write(chunk, 0, read);
            }
        }
        budget.remaining -= total;
        return Media.builder()
                // base64 is ASCII, so each byte is one character
                .base64Data(encoded.toString(StandardCharsets.ISO_8859_1))
                .mimeType(mimeType(declaredMimeType, response.getHeaderString(HttpHeaders.CONTENT_TYPE), url))
                .build();
    }

    /** The length of the base64 encoding of that many bytes, padding included. */
    private static int base64Length(int length) {
        return Math.toIntExact(4 * ((length + 2L) / 3));
    }

    /** The order the SDKs use, with the response's own type ahead of a guess from the extension. */
    private static String mimeType(String declaredMimeType, String contentType, String url) {
        return Optional.ofNullable(declaredMimeType)
                .or(() -> Optional.ofNullable(contentType)
                        .map(type -> StringUtils.substringBefore(type, ';').strip())
                        .filter(StringUtils::isNotBlank))
                .orElseGet(() -> URLConnection.guessContentTypeFromName(URI.create(url).getPath()));
    }
}
