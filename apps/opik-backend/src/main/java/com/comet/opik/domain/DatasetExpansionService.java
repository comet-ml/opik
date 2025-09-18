package com.comet.opik.domain;

import com.comet.opik.api.DatasetExpansion;
import com.comet.opik.api.DatasetExpansionResponse;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class DatasetExpansionService {

    private final @NonNull ChatCompletionService chatCompletionService;
    private final @NonNull DatasetItemService datasetItemService;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull ObjectMapper objectMapper;
    private final @NonNull IdGenerator idGenerator;

    public DatasetExpansionResponse expandDataset(@NonNull UUID datasetId, @NonNull DatasetExpansion request) {
        var workspaceId = requestContext.get().getWorkspaceId();
        log.info("Starting dataset expansion for datasetId: '{}', workspaceId: '{}', sampleCount: '{}'",
                datasetId, workspaceId, request.sampleCount());

        // Get existing dataset items to analyze
        var existingItems = datasetItemService.getItems(datasetId, 1, 10, false)
                .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, requestContext.get().getUserName())
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        if (CollectionUtils.isEmpty(existingItems.content())) {
            throw new BadRequestException("Cannot expand empty dataset. Add at least one sample first");
        }

        // Build generation prompt
        var generationPrompt = StringUtils.isNotBlank(request.customPrompt())
                ? request.customPrompt().trim()
                : buildGenerationPrompt(existingItems.content(), request);

        // Generate samples using LLM
        var generatedSamples = generateSamples(generationPrompt, request, datasetId, workspaceId);

        log.info("Finished dataset expansion for datasetId: '{}', workspaceId: '{}', total samples: '{}'",
                datasetId, workspaceId, generatedSamples.size());

        return DatasetExpansionResponse.builder()
                .generatedSamples(generatedSamples)
                .model(request.model())
                .totalGenerated(generatedSamples.size())
                .generationTime(Instant.now())
                .build();
    }

    private String buildGenerationPrompt(List<DatasetItem> existingItems, DatasetExpansion request) {
        // Analyze existing items to understand structure
        var exampleJsons = existingItems.stream()
                .limit(3) // Use first 3 as examples
                .map(item -> {
                    try {
                        return JsonUtils.writeValueAsString(item.data());
                    } catch (Exception exception) {
                        log.warn("Failed to serialize dataset item", exception);
                        return "{}";
                    }
                })
                .toList();

        var prompt = new StringBuilder();
        prompt.append("You are a synthetic data generator for machine learning datasets. ");
        prompt.append("Generate ").append(request.sampleCount()).append(" new dataset samples ");
        prompt.append("that follow the same JSON structure and patterns as the examples provided.\n\n");

        prompt.append("EXAMPLES:\n");
        for (int i = 0; i < exampleJsons.size(); i++) {
            prompt.append("Example ").append(i + 1).append(":\n");
            prompt.append(exampleJsons.get(i)).append("\n\n");
        }

        prompt.append("REQUIREMENTS:\n");
        prompt.append("- Generate exactly ").append(request.sampleCount()).append(" samples\n");
        prompt.append("- Maintain the exact same JSON structure as the examples\n");
        prompt.append("- Create realistic and diverse variations of the data\n");
        prompt.append("- Return ONLY a JSON array of the generated samples, no additional text\n");

        if (CollectionUtils.isNotEmpty(request.preserveFields())) {
            prompt.append("- Keep these fields consistent with patterns from examples: ")
                    .append(String.join(", ", request.preserveFields())).append("\n");
        }

        if (StringUtils.isNotBlank(request.variationInstructions())) {
            prompt.append("- Additional instructions: ").append(request.variationInstructions()).append("\n");
        }

        prompt.append("\nGenerate the samples now:");

        return prompt.toString();
    }

    private List<DatasetItem> generateSamples(
            String prompt, DatasetExpansion request, UUID datasetId, String workspaceId) {
        try {
            // Create chat completion request
            var chatRequest = ChatCompletionRequest.builder()
                    .model(request.model())
                    .addUserMessage(prompt)
                    .temperature(1.0)
                    .maxCompletionTokens(4000)
                    .build();

            // Call LLM
            var response = chatCompletionService.create(chatRequest, workspaceId);
            var generatedContent = response.choices().get(0).message().content();

            // Parse the JSON response
            return parseGeneratedSamples(generatedContent, datasetId, request.model());

        } catch (Exception exception) {
            log.error("Failed to generate samples using LLM", exception);
            throw new BadRequestException("Failed to generate synthetic samples", exception);
        }
    }

    private List<DatasetItem> parseGeneratedSamples(String generatedContent, UUID datasetId, String model) {
        try {
            // Clean the response - sometimes LLMs add markdown formatting
            String cleanedContent = generatedContent.trim();
            if (cleanedContent.startsWith("```json")) {
                cleanedContent = cleanedContent.substring(7);
            }
            if (cleanedContent.startsWith("```")) {
                cleanedContent = cleanedContent.substring(3);
            }
            if (cleanedContent.endsWith("```")) {
                cleanedContent = cleanedContent.substring(0, cleanedContent.length() - 3);
            }
            cleanedContent = cleanedContent.trim();

            var rootNode = JsonUtils.getJsonNodeFromString(cleanedContent);
            List<DatasetItem> samples = new ArrayList<>();

            if (rootNode.isArray()) {
                for (var sampleNode : rootNode) {
                    if (sampleNode.isObject()) {
                        var dataNode = (ObjectNode) sampleNode;

                        // Add metadata to indicate this is synthetic
                        dataNode.put("_generated", true);
                        dataNode.put("_generation_model", model);

                        // Convert to Map for DatasetItem
                        Map<String, JsonNode> dataMap = objectMapper.convertValue(dataNode,
                                objectMapper.getTypeFactory().constructMapType(Map.class, String.class,
                                        JsonNode.class));

                        var sample = DatasetItem.builder()
                                .id(idGenerator.generateId())
                                .datasetId(datasetId)
                                .data(dataMap)
                                .source(com.comet.opik.api.DatasetItemSource.MANUAL)
                                .build();

                        samples.add(sample);
                    }
                }
            }

            return samples;

        } catch (Exception exception) {
            log.error("Failed to parse generated samples", exception);
            throw new BadRequestException("Unable to parse AI model response", exception);
        }
    }
}
