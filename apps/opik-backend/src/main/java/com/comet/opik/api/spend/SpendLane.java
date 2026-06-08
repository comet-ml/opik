package com.comet.opik.api.spend;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.List;

/**
 * Catalog of token-flow lanes for the spend composition (Sankey). Each lane maps to a token total
 * extracted from the trace-level {@code metadata.cc.*} schema emitted by the coding-agent plugin.
 *
 * <p>The output-side tool-call lanes (built-in / MCP / skill invocations) are sourced from span-level
 * {@code cc.llm_call} block attribution and are added in a follow-up; they are intentionally not part
 * of this trace-level catalog.
 */
@Getter
@RequiredArgsConstructor
public enum SpendLane {

    PRIOR_ASSISTANT("prior_assistant", "Prior assistant context", Side.INPUT, true,
            "JSONExtractInt(metadata, 'cc', 'prior_assistant', 'summary', 'total_tokens')"),
    TOOL_RESULTS("tool_results", "Tool results", Side.INPUT, true,
            "JSONExtractInt(metadata, 'cc', 'tool_results', 'summary', 'total_tokens')"),
    USER_PROMPTS("user_prompts", "User prompts", Side.INPUT, false,
            "JSONExtractInt(metadata, 'cc', 'user_prompts', 'summary', 'total_tokens')"),
    SKILLS_LOADED("skills_loaded", "Skills loaded", Side.INPUT, true,
            "JSONExtractInt(metadata, 'cc', 'skills', 'summary', 'loaded_tokens')"),
    MCP_SERVERS("mcp_servers", "MCP servers", Side.INPUT, true,
            "JSONExtractInt(metadata, 'cc', 'tools', 'summary', 'by_source', 'mcp', 'schema_tokens')"),
    FILE_ATTACHMENTS("file_attachments", "File attachments", Side.INPUT, true,
            "JSONExtractInt(metadata, 'cc', 'file_attachments', 'summary', 'total_tokens')"),
    STATIC_OVERHEAD("static_overhead", "Static overhead", Side.INPUT, true,
            "(JSONExtractInt(metadata, 'cc', 'memory', 'summary', 'total_tokens')"
                    + " + JSONExtractInt(metadata, 'cc', 'skills', 'summary', 'menu_tokens')"
                    + " + JSONExtractInt(metadata, 'cc', 'tools', 'summary', 'by_source', 'builtin', 'schema_tokens'))"),

    THINKING("thinking", "Thinking", Side.OUTPUT, true,
            "JSONExtractInt(metadata, 'cc', 'thinking', 'summary', 'total_tokens')"),
    ASSISTANT_TEXT("assistant_text", "Assistant text", Side.OUTPUT, false,
            "JSONExtractInt(metadata, 'cc', 'assistant_text', 'summary', 'total_tokens')");

    private final String key;
    private final String label;
    private final Side side;
    private final boolean hasBreakdown;
    private final String tokenExpression;

    public enum Side {
        INPUT,
        OUTPUT
    }

    public static List<SpendLane> bySide(Side side) {
        return Arrays.stream(values()).filter(lane -> lane.side == side).toList();
    }
}
