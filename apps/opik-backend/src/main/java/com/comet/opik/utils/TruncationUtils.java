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
 *
 * <h2>Smart Truncation Strategy</h2>
 * <p>The optimized truncation approach uses ClickHouse's JSONExtractKeysAndValuesRaw function
 * to extract all top-level fields as raw strings, then truncates each value individually while
 * maintaining valid top-level JSON structure.
 *
 * <p>The truncation logic:
 * <ul>
 *   <li><b>Image Replacement:</b> Replaces base64-encoded images with "[image]" placeholder</li>
 *   <li><b>Field Extraction:</b> Uses JSONExtractKeysAndValuesRaw to get all fields (nested objects become strings)</li>
 *   <li><b>Value Truncation:</b> Truncates ALL field values as strings (primitives, nested objects, arrays)</li>
 *   <li><b>JSON Reconstruction:</b> Rebuilds valid top-level JSON with toJSONString</li>
 * </ul>
 *
 * <h2>Performance Optimization</h2>
 * <p>ClickHouse's Common Subexpression Elimination (CSE) automatically deduplicates repeated
 * {@code replaceRegexpAll} calls within the same expression, reducing regex operations from 5 to 1.
 *
 * <h2>Graceful Degradation</h2>
 * <p>Truncated nested objects may contain invalid JSON (cut mid-structure), but the top-level
 * JSON remains valid. The Java mapper's {@code JsonUtils.getJsonNodeFromStringWithFallback}
 * handles this gracefully by returning TextNode for unparseable values.
 *
 * @see com.comet.opik.domain.ExperimentItemDAO
 * @see com.comet.opik.domain.DatasetItemVersionDAO
 * @see com.comet.opik.domain.DatasetItemDAO
 */
@Slf4j
@UtilityClass
public final class TruncationUtils {

    /**
     * SQL template for smart truncation of the input field.
     * Applies image replacement first, then truncates all top-level field values as strings.
     *
     * <p>This optimized approach:
     * <ol>
     *   <li><b>Image Replacement:</b> Replaces image patterns with "[image]" placeholder (ClickHouse CSE optimizes multiple calls)</li>
     *   <li><b>Field-level Truncation:</b> Uses JSONExtractKeysAndValuesRaw to get all fields as raw strings, truncates each value</li>
     *   <li><b>JSON Reconstruction:</b> Rebuilds valid top-level JSON structure with toJSONString</li>
     * </ol>
     *
     * <p>Key behaviors:
     * <ul>
     *   <li>Truncates ALL field values as strings (primitives, nested objects, arrays)</li>
     *   <li>Top-level JSON structure remains valid for deserialization</li>
     *   <li>Truncated nested objects may become invalid JSON, handled gracefully by JsonUtils.getJsonNodeFromStringWithFallback</li>
     *   <li>ClickHouse Common Subexpression Elimination automatically deduplicates replaceRegexpAll calls</li>
     * </ul>
     *
     * <p>Template parameters:
     * <ul>
     *   <li>{@code <truncationSize>} - maximum size for each truncated field value</li>
     *   <li>{@code <truncate>} - regex pattern for image data to replace with [image]</li>
     * </ul>
     */
    public static final String SMART_INPUT_TRUNCATION = """
            if(length(JSONExtractKeys(replaceRegexpAll(input, '<truncate>', '"[image]"'))) > 0,
                toJSONString(
                  mapFromArrays(
                    arrayMap(kv -> kv.1,
                      arrayMap(
                        kv -> tuple(kv.1, substring(kv.2, 1, <truncationSize>)),
                        JSONExtractKeysAndValuesRaw(replaceRegexpAll(input, '<truncate>', '"[image]"'))
                      )
                    ),
                    arrayMap(kv -> kv.2,
                      arrayMap(
                        kv -> tuple(kv.1, substring(kv.2, 1, <truncationSize>)),
                        JSONExtractKeysAndValuesRaw(replaceRegexpAll(input, '<truncate>', '"[image]"'))
                      )
                    )
                  )
                ),
                substring(replaceRegexpAll(input, '<truncate>', '"[image]"'), 1, <truncationSize>)
            )""";

    /**
     * SQL template for smart truncation of the output field.
     * Applies image replacement first, then truncates all top-level field values as strings.
     *
     * <p>This optimized approach:
     * <ol>
     *   <li><b>Image Replacement:</b> Replaces image patterns with "[image]" placeholder (ClickHouse CSE optimizes multiple calls)</li>
     *   <li><b>Field-level Truncation:</b> Uses JSONExtractKeysAndValuesRaw to get all fields as raw strings, truncates each value</li>
     *   <li><b>JSON Reconstruction:</b> Rebuilds valid top-level JSON structure with toJSONString</li>
     * </ol>
     *
     * <p>Key behaviors:
     * <ul>
     *   <li>Truncates ALL field values as strings (primitives, nested objects, arrays)</li>
     *   <li>Top-level JSON structure remains valid for deserialization</li>
     *   <li>Truncated nested objects may become invalid JSON, handled gracefully by JsonUtils.getJsonNodeFromStringWithFallback</li>
     *   <li>ClickHouse Common Subexpression Elimination automatically deduplicates replaceRegexpAll calls</li>
     * </ul>
     *
     * <p>Template parameters:
     * <ul>
     *   <li>{@code <truncationSize>} - maximum size for each truncated field value</li>
     *   <li>{@code <truncate>} - regex pattern for image data to replace with [image]</li>
     * </ul>
     */
    public static final String SMART_OUTPUT_TRUNCATION = """
            if(length(JSONExtractKeys(replaceRegexpAll(output, '<truncate>', '"[image]"'))) > 0,
                toJSONString(
                  mapFromArrays(
                    arrayMap(kv -> kv.1,
                      arrayMap(
                        kv -> tuple(kv.1, substring(kv.2, 1, <truncationSize>)),
                        JSONExtractKeysAndValuesRaw(replaceRegexpAll(output, '<truncate>', '"[image]"'))
                      )
                    ),
                    arrayMap(kv -> kv.2,
                      arrayMap(
                        kv -> tuple(kv.1, substring(kv.2, 1, <truncationSize>)),
                        JSONExtractKeysAndValuesRaw(replaceRegexpAll(output, '<truncate>', '"[image]"'))
                      )
                    )
                  )
                ),
                substring(replaceRegexpAll(output, '<truncate>', '"[image]"'), 1, <truncationSize>)
            )""";

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
