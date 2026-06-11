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
 * per-lane breakdown label is the suffix after '/'.
 */
@Getter
@RequiredArgsConstructor
public enum OutputLane {

    THINKING("thinking", "Thinking",
            "name = 'thinking'",
            "'thinking'"),
    ASSISTANT_TEXT("assistant_text", "Assistant text",
            "name = 'assistant_text'",
            "'assistant_text'"),
    BUILT_IN_TOOL_CALLS("built_in_tool_calls", "Built-in tool calls",
            "startsWith(name, 'built_in_tools/')",
            "substring(name, length('built_in_tools/') + 1)"),
    MCP_TOOL_CALLS("mcp_tool_calls", "MCP tool calls",
            "startsWith(name, 'mcp_servers/')",
            "substring(name, length('mcp_servers/') + 1)"),
    SKILL_INVOCATIONS("skill_invocations", "Skill invocations",
            "startsWith(name, 'skills/')",
            "substring(name, length('skills/') + 1)");

    private final String key;
    private final String label;
    /** Predicate over the extracted item `name` selecting this lane's items. */
    private final String namePredicate;
    /** Expression producing the breakdown label from the item `name`. */
    private final String labelExpression;

    public boolean hasBreakdown() {
        return true;
    }

    public static Optional<OutputLane> fromKey(String key) {
        return Arrays.stream(values()).filter(lane -> lane.key.equals(key)).findFirst();
    }
}
