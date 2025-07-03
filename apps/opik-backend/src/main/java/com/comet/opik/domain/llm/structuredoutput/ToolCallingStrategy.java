package com.comet.opik.domain.llm.structuredoutput;

import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchema;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import jakarta.validation.constraints.NotNull;
import lombok.NonNull;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ToolCallingStrategy implements StructuredOutputStrategy {

    static final String SCORE_FIELD_NAME = "score";
    static final String REASON_FIELD_NAME = "reason";
    private static final String DEFAULT_SCHEMA_NAME = "scoring_schema";
    private static final String SCORE_FIELD_DESCRIPTION = "the score for ";
    private static final String REASON_FIELD_DESCRIPTION = "the reason for the score for ";

    @Override
    public ChatRequest.Builder apply(
            @NonNull ChatRequest.Builder chatRequestBuilder,
            @NonNull List<ChatMessage> messages,
            @NonNull List<LlmAsJudgeOutputSchema> schema) {
        var responseFormat = toResponseFormat(schema);
        chatRequestBuilder.responseFormat(responseFormat);

        return chatRequestBuilder;
    }

    private ResponseFormat toResponseFormat(@NotNull List<LlmAsJudgeOutputSchema> schema) {
        Map<String, JsonSchemaElement> structuredFields = schema.stream()
                .map(scoreDefinition -> Map.entry(scoreDefinition.name(),
                        JsonObjectSchema.builder()
                                .description(scoreDefinition.description())
                                .required(SCORE_FIELD_NAME, REASON_FIELD_NAME)
                                .addProperties(Map.of(
                                        SCORE_FIELD_NAME, switch (scoreDefinition.type()) {
                                            case BOOLEAN -> JsonBooleanSchema.builder()
                                                    .description(SCORE_FIELD_DESCRIPTION + scoreDefinition.name())
                                                    .build();
                                            case INTEGER -> JsonIntegerSchema.builder()
                                                    .description(SCORE_FIELD_DESCRIPTION + scoreDefinition.name())
                                                    .build();
                                            case DOUBLE -> JsonNumberSchema.builder()
                                                    .description(SCORE_FIELD_DESCRIPTION + scoreDefinition.name())
                                                    .build();
                                        },
                                        REASON_FIELD_NAME, JsonStringSchema.builder()
                                                .description(REASON_FIELD_DESCRIPTION + scoreDefinition.name())
                                                .build()))
                                .build()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        var allPropertyNames = structuredFields.keySet().stream().toList();

        var jsonObjectSchema = JsonObjectSchema.builder()
                .addProperties(structuredFields)
                .required(allPropertyNames)
                .build();

        var jsonSchema = JsonSchema.builder()
                .name(DEFAULT_SCHEMA_NAME)
                .rootElement(jsonObjectSchema)
                .build();

        return ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(jsonSchema)
                .build();
    }
}
