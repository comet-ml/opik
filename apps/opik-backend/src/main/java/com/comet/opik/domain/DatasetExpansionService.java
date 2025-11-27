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
import java.util.Set;
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
        log.info("Starting dataset expansion for datasetId '{}', workspaceId '{}', sampleCount: '{}'",
                datasetId, workspaceId, request.sampleCount());
        // Get existing dataset items to analyze
        var searchCriteria = DatasetItemSearchCriteria.builder()
                .datasetId(datasetId)
                .experimentIds(Set.of()) // Empty set for no experiment filtering
                .entityType(EntityType.TRACE)
                .filters(null) // No filters needed
                .truncate(false)
                .versionHashOrTag(null) // Get draft items
                .build();

        var existingItems = datasetItemService.getItems(1, 10, searchCriteria)
                .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, requestContext.get().getUserName())
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();
        if (CollectionUtils.isEmpty(existingItems.content())) {
            throw new BadRequestException("Cannot expand empty dataset. Add at least one sample first");
        }

        // Use custom prompt if provided, otherwise build default prompt
        var generationPrompt = StringUtils.isNotBlank(request.customPrompt())
                ? request.customPrompt().trim()
                : buildGenerationPrompt(existingItems.content(), request);
        // Generate samples using LLM with batch processing for large requests
        var generatedSamples = generateSamplesInBatches(generationPrompt, request, datasetId, workspaceId);
        log.info("Finished dataset expansion for datasetId '{}', workspaceId '{}', total samples '{}'",
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

    private List<DatasetItem> generateSamplesInBatches(
            String basePrompt, DatasetExpansion request, UUID datasetId, String workspaceId) {
        var allSamples = new ArrayList<DatasetItem>();
        var totalSamples = request.sampleCount();
        var batchSize = Math.min(20, totalSamples); // Process in batches of up to 20
        var remainingSamples = totalSamples;
        var batchNumber = 1;

        while (remainingSamples > 0) {
            var currentBatchSize = Math.min(batchSize, remainingSamples);

            // Create batch-specific prompt
            String batchPrompt;
            if (StringUtils.isNotBlank(request.customPrompt())) {
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

            log.info("Processing batch '{}/{}' - generating '{}' samples",
                    batchNumber, (int) Math.ceil((double) totalSamples / batchSize), currentBatchSize);

            // Generate samples for this batch
            var batchSamples = generateSamples(batchPrompt,
                    DatasetExpansion.builder()
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

    private List<DatasetItem> generateSamples(
            String prompt, DatasetExpansion request, UUID datasetId, String workspaceId) {
        try {
            // Create chat completion request, request should handle most models including reasoning models like GPT-5, Sonnet, etc.
            var chatRequest = ChatCompletionRequest.builder()
                    .model(request.model())
                    .addUserMessage(prompt)
                    .temperature(1.0) // Set temperature to 1.0 for consistent output
                    .maxCompletionTokens(4000)
                    .stream(false) // Non-streaming request for dataset expansion
                    .build();

            // Call LLM
            var response = chatCompletionService.create(chatRequest, workspaceId);

            var generatedContent = response.choices().get(0).message().content();
            log.debug("LLM generated content length: '{}' characters", generatedContent.length());

            // Parse the JSON response
            var parsedSamples = parseGeneratedSamples(
                    generatedContent, datasetId, request.model(), request.sampleCount());
            log.debug("Parsed '{}' samples from LLM response", parsedSamples.size());
            return parsedSamples;

        } catch (Exception exception) {
            log.error("Failed to generate samples using LLM", exception);
            // If it's already a RuntimeException with a detailed message, preserve it
            if (exception instanceof BadRequestException && exception.getMessage().contains("AI model")) {
                throw exception;
            }
            throw new BadRequestException("Failed to generate synthetic samples", exception);
        }
    }

    private List<DatasetItem> parseGeneratedSamples(
            String generatedContent, UUID datasetId, String model, int requestedSampleCount) {
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

            // Additional cleaning for common LLM response patterns
            if (cleanedContent.startsWith("Here") || cleanedContent.startsWith("I'll")) {
                // Look for the first '[' to find the actual JSON array
                int firstBracket = cleanedContent.indexOf('[');
                if (firstBracket != -1) {
                    cleanedContent = cleanedContent.substring(firstBracket);
                }
            }

            // Log the actual content we're trying to parse for debugging
            log.debug("Attempting to parse LLM response. Content length: '{}'", cleanedContent.length());
            log.debug("Content to parse: '{}'",
                    cleanedContent.length() > 500 ? cleanedContent.substring(0, 500) + "..." : cleanedContent);

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
            } else if (rootNode.isObject()) {
                // Handle case where LLM returns a single object instead of array
                log.warn("LLM returned single object instead of array, wrapping in array");
                var dataNode = (ObjectNode) rootNode;
                dataNode.put("_generated", true);
                dataNode.put("_generation_model", model);

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
            } else {
                throw new BadRequestException(
                        "Expected JSON array or object, but got: '%s'".formatted(rootNode.getNodeType()));
            }

            // Check if we got any samples
            if (samples.isEmpty()) {
                throw new BadRequestException(
                        "No valid samples found in the AI response. The response may be empty or contain only invalid data structures");
            }

            // Limit to the requested number of samples even if LLM provided more
            if (samples.size() > requestedSampleCount) {
                log.info("LLM generated '{}' samples but only '{}' were requested. Limiting to requested count.",
                        samples.size(), requestedSampleCount);
                samples = samples.subList(0, requestedSampleCount);
            }

            return samples;

        } catch (Exception exception) {
            log.error("Failed to parse generated samples", exception);

            // Log the raw content for debugging
            log.error("Raw LLM response that failed to parse: '{}'",
                    generatedContent.length() > 2000 ? generatedContent.substring(0, 2000) + "..." : generatedContent);

            // Provide detailed user-friendly error messages
            var userMessage = buildUserFriendlyErrorMessage(exception, generatedContent);
            throw new BadRequestException(userMessage, exception);
        }
    }

    private String buildUserFriendlyErrorMessage(Exception e, String generatedContent) {
        // Check the type of error and provide specific guidance
        if (e instanceof com.fasterxml.jackson.core.JsonParseException) {
            com.fasterxml.jackson.core.JsonParseException jsonError = (com.fasterxml.jackson.core.JsonParseException) e;
            return String.format("The AI model returned invalid JSON at line %d, column %d: %s. " +
                    "This often happens when the model includes explanatory text before or after the JSON. " +
                    "Try using a different model or a custom prompt that emphasizes returning only valid JSON.",
                    jsonError.getLocation().getLineNr(),
                    jsonError.getLocation().getColumnNr(),
                    jsonError.getOriginalMessage());
        }

        if (e instanceof com.fasterxml.jackson.core.io.JsonEOFException) {
            return "The AI model's response was cut off before completing the JSON structure. " +
                    "This usually means the response hit the model's token limit. " +
                    "Try reducing the number of samples requested, using a simpler data structure, or using a model with a higher token limit.";
        }

        if (e instanceof com.fasterxml.jackson.databind.JsonMappingException) {
            return "The AI model returned JSON with an unexpected structure that couldn't be processed. " +
                    "The model may not have followed the exact format from your dataset examples. " +
                    "Try using a custom prompt with more specific formatting instructions.";
        }

        // Check if the response looks like it contains explanation text
        if (generatedContent.contains("Here") || generatedContent.contains("I'll") ||
                generatedContent.contains("explanation") || generatedContent.contains("generate")) {
            return "The AI model included explanatory text instead of returning pure JSON. " +
                    "This model may need more specific instructions. " +
                    "Try a custom prompt that ends with 'Return ONLY the JSON array, no explanation or additional text.'";
        }

        // Check if response is empty or very short
        if (generatedContent.trim().length() < 10) {
            return "The AI model returned an empty or very short response. " +
                    "This may indicate the model encountered an error or the prompt was unclear. " +
                    "Try rephrasing your request or using a different model.";
        }

        // Generic fallback with the original error
        return String.format("Unable to parse the AI model's response: %s. " +
                "The response format may not match expectations. " +
                "You can try using fewer samples, a different model, or a custom prompt with specific formatting requirements.",
                e.getMessage());
    }
}
