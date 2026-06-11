package com.comet.opik.api.spend;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

@Getter
@RequiredArgsConstructor
public enum SpendLane {

    PRIOR_ASSISTANT("prior_assistant", "Prior assistant context", Side.INPUT, false, null),
    TOOL_RESULTS("tool_results", "Tool results", Side.INPUT, true, "Tokens returned by each tool"),
    USER_PROMPTS("user_prompts", "User prompts", Side.INPUT, false, null),
    SKILLS_AVAILABLE("skills_available", "Skills available", Side.INPUT, true, "Menu tokens per available skill"),
    SKILLS_LOADED("skills_loaded", "Skills loaded", Side.INPUT, true, "Tokens per loaded skill body"),
    TOOLS("tools", "Tools schema", Side.INPUT, true, "Schema overhead per tool server"),
    MEMORY("memory", "Memory", Side.INPUT, true, "Tokens per memory file"),
    FILE_ATTACHMENTS("file_attachments", "File attachments", Side.INPUT, true, "Tokens by content type");

    private final String key;
    private final String label;
    private final Side side;
    @Getter(AccessLevel.NONE)
    private final boolean hasBreakdown;
    private final String breakdownSubtitle;

    public boolean hasBreakdown() {
        return hasBreakdown;
    }

    public enum Side {
        INPUT,
        OUTPUT
    }

    public static Optional<SpendLane> fromKey(String key) {
        return Arrays.stream(values()).filter(lane -> lane.key.equals(key)).findFirst();
    }
}
