package com.comet.opik.infrastructure.llm;

import com.comet.opik.infrastructure.llm.MediaContentUtils.Base64DataUri;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.VideoContent;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class MediaContentUtilsTest {

    private static final String BASE64_DATA = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

    static Stream<Arguments> base64DataUris() {
        return Stream.of(
                Arguments.of("data:image/png;base64," + BASE64_DATA, "image/png"),
                Arguments.of("data:image/webp;base64," + BASE64_DATA, "image/webp"),
                Arguments.of("data:audio/wav;base64," + BASE64_DATA, "audio/wav"),
                Arguments.of("data:video/mp4;base64," + BASE64_DATA, "video/mp4"),
                Arguments.of("DATA:IMAGE/PNG;BASE64," + BASE64_DATA, "image/png"),
                Arguments.of("data:audio/mpeg;name=clip.mp3;base64," + BASE64_DATA, "audio/mpeg"));
    }

    @ParameterizedTest(name = "{index}: {1}")
    @MethodSource("base64DataUris")
    void parseBase64DataUriReturnsMimeTypeAndData(String url, String expectedMimeType) {
        assertThat(MediaContentUtils.parseBase64DataUri(url)).contains(Base64DataUri.builder()
                .mimeType(expectedMimeType)
                .base64Data(BASE64_DATA)
                .build());
    }

    static Stream<String> nonBase64Urls() {
        return Stream.of(
                "https://example.com/image.png?width=200",
                "gs://bucket/video.mp4",
                "data:image/svg+xml,%3Csvg%3E%3C%2Fsvg%3E",
                "data:;base64," + BASE64_DATA,
                "data:image/png;base64,",
                "data:image/png;base64");
    }

    @ParameterizedTest
    @MethodSource("nonBase64Urls")
    void parseBase64DataUriWhenNotBase64DataUriReturnsEmpty(String url) {
        assertThat(MediaContentUtils.parseBase64DataUri(url)).isEmpty();
    }

    static Stream<Arguments> contentFactories() {
        return Stream.of(
                Arguments.of(Named.of("image", (Function<String, Content>) MediaContentUtils::toImageContent),
                        "image/png",
                        ImageContent.from(BASE64_DATA, "image/png"),
                        ImageContent.from("https://example.com/media")),
                Arguments.of(Named.of("audio", (Function<String, Content>) MediaContentUtils::toAudioContent),
                        "audio/wav",
                        AudioContent.from(BASE64_DATA, "audio/wav"),
                        AudioContent.from("https://example.com/media")),
                Arguments.of(Named.of("video", (Function<String, Content>) MediaContentUtils::toVideoContent),
                        "video/mp4",
                        VideoContent.from(BASE64_DATA, "video/mp4"),
                        VideoContent.from("https://example.com/media")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contentFactories")
    void toContentWhenBase64DataUriReturnsInlineDataOtherwiseUrl(
            Function<String, Content> toContent, String mimeType, Content expectedInline, Content expectedUrl) {
        assertThat(toContent.apply("data:" + mimeType + ";base64," + BASE64_DATA)).isEqualTo(expectedInline);
        assertThat(toContent.apply("https://example.com/media")).isEqualTo(expectedUrl);
    }
}
