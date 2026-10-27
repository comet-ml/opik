package com.comet.opik.infrastructure.llm;

import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.customllm.ProviderRequestRefusedException;
import com.comet.opik.infrastructure.net.DestinationGuard;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.comet.opik.infrastructure.llm.customllm.ProviderRequestRefusedException.Reason.MEDIA_URL_DOWNLOAD;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemoteMediaInlinerTest {

    private final WireMockUtils.WireMockRuntime wireMock = WireMockUtils.startWireMock();

    // the mock server is on loopback, which strict mode refuses; the refusal itself is covered through the API
    @BeforeAll
    void setUp() {
        RemoteMediaInliner.setConfig(config(DestinationGuard.Mode.RELAXED));
    }

    @AfterAll
    void tearDown() {
        RemoteMediaInliner.setConfig(config(DestinationGuard.Mode.STRICT));
        wireMock.server().stop();
    }

    private static LlmProviderClientConfig config(DestinationGuard.Mode mode) {
        var config = new LlmProviderClientConfig();
        config.setVideoUrlDestinationGuard(mode);
        return config;
    }

    private Stream<Arguments> vertexAiMediaIsDownloadedAndInlined() {
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
    @DisplayName("Vertex AI media URLs are downloaded, following redirects, and inlined with the response's type")
    void vertexAiMediaIsDownloadedAndInlined(String mimeType, Function<String, Content> fromUrl,
            BiFunction<String, String, Content> fromBase64) {
        var bytes = RandomUtils.secure().randomBytes(64);
        var mediaPath = "/media-" + UUID.randomUUID();
        var redirectPath = "/redirect-" + UUID.randomUUID();
        wireMock.server().stubFor(get(urlPathEqualTo(redirectPath))
                .willReturn(aResponse().withStatus(302).withHeader("Location", mediaPath)));
        wireMock.server().stubFor(get(urlPathEqualTo(mediaPath))
                .willReturn(aResponse().withHeader("Content-Type", mimeType + "; charset=binary").withBody(bytes)));
        var system = SystemMessage.from("be brief");
        var text = TextContent.from("describe");
        List<ChatMessage> messages = List.of(system,
                UserMessage.from(text, fromUrl.apply(wireMock.runtimeInfo().getHttpBaseUrl() + redirectPath)));

        assertThat(RemoteMediaInliner.inlineForVertexAi(messages)).isEqualTo(List.of(system,
                UserMessage.from(text, fromBase64.apply(Base64.getEncoder().encodeToString(bytes), mimeType))));
    }

    @Test
    @DisplayName("media the SDK passes to the provider by URL is left untouched and never downloaded")
    void mediaNotDownloadedBySdkIsLeftUntouched() {
        var mediaPath = "/media-" + UUID.randomUUID();
        var mediaUrl = wireMock.runtimeInfo().getHttpBaseUrl() + mediaPath;
        List<ChatMessage> gemini = List.of(UserMessage.from(VideoContent.from(mediaUrl), AudioContent.from(mediaUrl)));
        List<ChatMessage> vertexAi = List.of(
                UserMessage.from(ImageContent.from("gs://bucket-%s/image.png".formatted(UUID.randomUUID()))));

        assertThat(RemoteMediaInliner.inlineForGemini(gemini)).isEqualTo(gemini);
        assertThat(RemoteMediaInliner.inlineForVertexAi(vertexAi)).isEqualTo(vertexAi);
        wireMock.server().verify(0, anyRequestedFor(urlPathEqualTo(mediaPath)));
    }

    private Stream<Arguments> failedDownloadIsRefused() {
        var loopPath = "/loop-" + UUID.randomUUID();
        return Stream.of(
                arguments("/missing-" + UUID.randomUUID(), aResponse().withStatus(404)),
                arguments("/oversized-" + UUID.randomUUID(),
                        aResponse().withBody(new byte[RemoteMediaInliner.MAX_BYTES + 1])),
                arguments(loopPath, aResponse().withStatus(302).withHeader("Location", loopPath)));
    }

    @ParameterizedTest
    @MethodSource
    @DisplayName("a media URL that isn't found, is too large or redirects too often fails the request")
    void failedDownloadIsRefused(String mediaPath, ResponseDefinitionBuilder response) {
        wireMock.server().stubFor(get(urlPathEqualTo(mediaPath)).willReturn(response));
        List<ChatMessage> messages = List.of(
                UserMessage.from(ImageContent.from(wireMock.runtimeInfo().getHttpBaseUrl() + mediaPath)));

        assertThatThrownBy(() -> RemoteMediaInliner.inlineForGemini(messages))
                .isInstanceOf(ProviderRequestRefusedException.class)
                .hasMessage(new ProviderRequestRefusedException(MEDIA_URL_DOWNLOAD).getMessage());
    }
}
