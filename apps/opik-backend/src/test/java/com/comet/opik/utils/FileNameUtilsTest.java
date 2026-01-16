package com.comet.opik.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FileNameUtilsTest {

    private static final UUID TEST_JOB_ID = UUID.fromString("12345678-1234-1234-1234-123456789abc");

    @Test
    void buildDatasetExportFilename_shouldReturnFallback_whenDatasetNameIsNull() {
        String result = FileNameUtils.buildDatasetExportFilename(null, TEST_JOB_ID);

        assertThat(result).isEqualTo("dataset-export-" + TEST_JOB_ID + ".csv");
    }

    @Test
    void buildDatasetExportFilename_shouldReturnFallback_whenDatasetNameIsEmpty() {
        String result = FileNameUtils.buildDatasetExportFilename("", TEST_JOB_ID);

        assertThat(result).isEqualTo("dataset-export-" + TEST_JOB_ID + ".csv");
    }

    @Test
    void buildDatasetExportFilename_shouldReturnFallback_whenDatasetNameIsBlank() {
        String result = FileNameUtils.buildDatasetExportFilename("   ", TEST_JOB_ID);

        assertThat(result).isEqualTo("dataset-export-" + TEST_JOB_ID + ".csv");
    }

    @Test
    void buildDatasetExportFilename_shouldReturnDatasetName_whenValidName() {
        String result = FileNameUtils.buildDatasetExportFilename("My Dataset", TEST_JOB_ID);

        assertThat(result).isEqualTo("My Dataset.csv");
    }

    @Test
    void buildDatasetExportFilename_shouldTrimWhitespace() {
        String result = FileNameUtils.buildDatasetExportFilename("  My Dataset  ", TEST_JOB_ID);

        assertThat(result).isEqualTo("My Dataset.csv");
    }

    @Test
    void buildDatasetExportFilename_shouldTruncateLongNames() {
        String longName = "A".repeat(150);
        String result = FileNameUtils.buildDatasetExportFilename(longName, TEST_JOB_ID);

        // Should truncate to 100 chars + .csv extension
        assertThat(result).hasSize(104);
        assertThat(result).startsWith("A".repeat(100));
        assertThat(result).endsWith(".csv");
    }

    // Header injection prevention tests

    @Test
    void buildDatasetExportFilename_shouldRemoveCarriageReturn() {
        String maliciousName = "foo\rSet-Cookie: hax=1";
        String result = FileNameUtils.buildDatasetExportFilename(maliciousName, TEST_JOB_ID);

        assertThat(result).isEqualTo("fooSet-Cookie: hax=1.csv");
        assertThat(result).doesNotContain("\r");
    }

    @Test
    void buildDatasetExportFilename_shouldRemoveLineFeed() {
        String maliciousName = "foo\nSet-Cookie: hax=1";
        String result = FileNameUtils.buildDatasetExportFilename(maliciousName, TEST_JOB_ID);

        assertThat(result).isEqualTo("fooSet-Cookie: hax=1.csv");
        assertThat(result).doesNotContain("\n");
    }

    @Test
    void buildDatasetExportFilename_shouldRemoveCRLF() {
        String maliciousName = "foo\r\nSet-Cookie: hax=1";
        String result = FileNameUtils.buildDatasetExportFilename(maliciousName, TEST_JOB_ID);

        assertThat(result).isEqualTo("fooSet-Cookie: hax=1.csv");
        assertThat(result).doesNotContain("\r");
        assertThat(result).doesNotContain("\n");
    }

    @Test
    void buildDatasetExportFilename_shouldRemoveTab() {
        String maliciousName = "foo\tbar";
        String result = FileNameUtils.buildDatasetExportFilename(maliciousName, TEST_JOB_ID);

        assertThat(result).isEqualTo("foobar.csv");
        assertThat(result).doesNotContain("\t");
    }

    @Test
    void buildDatasetExportFilename_shouldRemoveDoubleQuotes() {
        String maliciousName = "foo\"bar";
        String result = FileNameUtils.buildDatasetExportFilename(maliciousName, TEST_JOB_ID);

        assertThat(result).isEqualTo("foobar.csv");
        assertThat(result).doesNotContain("\"");
    }

    @Test
    void buildDatasetExportFilename_shouldRemoveBackslashes() {
        String maliciousName = "foo\\bar";
        String result = FileNameUtils.buildDatasetExportFilename(maliciousName, TEST_JOB_ID);

        assertThat(result).isEqualTo("foobar.csv");
        assertThat(result).doesNotContain("\\");
    }

    @Test
    void buildDatasetExportFilename_shouldHandleComplexHeaderInjectionAttempt() {
        // Attempt to inject a Set-Cookie header via CRLF injection
        String maliciousName = "legitimate\r\nSet-Cookie: session=hacked\r\n\r\n<script>alert(1)</script>";
        String result = FileNameUtils.buildDatasetExportFilename(maliciousName, TEST_JOB_ID);

        assertThat(result).doesNotContain("\r");
        assertThat(result).doesNotContain("\n");
        assertThat(result).isEqualTo("legitimateSet-Cookie: session=hacked<script>alert(1)</script>.csv");
    }

    @Test
    void buildDatasetExportFilename_shouldReturnFallback_whenNameBecomesEmptyAfterSanitization() {
        // Name consisting only of control characters
        String maliciousName = "\r\n\t";
        String result = FileNameUtils.buildDatasetExportFilename(maliciousName, TEST_JOB_ID);

        assertThat(result).isEqualTo("dataset-export-" + TEST_JOB_ID + ".csv");
    }

    @ParameterizedTest
    @MethodSource("provideControlCharacters")
    void buildDatasetExportFilename_shouldRemoveAllControlCharacters(String controlChar, String description) {
        String maliciousName = "foo" + controlChar + "bar";
        String result = FileNameUtils.buildDatasetExportFilename(maliciousName, TEST_JOB_ID);

        assertThat(result)
                .as("Should remove %s", description)
                .isEqualTo("foobar.csv");
    }

    private static Stream<Arguments> provideControlCharacters() {
        return Stream.of(
                Arguments.of("\u0000", "NULL"),
                Arguments.of("\u0001", "SOH"),
                Arguments.of("\u0007", "BELL"),
                Arguments.of("\u0008", "BACKSPACE"),
                Arguments.of("\t", "TAB"),
                Arguments.of("\n", "LINE FEED"),
                Arguments.of("\u000B", "VERTICAL TAB"),
                Arguments.of("\u000C", "FORM FEED"),
                Arguments.of("\r", "CARRIAGE RETURN"),
                Arguments.of("\u001B", "ESCAPE"),
                Arguments.of("\u007F", "DELETE"));
    }

    // Generic buildSafeFilename tests

    @Test
    void buildSafeFilename_shouldUseCustomPrefixAndExtension() {
        String result = FileNameUtils.buildSafeFilename(null, ".json", TEST_JOB_ID, "export-");

        assertThat(result).isEqualTo("export-" + TEST_JOB_ID + ".json");
    }

    @Test
    void buildSafeFilename_shouldPreserveValidSpecialCharacters() {
        // These characters should be preserved (they're safe for filenames)
        String validName = "My Dataset (2024) - Final_v1.0";
        String result = FileNameUtils.buildSafeFilename(validName, ".csv", TEST_JOB_ID, "export-");

        assertThat(result).isEqualTo("My Dataset (2024) - Final_v1.0.csv");
    }
}
