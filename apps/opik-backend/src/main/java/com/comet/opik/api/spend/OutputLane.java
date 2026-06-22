package com.comet.opik.api.spend;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

/**
 * Output lanes of the AI Spend composition. Each lane corresponds 1:1 to a
 * cipx output block {@code category}; output blocks live in
 * {@code metadata.cipx.blocks[]} with {@code side = 'output'} on the same
 * LLM-call span as the input blocks. Per-lane breakdowns group on
 * {@code tool_name}, {@code tool_server}, or {@code resource} depending on
 * the lane.
 */
@Getter
@RequiredArgsConstructor
public enum OutputLane {

    THINKING("thinking", "Thinking", "block"),
    ASSISTANT_TEXT("assistant_text", "Assistant text", "block"),
    BUILT_IN_TOOL_CALLS("built_in_tool_calls", "Built-in tool calls", "call"),
    MCP_TOOL_CALLS("mcp_tool_calls", "MCP tool calls", "call"),
    SKILL_INVOCATIONS("skill_invocations", "Skill invocations", "call");

    private final String key;
    private final String label;
    /** Singular noun for item.count events (blocks emitted / tool calls made). */
    private final String itemUnit;

    public boolean hasBreakdown() {
        return true;
    }

    public static Optional<OutputLane> fromKey(String key) {
        return Arrays.stream(values()).filter(lane -> lane.key.equals(key)).findFirst();
    }
}
