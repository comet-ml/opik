package com.comet.opik.infrastructure.llm.vertexai.internal;

import com.google.cloud.vertexai.api.Content;
import com.google.cloud.vertexai.api.Part;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ContentsMapper {

    static class InstructionAndContent {
        public Content systemInstruction = null;
        public List<Content> contents = new ArrayList<>();

        @Override
        public String toString() {
            return "InstructionAndContent {\n" +
                    " systemInstruction = " + systemInstruction +
                    ",\n contents = " + contents +
                    "\n}";
        }
    }

    static ContentsMapper.InstructionAndContent splitInstructionAndContent(List<ChatMessage> messages) {
        ContentsMapper.InstructionAndContent instructionAndContent = new ContentsMapper.InstructionAndContent();
        List<Part> sysInstructionParts = new ArrayList<>();

        List<ToolExecutionResultMessage> executionResultMessages = new ArrayList<>();

        for (int msgIdx = 0; msgIdx < messages.size(); msgIdx++) {
            ChatMessage message = messages.get(msgIdx);
            boolean isLastMessage = msgIdx == messages.size() - 1;

            if (message instanceof ToolExecutionResultMessage) {
                ToolExecutionResultMessage toolResult = (ToolExecutionResultMessage) message;
                if (isLastMessage) {
                    // if there's no accumulated tool results, add it right away to the list of messages
                    if (executionResultMessages.isEmpty()) {
                        instructionAndContent.contents.add(createContent(message));
                    } else { // otherwise add to the list, and create the new user message with all the tool results
                        executionResultMessages.add(toolResult);
                        instructionAndContent.contents.add(createToolExecutionResultContent(executionResultMessages));
                    }
                } else { // not the last message, so just accumulate the new tool result
                    executionResultMessages.add(toolResult);
                }
            } else {
                // if we're done with tool results and encounter a new user or AI message
                // then bundle all the tool results into a new user message
                if (!executionResultMessages.isEmpty()) {
                    instructionAndContent.contents.add(createToolExecutionResultContent(executionResultMessages));
                    executionResultMessages = new ArrayList<>();
                }

                // directly add user and AI messages to the list
                if (message instanceof UserMessage || message instanceof AiMessage) {
                    instructionAndContent.contents.add(createContent(message));
                } else if (message instanceof SystemMessage) { // save system messages separately
                    sysInstructionParts.addAll(PartsMapper.map(message));
                }
            }
        }

        // if there are system instructions, collect them together into one system instruction Content
        if (!sysInstructionParts.isEmpty()) {
            instructionAndContent.systemInstruction = Content.newBuilder()
                    .setRole("system")
                    .addAllParts(sysInstructionParts)
                    .build();
        }

        return instructionAndContent;
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
                                .collect(Collectors.toList()))
                .build();
    }
}
