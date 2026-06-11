package com.comet.opik.api.spend;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

/**
 * Input lanes of the AI Spend composition, backed by the plugin's
 * {@code metadata.cc.billing} block (OPIK-6873). Every number under
 * {@code cc.billing} is a per-LLM-call billing event, so plain SUM across
 * traces is exact against API usage: lane totals come from the fixed path
 * {@code cc.billing.lanes.<key>.total}, and per-entity breakdowns from the
 * {@code cc.billing.lanes.<key>.items} array ({name, kind, count, total,
 * cache_read, cache_creation, input, output}).
 */
@Getter
@RequiredArgsConstructor
public enum SpendLane {

    USER_PROMPTS("user_prompts", "User prompts",
            "Tokens billed for the text your team typed, re-billed on every turn after it."),
    FILE_ATTACHMENTS("file_attachments", "File attachments",
            "Tokens billed for attached files, resent in every request after they're attached."),
    BUILT_IN_TOOLS("built_in_tools", "Built-in tools",
            "Tokens billed for built-in tool activity (Bash, Read...), re-billed each turn."),
    PRIOR_ASSISTANT("prior_assistant", "Prior assistant context",
            "Tokens billed replaying Claude's own text and thinking - the cost of session length."),
    SKILLS("skills", "Skills",
            "Tokens billed for skills: always-on menu entries plus bodies loaded on use."),
    CUSTOM_AGENTS("custom_agents", "Custom agents",
            "Tokens billed for subagent dispatch blurbs, riding on every request."),
    MCP_SERVERS("mcp_servers", "MCP servers",
            "Tokens billed for MCP servers: schemas + instructions every request, plus tool usage."),
    MEMORY("memory", "Memory",
            "Tokens billed for project instructions (CLAUDE.md, rules, auto-memory) in every request."),
    STATIC_OVERHEAD("static_overhead", "Static overhead",
            "Tokens billed for Claude Code itself: system prompt + built-in tool schemas."),
    UNATTRIBUTED("unattributed", "Unattributed",
            "Billed tokens not yet attributable to a lane (system reminders, request envelope, estimation drift).");

    private final String key;
    private final String label;
    private final String description;

    /** Fixed JSON path expression for the lane's billed total. */
    public String getTokenExpression() {
        return getTierExpression("total");
    }

    /** Fixed JSON path expression for one of the lane's tier columns. */
    public String getTierExpression(String column) {
        return "JSONExtractInt(metadata, 'cc', 'billing', 'lanes', '%s', '%s')".formatted(key, column);
    }

    /** The unattributed lane has no entity items by design. */
    public boolean hasBreakdown() {
        return this != UNATTRIBUTED;
    }

    public static Optional<SpendLane> fromKey(String key) {
        return Arrays.stream(values()).filter(lane -> lane.key.equals(key)).findFirst();
    }
}
