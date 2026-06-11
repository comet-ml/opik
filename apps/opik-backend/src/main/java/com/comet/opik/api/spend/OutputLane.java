package com.comet.opik.api.spend;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

@Getter
@RequiredArgsConstructor
public enum OutputLane {

    THINKING("thinking", "Thinking", "Output tokens per model"),
    ASSISTANT_TEXT("assistant_text", "Assistant text", "Output tokens per model"),
    BUILT_IN_TOOL_CALLS("built_in_tool_calls", "Built-in tool calls", "Output tokens per tool"),
    MCP_TOOL_CALLS("mcp_tool_calls", "MCP tool calls", "Output tokens per MCP server"),
    SKILL_INVOCATIONS("skill_invocations", "Skill invocations", "Output tokens per skill");

    private final String key;
    private final String label;
    private final String breakdownSubtitle;

    public boolean hasBreakdown() {
        return true;
    }

    public static Optional<OutputLane> fromKey(String key) {
        return Arrays.stream(values()).filter(lane -> lane.key.equals(key)).findFirst();
    }
}
