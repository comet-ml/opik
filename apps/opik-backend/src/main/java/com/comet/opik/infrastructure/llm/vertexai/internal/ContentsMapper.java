package com.comet.opik.infrastructure.llm.vertexai.internal;

import com.google.cloud.vertexai.api.Content;
import com.google.cloud.vertexai.api.Part;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.Builder;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;

@UtilityClass
class ContentsMapper {

    @Builder(toBuilder = true)
    record InstructionAndContent(Content systemInstruction, List<Content> contents) {

        InstructionAndContent() {
            this(null, new ArrayList<>());
        }
    }

    static InstructionAndContent splitInstructionAndContent(List<ChatMessage> messages) {
        InstructionAndContent instructionAndContent = new InstructionAndContent();
        List<Part> sysInstructionParts = new ArrayList<>();

        List<ToolExecutionResultMessage> executionResultMessages = new ArrayList<>();

        for (int msgIdx = 0; msgIdx < messages.size(); msgIdx++) {
            ChatMessage message = messages.get(msgIdx);
            boolean isLastMessage = msgIdx == messages.size() - 1;

            if (message instanceof ToolExecutionResultMessage toolResult) {
                handleToolExecutionResultMessage(toolResult, isLastMessage, instructionAndContent,
                        executionResultMessages);
            } else {
                handleNonToolMessage(message, instructionAndContent, sysInstructionParts, executionResultMessages);
            }
        }

        if (!sysInstructionParts.isEmpty()) {
            instructionAndContent = buildSystemInstruction(instructionAndContent, sysInstructionParts);
        }

        return instructionAndContent;
    }

    private void handleToolExecutionResultMessage(ToolExecutionResultMessage toolResult, boolean isLastMessage,
            InstructionAndContent instructionAndContent, List<ToolExecutionResultMessage> executionResultMessages) {

        if (isLastMessage) {
            if (executionResultMessages.isEmpty()) {
                instructionAndContent.contents().add(createContent(toolResult));
            } else {
                executionResultMessages.add(toolResult);
                instructionAndContent.contents().add(createToolExecutionResultContent(executionResultMessages));
            }
        } else {
            executionResultMessages.add(toolResult);
        }
    }

    private void handleNonToolMessage(
            ChatMessage message,
            InstructionAndContent instructionAndContent,
            List<Part> sysInstructionParts,
            List<ToolExecutionResultMessage> executionResultMessages) {

        if (!executionResultMessages.isEmpty()) {
            instructionAndContent.contents().add(createToolExecutionResultContent(executionResultMessages));
            executionResultMessages.clear();
        }

        if (message instanceof UserMessage || message instanceof AiMessage) {
            instructionAndContent.contents().add(createContent(message));
        } else if (message instanceof SystemMessage) {
            sysInstructionParts.addAll(PartsMapper.map(message));
        }
    }

    private InstructionAndContent buildSystemInstruction(
            InstructionAndContent instructionAndContent,
            List<Part> sysInstructionParts) {

        return instructionAndContent.toBuilder()
                .systemInstruction(
                        Content.newBuilder()
                                .setRole("system")
                                .addAllParts(sysInstructionParts)
                                .build())
                .build();
    }

    // transform a LangChain4j ChatMessage into a Gemini Content
    private static Content createContent(ChatMessage message) {
        return Content.newBuilder()
                .setRole(RoleMapper.map(message.type()))
                .addAllParts(PartsMapper.map(message))
                .build();
    }

    // transform a list of LangChain4j tool execution results
    // into a user message made of multiple Gemini Parts
    private static Content createToolExecutionResultContent(List<ToolExecutionResultMessage> executionResultMessages) {
        return Content.newBuilder()
                .setRole(RoleMapper.map(ChatMessageType.TOOL_EXECUTION_RESULT))
                .addAllParts(
                        executionResultMessages.stream()
                                .map(PartsMapper::map)
                                .flatMap(List::stream)
                                .toList())
                .build();
    }
}
