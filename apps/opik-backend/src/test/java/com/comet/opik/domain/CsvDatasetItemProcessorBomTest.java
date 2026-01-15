package com.comet.opik.domain;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.io.input.BOMInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test to reproduce and verify fix for OPIK-3747: BOM character in CSV column names causes variables
 * to be "not defined" in Playground.
 *
 * This test creates CSV files with and without BOM to demonstrate the issue and verify the fix.
 */
@DisplayName("CSV BOM Character Handling Test - OPIK-3747")
class CsvDatasetItemProcessorBomTest {

    @Test
    @DisplayName("Reproduce: CSV with UTF-8 BOM in column headers - demonstrates the issue")
    void testCsvWithBomCharacter(@TempDir Path tempDir) throws IOException {
        // Create a CSV file with UTF-8 BOM (0xEF 0xBB 0xBF) at the beginning
        Path csvWithBom = tempDir.resolve("test_with_bom.csv");

        try (FileOutputStream fos = new FileOutputStream(csvWithBom.toFile());
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8))) {

            // Write UTF-8 BOM bytes manually
            fos.write(0xEF);
            fos.write(0xBB);
            fos.write(0xBF);

            // Write CSV content with Chinese characters (matching customer scenario)
            writer.write("Standard Question,Standard Answer,Other Field\n");
            writer.write("如何强制触发4G/5G后台搜索？,测试答案,测试值\n");
            writer.write("Second question,Second answer,Value2\n");
        }

        // Verify BOM is present in the file
        byte[] fileBytes = Files.readAllBytes(csvWithBom);
        assertThat(fileBytes).as("File should start with UTF-8 BOM")
                .startsWith(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});

        // Read the first line to show that BOM is included in the first column name
        String firstLine = Files.readAllLines(csvWithBom, StandardCharsets.UTF_8).get(0);

        // The BOM character (U+FEFF) will be in the string
        assertThat(firstLine.charAt(0))
                .as("First character should be BOM (U+FEFF)")
                .isEqualTo('\uFEFF');

        // Show that the column name includes BOM
        assertThat(firstLine)
                .as("First column name includes BOM character")
                .startsWith("\uFEFFStandard Question");

        System.out.println("=== BOM Issue Reproduction ===");
        System.out.println("First line with BOM: " + firstLine);
        System.out.println("First char code: " + Integer.toHexString(firstLine.charAt(0)));
        System.out.println("Expected: 'Standard Question'");
        System.out.println("Actual: '" + firstLine.split(",")[0] + "'");
        System.out.println("============================");

        // This demonstrates the issue:
        // When the CSV parser reads the headers, the first column name will be "\uFEFFStandard Question"
        // But in the Playground, users reference it as "{{Standard Question}}" without the BOM
        // This causes the variable to be "not defined"
    }

    @Test
    @DisplayName("Control: CSV without BOM in column headers - works correctly")
    void testCsvWithoutBomCharacter(@TempDir Path tempDir) throws IOException {
        // Create a CSV file WITHOUT BOM
        Path csvWithoutBom = tempDir.resolve("test_without_bom.csv");

        Files.writeString(csvWithoutBom,
                "Standard Question,Standard Answer,Other Field\n" +
                        "如何强制触发4G/5G后台搜索？,测试答案,测试值\n" +
                        "Second question,Second answer,Value2\n",
                StandardCharsets.UTF_8);

        // Verify NO BOM is present
        byte[] fileBytes = Files.readAllBytes(csvWithoutBom);
        boolean startsWithBom = fileBytes.length >= 3 &&
                fileBytes[0] == (byte) 0xEF &&
                fileBytes[1] == (byte) 0xBB &&
                fileBytes[2] == (byte) 0xBF;
        assertThat(startsWithBom).as("File should NOT start with UTF-8 BOM").isFalse();

        // Read the first line
        String firstLine = Files.readAllLines(csvWithoutBom, StandardCharsets.UTF_8).get(0);

        // Verify no BOM character
        assertThat(firstLine.charAt(0))
                .as("First character should NOT be BOM")
                .isNotEqualTo('\uFEFF');

        // Show clean column name
        assertThat(firstLine)
                .as("First column name is clean without BOM")
                .startsWith("Standard Question");

        System.out.println("=== Control (No BOM) ===");
        System.out.println("First line without BOM: " + firstLine);
        System.out.println("First char code: " + Integer.toHexString(firstLine.charAt(0)));
        System.out.println("Column name: '" + firstLine.split(",")[0] + "'");
        System.out.println("======================");
    }

    @Test
    @DisplayName("Fix verification: BOMInputStream strips BOM from CSV headers correctly")
    void testBomInputStreamStripsBom(@TempDir Path tempDir) throws IOException {
        // Given: Create a CSV file with UTF-8 BOM
        Path csvWithBom = tempDir.resolve("test_with_bom.csv");

        try (FileOutputStream fos = new FileOutputStream(csvWithBom.toFile());
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8))) {

            // Write UTF-8 BOM bytes manually
            fos.write(0xEF);
            fos.write(0xBB);
            fos.write(0xBF);

            // Write CSV content
            writer.write("Standard Question,Standard Answer,Other Field\n");
            writer.write("How to test?,This is a test,Value1\n");
        }

        // When: Parse CSV using BOMInputStream (the fix)
        try (BOMInputStream bomStream = BOMInputStream.builder()
                .setInputStream(Files.newInputStream(csvWithBom))
                .get();
                InputStreamReader reader = new InputStreamReader(bomStream, StandardCharsets.UTF_8);
                CSVParser parser = CSVFormat.DEFAULT
                        .builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .setTrim(true)
                        .build()
                        .parse(reader)) {

            // Then: Headers should NOT contain BOM character
            List<String> headers = parser.getHeaderNames();

            assertThat(headers).hasSize(3);
            assertThat(headers.get(0))
                    .as("First header should NOT contain BOM character after using BOMInputStream")
                    .isEqualTo("Standard Question")
                    .doesNotContain("\uFEFF");

            assertThat(headers.get(1)).isEqualTo("Standard Answer");
            assertThat(headers.get(2)).isEqualTo("Other Field");

            System.out.println("=== Fix Verification ===");
            System.out.println("Headers after BOMInputStream: " + headers);
            System.out.println("First header: '" + headers.get(0) + "'");
            System.out.println("First header char code: " + Integer.toHexString(headers.get(0).charAt(0)));
            System.out.println("✓ BOM successfully stripped! Headers are clean.");
            System.out.println("=======================");
        }
    }
}
