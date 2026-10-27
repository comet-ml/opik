package com.comet.opik.infrastructure.llm;

import com.comet.opik.TestConfigUtils;
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
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.comet.opik.infrastructure.llm.customllm.ProviderRequestRefusedException.Reason.MEDIA_URL;
import static com.comet.opik.infrastructure.llm.customllm.ProviderRequestRefusedException.Reason.MEDIA_URL_DOWNLOAD;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemoteMediaInlinerTest {

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
            "jar:file:///app.jar!/image.png"})
    @DisplayName("a media URL with any other scheme is refused before anything is read")
    void otherSchemesAreRefused(String url) {
        List<ChatMessage> messages = List.of(UserMessage.from(ImageContent.from(url)));
        var expectedMessage = new ProviderRequestRefusedException(MEDIA_URL).getMessage();

        assertThatThrownBy(() -> remoteMediaInliner.inline(messages))
                .isExactlyInstanceOf(ProviderRequestRefusedException.class)
                .hasMessage(expectedMessage);
    }

    private Stream<Arguments> failedDownloadIsRefused() {
        return Stream.of(
                arguments("/missing-" + UUID.randomUUID(), aResponse().withStatus(404)),
                arguments("/oversized-" + UUID.randomUUID(),
                        aResponse().withBody(new byte[maxBytes + 1])),
                arguments("/redirect-" + UUID.randomUUID(),
                        aResponse().withStatus(302).withHeader("Location", "/media-" + UUID.randomUUID())));
    }

    @ParameterizedTest
    @MethodSource
    @DisplayName("a media URL that isn't found, is too large or redirects fails the request")
    void failedDownloadIsRefused(String mediaPath, ResponseDefinitionBuilder response) {
        wireMock.server().stubFor(get(urlPathEqualTo(mediaPath)).willReturn(response));
        List<ChatMessage> messages = List.of(
                UserMessage.from(ImageContent.from(wireMock.runtimeInfo().getHttpBaseUrl() + mediaPath)));
        var expectedMessage = new ProviderRequestRefusedException(MEDIA_URL_DOWNLOAD).getMessage();

        assertThatThrownBy(() -> remoteMediaInliner.inline(messages))
                .isExactlyInstanceOf(ProviderRequestRefusedException.class)
                .hasMessage(expectedMessage);
    }
}
