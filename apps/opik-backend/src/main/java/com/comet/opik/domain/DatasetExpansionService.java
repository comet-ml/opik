package com.comet.opik.domain;

import com.comet.opik.api.DatasetExpansionRequest;
import com.comet.opik.api.DatasetExpansionResponse;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Preconditions;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class DatasetExpansionService {

    private final @NonNull ChatCompletionService chatCompletionService;
    private final @NonNull DatasetItemService datasetItemService;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull ObjectMapper objectMapper;
    private final @NonNull IdGenerator idGenerator;

    public DatasetExpansionResponse expandDataset(@NonNull UUID datasetId, @NonNull DatasetExpansionRequest request) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Starting dataset expansion for datasetId '{}', workspaceId '{}', sampleCount: {}", datasetId,
                workspaceId, request.sampleCount());

        // Validate request
        Preconditions.checkArgument(request.sampleCount() > 0 && request.sampleCount() <= 200,
                "Sample count must be between 1 and 200");
        Preconditions.checkArgument(request.model() != null && !request.model().trim().isEmpty(),
                "Model must be specified");

        // Get existing dataset items to analyze
        DatasetItem.DatasetItemPage existingItems = datasetItemService.getItems(datasetId, 1, 10, false)
                .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, requestContext.get().getUserName())
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        if (existingItems == null || CollectionUtils.isEmpty(existingItems.content())) {
            throw new IllegalArgumentException("Cannot expand empty dataset. Add at least one sample first.");
        }

        // Use custom prompt if provided, otherwise build default prompt
        String generationPrompt = request.customPrompt() != null && !request.customPrompt().trim().isEmpty()
                ? request.customPrompt().trim()
                : buildGenerationPrompt(existingItems.content(), request);

        // Generate samples using LLM with batch processing for large requests
        List<DatasetItem> generatedSamples = generateSamplesInBatches(generationPrompt, request, datasetId,
                workspaceId);

        log.info("Generated {} samples for datasetId '{}', workspaceId '{}'",
                generatedSamples.size(), datasetId, workspaceId);

        return DatasetExpansionResponse.builder()
                .generatedSamples(generatedSamples)
                .model(request.model())
                .totalGenerated(generatedSamples.size())
                .generationTime(Instant.now())
                .build();
    }

    private String buildGenerationPrompt(List<DatasetItem> existingItems, DatasetExpansionRequest request) {
        // Analyze existing items to understand structure
        List<String> exampleJsons = existingItems.stream()
                .limit(3) // Use first 3 as examples
                .map(item -> {
                    try {
                        return objectMapper.writeValueAsString(item.data());
                    } catch (Exception e) {
                        log.warn("Failed to serialize dataset item", e);
                        return "{}";
                    }
                })
                .collect(Collectors.toList());

        StringBuilder prompt = new StringBuilder();
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

        if (request.preserveFields() != null && !request.preserveFields().isEmpty()) {
            prompt.append("- Keep these fields consistent with patterns from examples: ")
                    .append(String.join(", ", request.preserveFields())).append("\n");
        }

        if (request.variationInstructions() != null && !request.variationInstructions().trim().isEmpty()) {
            prompt.append("- Additional instructions: ").append(request.variationInstructions()).append("\n");
        }

        prompt.append("\nGenerate the samples now:");

        return prompt.toString();
    }

    private List<DatasetItem> generateSamplesInBatches(String basePrompt, DatasetExpansionRequest request,
            UUID datasetId, String workspaceId) {
        List<DatasetItem> allSamples = new ArrayList<>();
        int totalSamples = request.sampleCount();
        int batchSize = Math.min(20, totalSamples); // Process in batches of up to 20
        int remainingSamples = totalSamples;
        int batchNumber = 1;

        while (remainingSamples > 0) {
            int currentBatchSize = Math.min(batchSize, remainingSamples);

            // Create batch-specific prompt
            String batchPrompt;
            if (request.customPrompt() != null && !request.customPrompt().trim().isEmpty()) {
                // For custom prompts, we need to be more flexible with batching
                // Look for number patterns and replace them, or append batch instructions
                batchPrompt = basePrompt.replaceAll("\\b" + totalSamples + "\\b", String.valueOf(currentBatchSize));
                if (!batchPrompt.contains(String.valueOf(currentBatchSize))) {
                    // If no number replacement worked, append batch instruction
                    batchPrompt = batchPrompt + "\n\nGenerate exactly " + currentBatchSize + " samples for this batch.";
                }
            } else {
                // For default prompts, use the existing replacement logic
                batchPrompt = basePrompt.replace(
                        "Generate " + totalSamples + " new dataset samples",
                        "Generate " + currentBatchSize + " new dataset samples").replace(
                                "Generate exactly " + totalSamples + " samples",
                                "Generate exactly " + currentBatchSize + " samples");
            }

            log.info("Processing batch {}/{} - generating {} samples",
                    batchNumber, (int) Math.ceil((double) totalSamples / batchSize), currentBatchSize);

            // Generate samples for this batch
            List<DatasetItem> batchSamples = generateSamples(batchPrompt,
                    DatasetExpansionRequest.builder()
                            .model(request.model())
                            .sampleCount(currentBatchSize)
                            .preserveFields(request.preserveFields())
                            .variationInstructions(request.variationInstructions())
                            .build(),
                    datasetId, workspaceId);

            allSamples.addAll(batchSamples);
            remainingSamples -= currentBatchSize;
            batchNumber++;
        }

        return allSamples;
    }

    private List<DatasetItem> generateSamples(String prompt, DatasetExpansionRequest request,
            UUID datasetId, String workspaceId) {
        try {
            // Create chat completion request
            ChatCompletionRequest chatRequest = ChatCompletionRequest.builder()
                    .model(request.model())
                    .addUserMessage(prompt)
                    .temperature(0.8) // Higher temperature for more variety
                    .maxCompletionTokens(4000)
                    .build();

            // Call LLM
            ChatCompletionResponse response = chatCompletionService.create(chatRequest, workspaceId);

            String generatedContent = response.choices().get(0).message().content();
            log.info("LLM generated content length: {} characters", generatedContent.length());
            log.info("LLM content preview: {}",
                    generatedContent.length() > 200 ? generatedContent.substring(0, 200) + "..." : generatedContent);

            // Parse the JSON response
            List<DatasetItem> parsedSamples = parseGeneratedSamples(generatedContent, datasetId, request.model(),
                    request.sampleCount());
            log.info("Parsed {} samples from LLM response", parsedSamples.size());

            // Log the actual sample data to debug empty samples
            for (int i = 0; i < parsedSamples.size() && i < 3; i++) {
                var sample = parsedSamples.get(i);
                log.info("Sample {} data: {}", i + 1, sample.data());
            }

            return parsedSamples;

        } catch (Exception e) {
            log.error("Failed to generate samples using LLM", e);
            throw new RuntimeException("Failed to generate synthetic samples: " + e.getMessage());
        }
    }

    private List<DatasetItem> parseGeneratedSamples(String generatedContent, UUID datasetId, String model,
            int requestedSampleCount) {
        try {
            // Clean the response - sometimes LLMs add markdown formatting
            String cleanedContent = generatedContent.trim();
            if (cleanedContent.startsWith("```json")) {
                cleanedContent = cleanedContent.substring(7);
            }
            if (cleanedContent.endsWith("```")) {
                cleanedContent = cleanedContent.substring(0, cleanedContent.length() - 3);
            }
            cleanedContent = cleanedContent.trim();

            // Log the actual content we're trying to parse for debugging
            log.info("Attempting to parse LLM response. Content length: {}", cleanedContent.length());
            log.info("Content to parse: {}",
                    cleanedContent.length() > 500 ? cleanedContent.substring(0, 500) + "..." : cleanedContent);

            JsonNode rootNode = objectMapper.readTree(cleanedContent);
            List<DatasetItem> samples = new ArrayList<>();

            if (rootNode.isArray()) {
                for (JsonNode sampleNode : rootNode) {
                    if (sampleNode.isObject()) {
                        ObjectNode dataNode = (ObjectNode) sampleNode;

                        // Add metadata to indicate this is synthetic
                        dataNode.put("_generated", true);
                        dataNode.put("_generation_model", model);

                        // Convert to Map for DatasetItem
                        Map<String, JsonNode> dataMap = objectMapper.convertValue(dataNode,
                                objectMapper.getTypeFactory().constructMapType(Map.class, String.class,
                                        JsonNode.class));

                        DatasetItem sample = DatasetItem.builder()
                                .id(idGenerator.generateId())
                                .datasetId(datasetId)
                                .data(dataMap)
                                .source(com.comet.opik.api.DatasetItemSource.MANUAL)
                                .build();

                        samples.add(sample);
                    }
                }
            }

            // Limit to the requested number of samples even if LLM provided more
            if (samples.size() > requestedSampleCount) {
                log.info("LLM generated {} samples but only {} were requested. Limiting to requested count.",
                        samples.size(), requestedSampleCount);
                samples = samples.subList(0, requestedSampleCount);
            }

            return samples;

        } catch (Exception e) {
            log.error("Failed to parse generated samples", e);

            // Check if it's a JSON parsing error and provide more specific error message
            if (e instanceof com.fasterxml.jackson.core.JsonParseException ||
                    e instanceof com.fasterxml.jackson.core.io.JsonEOFException) {
                throw new RuntimeException(
                        "The AI model returned malformed or incomplete JSON. This may happen if the response was too long and got truncated. Try generating fewer samples or using a custom prompt with simpler JSON structure.");
            }

            throw new RuntimeException("Failed to parse generated samples: " + e.getMessage());
        }
    }

}