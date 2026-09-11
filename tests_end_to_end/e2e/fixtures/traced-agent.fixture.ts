import { test as baseTest } from './feedback-definition.fixture';

export interface TracedAgentSpanRef {
  name: string;
  type: 'general' | 'llm' | 'tool';
  /** Index into `spans` of this span's parent; null for a root span. */
  parentIndex: number | null;
}

export interface TracedAgentRef {
  id: string;
  name: string;
  projectId: string;
  projectName: string;
  tags: string[];
  feedbackScore: { name: string; value: number };
  /** The LLM span's cost/token expectations, surfaced in span detail. */
  llmSpan: { name: string; model: string; totalTokens: number; totalCost: number };
  spans: TracedAgentSpanRef[];
  spanCount: number;
}

export interface TracedAgentFixtures {
  tracedAgent: TracedAgentRef;
}

/**
 * A small agent-shaped trace: a `plan` (general) root span fanning out to an
 * `llm-call` (llm, with model/usage/cost) and a `search-tool` (tool). Nested
 * two levels deep so the span tree, expand/collapse, span types, and LLM
 * token/cost detail all have real data to assert against. Seeded entirely
 * through the public SDK so the same shape lands on OSS and Cloud.
 */
const ROOT_SPAN = 'plan';
const LLM_SPAN = 'llm-call';
const TOOL_SPAN = 'search-tool';
const LLM_MODEL = 'gpt-4o';
const LLM_TOTAL_TOKENS = 15;
const LLM_TOTAL_COST = 0.00042;
const FEEDBACK_SCORE = { name: 'relevance', value: 0.9 };
const TRACE_TAGS = ['e2e', 'nested'];

export const test = baseTest.extend<TracedAgentFixtures>({
  tracedAgent: async ({ sdkClient, project, testNamespace }, use, testInfo) => {
    const name = `${testNamespace}-agent`;

    const created = await sdkClient.python.createNestedTrace({
      project_name: project.name,
      name,
      input: { question: 'What is the capital of France?' },
      output: { answer: 'Paris' },
      metadata: { agent: 'researcher' },
      tags: TRACE_TAGS,
      feedback_scores: [{ name: FEEDBACK_SCORE.name, value: FEEDBACK_SCORE.value, reason: 'on topic' }],
      spans: [
        { name: ROOT_SPAN, type: 'general', input: { goal: 'answer geography q' }, output: { steps: 2 } },
        {
          name: LLM_SPAN,
          type: 'llm',
          parent_index: 0,
          model: LLM_MODEL,
          provider: 'openai',
          input: { prompt: 'capital of France?' },
          output: { completion: 'Paris' },
          usage: { prompt_tokens: 12, completion_tokens: 3, total_tokens: LLM_TOTAL_TOKENS },
          total_cost: LLM_TOTAL_COST,
        },
        { name: TOOL_SPAN, type: 'tool', parent_index: 0, input: { query: 'France capital' }, output: { hit: 'Paris' } },
      ],
    });

    const ref: TracedAgentRef = {
      id: created.id,
      name: created.name,
      projectId: created.project_id,
      projectName: project.name,
      tags: TRACE_TAGS,
      feedbackScore: FEEDBACK_SCORE,
      llmSpan: { name: LLM_SPAN, model: LLM_MODEL, totalTokens: LLM_TOTAL_TOKENS, totalCost: LLM_TOTAL_COST },
      spans: [
        { name: ROOT_SPAN, type: 'general', parentIndex: null },
        { name: LLM_SPAN, type: 'llm', parentIndex: 0 },
        { name: TOOL_SPAN, type: 'tool', parentIndex: 0 },
      ],
      spanCount: created.span_count,
    };

    await testInfo.attach('opik.tracedAgent', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);
    // No explicit teardown — the project fixture's deleteProject cascades.
  },
});

export { expect } from './feedback-definition.fixture';
