package com.comet.opik.domain.llm.structuredoutput;

import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchema;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class InstructionStrategy implements StructuredOutputStrategy {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String INSTRUCTION = """

            IMPORTANT:
            You must respond with ONLY a single valid JSON object that conforms to the structure of the example below.
            Pay attention to the field names and data types (boolean, integer, double).
            Do NOT include any other text, explanation, or markdown formatting.
            The JSON object should look like this:
            %s

            Here are the descriptions for each field:
            %s""";

    @Override
    public ChatRequest.Builder apply(
            @NonNull ChatRequest.Builder chatRequestBuilder,
            @NonNull List<ChatMessage> messages,
            @NonNull List<LlmAsJudgeOutputSchema> schema) {
        if (messages.isEmpty()) {
            return chatRequestBuilder;
        }

        String instruction = INSTRUCTION.formatted(generateJsonExample(schema), generateJsonDescriptions(schema));

        // Create a mutable copy to work with
        List<ChatMessage> modifiableMessages = new ArrayList<>(messages);

        int lastUserMessageIndex = -1;
        for (int i = modifiableMessages.size() - 1; i >= 0; i--) {
            if (modifiableMessages.get(i) instanceof UserMessage) {
                lastUserMessageIndex = i;
                break;
            }
        }

        if (lastUserMessageIndex != -1) {
            UserMessage userMessage = (UserMessage) modifiableMessages.get(lastUserMessageIndex);

            // Handle both single-text and multi-content (with images/videos) messages
            UserMessage modifiedUserMessage;
            if (userMessage.contents().size() == 1 && userMessage.contents().get(0) instanceof TextContent) {
                // Simple case: single text content - use singleText()
                String newContent = userMessage.singleText() + instruction;
                modifiedUserMessage = UserMessage.from(newContent);
            } else {
                // Multi-content case: append instruction to the first text content, preserve other content
                var contentBuilder = UserMessage.builder();
                boolean instructionAdded = false;

                for (var content : userMessage.contents()) {
                    if (!instructionAdded && content instanceof TextContent textContent) {
                        // Append instruction to the first text content
                        contentBuilder.addContent(new TextContent(textContent.text() + instruction));
                        instructionAdded = true;
                    } else {
                        // Preserve other content (images, videos) as-is
                        contentBuilder.addContent(content);
                    }
                }

                // If no text content was found, add instruction as a new text content at the beginning
                if (!instructionAdded) {
                    var newContents = new ArrayList<Content>();
                    newContents.add(new TextContent(instruction));
                    newContents.addAll(userMessage.contents());
                    modifiedUserMessage = UserMessage.builder().contents(newContents).build();
                } else {
                    modifiedUserMessage = contentBuilder.build();
                }
            }

            // Remove the original user message
            modifiableMessages.remove(lastUserMessageIndex);
            // Add the modified user message to the end
            modifiableMessages.add(modifiedUserMessage);

            // Update the request builder with the new message list
            chatRequestBuilder.messages(modifiableMessages);
        }

        return chatRequestBuilder;
    }

    private String generateJsonExample(List<LlmAsJudgeOutputSchema> schema) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        for (LlmAsJudgeOutputSchema scoreDefinition : schema) {
            ObjectNode scoreNode = root.putObject(scoreDefinition.name());
            switch (scoreDefinition.type()) {
                case BOOLEAN :
                    scoreNode.put("score", true);
                    break;
                case INTEGER :
                    scoreNode.put("score", 1);
                    break;
                case DOUBLE :
                    scoreNode.put("score", 1.0);
                    break;
            }
            scoreNode.put("reason", "A brief explanation for the score.");
        }

        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error generating JSON example", e);
        }
    }

    private String generateJsonDescriptions(List<LlmAsJudgeOutputSchema> schema) {
        return schema.stream()
                .map(scoreDefinition -> String.format(
                        "- %s: %s",
                        scoreDefinition.name(),
                        scoreDefinition.description()))
                .collect(Collectors.joining("\n"));
    }
}
