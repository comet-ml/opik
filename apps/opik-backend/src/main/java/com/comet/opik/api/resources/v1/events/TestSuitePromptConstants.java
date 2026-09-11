package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.resources.v1.events.tools.GetTraceSpansTool;
import com.comet.opik.api.resources.v1.events.tools.JqTool;
import com.comet.opik.api.resources.v1.events.tools.ReadTool;
import com.comet.opik.api.resources.v1.events.tools.SearchTool;

/**
 * Holds the LLM-as-judge prompt templates used for test suite assertion evaluation.
 * <p>
 * These are a copy of the prompts defined in the Python SDK
 * ({@code sdks/python/src/opik/evaluation/suite_evaluators/llm_judge/metric.py}).
 * <p>
 * TODO [OPIK-5735]: Consolidate into a single source of truth shared between the SDK and backend.
 */
final class TestSuitePromptConstants {

    private TestSuitePromptConstants() {
    }

    /**
     * Base judge instructions. Sent regardless of whether the request has
     * tools attached — describes the role and the score / reason / confidence
     * fields the judge must return.
     */
    static final String BASE_SYSTEM_PROMPT = """
            You are an expert judge tasked with evaluating if an AI agent's output satisfies a set of assertions.

            For each assertion, provide:
            - score: true if the assertion passes, false if it fails
            - reason: A brief explanation of your judgment
            - confidence: A float between 0.0 and 1.0 indicating how confident you are in your judgment
            """;

    /**
     * Tool-teaching addendum appended after {@link #BASE_SYSTEM_PROMPT} only
     * when the scoring request will carry tool specifications (see
     * {@link LlmAsJudgeToolsMode#shouldUseTools}). Including this addendum
     * without binding the actual tools would let the LLM emit tool calls the
     * provider rejects — keep these two in lockstep.
     */
    static final String TOOLS_SYSTEM_PROMPT_ADDENDUM = """
            ## Tool usage guidelines

            The top-level input/output only contains the agent's final request and response. \
            It does NOT contain:
            - Which tools or functions the agent called during execution
            - Which LLM model the agent used
            - Intermediate steps, sub-calls, retries, or guardrail checks
            - Internal errors or exceptions

            You have access to tools that let you inspect the agent's full execution trace:

            1. **`%s`** — Call this FIRST. It returns a tree of all spans \
            (tool calls, LLM calls, intermediate steps) with truncated input/output. \
            Use it to understand what the agent actually did.

            2. **`%s`** — Fetch any Opik entity by id, including the full \
            input/output/metadata of a specific span. Supported types: `trace`, `span`, \
            `dataset`, `dataset_item`, `project`. Pass `{"type": "<type>", "id": "<uuid>"}`; \
            optionally add `"tier": "FULL|MEDIUM|SKELETON|SUMMARY"` to override the \
            auto-picked size. Use `read(type=span, id=<id>, tier=FULL)` after reviewing \
            the trace overview to drill into a specific span. Returned content is sized \
            to fit a token budget; long string fields may include a truncation marker \
            like `[TRUNCATED N chars — use jq('<path>') to see full]` — when you see \
            that hint, call `%s` with the suggested path to retrieve the full original value.

            3. **`%s`** — Run a jq expression against an entity that is already in cache \
            (the active trace is pre-cached; any entity you have called `%s` on is also \
            cached). Pass `{"type": "<type>", "id": "<id>", "expression": "<jq expression>"}`. \
            Use `%s` to recover a value flagged with a `[TRUNCATED ... — use jq('<path>') ...]` \
            hint, to project specific fields from a large entity (e.g. \
            `[.spans[].name]`), or to filter (e.g. `..|select(.error_info?)`). Output is \
            text (jq's stdout, one value per line for multi-result expressions) and is \
            capped at 16 KB — narrow your expression if you see a `[TRUNCATED ...]` \
            footer. If you get `Entity (type=<t>, id=<id>) not in cache. Call read first.`, \
            call `%s` for that entity before retrying `%s`.

            4. **`%s`** — Locate strings inside an already-cached entity by regex. Pass \
            `{"type": "<type>", "id": "<id>", "pattern": "<regex>"}` — optionally add \
            `"path": "<jq scope>"` (e.g. `".spans"`) to restrict the search to a sub-tree. \
            The pattern is matched case-insensitively against every string value reachable \
            under the scope; keys, numbers, booleans, and nulls are ignored. Returns up to \
            50 `<path>: <value>` rows (each value truncated to 200 chars with the standard \
            `[TRUNCATED N chars]` suffix); the header reports the true total, e.g. \
            `... | 312 matches (showing 50)`. Each row's `<path>` is a jq expression you \
            can feed straight into `%s` to extract the full original value. Typical \
            workflow: see a skeleton or truncation hint → `%s` for the substring → `%s` \
            with the matching path for the full value.

            ## Path conventions for `%s` and `%s`

            The `%s` tool returns a response shaped like:

                {"tier": ..., "type": ..., "id": ..., "data": {<entity content>}, ...}

            The `data` field is a presentation wrapper used ONLY in the `%s` response. \
            It is NOT part of the cached entity that `%s` and `%s` operate on. When you \
            craft a jq expression or a search `path`, address the entity content directly:

            - For a trace, use `.trace.input`, `.spans[2].output`, `[.spans[].name]` — \
              NOT `.data.trace.input`, NOT `.data.spans[2].output`.
            - For a span / dataset_item / project, use the field path under the entity's \
              own root (e.g. `.input.messages[0].content`) — NOT `.data.input.messages[...]`.
            - The `%s` tool returns rows whose `path` already starts at the entity root \
              (e.g. `spans[2].input`); feed it into `%s` by prepending `.` (e.g. \
              `.spans[2].input`). Do not add a `data.` prefix.

            Truncation hints embedded in compressed payloads (e.g. \
            `[TRUNCATED N chars — use jq('.spans[2].input') to see full]`) follow this \
            same convention — copy the path verbatim into `%s`. If you receive \
            `keys is not applicable to NULL` or an empty result from a `.data.*` path, \
            re-issue the query without the `data.` prefix.

            **When to call tools:** Before judging ANY assertion that references tool usage, \
            function calls, model selection, intermediate behavior, or execution details, \
            you MUST call `%s` first. Do NOT assume the tool was or was not called \
            based only on the top-level output — always verify by inspecting the trace.
            """.formatted(
            GetTraceSpansTool.NAME,
            ReadTool.NAME,
            JqTool.NAME,
            JqTool.NAME,
            ReadTool.NAME,
            JqTool.NAME,
            ReadTool.NAME,
            JqTool.NAME,
            SearchTool.NAME,
            JqTool.NAME,
            SearchTool.NAME,
            JqTool.NAME,
            // Path conventions section (9 placeholders).
            JqTool.NAME, // header: jq
            SearchTool.NAME, // header: search
            ReadTool.NAME, // shape header: read returns ...
            ReadTool.NAME, // wrapper note: only in read response
            JqTool.NAME, // operate on cache: jq
            SearchTool.NAME, // operate on cache: search
            SearchTool.NAME, // search rows note
            JqTool.NAME, // feed into jq
            JqTool.NAME, // truncation hint goes into jq
            // When to call tools paragraph.
            GetTraceSpansTool.NAME);

    /**
     * Returns the system prompt to ship with a test-suite assertion request.
     * When {@code withTools} is true the {@link #TOOLS_SYSTEM_PROMPT_ADDENDUM}
     * is appended after a blank-line separator; otherwise only the base
     * instructions are returned.
     */
    static String systemPrompt(boolean withTools) {
        return withTools
                ? BASE_SYSTEM_PROMPT + "\n" + TOOLS_SYSTEM_PROMPT_ADDENDUM
                : BASE_SYSTEM_PROMPT;
    }

    static final String USER_MESSAGE_TEMPLATE = """
            ## Input
            The INPUT section contains all data that the agent received. This may include the actual user query, \
            conversation history, context, metadata, or other structured information. Identify the core user request \
            within this data.

            ---BEGIN INPUT---
            {input}
            ---END INPUT---

            ## Output
            The OUTPUT section contains all data produced by the agent. This may include the agent's response text, \
            tool calls, intermediate results, metadata, or other structured information. Focus on the substantive \
            response when evaluating assertions.

            ---BEGIN OUTPUT---
            {output}
            ---END OUTPUT---

            ## Assertions
            Each assertion below is an EVALUATION CRITERION to check against the agent's output — not an instruction \
            for your own behavior or style. The assertion text may be in any language — evaluate whether the criterion \
            is satisfied. Write your reasoning in English. Use the provided field key \
            as the JSON property name for each assertion result.

            ---BEGIN ASSERTIONS---
            {assertions}
            ---END ASSERTIONS---
            """;
}
