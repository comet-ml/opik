import { test as baseTest } from './alert.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

export interface ModelCostSpanSeed {
  name: string;
  model: string;
  provider: string;
  /**
   * The cost the server must resolve for this span from its own price table,
   * or 0 for a model id the table must NOT match.
   *
   * Never sent to the backend — the whole point of this fixture is that the
   * client supplies `usage` and no `total_cost`, so the price is the server's
   * answer and this is only what the test compares it against.
   */
  expectedCost: number;
}

export interface ModelCostSpansRef {
  traceId: string;
  spans: ModelCostSpanSeed[];
  promptTokens: number;
  completionTokens: number;
  /** Sum of `expectedCost` across every span — the trace's rolled-up total. */
  expectedTraceCost: number;
}

export interface ModelCostSpansFixtures {
  modelCostSpans: ModelCostSpansRef;
}

/**
 * A million of each, so every resolved price clears `formatCost`'s `<$0.01`
 * floor and renders as an exact dollar amount the UI half of the spec can read.
 * At the seeded rates a realistic token count would render as `<$0.01` for
 * every model alike, which no assertion could tell apart.
 */
const PROMPT_TOKENS = 1_000_000;
const COMPLETION_TOKENS = 1_000_000;

/**
 * Five LLM spans whose model ids each exercise one step of server-side price
 * resolution, with the two ways it could go wrong.
 *
 * Costs are the shipped price table's own numbers at 1M prompt + 1M completion
 * tokens (`model_prices_and_context_window.json` / `model_prices_overrides.json`):
 *
 *   claude-opus-4-6            $5/M in + $25/M out  -> $30.00
 *   claude-haiku-4-5-20251001  $1/M in + $5/M out   -> $6.00
 *   gpt-5.2                    $1.75/M in + $14/M out -> $15.75
 *
 * The two controls matter as much as the three prices. Both are `gpt-5.2` with
 * eight trailing digits that are not a date; a stripper eager enough to remove
 * them would silently bill another model's rate, and the failure would look
 * exactly like an ordinary cost.
 */
const SPAN_SEEDS: Array<Omit<ModelCostSpanSeed, 'name'> & { suffix: string }> = [
  {
    suffix: 'opus-prefixed',
    // Prefix strip + dot-normalise + compact-date strip + the claude-4-6-opus alias.
    model: 'anthropic/claude-4.6-opus-20260205',
    provider: 'anthropic',
    expectedCost: 30,
  },
  {
    suffix: 'haiku-dotted',
    // Dot-normalise only: claude-haiku-4-5-20251001 is a price-table key as-is.
    model: 'claude-haiku-4.5-20251001',
    provider: 'anthropic',
    expectedCost: 6,
  },
  {
    suffix: 'gpt-dated',
    // Compact-date strip; the old regex could not do this and read $0.
    model: 'gpt-5.2-20251217',
    provider: 'openai',
    expectedCost: 15.75,
  },
  {
    suffix: 'gpt-build-number',
    // Negative control: 8 digits, but a build number, not a date.
    model: 'gpt-5.2-99999999',
    provider: 'openai',
    expectedCost: 0,
  },
  {
    suffix: 'gpt-impossible-date',
    // Negative control: 8 digits shaped like a date, but month 13 / day 45.
    model: 'gpt-5.2-20251345',
    provider: 'openai',
    expectedCost: 0,
  },
];

/**
 * One trace carrying five LLM spans that report token `usage` and **no**
 * `total_cost`, so the backend has to price them itself.
 *
 * Every other cost fixture in the estate (`tracedAgent`, the thread seeds)
 * supplies `total_cost` from the client, which means the server-side price
 * resolution path has never been exercised end to end — a wrong price there is
 * invisible, because the number still renders as a perfectly ordinary cost.
 *
 * Teardown deletes the trace (and with it its spans) here rather than in the
 * test: an assertion failure must not leave priced spans behind, since the
 * project's own rolled-up cost is one of the things asserted.
 */
export const test = baseTest.extend<ModelCostSpansFixtures>({
  modelCostSpans: async ({ sdkClient, backendClient, project, testNamespace }, use, testInfo) => {
    const spans: ModelCostSpanSeed[] = SPAN_SEEDS.map(({ suffix, ...seed }) => ({
      ...seed,
      name: `${testNamespace}-${suffix}`,
    }));

    const created = await sdkClient.python.createNestedTrace({
      project_name: project.name,
      name: `${testNamespace}-cost-trace`,
      input: { question: 'seeded model cost resolution' },
      output: { answer: 'seeded model cost resolution' },
      spans: spans.map((span) => ({
        name: span.name,
        type: 'llm' as const,
        model: span.model,
        provider: span.provider,
        usage: {
          prompt_tokens: PROMPT_TOKENS,
          completion_tokens: COMPLETION_TOKENS,
          total_tokens: PROMPT_TOKENS + COMPLETION_TOKENS,
        },
        // No `total_cost` — deliberately. See the doc comment above.
      })),
    });

    if (created.span_count !== spans.length) {
      throw new Error(
        `[modelCostSpans fixture] expected ${spans.length} spans, bridge reported ${created.span_count}`,
      );
    }

    const ref: ModelCostSpansRef = {
      traceId: created.id,
      spans,
      promptTokens: PROMPT_TOKENS,
      completionTokens: COMPLETION_TOKENS,
      expectedTraceCost: spans.reduce((acc, s) => acc + s.expectedCost, 0),
    };

    await testInfo.attach('opik.modelCostSpans', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteTraces([created.id]);
      } catch (err) {
        console.warn(`[modelCostSpans fixture] delete warning for trace ${created.id}:`, err);
      }
    }
  },
});

export { expect } from './alert.fixture';
