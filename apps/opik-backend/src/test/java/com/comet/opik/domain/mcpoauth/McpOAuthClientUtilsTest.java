package com.comet.opik.domain.mcpoauth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The sanitisers are pure functions over attacker-controlled strings, so their rules are pinned here rather
 * than through the registration round trip, which only proves they are wired in.
 */
@DisplayName("MCP OAuth Client Utils")
class McpOAuthClientUtilsTest {

    private static final int TEXT_MAX = 255;
    private static final int URI_MAX = 2048;
    private static final String URL_PREFIX = "https://example.test/";
    // Built from code points on purpose: a unicode escape for these is translated before the lexer sees the
    // literal it sits in, and the source stops compiling.
    private static final String LINE_SEPARATOR = String.valueOf((char) 0x2028);
    private static final String PARAGRAPH_SEPARATOR = String.valueOf((char) 0x2029);

    static Stream<Arguments> blankText() {
        return Stream.of(null, "", "   ", "\r\n", LINE_SEPARATOR, PARAGRAPH_SEPARATOR + LINE_SEPARATOR)
                .map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("blankText")
    @DisplayName("nothing, or nothing but separators, is null")
    void blankTextIsNull(String value) {
        assertThat(McpOAuthClientUtils.sanitizeDisplayText(value)).isNull();
    }

    @Test
    @DisplayName("control characters and the Unicode separators become spaces")
    void lineBreakingCharactersBecomeSpaces() {
        assertThat(McpOAuthClientUtils.sanitizeDisplayText("evil\r\nFAKE LOG LINE")).isEqualTo("evil  FAKE LOG LINE");
        assertThat(McpOAuthClientUtils.sanitizeDisplayText("1.0" + LINE_SEPARATOR + "forged" + PARAGRAPH_SEPARATOR
                + "line")).isEqualTo("1.0 forged line");
        assertThat(McpOAuthClientUtils.sanitizeDisplayText("  padded  ")).isEqualTo("padded");
    }

    @Test
    @DisplayName("text is capped at the column width, and a value that fits is untouched")
    void textIsCappedAtTheColumnWidth() {
        assertThat(McpOAuthClientUtils.sanitizeDisplayText("v".repeat(5000))).isEqualTo("v".repeat(TEXT_MAX));
        assertThat(McpOAuthClientUtils.sanitizeDisplayText("v".repeat(TEXT_MAX))).hasSize(TEXT_MAX);
        assertThat(McpOAuthClientUtils.sanitizeDisplayText("v".repeat(TEXT_MAX - 1))).hasSize(TEXT_MAX - 1);
    }

    @Test
    @DisplayName("the cap never splits a surrogate pair")
    void capNeverSplitsASurrogatePair() {
        // The emoji straddles the cap: half of one is not a character, and reaches utf8mb4 as a replacement byte.
        String emoji = new String(Character.toChars(0x1F600));

        String sanitized = McpOAuthClientUtils.sanitizeDisplayText("v".repeat(TEXT_MAX - 1) + emoji);

        assertThat(sanitized).isEqualTo("v".repeat(TEXT_MAX - 1));
        assertThat(sanitized.chars().anyMatch(c -> Character.isSurrogate((char) c))).as("no half character")
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "  DATA:text/html;base64,PHNjcmlwdD4=",
            "java\tscript:alert(1)",
            "//evil.test/logo.png",
            "http:///no-host",
            "http://[bad-host/logo.png",
            "https://user:s3cr3t@example.test/logo.png",
            "ftp://example.test/logo.png"})
    @DisplayName("anything but a well-formed, credential-free http(s) URL is dropped")
    void unsafeUrlsAreDropped(String value) {
        assertThat(McpOAuthClientUtils.sanitizeDisplayUri(value)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.test/logo.png",
            "HTTP://Example.TEST/logo.png",
            "http://127.0.0.1:3000/logo.png",
            "https://example.test/logo@2x.png",
            "https://example.test/logo.png?v=1&sig=abc"})
    @DisplayName("a real logo URL is kept exactly as sent")
    void safeUrlsAreKept(String value) {
        assertThat(McpOAuthClientUtils.sanitizeDisplayUri(value)).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    @DisplayName("the cap backs off rather than leave half a percent-escape")
    void capBacksOffAPercentEscape(int backOff) {
        String sent = URL_PREFIX + "p".repeat(URI_MAX - URL_PREFIX.length() - backOff) + "%20tail";

        String sanitized = McpOAuthClientUtils.sanitizeDisplayUri(sent);

        assertThat(sanitized).hasSize(URI_MAX - backOff).doesNotEndWith("%").doesNotEndWith("%2");
        assertThatCode(() -> new URI(sanitized)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an over-long URL is truncated, and one that fits is untouched")
    void urlsAreCappedAtTheColumnWidth() {
        assertThat(McpOAuthClientUtils.sanitizeDisplayUri(URL_PREFIX + "p".repeat(5000))).hasSize(URI_MAX);
        String atCap = URL_PREFIX + "p".repeat(URI_MAX - URL_PREFIX.length());
        assertThat(McpOAuthClientUtils.sanitizeDisplayUri(atCap)).isEqualTo(atCap);
    }

    @Test
    @DisplayName("the read-side filter cleans every display field, falling back to the client id for a blank name")
    void readSideFilterCleansEveryDisplayField() {
        var client = McpOAuthClient.builder()
                .id("client-id")
                .name(LINE_SEPARATOR)
                .redirectUris(Set.of("http://127.0.0.1:1234/cb"))
                .logoUri("javascript:alert(1)")
                .clientUri("data:text/html;base64,PHNjcmlwdD4=")
                .softwareId("legacy\r\nid")
                .softwareVersion("1.0")
                .build();

        var sanitized = McpOAuthClientUtils.sanitizeDisplayFields(client);

        assertThat(sanitized.name()).as("a name that sanitises to nothing falls back").isEqualTo("client-id");
        assertThat(sanitized.logoUri()).isNull();
        assertThat(sanitized.clientUri()).isNull();
        assertThat(sanitized.softwareId()).isEqualTo("legacy  id");
        assertThat(sanitized.softwareVersion()).isEqualTo("1.0");
    }
}
