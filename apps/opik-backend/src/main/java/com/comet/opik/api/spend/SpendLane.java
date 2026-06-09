package com.comet.opik.api.spend;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Catalog of token-flow lanes for the spend composition (Sankey). Each lane maps to a token total
 * extracted from the trace-level {@code metadata.cc.*} schema emitted by the coding-agent plugin.
 *
 * <p>Lanes flagged with a breakdown also expose a per-item detail (drawer), backed by a
 * {@code cc.*} array (e.g. {@code cc.skills.loaded[]}, {@code cc.tool_results.by_tool[]}).
 *
 * <p>The output-side tool-call lanes (built-in / MCP / skill invocations) are sourced from span-level
 * {@code cc.llm_call} block attribution and are added in a follow-up; they are intentionally not part
 * of this trace-level catalog.
 */
@Getter
@RequiredArgsConstructor
public enum SpendLane {

    PRIOR_ASSISTANT("prior_assistant", "Prior assistant context", Side.INPUT,
            "JSONExtractInt(metadata, 'cc', 'prior_assistant', 'summary', 'total_tokens')",
            null, null, null, null),
    TOOL_RESULTS("tool_results", "Tool results", Side.INPUT,
            "JSONExtractInt(metadata, 'cc', 'tool_results', 'summary', 'total_tokens')",
            "'cc', 'tool_results', 'by_tool'", "name", "tokens", "Tokens returned by each tool"),
    USER_PROMPTS("user_prompts", "User prompts", Side.INPUT,
            "JSONExtractInt(metadata, 'cc', 'user_prompts', 'summary', 'total_tokens')",
            null, null, null, null),
    SKILLS_LOADED("skills_loaded", "Skills loaded", Side.INPUT,
            "JSONExtractInt(metadata, 'cc', 'skills', 'summary', 'loaded_tokens')",
            "'cc', 'skills', 'loaded'", "name", "body_tokens", "Tokens per loaded skill body"),
    MCP_SERVERS("mcp_servers", "MCP servers", Side.INPUT,
            "JSONExtractInt(metadata, 'cc', 'tools', 'summary', 'by_source', 'mcp', 'schema_tokens')",
            "'cc', 'tools', 'summary', 'by_server'", "server", "schema_tokens",
            "Schema overhead per MCP server"),
    FILE_ATTACHMENTS("file_attachments", "File attachments", Side.INPUT,
            "JSONExtractInt(metadata, 'cc', 'file_attachments', 'summary', 'total_tokens')",
            "'cc', 'file_attachments', 'files'", "path", "body_tokens", "Tokens per attached file"),
    STATIC_OVERHEAD("static_overhead", "Static overhead", Side.INPUT,
            "(JSONExtractInt(metadata, 'cc', 'memory', 'summary', 'total_tokens')"
                    + " + JSONExtractInt(metadata, 'cc', 'skills', 'summary', 'menu_tokens')"
                    + " + JSONExtractInt(metadata, 'cc', 'tools', 'summary', 'by_source', 'builtin', 'schema_tokens'))",
            "'cc', 'memory', 'files'", "path", "body_tokens", "Tokens per memory file"),

    THINKING("thinking", "Thinking", Side.OUTPUT,
            "JSONExtractInt(metadata, 'cc', 'thinking', 'summary', 'total_tokens')",
            "'cc', 'thinking', 'by_model'", "model", "tokens", "Thinking tokens per model"),
    ASSISTANT_TEXT("assistant_text", "Assistant text", Side.OUTPUT,
            "JSONExtractInt(metadata, 'cc', 'assistant_text', 'summary', 'total_tokens')",
            null, null, null, null);

    private final String key;
    private final String label;
    private final Side side;
    private final String tokenExpression;
    private final String breakdownArrayPath;
    private final String breakdownLabelField;
    private final String breakdownTokenField;
    private final String breakdownSubtitle;

    public boolean hasBreakdown() {
        return breakdownArrayPath != null;
    }

    public enum Side {
        INPUT,
        OUTPUT
    }

    public static List<SpendLane> bySide(Side side) {
        return Arrays.stream(values()).filter(lane -> lane.side == side).toList();
    }

    public static Optional<SpendLane> fromKey(String key) {
        return Arrays.stream(values()).filter(lane -> lane.key.equals(key)).findFirst();
    }
}
