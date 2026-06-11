import {
  Issue,
  ISSUE_CATEGORY,
  ISSUE_SEVERITY,
  ISSUE_STATUS,
  OccurrencePoint,
  PROMPT_DIFF_LINE_TYPE,
  PromptDiffLine,
  SignalsStats,
} from "@/types/signals";

const ctx = (text: string): PromptDiffLine => ({
  type: PROMPT_DIFF_LINE_TYPE.context,
  text,
});
const removed = (text: string): PromptDiffLine => ({
  type: PROMPT_DIFF_LINE_TYPE.removed,
  text,
});
const added = (text: string): PromptDiffLine => ({
  type: PROMPT_DIFF_LINE_TYPE.added,
  text,
});

// ---------------------------------------------------------------------------
// TEMPORARY MOCK DATA
// The Signals backend does not exist yet. These mocks let us build and review
// the UI. Everything here mirrors the response shapes documented in
// `SIGNALS_BACKEND_API.md`. Delete this file once the real endpoints land.
// ---------------------------------------------------------------------------

const generateOccurrences = (
  base: number,
  variance: number,
  points = 25,
): OccurrencePoint[] => {
  const start = new Date("2026-05-10T00:00:00Z").getTime();
  const day = 24 * 60 * 60 * 1000;
  return Array.from({ length: points }, (_, i) => {
    // deterministic pseudo-wave so the chart looks organic but stable
    const wave = Math.sin(i / 2.2) + Math.cos(i / 1.5);
    return {
      time: new Date(start + i * day).toISOString(),
      count: Math.max(0, Math.round(base + wave * variance)),
    };
  });
};

const exampleTraces = (model: string) =>
  Array.from({ length: 3 }, (_, i) => ({
    id: `019e${i}cca-0000-0000-0000-00000000000${i}`,
    duration: 1.3,
    span_count: 617,
    cost: 0.001,
    model,
    last_updated_at: "2026-06-07T09:45:00Z",
  }));

export const MOCK_ISSUES: Issue[] = [
  {
    id: "issue-agent-tool-loop",
    project_id: "mock-project",
    name: "Agent tool loop",
    severity: ISSUE_SEVERITY.high,
    category: ISSUE_CATEGORY.tool_failure,
    status: ISSUE_STATUS.open,
    short_description: "Tool call repeating without termination",
    summary:
      "The agent enters an infinite loop when search_docs returns an empty result set. Instead of falling back gracefully, it retries the same call repeatedly until the conversation times out. Affects 4.3% of all traces and has been trending upward since May 19.",
    occurrences: 122,
    users_impacted: 21,
    rate: 0.0017,
    first_seen_at: "2026-04-23T11:20:00Z",
    last_seen_at: "2026-04-23T18:05:00Z",
    ollie_fix: {
      analyzed_traces: 534,
      root_cause:
        "your system prompt has no fallback instruction when search_docs returns empty. Adding one instruction eliminates the loop in 97% of affected traces.",
      resolution_rate: 0.97,
      suggested_prompt_change: [
        ctx(
          "You are a helpful customer support agent. Use the search_docs tool to find relevant documentation before answering questions about our product.",
        ),
        removed("If you cannot find an answer, try searching again"),
        removed("with different keywords."),
        added("If search_docs returns no results, do not retry."),
        added("Instead, say: \"I couldn't find documentation on"),
        added('that — can you rephrase or contact support?"'),
        ctx("Always be concise and cite your sources."),
      ],
    },
    occurrences_over_time: generateOccurrences(40, 12),
    example_traces: exampleTraces("anthropic/claude-4.6-opus"),
  },
  {
    id: "issue-missing-tool-parameter",
    project_id: "mock-project",
    name: "Missing tool parameter",
    severity: ISSUE_SEVERITY.medium,
    category: ISSUE_CATEGORY.tool_failure,
    status: ISSUE_STATUS.open,
    short_description: "search_docs called without required `query`",
    summary:
      "The model invokes the search_docs tool without supplying the required `query` argument, causing the tool call to fail validation. The agent rarely recovers and usually returns a generic apology to the user.",
    occurrences: 122,
    users_impacted: 18,
    rate: 0.0012,
    first_seen_at: "2026-04-23T08:00:00Z",
    last_seen_at: "2026-06-06T14:30:00Z",
    ollie_fix: {
      analyzed_traces: 210,
      root_cause:
        "the tool schema marks `query` as optional in the JSON schema, so the model sometimes omits it. Marking it required and adding an example fixes 88% of cases.",
      resolution_rate: 0.88,
      suggested_prompt_change: [
        ctx("When the user asks a question, call the search_docs tool."),
        removed("search_docs(query?)"),
        added("search_docs(query)  // query is required"),
        added(
          'Always pass a non-empty "query" string describing what to look for.',
        ),
      ],
    },
    occurrences_over_time: generateOccurrences(28, 8),
    example_traces: exampleTraces("anthropic/claude-4.6-sonnet"),
  },
  {
    id: "issue-hallucinated-product-names",
    project_id: "mock-project",
    name: "Hallucinated product names",
    severity: ISSUE_SEVERITY.low,
    category: ISSUE_CATEGORY.hallucination,
    status: ISSUE_STATUS.open,
    short_description: "Agent cites products not in catalog",
    summary:
      "The agent occasionally references product names that do not exist in the catalog. This typically happens when the user asks about a category with sparse retrieval results.",
    occurrences: 122,
    users_impacted: 9,
    rate: 0.0008,
    first_seen_at: "2026-04-23T09:10:00Z",
    last_seen_at: "2026-06-05T20:00:00Z",
    occurrences_over_time: generateOccurrences(15, 6),
    example_traces: exampleTraces("openai/gpt-4o"),
  },
  {
    id: "issue-context-window-exceeded",
    project_id: "mock-project",
    name: "Context window exceeded",
    severity: ISSUE_SEVERITY.low,
    category: ISSUE_CATEGORY.timeout,
    status: ISSUE_STATUS.open,
    short_description: "Long conversations hitting token limit silently",
    summary:
      "Long multi-turn conversations silently exceed the model's context window. Earlier turns are truncated without warning, degrading answer quality late in the conversation.",
    occurrences: 122,
    users_impacted: 6,
    rate: 0.0005,
    first_seen_at: "2026-04-23T07:45:00Z",
    last_seen_at: "2026-06-04T12:15:00Z",
    occurrences_over_time: generateOccurrences(10, 5),
    example_traces: exampleTraces("anthropic/claude-4.6-opus"),
  },
  {
    id: "issue-slow-rag-retrieval",
    project_id: "mock-project",
    name: "Slow RAG retrieval",
    severity: ISSUE_SEVERITY.low,
    category: ISSUE_CATEGORY.timeout,
    status: ISSUE_STATUS.open,
    short_description: "Retrieval step >8s in 12% of traces",
    summary:
      "The retrieval step takes longer than 8 seconds in 12% of traces, driving up end-to-end latency. The slowdown correlates with queries that hit the cold vector index partition.",
    occurrences: 122,
    users_impacted: 14,
    rate: 0.0011,
    first_seen_at: "2026-04-23T06:30:00Z",
    last_seen_at: "2026-06-07T03:00:00Z",
    occurrences_over_time: generateOccurrences(20, 7),
    example_traces: exampleTraces("anthropic/claude-4.6-sonnet"),
  },
];

export const MOCK_SIGNALS_STATS: SignalsStats = {
  traces_affected: {
    value: 1240,
    trend: -0.12,
    trend_type: "percentage",
    trend_direction_positive: false,
  },
  open_issues: {
    value: 5,
    trend: 3,
    trend_type: "absolute",
    trend_direction_positive: false,
  },
  resolved_this_week: {
    value: 0,
    trend: 0,
    trend_type: "percentage",
    trend_direction_positive: true,
  },
  last_scan_at: "2026-06-07T09:00:00Z",
};

// Simulate network latency so loading states are exercised in dev.
export const mockDelay = <T>(data: T, ms = 400): Promise<T> =>
  new Promise((resolve) => setTimeout(() => resolve(data), ms));
