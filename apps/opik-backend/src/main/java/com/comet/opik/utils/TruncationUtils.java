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
 * <h2>Smart JSON Truncation</h2>
 * <p>The smart truncation approach preserves JSON structure by truncating individual field values
 * rather than the entire JSON string. This ensures all keys remain accessible even when values
 * exceed the truncation threshold.
 * 
 * <h3>Output Truncation</h3>
 * <p>Uses the materialized {@code output_keys} column (Array of Tuple(key, type)) for efficient
 * key extraction. The SQL pattern is:
 * <pre>{@code
 * if(length(output_keys) > 0,
 *     toJSONString(mapFromArrays(
 *         arrayMap(kt -> tupleElement(kt, 1), output_keys),
 *         arrayMap(kt -> multiIf(...truncation logic...), output_keys)
 *     )),
 *     substring(output, 1, truncationSize)  -- fallback for legacy data
 * )
 * }</pre>
 * 
 * <h3>Input Truncation</h3>
 * <p>Uses {@code JSONExtractKeys(input)} at query time since there's no materialized input_keys column.
 * The SQL pattern is:
 * <pre>{@code
 * if(length(JSONExtractKeys(input)) > 0,
 *     toJSONString(mapFromArrays(
 *         JSONExtractKeys(input),
 *         arrayMap(k -> multiIf(...truncation logic...), JSONExtractKeys(input))
 *     )),
 *     substring(input, 1, truncationSize)  -- fallback for non-JSON input
 * )
 * }</pre>
 * 
 * <p>The truncation logic for each field value:
 * <ul>
 *   <li>String values exceeding limit: truncated with "...[truncated]" marker</li>
 *   <li>Non-string values (objects, arrays) exceeding limit: replaced with "[value truncated]"</li>
 *   <li>Values within limit: preserved with original type</li>
 *   <li>Image patterns: replaced with "[image]" placeholder</li>
 * </ul>
 * 
 * @see com.comet.opik.domain.ExperimentItemDAO
 * @see com.comet.opik.domain.DatasetItemVersionDAO
 */
@Slf4j
@UtilityClass
public final class TruncationUtils {

    /**
     * SQL template for smart truncation of the input field.
     * Uses JSONExtractKeys at runtime since there's no materialized input_keys column.
     * Preserves JSON structure by truncating individual field values rather than the entire string.
     * 
     * <p>Template parameters:
     * <ul>
     *   <li>{@code <truncationSize>} - maximum size for each field value</li>
     *   <li>{@code <truncate>} - regex pattern for image data to replace with [image]</li>
     * </ul>
     */
    public static final String SMART_INPUT_TRUNCATION = """
            if(length(JSONExtractKeys(input)) > 0,
                toJSONString(
                    mapFromArrays(
                        JSONExtractKeys(input),
                        arrayMap(
                            k ->
                                multiIf(
                                    JSONType(input, k) = 'String' AND length(JSONExtractString(input, k)) > <truncationSize>,
                                    concat('"', replaceRegexpAll(substring(JSONExtractString(input, k), 1, <truncationSize> - 20), '<truncate>', '[image]'), '...[truncated]"'),
                                    length(JSONExtractRaw(input, k)) > <truncationSize>,
                                    '"[value truncated]"',
                                    replaceRegexpAll(JSONExtractRaw(input, k), '<truncate>', '"[image]"')
                                ),
                            JSONExtractKeys(input)
                        )
                    )
                ),
                substring(replaceRegexpAll(input, '<truncate>', '"[image]"'), 1, <truncationSize>)
            )""";

    /**
     * SQL template for smart truncation of the output field.
     * Uses the materialized output_keys column (Array of Tuple(key, type)) for efficient key extraction.
     * Preserves JSON structure by truncating individual field values rather than the entire string.
     * 
     * <p>Template parameters:
     * <ul>
     *   <li>{@code <truncationSize>} - maximum size for each field value</li>
     *   <li>{@code <truncate>} - regex pattern for image data to replace with [image]</li>
     * </ul>
     * 
     * <p>Requires the query to select {@code output_keys} from the traces table.
     */
    public static final String SMART_OUTPUT_TRUNCATION = """
            if(length(output_keys) > 0,
                toJSONString(
                    mapFromArrays(
                        arrayMap(kt -> tupleElement(kt, 1), output_keys),
                        arrayMap(
                            kt ->
                                multiIf(
                                    tupleElement(kt, 2) = 'String' AND length(JSONExtractString(output, tupleElement(kt, 1))) > <truncationSize>,
                                    concat('"', replaceRegexpAll(substring(JSONExtractString(output, tupleElement(kt, 1)), 1, <truncationSize> - 20), '<truncate>', '[image]'), '...[truncated]"'),
                                    length(JSONExtractRaw(output, tupleElement(kt, 1))) > <truncationSize>,
                                    '"[value truncated]"',
                                    replaceRegexpAll(JSONExtractRaw(output, tupleElement(kt, 1)), '<truncate>', '"[image]"')
                                ),
                            output_keys
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
