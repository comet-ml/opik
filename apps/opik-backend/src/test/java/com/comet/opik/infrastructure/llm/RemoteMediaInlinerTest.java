package com.comet.opik.infrastructure.llm;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.resources.utils.TestHttpClientUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.infrastructure.llm.customllm.ProviderRequestRefusedException;
import com.comet.opik.infrastructure.net.DestinationGuard;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
import io.dropwizard.util.DataSize;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemoteMediaInlinerTest {

    /** The refusals returned to callers, as they read on the wire. */
    private static final String MEDIA_URL_REFUSED = """
            a media URL was refused: it must be an http or https URL that resolves to a public address \
            (self-hosted deployments serving media from internal networks can set \
            LLM_PROVIDER_VIDEO_URL_DESTINATION_GUARD=relaxed)""";
    private static final String MEDIA_URL_DOWNLOAD_REFUSED = """
            a media URL could not be downloaded: it must return the file itself, not a redirect, with a \
            success status, within the download time and size limits (LLM_PROVIDER_MEDIA_DOWNLOAD_TIMEOUT, \
            LLM_PROVIDER_MEDIA_DOWNLOAD_MAX_SIZE)""";

    private final WireMockUtils.WireMockRuntime wireMock = WireMockUtils.startWireMock();

    /** The mock server is on loopback, which strict mode refuses; the refusal itself is covered through the API. */
    private final RemoteMediaInliner remoteMediaInliner = LlmTestClients.remoteMediaInliner(
            DestinationGuard.Mode.RELAXED);
    private final int maxBytes = Math.toIntExact(
            TestConfigUtils.loadConfigTest().getLlmProviderClient().getMediaDownloadMaxSize().toBytes());

    @AfterAll
    void tearDown() {
        wireMock.server().stop();
    }

    private Stream<Arguments> mediaIsDownloadedAndInlined() {
        return Stream.of(
                arguments("image/png", (Function<String, Content>) ImageContent::from,
                        (BiFunction<String, String, Content>) ImageContent::from),
                arguments("audio/wav", (Function<String, Content>) AudioContent::from,
                        (BiFunction<String, String, Content>) AudioContent::from),
                arguments("video/mp4", (Function<String, Content>) VideoContent::from,
                        (BiFunction<String, String, Content>) VideoContent::from),
                arguments("application/pdf", (Function<String, Content>) PdfFileContent::from,
                        (BiFunction<String, String, Content>) PdfFileContent::from));
    }

    @ParameterizedTest
    @MethodSource
    @DisplayName("media URLs are downloaded and inlined with the response's type")
    void mediaIsDownloadedAndInlined(String mimeType, Function<String, Content> fromUrl,
            BiFunction<String, String, Content> fromBase64) {
        var bytes = RandomUtils.secure().randomBytes(64);
        var mediaPath = "/media-" + UUID.randomUUID();
        wireMock.server().stubFor(get(urlPathEqualTo(mediaPath))
                .willReturn(aResponse().withHeader("Content-Type", mimeType + "; charset=binary").withBody(bytes)));
        var system = SystemMessage.from("be brief");
        var text = TextContent.from("describe");
        List<ChatMessage> messages = List.of(system,
                UserMessage.from(text, fromUrl.apply(wireMock.runtimeInfo().getHttpBaseUrl() + mediaPath)));
        var expectedMessages = List.of(system,
                UserMessage.from(text, fromBase64.apply(Base64.getEncoder().encodeToString(bytes), mimeType)));

        var actualMessages = remoteMediaInliner.inline(messages);

        assertThat(actualMessages).isEqualTo(expectedMessages);
    }

    @Test
    @DisplayName("an inlined image keeps everything it carried except its URL")
    void inlinedImageKeepsItsOtherFields() {
        var bytes = RandomUtils.secure().randomBytes(64);
        var mediaPath = "/media-" + UUID.randomUUID();
        wireMock.server().stubFor(get(urlPathEqualTo(mediaPath))
                .willReturn(aResponse().withHeader("Content-Type", "image/jpeg").withBody(bytes)));
        var revisedPrompt = UUID.randomUUID().toString();
        List<ChatMessage> messages = List.of(UserMessage.builder()
                .name(UUID.randomUUID().toString())
                .attributes(Map.of(UUID.randomUUID().toString(), UUID.randomUUID().toString()))
                .contents(List.of(ImageContent.from(Image.builder()
                        .url(wireMock.runtimeInfo().getHttpBaseUrl() + mediaPath)
                        .mimeType("image/png")
                        .revisedPrompt(revisedPrompt)
                        .build(), ImageContent.DetailLevel.HIGH)))
                .build());
        var original = (UserMessage) messages.getFirst();
        var expectedMessages = List.of(original.toBuilder()
                .contents(List.of(ImageContent.from(Image.builder()
                        .base64Data(Base64.getEncoder().encodeToString(bytes))
                        .mimeType("image/png")
                        .revisedPrompt(revisedPrompt)
                        .build(), ImageContent.DetailLevel.HIGH)))
                .build());

        var actualMessages = remoteMediaInliner.inline(messages);

        assertThat(actualMessages).isEqualTo(expectedMessages);
    }

    private byte[] gzip(byte[] bytes) throws IOException {
        var compressed = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(compressed)) {
            gzip.write(bytes);
        }
        return compressed.toByteArray();
    }

    @Test
    @DisplayName("a compressed media file is inflated, as the production client does, and inlined")
    void compressedMediaIsInflatedAndInlined() throws IOException {
        var gzipInliner = LlmTestClients.remoteMediaInliner(DestinationGuard.Mode.RELAXED,
                TestHttpClientUtils.gzipClient());
        var bytes = RandomUtils.secure().randomBytes(64);
        var mediaPath = "/media-" + UUID.randomUUID();
        wireMock.server().stubFor(get(urlPathEqualTo(mediaPath)).willReturn(aResponse()
                .withHeader("Content-Type", "image/png")
                .withHeader("Content-Encoding", "gzip")
                .withBody(gzip(bytes))));
        List<ChatMessage> messages = List.of(
                UserMessage.from(ImageContent.from(wireMock.runtimeInfo().getHttpBaseUrl() + mediaPath)));
        var expectedMessages = List.<ChatMessage>of(
                UserMessage.from(ImageContent.from(Base64.getEncoder().encodeToString(bytes), "image/png")));

        var actualMessages = gzipInliner.inline(messages);

        assertThat(actualMessages).isEqualTo(expectedMessages);
    }

    @Test
    @DisplayName("a compressed media file over the size limit once inflated fails the request")
    void compressedMediaOverTheLimitOnceInflatedIsRefused() throws IOException {
        var gzipInliner = LlmTestClients.remoteMediaInliner(DestinationGuard.Mode.RELAXED,
                TestHttpClientUtils.gzipClient());
        var mediaPath = "/media-" + UUID.randomUUID();
        wireMock.server().stubFor(get(urlPathEqualTo(mediaPath)).willReturn(aResponse()
                .withHeader("Content-Type", "image/png")
                .withHeader("Content-Encoding", "gzip")
                .withBody(gzip(new byte[maxBytes + 1]))));
        List<ChatMessage> messages = List.of(
                UserMessage.from(ImageContent.from(wireMock.runtimeInfo().getHttpBaseUrl() + mediaPath)));
        var expectedMessage = MEDIA_URL_DOWNLOAD_REFUSED;

        assertThatThrownBy(() -> gzipInliner.inline(messages))
                .isExactlyInstanceOf(ProviderRequestRefusedException.class)
                .hasMessage(expectedMessage);
    }

    /** An inliner whose limit for all the media of a request is that many bytes. */
    private RemoteMediaInliner inlinerWithLimit(long maxBytes) {
        var config = TestConfigUtils.loadConfigTest().getLlmProviderClient();
        config.setVideoUrlDestinationGuard(DestinationGuard.Mode.RELAXED);
        config.setMediaDownloadMaxSize(DataSize.bytes(maxBytes));
        return new RemoteMediaInliner(TestHttpClientUtils.client(), config);
    }

    /** Serves the bytes at a new path, returning its URL. */
    private String serve(byte[] bytes) {
        var mediaPath = "/media-" + UUID.randomUUID();
        wireMock.server().stubFor(get(urlPathEqualTo(mediaPath))
                .willReturn(aResponse().withHeader("Content-Type", "image/png").withBody(bytes)));
        return wireMock.runtimeInfo().getHttpBaseUrl() + mediaPath;
    }

    @Test
    @DisplayName("media files under the size limit each but over it together fail the request, and no more are read")
    void mediaOverTheLimitTogetherAreRefused() {
        int limit = RandomUtils.secure().randomInt(100, 1_000);
        var lastUrl = serve(RandomUtils.secure().randomBytes(1));
        List<ChatMessage> messages = List.of(
                UserMessage.from(ImageContent.from(serve(RandomUtils.secure().randomBytes(limit / 2 + 1)))),
                UserMessage.from(ImageContent.from(serve(RandomUtils.secure().randomBytes(limit / 2 + 1))),
                        ImageContent.from(lastUrl)));
        var expectedMessage = MEDIA_URL_DOWNLOAD_REFUSED;

        assertThatThrownBy(() -> inlinerWithLimit(limit).inline(messages))
                .isExactlyInstanceOf(ProviderRequestRefusedException.class)
                .hasMessage(expectedMessage);
        wireMock.server().verify(0, getRequestedFor(urlEqualTo(URI.create(lastUrl).getPath())));
    }

    @Test
    @DisplayName("media files exactly at the size limit together are inlined")
    void mediaAtTheLimitTogetherAreInlined() {
        int limit = RandomUtils.secure().randomInt(100, 1_000) * 2;
        var first = RandomUtils.secure().randomBytes(limit / 2);
        var second = RandomUtils.secure().randomBytes(limit / 2);
        List<ChatMessage> messages = List.of(UserMessage.from(ImageContent.from(serve(first)),
                ImageContent.from(serve(second))));
        var expectedMessages = List.<ChatMessage>of(UserMessage.from(
                ImageContent.from(Base64.getEncoder().encodeToString(first), "image/png"),
                ImageContent.from(Base64.getEncoder().encodeToString(second), "image/png")));

        var actualMessages = inlinerWithLimit(limit).inline(messages);

        assertThat(actualMessages).isEqualTo(expectedMessages);
    }

    @Test
    @DisplayName("Cloud Storage and data: URLs never reach the network from here, so they are left untouched")
    void passThroughUrlsAreLeftUntouched() {
        List<ChatMessage> messages = List.of(UserMessage.from(
                ImageContent.from("gs://bucket-%s/image.png".formatted(UUID.randomUUID())),
                AudioContent.from("data:audio/wav,%s".formatted(UUID.randomUUID()))));

        var actualMessages = remoteMediaInliner.inline(messages);

        assertThat(actualMessages).isEqualTo(messages);
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///etc/hosts", "ftp://example.com/image.png", "GS://bucket/image.png",
            "jar:file:///app.jar!/image.png", "example.com/image.png", "//example.com/image.png"})
    @DisplayName("a media URL with any other scheme, or none, is refused before anything is read")
    void otherSchemesAreRefused(String url) {
        List<ChatMessage> messages = List.of(UserMessage.from(ImageContent.from(url)));
        var expectedMessage = MEDIA_URL_REFUSED;

        assertThatThrownBy(() -> remoteMediaInliner.inline(messages))
                .isExactlyInstanceOf(ProviderRequestRefusedException.class)
                .hasMessage(expectedMessage);
    }

    /** A public address literal, so nothing but the scheme can refuse it and no lookup is needed. */
    @Test
    @DisplayName("with https_only, an http media URL is refused before anything is read")
    void httpUrlIsRefusedWithHttpsOnly() {
        var httpsOnlyInliner = LlmTestClients.remoteMediaInliner(DestinationGuard.Mode.STRICT,
                DestinationGuard.Scheme.HTTPS_ONLY);
        List<ChatMessage> messages = List.of(UserMessage.from(
                ImageContent.from("http://8.8.8.8/media-%s.png".formatted(UUID.randomUUID()))));
        var expectedMessage = MEDIA_URL_REFUSED;

        assertThatThrownBy(() -> httpsOnlyInliner.inline(messages))
                .isExactlyInstanceOf(ProviderRequestRefusedException.class)
                .hasMessage(expectedMessage);
    }

    private Stream<Arguments> failedDownloadIsRefused() {
        return Stream.of(
                arguments("/missing-" + UUID.randomUUID(), aResponse().withStatus(404)),
                arguments("/empty-" + UUID.randomUUID(), aResponse().withStatus(200)),
                arguments("/oversized-" + UUID.randomUUID(),
                        aResponse().withBody(new byte[maxBytes + 1])),
                arguments("/redirect-" + UUID.randomUUID(),
                        aResponse().withStatus(302).withHeader("Location", "/media-" + UUID.randomUUID())));
    }

    @ParameterizedTest
    @MethodSource
    @DisplayName("a media URL that isn't found, is empty, is too large or redirects fails the request")
    void failedDownloadIsRefused(String mediaPath, ResponseDefinitionBuilder response) {
        wireMock.server().stubFor(get(urlPathEqualTo(mediaPath)).willReturn(response));
        List<ChatMessage> messages = List.of(
                UserMessage.from(ImageContent.from(wireMock.runtimeInfo().getHttpBaseUrl() + mediaPath)));
        var expectedMessage = MEDIA_URL_DOWNLOAD_REFUSED;

        assertThatThrownBy(() -> remoteMediaInliner.inline(messages))
                .isExactlyInstanceOf(ProviderRequestRefusedException.class)
                .hasMessage(expectedMessage);
    }
}
