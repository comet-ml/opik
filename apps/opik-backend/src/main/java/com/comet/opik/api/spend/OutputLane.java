package com.comet.opik.api.spend;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

@Getter
@RequiredArgsConstructor
public enum OutputLane {

    THINKING("thinking", "Thinking",
            "if(model != '', model, 'Unknown model')",
            "JSONExtractString(metadata, 'cc', 'llm_call', 'block_kind') = 'thinking'",
            "Output tokens per model"),
    ASSISTANT_TEXT("assistant_text", "Assistant text",
            "if(model != '', model, 'Unknown model')",
            "JSONExtractString(metadata, 'cc', 'llm_call', 'block_kind') = 'text'",
            "Output tokens per model"),
    BUILT_IN_TOOL_CALLS("built_in_tool_calls", "Built-in tool calls",
            "name",
            "JSONExtractString(metadata, 'cc', 'llm_call', 'block_kind') = 'tool_use'"
                    + " AND NOT startsWith(name, 'mcp__') AND name != 'Skill'",
            "Output tokens per tool"),
    MCP_TOOL_CALLS("mcp_tool_calls", "MCP tool calls",
            "if(splitByString('__', name)[2] != '', splitByString('__', name)[2], 'Unknown server')",
            "JSONExtractString(metadata, 'cc', 'llm_call', 'block_kind') = 'tool_use'"
                    + " AND startsWith(name, 'mcp__')",
            "Output tokens per MCP server"),
    SKILL_INVOCATIONS("skill_invocations", "Skill invocations",
            "if(JSONExtractString(input, 'skill') != '', JSONExtractString(input, 'skill'), 'Unknown skill')",
            "JSONExtractString(metadata, 'cc', 'llm_call', 'block_kind') = 'tool_use' AND name = 'Skill'",
            "Output tokens per skill");

    private final String key;
    private final String label;
    private final String breakdownLabelExpr;
    private final String breakdownPredicate;
    private final String breakdownSubtitle;

    public boolean hasBreakdown() {
        return breakdownLabelExpr != null;
    }

    public static Optional<OutputLane> fromKey(String key) {
        return Arrays.stream(values()).filter(lane -> lane.key.equals(key)).findFirst();
    }
}
