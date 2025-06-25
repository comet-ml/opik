package com.comet.opik.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
public class OpenAPISchemaCleanup {

    private static final ObjectMapper yamlMapper = new ObjectMapper(
            new YAMLFactory()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                    .enable(YAMLGenerator.Feature.INDENT_ARRAYS_WITH_INDICATOR));

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            log.error("Usage: OpenAPISchemaCleanup <input-file> <output-file>");
            System.exit(1);
        }

        String inputFile = args[0];
        String outputFile = args[1];

        cleanUnusedSchemas(inputFile, outputFile);
    }

    public static void cleanUnusedSchemas(String inputFile, String outputFile) throws Exception {
        // Read the YAML file directly using Jackson
        JsonNode openApiDoc = yamlMapper.readTree(new File(inputFile));

        if (openApiDoc == null || !openApiDoc.has("components") ||
                !openApiDoc.get("components").has("schemas")) {
            log.info("No schemas found in OpenAPI spec");
            return;
        }

        JsonNode schemas = openApiDoc.get("components").get("schemas");
        Set<String> allSchemas = new HashSet<>();
        schemas.fieldNames().forEachRemaining(allSchemas::add);

        log.info("Found {} schemas in total", allSchemas.size());

        Set<String> removedSchemas = new HashSet<>();

        // Iteratively remove unused schemas until no more can be removed
        boolean foundUnused;
        int iterations = 0;
        do {
            iterations++;
            foundUnused = false;

            // Get currently referenced schemas (excluding already removed ones)
            Set<String> currentlyReferenced = getCurrentlyReferencedSchemas(openApiDoc, removedSchemas);

            // Find schemas that are defined but not referenced
            Set<String> currentlyDefined = allSchemas.stream()
                    .filter(schema -> !removedSchemas.contains(schema))
                    .collect(Collectors.toSet());

            Set<String> unusedInThisIteration = new HashSet<>(currentlyDefined);
            unusedInThisIteration.removeAll(currentlyReferenced);

            if (!unusedInThisIteration.isEmpty()) {
                foundUnused = true;
                removedSchemas.addAll(unusedInThisIteration);

                log.info("🧹 Iteration {} - Found {} unused schemas", iterations, unusedInThisIteration.size());
                unusedInThisIteration.forEach(schema -> log.debug("  - Removing: {}", schema));
            }

        } while (foundUnused && iterations < 10); // Safety limit

        // Actually remove the schemas from the document
        if (!removedSchemas.isEmpty()) {
            ObjectNode schemasNode = (ObjectNode) schemas;
            removedSchemas.forEach(schemasNode::remove);
        }

        if (removedSchemas.isEmpty()) {
            log.info("✅ No unused schemas found");
        } else {
            log.info("✅ Total removed: {} schemas in {} iterations", removedSchemas.size(), iterations);
            log.debug("📋 Final removed schemas: {}", String.join(", ", removedSchemas));
        }

        // Write the cleaned spec maintaining original formatting
        try (FileWriter writer = new FileWriter(outputFile)) {
            yamlMapper.writeValue(writer, openApiDoc);
            log.info("✅ Cleaned OpenAPI spec written to: {}", outputFile);
        } catch (Exception e) {
            log.error("❌ Failed to write cleaned OpenAPI spec to: {}", outputFile, e);
            throw e;
        }
    }

    /**
     * Get all schemas that are currently referenced, excluding schemas that have been marked for removal
     */
    private static Set<String> getCurrentlyReferencedSchemas(JsonNode openApiDoc, Set<String> removedSchemas) {
        Set<String> referenced = new HashSet<>();

        // Check paths and operations
        JsonNode paths = openApiDoc.get("paths");
        if (paths != null) {
            paths.fields().forEachRemaining(pathEntry -> {
                JsonNode pathItem = pathEntry.getValue();

                // Check all HTTP methods
                Arrays.asList("get", "post", "put", "delete", "patch", "head", "options", "trace")
                        .forEach(method -> {
                            JsonNode operation = pathItem.get(method);
                            if (operation != null) {
                                scanOperation(operation, referenced, removedSchemas);
                            }
                        });
            });
        }

        // Check component schemas for cross-references (excluding removed schemas)
        JsonNode components = openApiDoc.get("components");
        if (components != null && components.has("schemas")) {
            JsonNode schemas = components.get("schemas");
            schemas.fields().forEachRemaining(schemaEntry -> {
                String schemaName = schemaEntry.getKey();
                JsonNode schema = schemaEntry.getValue();

                // Only scan schemas that haven't been marked for removal
                if (!removedSchemas.contains(schemaName)) {
                    scanSchema(schema, referenced, removedSchemas);
                }
            });
        }

        // Check other components
        scanOtherComponents(openApiDoc, referenced, removedSchemas);

        return referenced;
    }

    private static void scanOperation(JsonNode operation, Set<String> referenced, Set<String> removedSchemas) {
        // Request body
        JsonNode requestBody = operation.get("requestBody");
        if (requestBody != null) {
            JsonNode content = requestBody.get("content");
            if (content != null) {
                content.fields().forEachRemaining(mediaTypeEntry -> {
                    JsonNode mediaType = mediaTypeEntry.getValue();
                    JsonNode schema = mediaType.get("schema");
                    if (schema != null) {
                        scanSchema(schema, referenced, removedSchemas);
                    }
                });
            }
        }

        // Responses
        JsonNode responses = operation.get("responses");
        if (responses != null) {
            responses.fields().forEachRemaining(responseEntry -> {
                JsonNode response = responseEntry.getValue();
                JsonNode content = response.get("content");
                if (content != null) {
                    content.fields().forEachRemaining(mediaTypeEntry -> {
                        JsonNode mediaType = mediaTypeEntry.getValue();
                        JsonNode schema = mediaType.get("schema");
                        if (schema != null) {
                            scanSchema(schema, referenced, removedSchemas);
                        }
                    });
                }
            });
        }

        // Parameters
        JsonNode parameters = operation.get("parameters");
        if (parameters != null && parameters.isArray()) {
            parameters.forEach(parameter -> {
                JsonNode schema = parameter.get("schema");
                if (schema != null) {
                    scanSchema(schema, referenced, removedSchemas);
                }
            });
        }
    }

    private static void scanSchema(JsonNode schema, Set<String> referenced, Set<String> removedSchemas) {
        if (schema == null) return;

        // Direct reference
        JsonNode ref = schema.get("$ref");
        if (ref != null && ref.isTextual()) {
            String refValue = ref.asText();
            if (refValue.startsWith("#/components/schemas/")) {
                String schemaName = extractSchemaName(refValue);
                // Only add to referenced if it hasn't been marked for removal
                if (!removedSchemas.contains(schemaName)) {
                    referenced.add(schemaName);
                }
            }
            return;
        }

        // allOf, oneOf, anyOf
        JsonNode allOf = schema.get("allOf");
        if (allOf != null && allOf.isArray()) {
            allOf.forEach(s -> scanSchema(s, referenced, removedSchemas));
        }

        JsonNode oneOf = schema.get("oneOf");
        if (oneOf != null && oneOf.isArray()) {
            oneOf.forEach(s -> scanSchema(s, referenced, removedSchemas));
        }

        JsonNode anyOf = schema.get("anyOf");
        if (anyOf != null && anyOf.isArray()) {
            anyOf.forEach(s -> scanSchema(s, referenced, removedSchemas));
        }

        // Properties
        JsonNode properties = schema.get("properties");
        if (properties != null) {
            properties.fields()
                    .forEachRemaining(propEntry -> scanSchema(propEntry.getValue(), referenced, removedSchemas));
        }

        // Array items
        JsonNode items = schema.get("items");
        if (items != null) {
            scanSchema(items, referenced, removedSchemas);
        }

        // Additional properties
        JsonNode additionalProperties = schema.get("additionalProperties");
        if (additionalProperties != null && additionalProperties.isObject()) {
            scanSchema(additionalProperties, referenced, removedSchemas);
        }

        // Handle discriminator mapping
        JsonNode discriminator = schema.get("discriminator");
        if (discriminator != null) {
            JsonNode mapping = discriminator.get("mapping");
            if (mapping != null) {
                mapping.fields().forEachRemaining(mappingEntry -> {
                    String refValue = mappingEntry.getValue().asText();
                    if (refValue.startsWith("#/components/schemas/")) {
                        String schemaName = extractSchemaName(refValue);
                        if (!removedSchemas.contains(schemaName)) {
                            referenced.add(schemaName);
                        }
                    }
                });
            }
        }
    }

    private static void scanOtherComponents(JsonNode openApiDoc, Set<String> referenced, Set<String> removedSchemas) {
        JsonNode components = openApiDoc.get("components");
        if (components == null) return;

        // Parameters
        JsonNode parameters = components.get("parameters");
        if (parameters != null) {
            parameters.fields().forEachRemaining(paramEntry -> {
                JsonNode parameter = paramEntry.getValue();
                JsonNode schema = parameter.get("schema");
                if (schema != null) {
                    scanSchema(schema, referenced, removedSchemas);
                }
            });
        }

        // Responses
        JsonNode responses = components.get("responses");
        if (responses != null) {
            responses.fields().forEachRemaining(responseEntry -> {
                JsonNode response = responseEntry.getValue();
                JsonNode content = response.get("content");
                if (content != null) {
                    content.fields().forEachRemaining(mediaTypeEntry -> {
                        JsonNode mediaType = mediaTypeEntry.getValue();
                        JsonNode schema = mediaType.get("schema");
                        if (schema != null) {
                            scanSchema(schema, referenced, removedSchemas);
                        }
                    });
                }
            });
        }

        // Request bodies
        JsonNode requestBodies = components.get("requestBodies");
        if (requestBodies != null) {
            requestBodies.fields().forEachRemaining(requestBodyEntry -> {
                JsonNode requestBody = requestBodyEntry.getValue();
                JsonNode content = requestBody.get("content");
                if (content != null) {
                    content.fields().forEachRemaining(mediaTypeEntry -> {
                        JsonNode mediaType = mediaTypeEntry.getValue();
                        JsonNode schema = mediaType.get("schema");
                        if (schema != null) {
                            scanSchema(schema, referenced, removedSchemas);
                        }
                    });
                }
            });
        }

        // Headers
        JsonNode headers = components.get("headers");
        if (headers != null) {
            headers.fields().forEachRemaining(headerEntry -> {
                JsonNode header = headerEntry.getValue();
                JsonNode schema = header.get("schema");
                if (schema != null) {
                    scanSchema(schema, referenced, removedSchemas);
                }
            });
        }
    }

    private static String extractSchemaName(String ref) {
        return ref.substring(ref.lastIndexOf("/") + 1);
    }
}