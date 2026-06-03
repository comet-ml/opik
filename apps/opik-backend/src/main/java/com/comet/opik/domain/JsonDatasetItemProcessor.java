package com.comet.opik.domain;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.EvaluatorItem;
import com.comet.opik.api.ExecutionPolicy;
import com.comet.opik.api.JsonUploadFormat;
import com.comet.opik.api.Visibility;
import com.comet.opik.domain.DatasetItemUploadSupport.BatchAccumulator;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.type.CollectionType;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.input.BOMInputStream;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Processes JSON / JSONL files for dataset items.
 *
 * <p>Mirrors {@link CsvDatasetItemProcessor} via shared {@link DatasetItemUploadSupport}:
 * synchronously buffers the upload to a temp file, validates the head, verifies that the
 * dataset exists, flips the dataset status to {@code PROCESSING}, returns 202 to the
 * caller, then streams the file element-by-element on the {@code boundedElastic}
 * scheduler, flushing batches through {@link DatasetItemService#saveBatch(UUID, List)}.
 *
 * <p>JSON files must contain a top-level array of objects. JSONL files contain one JSON
 * object per non-blank line.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class JsonDatasetItemProcessor {

    private static final String FORMAT_LABEL = "JSON";

    private static final String RESERVED_ID = "id";
    private static final String RESERVED_SOURCE = "source";
    private static final String RESERVED_DESCRIPTION = "description";
    private static final String RESERVED_TAGS = "tags";
    private static final String RESERVED_EVALUATORS = "evaluators";
    private static final String RESERVED_EXECUTION_POLICY_SNAKE = "execution_policy";
    private static final String RESERVED_EXECUTION_POLICY_CAMEL = "executionPolicy";

    private final @NonNull DatasetItemUploadSupport uploadSupport;
    private final @NonNull IdGenerator idGenerator;

    /**
     * Buffers an uploaded JSON/JSONL file, validates the head, verifies that the
     * dataset exists, then processes the remainder asynchronously.
     *
     * @param inputStream    file input stream from the multipart upload
     * @param datasetId      dataset to write items to
     * @param workspaceId    workspace UUID
     * @param userName       user name
     * @param visibility     visibility setting
     * @param format         file format ({@code JSON} array or {@code JSONL})
     * @throws BadRequestException                       if buffering fails or the head fails shape validation
     * @throws jakarta.ws.rs.NotFoundException           if the dataset does not exist in the workspace
     */
    public void processUploadedJson(InputStream inputStream, UUID datasetId, String workspaceId,
            String userName, Visibility visibility, @NonNull JsonUploadFormat format) {
        log.info("Processing JSON upload for dataset '{}' on workspaceId '{}', format '{}'",
                datasetId, workspaceId, format);

        Path tempFile;
        try {
            tempFile = uploadSupport.bufferToTempFile(inputStream, "json-upload-", ".json", FORMAT_LABEL);
        } catch (IOException e) {
            log.error("Failed to buffer JSON file to temp storage for dataset '{}'", datasetId, e);
            throw new InternalServerErrorException("Failed to process JSON file");
        }

        try {
            validateHead(tempFile, format);
            uploadSupport.verifyDatasetExists(datasetId, workspaceId, visibility);
            uploadSupport.markProcessing(datasetId, workspaceId);
        } catch (Exception e) {
            uploadSupport.deleteTempFile(tempFile);
            throw e;
        }

        log.info("Starting asynchronous JSON processing for dataset '{}' on workspaceId '{}', format '{}'",
                datasetId, workspaceId, format);
        uploadSupport.runAsync(
                processFileFromPath(tempFile, format, datasetId, workspaceId, userName, visibility),
                tempFile, datasetId, workspaceId, FORMAT_LABEL);
    }

    /**
     * Validates the file head synchronously so obvious shape errors surface as 400
     * before the request thread releases.
     */
    private void validateHead(Path tempFile, JsonUploadFormat format) {
        ObjectMapper mapper = JsonUtils.getMapper();
        try (BOMInputStream bomStream = BOMInputStream.builder()
                .setInputStream(Files.newInputStream(tempFile))
                .get();
                InputStreamReader reader = new InputStreamReader(bomStream, StandardCharsets.UTF_8)) {

            if (format == JsonUploadFormat.JSON) {
                try (JsonParser parser = mapper.getFactory().createParser(reader)) {
                    JsonToken first = parser.nextToken();
                    if (first == null) {
                        throw new BadRequestException("JSON file is empty");
                    }
                    if (first != JsonToken.START_ARRAY) {
                        throw new BadRequestException(
                                "JSON file must contain a top-level array of objects");
                    }
                    JsonToken second = parser.nextToken();
                    if (second == JsonToken.END_ARRAY) {
                        throw new BadRequestException("JSON file contains no items");
                    }
                    if (second != JsonToken.START_OBJECT) {
                        throw new BadRequestException(
                                "JSON array must contain only objects; first element is not an object");
                    }
                }
            } else {
                try (BufferedReader br = new BufferedReader(reader)) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.isBlank()) {
                            continue;
                        }
                        JsonNode node;
                        try {
                            node = mapper.readTree(line);
                        } catch (IOException e) {
                            throw new BadRequestException(
                                    "First non-blank line is not valid JSON: %s".formatted(e.getMessage()));
                        }
                        if (node == null || !node.isObject()) {
                            throw new BadRequestException(
                                    "JSONL first non-blank line must be a JSON object");
                        }
                        return;
                    }
                    throw new BadRequestException("JSON file contains no items");
                }
            }
        } catch (IOException e) {
            log.error("Failed to validate JSON head", e);
            throw new BadRequestException("Failed to read JSON file");
        }
    }

    private Mono<Long> processFileFromPath(Path tempFile, JsonUploadFormat format, UUID datasetId, String workspaceId,
            String userName, Visibility visibility) {
        return Mono.fromCallable(() -> {
            try (BOMInputStream bomStream = BOMInputStream.builder()
                    .setInputStream(Files.newInputStream(tempFile))
                    .get();
                    InputStreamReader reader = new InputStreamReader(bomStream, StandardCharsets.UTF_8)) {

                if (format == JsonUploadFormat.JSON) {
                    return processJsonArray(reader, datasetId, workspaceId, userName, visibility);
                }
                return processJsonLines(reader, datasetId, workspaceId, userName, visibility);
            } catch (IOException e) {
                log.warn("Failed to process JSON file for dataset '{}'", datasetId, e);
                throw new BadRequestException("Failed to read JSON file");
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private long processJsonArray(InputStreamReader reader, UUID datasetId, String workspaceId,
            String userName, Visibility visibility) throws IOException {
        ObjectMapper mapper = JsonUtils.getMapper();
        try (JsonParser parser = mapper.getFactory().createParser(reader)) {
            JsonToken first = parser.nextToken();
            if (first != JsonToken.START_ARRAY) {
                throw new BadRequestException("JSON file must contain a top-level array of objects");
            }

            int logFrequency = uploadSupport.getLogFrequency();
            BatchAccumulator accumulator = uploadSupport.newBatchAccumulator(datasetId, workspaceId, userName,
                    visibility);
            long rowIndex = 0;

            JsonToken token;
            while ((token = parser.nextToken()) != null && token != JsonToken.END_ARRAY) {
                if (token != JsonToken.START_OBJECT) {
                    throw new BadRequestException(
                            "JSON array element at index %d is not an object".formatted(rowIndex));
                }
                rowIndex++;
                JsonNode node = mapper.readTree(parser);
                if (!node.isObject()) {
                    throw new BadRequestException(
                            "JSON array element at index %d is not an object".formatted(rowIndex - 1));
                }

                if (rowIndex % logFrequency == 0) {
                    log.debug("Processing element '{}' for dataset '{}'", rowIndex, datasetId);
                }

                accumulator.add(buildDatasetItem((ObjectNode) node, rowIndex));
            }

            if (rowIndex == 0) {
                throw new BadRequestException("JSON file contains no items");
            }

            long totalProcessed = accumulator.finish();
            log.info("Completed JSON array processing for dataset '{}', total items: '{}', batches: '{}'",
                    datasetId, totalProcessed, accumulator.batchNumber());
            return totalProcessed;
        }
    }

    private long processJsonLines(InputStreamReader reader, UUID datasetId, String workspaceId,
            String userName, Visibility visibility) throws IOException {
        ObjectMapper mapper = JsonUtils.getMapper();
        int logFrequency = uploadSupport.getLogFrequency();
        BatchAccumulator accumulator = uploadSupport.newBatchAccumulator(datasetId, workspaceId, userName, visibility);
        long lineNumber = 0;
        long rowIndex = 0;

        try (BufferedReader br = new BufferedReader(reader)) {
            String line;
            while ((line = br.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }

                JsonNode node;
                try {
                    node = mapper.readTree(line);
                } catch (IOException e) {
                    throw new BadRequestException(
                            "JSONL line %d is not valid JSON: %s".formatted(lineNumber, e.getMessage()));
                }
                if (node == null || !node.isObject()) {
                    throw new BadRequestException(
                            "JSONL line %d is not a JSON object".formatted(lineNumber));
                }

                rowIndex++;
                if (rowIndex % logFrequency == 0) {
                    log.debug("Processing line '{}' for dataset '{}'", lineNumber, datasetId);
                }

                accumulator.add(buildDatasetItem((ObjectNode) node, lineNumber));
            }
        }

        if (rowIndex == 0) {
            throw new BadRequestException("JSON file contains no items");
        }

        long totalProcessed = accumulator.finish();
        log.info("Completed JSONL processing for dataset '{}', total items: '{}', batches: '{}'",
                datasetId, totalProcessed, accumulator.batchNumber());
        return totalProcessed;
    }

    /**
     * Extracts reserved keys from {@code node} and folds everything left into the
     * {@code data} map. Reserved keys are consumed via {@link ObjectNode#remove(String)}
     * so the residual map is automatic.
     *
     * @param rowRef 1-based row index (for JSON array) or line number (for JSONL) — used
     *               only for error messages.
     */
    private DatasetItem buildDatasetItem(ObjectNode node, long rowRef) {
        UUID id = consumeUuid(node, rowRef, RESERVED_ID);
        DatasetItemSource source = consumeSource(node, rowRef);
        String description = consumeText(node, rowRef, RESERVED_DESCRIPTION);
        Set<String> tags = consumeTags(node, rowRef);
        List<EvaluatorItem> evaluators = consumeAs(node, rowRef, RESERVED_EVALUATORS, EvaluatorItem.class);
        ExecutionPolicy executionPolicy = consumeObject(node, rowRef, ExecutionPolicy.class,
                RESERVED_EXECUTION_POLICY_SNAKE, RESERVED_EXECUTION_POLICY_CAMEL);

        Map<String, JsonNode> data = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
          data.put(entry.getKey(), entry.getValue());
        }

        if (data.isEmpty()) {
            throw new BadRequestException(
                    "Row %d has no data fields after extracting reserved keys".formatted(rowRef));
        }

        return DatasetItem.builder()
                .id(id != null ? id : idGenerator.generateId())
                .source(source != null ? source : DatasetItemSource.MANUAL)
                .description(description)
                .tags(tags)
                .evaluators(evaluators)
                .executionPolicy(executionPolicy)
                .data(data)
                .build();
    }

    private UUID consumeUuid(ObjectNode node, long rowRef, String... keys) {
        for (String key : keys) {
            if (!node.has(key)) {
                continue;
            }
            JsonNode value = node.remove(key);
            if (value == null || value.isNull()) {
                continue;
            }
            if (!value.isTextual()) {
                throw new BadRequestException(
                        "Row %d field '%s' must be a UUID string".formatted(rowRef, key));
            }
            String text = value.asText();
            if (text.isBlank()) {
                continue;
            }
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException e) {
                throw new BadRequestException(
                        "Row %d field '%s' is not a valid UUID: '%s'".formatted(rowRef, key, text));
            }
        }
        return null;
    }

    private DatasetItemSource consumeSource(ObjectNode node, long rowRef) {
        if (!node.has(RESERVED_SOURCE)) {
            return null;
        }
        JsonNode value = node.remove(RESERVED_SOURCE);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new BadRequestException(
                    "Row %d field 'source' must be a string".formatted(rowRef));
        }
        try {
            return DatasetItemSource.fromString(value.asText());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(
                    "Row %d field 'source' has unsupported value: '%s'".formatted(rowRef, value.asText()));
        }
    }

    private String consumeText(ObjectNode node, long rowRef, String key) {
        if (!node.has(key)) {
            return null;
        }
        JsonNode value = node.remove(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new BadRequestException(
                    "Row %d field '%s' must be a string".formatted(rowRef, key));
        }
        return value.asText();
    }

    private Set<String> consumeTags(ObjectNode node, long rowRef) {
        if (!node.has(RESERVED_TAGS)) {
            return null;
        }
        JsonNode value = node.remove(RESERVED_TAGS);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isArray()) {
            throw new BadRequestException(
                    "Row %d field 'tags' must be an array of strings".formatted(rowRef));
        }
        Set<String> tags = new LinkedHashSet<>();
        for (JsonNode element : value) {
            if (!element.isTextual()) {
                throw new BadRequestException(
                        "Row %d field 'tags' must contain only strings".formatted(rowRef));
            }
            tags.add(element.asText());
        }
        return tags;
    }

    private <T> List<T> consumeAs(ObjectNode node, long rowRef, String key, Class<T> elementType) {
        if (!node.has(key)) {
            return null;
        }
        JsonNode value = node.remove(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isArray()) {
            throw new BadRequestException(
                    "Row %d field '%s' must be an array".formatted(rowRef, key));
        }
        ObjectMapper mapper = JsonUtils.getMapper();
        CollectionType collectionType = mapper.getTypeFactory().constructCollectionType(List.class, elementType);
        try {
            return mapper.treeToValue(value, collectionType);
        } catch (Exception e) {
            throw new BadRequestException(
                    "Row %d field '%s' could not be parsed: %s".formatted(rowRef, key, e.getMessage()));
        }
    }

    private <T> T consumeObject(ObjectNode node, long rowRef, Class<T> type, String... keys) {
        for (String key : keys) {
            if (!node.has(key)) {
                continue;
            }
            JsonNode value = node.remove(key);
            if (value == null || value.isNull()) {
                continue;
            }
            try {
                return JsonUtils.getMapper().treeToValue(value, type);
            } catch (Exception e) {
                throw new BadRequestException(
                        "Row %d field '%s' could not be parsed: %s".formatted(rowRef, key, e.getMessage()));
            }
        }
        return null;
    }
}
