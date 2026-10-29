package com.comet.opik.infrastructure.llm;

import com.comet.opik.infrastructure.llm.MediaContentUtils.Base64DataUri;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.VideoContent;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
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
                Arguments.of("data:audio/mpeg;name=clip.mp3;base64," + BASE64_DATA, "audio/mpeg"),
                Arguments.of("data:image/png;base64," + BASE64_DATA.replaceAll("(.{20})", "$1\n"), "image/png"),
                Arguments.of("data:image/png;base64," + BASE64_DATA.replaceAll("(.{16})", "$1\r\n "), "image/png"));
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
                "data:image/png;base64,\n \r\n",
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

    @Test
    void toUriStringKeepsAUrlAndRebuildsADataUriFromInlineData() {
        assertThat(MediaContentUtils.toUriString(URI.create("https://example.com/a.png"), null, null))
                .isEqualTo("https://example.com/a.png");
        assertThat(MediaContentUtils.toUriString(null, "audio/wav", BASE64_DATA))
                .isEqualTo("data:audio/wav;base64," + BASE64_DATA);
    }

    static Stream<Arguments> rawBase64Images() {
        return Stream.of(
                Arguments.of(BASE64_DATA, "image/png"),
                Arguments.of("/9j/4AAQSkZJRgABAQAAAQABAAA=", "image/jpeg"),
                Arguments.of("R0lGODlhAQABAIAAAA==", "image/gif"),
                Arguments.of("R0lGODdhAQABAIAAAA==", "image/gif"),
                Arguments.of("UklGRiQAAABXRUJQVlA4", "image/webp"),
                Arguments.of("Qk06AAAAAAAAADYAAAAo", "image/bmp"),
                Arguments.of("SUkqAAgAAAA=", "image/tiff"),
                Arguments.of("TU0AKgAAAAg=", "image/tiff"));
    }

    @ParameterizedTest(name = "{index}: {1}")
    @MethodSource("rawBase64Images")
    void toImageContentWhenRawBase64ImageReturnsInlineImage(String rawBase64, String expectedMimeType) {
        assertThat(MediaContentUtils.toImageContent(rawBase64))
                .isEqualTo(ImageContent.from(rawBase64, expectedMimeType));
    }

    static Stream<Arguments> lineWrappedRawBase64Images() {
        return Stream.of(
                Arguments.of(BASE64_DATA.replaceAll("(.{20})", "$1\n")),
                Arguments.of(BASE64_DATA.replaceAll("(.{16})", "$1\r\n ") + "\n"));
    }

    @ParameterizedTest
    @MethodSource("lineWrappedRawBase64Images")
    void toImageContentWhenRawBase64ImageIsLineWrappedReturnsItWithoutWhitespace(String rawBase64) {
        assertThat(MediaContentUtils.toImageContent(rawBase64))
                .isEqualTo(ImageContent.from(BASE64_DATA, "image/png"));
    }

    static Stream<String> notRawBase64Images() {
        return Stream.of(
                "iVBORw0KGgo.png",
                "Qk:not-base64",
                "/9j/photos/room.jpg",
                "AAAAIGZ0eXBpc29tAAACAGlzb21pc28y",
                "Qk",
                "QkAAAAA",
                "QkAA=AAA",
                "QkAAA===");
    }

    @ParameterizedTest
    @MethodSource("notRawBase64Images")
    void toImageContentWhenNotARawBase64ImageKeepsUrl(String value) {
        assertThat(MediaContentUtils.toImageContent(value)).isEqualTo(ImageContent.from(value));
    }

    @Test
    void toVideoContentDoesNotTreatRawBase64AsInlineData() {
        assertThat(MediaContentUtils.toVideoContent(BASE64_DATA)).isEqualTo(VideoContent.from(BASE64_DATA));
    }
}
