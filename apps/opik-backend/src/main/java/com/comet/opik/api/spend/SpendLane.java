package com.comet.opik.api.spend;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

/**
 * Input lanes of the AI Spend composition. Each lane aggregates one or more
 * {@code metadata.cipx.blocks[]} {@code category} values shipped by cipx on
 * each LLM-call span; the projection lives in {@code AiSpendCategoryMap}.
 * Per-call tokens are attributed to blocks by chars-share within each
 * {@code (side, cache_status)} tier from the structured
 * {@code metadata.cipx.call.usage} counters.
 */
@Getter
@RequiredArgsConstructor
public enum SpendLane {

    USER_PROMPTS("user_prompts", "User prompts", "prompt",
            "Tokens billed for the text your team typed, re-billed on every turn after it."),
    FILE_ATTACHMENTS("file_attachments", "File attachments", "file",
            "Tokens billed for attached files, resent in every request after they're attached."),
    BUILT_IN_TOOLS("built_in_tools", "Built-in tools", "call",
            "Tokens billed for built-in tool activity (Bash, Read...), re-billed each turn."),
    PRIOR_ASSISTANT("prior_assistant", "Prior assistant context", null,
            "Tokens billed replaying Claude's own text and thinking - the cost of session length."),
    SKILLS("skills", "Skills", "load",
            "Tokens billed for skills: always-on menu entries plus bodies loaded on use."),
    CUSTOM_AGENTS("custom_agents", "Custom agents", null,
            "Tokens billed for subagent dispatch blurbs, riding on every request."),
    MCP_SERVERS("mcp_servers", "MCP servers", "call",
            "Tokens billed for MCP servers: schemas + instructions every request, plus tool usage."),
    MEMORY("memory", "Memory", null,
            "Tokens billed for project instructions (CLAUDE.md, rules, auto-memory) in every request."),
    STATIC_OVERHEAD("static_overhead", "Static overhead", null,
            "Tokens billed for Claude Code itself: system prompt + built-in tool schemas."),
    UNATTRIBUTED("unattributed", "Unattributed", null,
            "Tokens cipx couldn't pin to a lane: input usage without a labeled cache tier, plus blocks under categories the FE doesn't surface yet.");

    private final String key;
    private final String label;
    // Singular noun for item.count events ("prompt", "call", "load"). Null for
    // lanes whose items are definition-only or counted output-side — their
    // counts are structurally 0 and the UI hides the column.
    private final String itemUnit;
    private final String description;

    /** The unattributed lane has no entity items by design. */
    public boolean hasBreakdown() {
        return this != UNATTRIBUTED;
    }

    public static Optional<SpendLane> fromKey(String key) {
        return Arrays.stream(values()).filter(lane -> lane.key.equals(key)).findFirst();
    }
}
