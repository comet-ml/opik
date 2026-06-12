package com.comet.opik.api.spend;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

/**
 * Output lanes, backed by {@code cc.billing.lanes.output.items}. Item names
 * are {@code thinking}, {@code assistant_text}, or {@code <lane>/<entity>}
 * for tool calls ({@code built_in_tools/Read}, {@code mcp_servers/opik-mcp},
 * {@code skills/Skill}), so the Sankey lanes are name-prefix groups and the
 * per-lane breakdown label is the suffix after '/'. The matching ClickHouse
 * expressions live in {@code AiSpendQueryBuilder}.
 */
@Getter
@RequiredArgsConstructor
public enum OutputLane {

    THINKING("thinking", "Thinking"),
    ASSISTANT_TEXT("assistant_text", "Assistant text"),
    BUILT_IN_TOOL_CALLS("built_in_tool_calls", "Built-in tool calls"),
    MCP_TOOL_CALLS("mcp_tool_calls", "MCP tool calls"),
    SKILL_INVOCATIONS("skill_invocations", "Skill invocations");

    private final String key;
    private final String label;

    public boolean hasBreakdown() {
        return true;
    }

    public static Optional<OutputLane> fromKey(String key) {
        return Arrays.stream(values()).filter(lane -> lane.key.equals(key)).findFirst();
    }
}
