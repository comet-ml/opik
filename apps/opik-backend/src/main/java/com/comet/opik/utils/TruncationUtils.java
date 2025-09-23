package com.comet.opik.utils;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import io.r2dbc.spi.Statement;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.UncheckedIOException;

/**
 * Utility class for handling data truncation operations in DAO classes.
 * Contains shared methods for JSON node processing and truncation threshold binding.
 */
@Slf4j
@UtilityClass
public final class TruncationUtils {

    /**
     * Returns a JsonNode from the given value, or a TextNode if the data is truncated.
     *
     * @param rowMetadata the row metadata to check for truncation flags
     * @param truncatedFlag the column name indicating if data is truncated
     * @param row the database row
     * @param value the string value to process
     * @return JsonNode representation of the value, or TextNode if truncated
     */
    public static JsonNode getJsonNodeOrTruncatedString(RowMetadata rowMetadata, String truncatedFlag, Row row,
            String value) {
        if (rowMetadata.contains(truncatedFlag) && Boolean.TRUE.equals(row.get(truncatedFlag, Boolean.class))) {
            return TextNode.valueOf(value);
        }

        try {
            return JsonUtils.getJsonNodeFromString(value);
        } catch (UncheckedIOException e) {
            log.warn("Failed to parse JSON, returning as plain text node. Error: {}", e.getMessage());
            return TextNode.valueOf(value);
        }
    }

    /**
     * Binds the truncation threshold parameter to a statement based on configuration.
     *
     * @param statement the database statement to bind the parameter to
     * @param truncationThresholdField the field name for the truncation threshold parameter
     * @param configuration the Opik configuration containing truncation settings
     */
    public static void bindTruncationThreshold(Statement statement, String truncationThresholdField,
            OpikConfiguration configuration) {
        if (configuration.getResponseFormatting().getTruncationSize() > 0) {
            statement.bind(truncationThresholdField, configuration.getResponseFormatting().getTruncationSize());
        } else {
            statement.bindNull(truncationThresholdField, Integer.class);
        }
    }
}
