package com.comet.opik.api.spend;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

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
    SKILLS_AVAILABLE("skills_available", "Skills available", Side.INPUT,
            "JSONExtractInt(metadata, 'cc', 'skills', 'summary', 'menu_tokens')",
            "'cc', 'skills', 'available'", "name", "menu_tokens", "Menu tokens per available skill"),
    SKILLS_LOADED("skills_loaded", "Skills loaded", Side.INPUT,
            "JSONExtractInt(metadata, 'cc', 'skills', 'summary', 'loaded_tokens')",
            "'cc', 'skills', 'loaded'", "name", "body_tokens", "Tokens per loaded skill body"),
    TOOLS("tools", "Tools schema", Side.INPUT,
            "JSONExtractInt(metadata, 'cc', 'tools', 'summary', 'schema_tokens')",
            "'cc', 'tools', 'summary', 'by_server'", "server", "schema_tokens",
            "Schema overhead per tool server"),
    MEMORY("memory", "Memory", Side.INPUT,
            "JSONExtractInt(metadata, 'cc', 'memory', 'summary', 'total_tokens')",
            "'cc', 'memory', 'files'", "path", "body_tokens", "Tokens per memory file"),
    FILE_ATTACHMENTS("file_attachments", "File attachments", Side.INPUT,
            "JSONExtractInt(metadata, 'cc', 'file_attachments', 'summary', 'total_tokens')",
            "'cc', 'file_attachments', 'files'", "content_type", "body_tokens", "Tokens by content type");

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

    public static Optional<SpendLane> fromKey(String key) {
        return Arrays.stream(values()).filter(lane -> lane.key.equals(key)).findFirst();
    }
}
