import { expect } from '@playwright/test';
import { test as baseTest } from './annotator-view-queue.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/**
 * One row of `model_prices_openrouter.json` as this release ships it, seeded as
 * an LLM span.
 */
export interface OpenRouterRepricedSpanSeed {
  /** Stable, namespace-free handle a spec looks a vector up by. */
  key: string;
  name: string;
  /** The model id as a client logs it — the three-segment spelling the file keys. */
  model: string;
  /**
   * The amount the shipped price file's own rates give at the seeded token
   * counts. `0` for the vector whose row this release REMOVED, which no row
   * prices any more.
   *
   * Never sent: every span is logged with `usage` and no `total_cost`, so the
   * amount is the server's own lookup and this is only what it is compared to.
   */
  expectedCost: number;
  /** The arithmetic behind `expectedCost`, for the assertion message. */
  workings: string;
}

export interface OpenRouterRepricedSpansRef {
  traceId: string;
  spans: OpenRouterRepricedSpanSeed[];
  promptTokens: number;
  completionTokens: number;
  /** Sum of `expectedCost` — what the trace must roll up to, the control adding nothing. */
  expectedTraceCost: number;
}

export interface OpenRouterRepricedSpansFixtures {
  openRouterRepricedSpans: OpenRouterRepricedSpansRef;
}

/**
 * A million of each, for the reason `openRouterCostSpans` uses the same counts:
 * OpenRouter's rows are cheap, and at realistic token counts every one of these
 * amounts would render as `<$0.01` and the panel half could not tell them
 * apart.
 */
const PROMPT_TOKENS = 1_000_000;
const COMPLETION_TOKENS = 1_000_000;

/**
 * The rows opik#9180 re-priced, plus the id it removed.
 *
 * All four are spelled three-segment (`openrouter/<vendor>/<model>`), which is
 * the spelling the price file keys and the only one that resolves —
 * `openRouterCostSpans` is where the two-segment id Opik writes for its own
 * runs is pinned, and that is a different question from this one. This fixture
 * is about the AMOUNTS: whether the rows this release moved are billed at the
 * rates it shipped.
 *
 *   openrouter/qwen/qwen3.8-27b                           $0.20/M in + $2.50/M out -> $2.70
 *   openrouter/mistralai/mistral-small-3.2-24b-instruct    $0.075/M in + $0.20/M out -> $0.275
 *   openrouter/minimax/minimax-m3.1-flash                  $0.15/M in + $0.60/M out -> $0.75
 *   openrouter/minimax/minimax-m3.1-flash:free             removed by 9180        -> nothing
 *
 * Why these three and not any three:
 *
 *  - **qwen3.8-27b's input rate moved 5x** (4e-08 -> 2e-07) and its output rate
 *    nearly doubled, so a build still reading the previous file prices it
 *    at $1.55 rather than $2.70 — a perfectly ordinary-looking figure.
 *  - **mistral-small-3.2-24b-instruct went DOWN.** Three rows that all rose
 *    could be satisfied by an across-the-board multiplier; one that fell cannot.
 *  - **minimax-m3.1-flash is the row the rename created.** It is the paid id
 *    that replaced a `:free` one, so it and the control are the same vendor and
 *    the same model differing only by the `:free` suffix.
 *  - **The three amounts are distinct and render as three distinct strings**
 *    ($2.7, $0.27, $0.75), so no single constant satisfies them and the panel
 *    half is discriminating per span.
 *
 * The `:free` id is the control, and a stronger one than a nonsense name:
 * `openrouter/minimax/minimax-m3.1-flash:free` was a real, priced row until
 * this release dropped it, so a lookup that had kept a stale copy of the file —
 * or that resolved a `:free` suffix to its paid sibling — prices it and fails
 * here. An unpriced row renders as no amount at all, which is what the panel
 * half asserts; nothing here claims the dash itself.
 */
const SPAN_SEEDS: Array<Omit<OpenRouterRepricedSpanSeed, 'name'>> = [
  {
    key: 'qwen-repriced',
    model: 'openrouter/qwen/qwen3.8-27b',
    expectedCost: 2.7,
    workings: '1.0M x $0.20/M in + 1.0M x $2.50/M out (input rate moved 5x in 9180)',
  },
  {
    key: 'mistral-repriced',
    model: 'openrouter/mistralai/mistral-small-3.2-24b-instruct',
    expectedCost: 0.275,
    workings: '1.0M x $0.075/M in + 1.0M x $0.20/M out (both rates went DOWN in 9180)',
  },
  {
    key: 'minimax-paid',
    model: 'openrouter/minimax/minimax-m3.1-flash',
    expectedCost: 0.75,
    workings: '1.0M x $0.15/M in + 1.0M x $0.60/M out (the paid row 9180 added)',
  },
  {
    key: 'minimax-free-removed',
    model: 'openrouter/minimax/minimax-m3.1-flash:free',
    expectedCost: 0,
    workings:
      '9180 removed this row, so nothing prices it — a cost here means a stale price file ' +
      'or a lookup that resolved the :free suffix to its paid sibling',
  },
];

/** Every seeded model id carries the `provider` a real OpenRouter call would. */
const PROVIDER = 'openrouter';

/**
 * One trace carrying four LLM spans that report `usage` and **no**
 * `total_cost`, so the backend has to price them itself.
 *
 * Written straight to `POST /v1/private/spans` rather than through the bridge,
 * for the reason `openRouterCostSpans` does it: the subject is the model id as
 * a client spells it and the price-key parse splits on its first slash, so
 * every character has to arrive exactly as written with no SDK normalisation in
 * between — and the `:free` suffix is the whole point of the control. The trace
 * is a REST write for the same reason: the bridge's `/traces` route emits
 * through `@opik.track`, which would add a root span carrying no model and no
 * usage and break the "the trace carries exactly the seeded spans" read.
 *
 * Teardown deletes the trace, and with it its spans, from a `finally` that
 * opens the moment the id exists: every write runs BEFORE `use()`, and the
 * trace's own rolled-up cost is one of the things asserted, so a failure must
 * not leave priced spans behind.
 */
export const test = baseTest.extend<OpenRouterRepricedSpansFixtures>({
  openRouterRepricedSpans: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const spans: OpenRouterRepricedSpanSeed[] = SPAN_SEEDS.map((seed) => ({
      ...seed,
      name: `${testNamespace}-${seed.key}`,
    }));

    // The premises the per-span assertions rest on, asserted before anything is
    // written: three amounts that are all different, and a control at zero. A
    // seed where two of the three coincided would make a passing panel
    // assertion unable to tell one span's amount from another's.
    const priced = spans.filter((s) => s.expectedCost > 0);
    expect(priced, 'three priced vectors').toHaveLength(3);
    expect(
      new Set(priced.map((s) => s.expectedCost)).size,
      'the three priced rows must expect three different amounts',
    ).toBe(priced.length);
    expect(
      spans.filter((s) => s.expectedCost === 0).map((s) => s.key),
      'exactly one control, the id 9180 removed',
    ).toEqual(['minimax-free-removed']);

    const traceId = uuid7();
    const now = new Date();

    try {
      await backendClient.createTraceWithSource({
        id: traceId,
        projectName: project.name,
        name: `${testNamespace}-openrouter-repriced-trace`,
        source: 'sdk',
        input: { question: 'seeded OpenRouter re-priced rows' },
        output: { answer: 'seeded OpenRouter re-priced rows' },
        startTime: now,
        endTime: now,
      });

      for (const span of spans) {
        await backendClient.createSpan({
          id: uuid7(),
          traceId,
          projectName: project.name,
          name: span.name,
          source: 'sdk',
          type: 'llm',
          model: span.model,
          provider: PROVIDER,
          usage: {
            prompt_tokens: PROMPT_TOKENS,
            completion_tokens: COMPLETION_TOKENS,
            total_tokens: PROMPT_TOKENS + COMPLETION_TOKENS,
            // No `total_cost` — deliberately. See the doc comment above.
          },
        });
      }

      const ref: OpenRouterRepricedSpansRef = {
        traceId,
        spans,
        promptTokens: PROMPT_TOKENS,
        completionTokens: COMPLETION_TOKENS,
        expectedTraceCost: spans.reduce((acc, s) => acc + s.expectedCost, 0),
      };

      await testInfo.attach('opik.openRouterRepricedSpans', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      // Swallow-and-warn, never rethrow: a delete that fails here would replace
      // whichever seeding or assertion error actually broke the test.
      if (!shouldLeaveArtifacts(testInfo)) {
        try {
          await backendClient.deleteTraces([traceId]);
        } catch (err) {
          console.warn(
            `[openRouterRepricedSpans fixture] delete warning for trace ${traceId}:`,
            err,
          );
        }
      }
    }
  },
});

export { expect } from './annotator-view-queue.fixture';
