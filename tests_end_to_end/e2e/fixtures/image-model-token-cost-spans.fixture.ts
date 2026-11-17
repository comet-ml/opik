import { test as baseTest } from './claude-code-otel-cache-spans.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

export interface ImageModelTokenCostSpanSeed {
  /** Stable, namespace-free handle a spec looks a vector up by. */
  key: string;
  name: string;
  /** The model id as a client logs it. */
  model: string;
  /** The `provider` field as a client logs it. */
  provider: string;
  /**
   * The cost the shipped price table says this vector costs.
   *
   * Never sent: every span is logged with `usage` and no `total_cost`, so the
   * price is the server's own answer and this is only what it is compared
   * against.
   */
  expectedCost: number;
  /** The arithmetic behind `expectedCost`, for the assertion message. */
  workings: string;
  /**
   * The price-file keys that collide with this model's own under
   * `ModelPriceTable`'s runtime key, for the assertion message.
   *
   * `parseModelName` strips everything up to the FIRST `/` from a price-file
   * key as well as from a logged model id, so `1024-x-1024/gpt-image-1.5` is
   * filed under the same runtime key as `gpt-image-1.5` itself.
   */
  collidingKeys: readonly string[];
}

export interface ImageModelTokenCostSpansRef {
  traceId: string;
  spans: ImageModelTokenCostSpanSeed[];
  promptTokens: number;
  completionTokens: number;
}

export interface ImageModelTokenCostSpansFixtures {
  imageModelTokenCostSpans: ImageModelTokenCostSpansRef;
}

/**
 * 150k prompt / 50k completion.
 *
 * Large on purpose, and for the same reason `modelCostSpans` and
 * `newProviderCostSpans` are: both resolved amounts have to clear `formatCost`'s
 * `<$0.01` floor so the UI half can read an exact dollar amount off the Cost
 * chip. At a realistic image-request token count ($0.01 on 1000 in / 500 out,
 * which is where the 2.2.131 staging pass drove it) the chip renders the same
 * string for a correctly-priced span and for one that gained a cent from
 * somewhere — and "no amount at all" is precisely the failure under test. Well
 * under 200k each, so no prompt tier is involved and the arithmetic is two
 * rates times two counts.
 */
const PROMPT_TOKENS = 150_000;
const COMPLETION_TOKENS = 50_000;

/**
 * Two OpenAI image models whose price rows collide with a per-image row, one of
 * which opik#9068 fixed.
 *
 * `ModelPriceTable` files every price-file key under a runtime key with
 * everything up to the first `/` stripped, so the nine
 * `{quality}/{size}/gpt-image-1.5` rows — which publish `input_cost_per_image`
 * and NOTHING else — land under the same key as `gpt-image-1.5` itself. Before
 * opik#9068 the last one parsed simply won, which is a `HashMap` iteration
 * order rather than a decision: the surviving row priced no token at all, so
 * `resolveCalculator` fell through to `defaultCost` and every span on the model
 * billed **$0**. The fix keeps a row that prices tokens in preference to one
 * that does not:
 *
 *   parsedModelPrices.merge(runtimeKey, price,
 *       (kept, row) -> hasTokenPrices(kept) && !hasTokenPrices(row) ? kept : row)
 *
 * $0 is the quietest possible pricing failure. The span is there, the model and
 * the token counts are right, and the Cost column reads nothing on the page
 * people open to decide what their LLM spend is — there is no error, no empty
 * state and no missing row to notice.
 *
 *   gpt-image-1.5   openai   150k x $5/M in + 50k x $10/M out  -> $1.25
 *   gpt-image-1     openai   150k x $5/M in + 50k x (no rate)  -> $0.75
 *
 * WHY `gpt-image-1` IS THE CONTROL AND NOT AN UNPRICED ONE. It is a second
 * model in the same shape — `mode: image_generation`, a token-priced row of its
 * own, and nine per-image/per-pixel rows colliding with it under the same
 * runtime key — so it is subject to the identical merge. Reading both says the
 * fix holds for the family rather than for one row, and it rules out the two
 * explanations a single vector could not: that `image_generation` had become a
 * mode the lookup skips, and that these amounts came from the per-image rows
 * rather than from the token ones.
 *
 * `gpt-image-1` publishes NO `output_cost_per_token` at all (only
 * `output_cost_per_image_token`), which is why its 50k completion tokens are
 * billed nothing and its amount is not the same arithmetic as its sibling's.
 * That is a fact about the row, stated here so the two expectations cannot be
 * mistaken for a typo — and it makes the pair render as two DIFFERENT strings,
 * which is what the UI half needs to tell one chip from the other.
 */
const SPAN_SEEDS: Array<Omit<ImageModelTokenCostSpanSeed, 'name'>> = [
  {
    key: 'gpt-image-1-5',
    model: 'gpt-image-1.5',
    provider: 'openai',
    expectedCost: 1.25,
    workings: '150k x $5/M in + 50k x $10/M out, from the model’s own token rates',
    collidingKeys: [
      '1024-x-1024/gpt-image-1.5',
      'low/1024-x-1024/gpt-image-1.5',
      'standard/1024-x-1024/gpt-image-1.5',
      'medium/1024-x-1024/gpt-image-1.5',
      'high/1024-x-1024/gpt-image-1.5',
    ],
  },
  {
    key: 'gpt-image-1',
    model: 'gpt-image-1',
    provider: 'openai',
    expectedCost: 0.75,
    workings:
      '150k x $5/M in, and nothing for the 50k completion tokens — this row publishes ' +
      'output_cost_per_image_token only, no output_cost_per_token',
    collidingKeys: [
      'high/1024-x-1024/gpt-image-1',
      'low/1024-x-1024/gpt-image-1',
      'medium/1024-x-1024/gpt-image-1',
    ],
  },
];

/**
 * One trace carrying two LLM spans that report `usage` and **no** `total_cost`,
 * so the backend has to price them itself.
 *
 * Written straight to `POST /v1/private/spans`. The subject is the price-table
 * lookup for a `model`/`provider` pair as a client logs it, and the model id has
 * to arrive character for character — the dot in `gpt-image-1.5` is itself part
 * of what is exercised, since `CostService` normalises dots to hyphens as a
 * FALLBACK after the exact key misses, and the bridge's usage builders would be
 * an extra translation between the seed and the lookup under test.
 *
 * The trace is a REST write for the usual reason: the bridge's `/traces` route
 * emits through `@opik.track`, which adds a root span carrying no model and no
 * usage. It would price at nothing and change no cost, but it would break the
 * "the trace carries exactly the seeded spans" read the spec opens with — which
 * is the check that stops a cost being attributed to the wrong span.
 *
 * Teardown deletes the trace, and with it its spans, from a `finally` that
 * opens the moment the id exists: every write happens BEFORE `use()`, so a
 * failure in any one of them would otherwise skip the only cleanup there is and
 * leave priced spans behind.
 */
export const test = baseTest.extend<ImageModelTokenCostSpansFixtures>({
  imageModelTokenCostSpans: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const spans: ImageModelTokenCostSpanSeed[] = SPAN_SEEDS.map((seed) => ({
      ...seed,
      name: `${testNamespace}-${seed.key}`,
    }));

    // Client-generated, and generated BEFORE the write: the id teardown needs
    // therefore exists whatever the write does.
    const traceId = uuid7();
    const now = new Date();

    try {
      await backendClient.createTraceWithSource({
        id: traceId,
        projectName: project.name,
        name: `${testNamespace}-image-model-cost-trace`,
        source: 'sdk',
        input: { question: 'seeded image-model token pricing' },
        output: { answer: 'seeded image-model token pricing' },
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
          provider: span.provider,
          usage: {
            prompt_tokens: PROMPT_TOKENS,
            completion_tokens: COMPLETION_TOKENS,
            total_tokens: PROMPT_TOKENS + COMPLETION_TOKENS,
            // No `total_cost` — deliberately. See the doc comment above.
          },
        });
      }

      const ref: ImageModelTokenCostSpansRef = {
        traceId,
        spans,
        promptTokens: PROMPT_TOKENS,
        completionTokens: COMPLETION_TOKENS,
      };

      await testInfo.attach('opik.imageModelTokenCostSpans', {
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
            `[imageModelTokenCostSpans fixture] delete warning for trace ${traceId}:`,
            err,
          );
        }
      }
    }
  },
});

export { expect } from './claude-code-otel-cache-spans.fixture';
