package com.comet.opik.infrastructure.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** The opik-inference v1 contract vendored from its {@code api/v1.json}, and the fixtures that follow it. */
@UtilityClass
public class InferenceContract {

    public static final String DECIDE = "POST /v1/decide";
    public static final String BATCH_DECIDE = "POST /v1/batch/decide";
    public static final String BATCH_GENERATE = "POST /v1/batch/generate";
    public static final String GET_JOB = "GET /v1/batch/jobs/{job_id}";
    public static final String JOBS_STATUS = "POST /v1/batch/jobs/status";
    public static final String CANCEL_JOB = "DELETE /v1/batch/jobs/{job_id}";

    private static final String DIRECTORY = "ai-labels/inference/";
    private static final JsonSchemaFactory SCHEMA_FACTORY = JsonSchemaFactory
            .getInstance(SpecVersion.VersionFlag.V202012);

    public static final JsonNode CONTRACT = resource("v1.json");

    public static JsonNode resource(String name) {
        try (var input = InferenceContract.class.getClassLoader().getResourceAsStream(DIRECTORY + name)) {
            if (input == null) {
                throw new IllegalArgumentException("missing test resource '%s'".formatted(name));
            }
            return InferenceJson.MAPPER.readTree(input);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public static String resourceText(String name) {
        try (var input = InferenceContract.class.getClassLoader().getResourceAsStream(DIRECTORY + name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** {@code part} is {@code request} or {@code response}. */
    public static Set<ValidationMessage> validateEndpoint(String endpoint, String part, JsonNode instance) {
        return SCHEMA_FACTORY.getSchema(CONTRACT.path("endpoints").path(endpoint).path(part)).validate(instance);
    }

    public static Set<ValidationMessage> validateErrorEnvelope(JsonNode instance) {
        return SCHEMA_FACTORY.getSchema(CONTRACT.path("errors").path("envelope")).validate(instance);
    }
}
