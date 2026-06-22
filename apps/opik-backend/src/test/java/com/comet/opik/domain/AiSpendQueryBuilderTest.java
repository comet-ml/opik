package com.comet.opik.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AiSpendQueryBuilder")
class AiSpendQueryBuilderTest {

    @ParameterizedTest(name = "category={0}, tool_server=''{1}'' -> {2}")
    @CsvSource(textBlock = """
            # Direct mappings — every entry in CATEGORY_TO_LANE projects 1:1.
            user_prompts,             '',                user_prompts
            prior_assistant,          '',                prior_assistant
            mcp_tools_active,         chrome-devtools,   mcp_servers
            mcp_tools_deferred,       '',                mcp_servers
            mcp_server_instructions,  '',                mcp_servers
            skills_menu,              '',                skills
            skills_loaded,            '',                skills
            custom_agents,            '',                custom_agents
            memory,                   '',                memory
            file_attachments,         '',                file_attachments
            system_prompt,            '',                static_overhead
            env_info,                 '',                static_overhead
            system_tools,             '',                static_overhead
            system_tools_deferred,    '',                static_overhead
            thinking,                 '',                thinking
            assistant_text,           '',                assistant_text
            built_in_tool_calls,      '',                built_in_tool_calls
            mcp_tool_calls,           chrome-devtools,   mcp_tool_calls
            skill_invocations,        '',                skill_invocations

            # tool_io splits on tool_server presence (SNORT Item 4).
            tool_io,                  '',                built_in_tools
            tool_io,                  chrome-devtools,   mcp_servers

            # Unmapped categories fold into unattributed so the residual stays exact.
            identity_context,         '',                unattributed
            slash_command,            '',                unattributed
            unknown,                  '',                unattributed
            some_future_category,     '',                unattributed
            """, ignoreLeadingAndTrailingWhitespace = true, emptyValue = "")
    @DisplayName("projectCategoryToLane covers all 19 wire categories plus tool_io split and unattributed fallback")
    void projectCategoryToLane(String category, String toolServer, String expected) {
        assertThat(AiSpendQueryBuilder.projectCategoryToLane(category, toolServer)).isEqualTo(expected);
    }

    @Test
    @DisplayName("projectCategoryToLane handles null tool_server identically to empty string")
    void projectCategoryToLane_nullToolServer() {
        assertThat(AiSpendQueryBuilder.projectCategoryToLane("tool_io", null)).isEqualTo("built_in_tools");
        assertThat(AiSpendQueryBuilder.projectCategoryToLane("user_prompts", null)).isEqualTo("user_prompts");
    }

    @Test
    @DisplayName("categoryToLane is immutable so callers can't mutate the lane projection at runtime")
    void categoryToLane_isImmutable() {
        var map = AiSpendQueryBuilder.categoryToLane();
        assertThat(map).isUnmodifiable();
    }
}
