import { test as baseTest } from './weekly-metric-spans.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/**
 * The `usage` key OpenAI-shaped spans report their cache hits under, as the
 * Python SDK (1.6.0+) logs it and as `textGenerationWithCacheCostOpenAI` reads
 * it first.
 */
export const CACHED_TOKENS_KEY = 'original_usage.prompt_tokens_details.cached_tokens';

export interface CachedTokenSpanSeed {
  /** Stable, namespace-free handle a spec looks a vector up by. */
  key: string;
  name: string;
  model: string;
  provider: string;
  /** Cached prompt tokens this vector reports, or 0 for the no-cache half of a pair. */
  cachedTokens: number;
  /**
   * The cost the server must resolve from its own price table.
   *
   * Never sent: every vector is logged with `usage` and no `total_cost`, so the
   * number under test is the backend's own arithmetic and this is only what it
   * is compared against.
   */
  expectedCost: number;
  /** The arithmetic behind `expectedCost`, for the assertion message. */
  workings: string;
}

export interface CachedTokenSpansRef {
  traceId: string;
  spans: CachedTokenSpanSeed[];
  promptTokens: number;
  completionTokens: number;
  /** Sum of `expectedCost` across every span — the trace's rolled-up total. */
  expectedTraceCost: number;
}

export interface CachedTokenSpansFixtures {
  cachedTokenSpans: CachedTokenSpansRef;
}

/**
 * 200k prompt / 10k completion, and 50k of the prompt cached where a vector says
 * so.
 *
 * Not the 1M/1M `model-cost-spans` uses. At these rates 1M prompt tokens on
 * `gpt-5.5-pro` would price at $30 while the `gpt-4.1` control landed near $2,
 * and the point of the pair is that both halves clear `formatCost`'s `<$0.01`
 * floor and render as exact amounts a UI assertion can read. These counts are
 * also the ones the release exploration observed on staging, so the expected
 * amounts below are a re-derivation of a number a human has already seen.
 */
const PROMPT_TOKENS = 200_000;
const COMPLETION_TOKENS = 10_000;
const CACHED_TOKENS = 50_000;

/**
 * Two pairs of LLM spans, differing within each pair ONLY in whether they report
 * cached prompt tokens.
 *
 * What the pairs discriminate is `CostService.resolveCalculator`: it routes a
 * span to the provider's cache-aware calculator only when the model's price
 * entry publishes a positive `cache_creation_input_token_cost` or
 * `cache_read_input_token_cost`. With neither, the span falls through to plain
 * `textGenerationCost`, which never subtracts cached tokens — so every cached
 * token is billed at the full input rate.
 *
 * The models are not interchangeable picks; each is the only thing that makes
 * its half of the comparison mean anything:
 *
 *   gpt-5.5-pro  $30/M in, $180/M out, NO cache_read rate  (release 2.2.60)
 *   gpt-4.1      $2/M in,  $8/M out,   $0.5/M cache_read   (control)
 *
 * At 200k prompt + 10k completion, with 50k of the prompt cached:
 *
 *   gpt-5.5-pro cached   200k x 30/M + 10k x 180/M            -> $7.80
 *   gpt-5.5-pro plain    200k x 30/M + 10k x 180/M            -> $7.80  (identical)
 *   gpt-4.1 cached       150k x 2/M + 50k x 0.5/M + 10k x 8/M -> $0.405
 *   gpt-4.1 plain        200k x 2/M + 10k x 8/M               -> $0.48
 *
 * The control pair is what makes the first pair's equality a finding rather than
 * a tautology: without a model that DOES discount, "cached tokens bought no
 * discount" is indistinguishable from "the cached-token count never reached the
 * calculator at all".
 *
 * NOTE ON DURABILITY — the gpt-5.5-pro pair pins the shipped price table as
 * 2.2.60 ships it. Whether OpenAI still offers a cached-input discount on this
 * model is a product question the release exploration raised and nobody has
 * answered yet: if the answer is that it does, the price entry gains a
 * `cache_read_input_token_cost` and this pair SHOULD go red. That is the signal,
 * not a flake — swap the vector onto whichever entry then publishes no cache
 * rate, and keep the comparison.
 */
const SPAN_SEEDS: Array<Omit<CachedTokenSpanSeed, 'name'>> = [
  {
    key: 'no-cache-rate-cached',
    model: 'gpt-5.5-pro',
    provider: 'openai',
    cachedTokens: CACHED_TOKENS,
    expectedCost: 7.8,
    workings: '200k x $30/M in (cached tokens included) + 10k x $180/M out',
  },
  {
    key: 'no-cache-rate-plain',
    model: 'gpt-5.5-pro',
    provider: 'openai',
    cachedTokens: 0,
    expectedCost: 7.8,
    workings: '200k x $30/M in + 10k x $180/M out',
  },
  {
    key: 'cache-rate-cached',
    model: 'gpt-4.1',
    provider: 'openai',
    cachedTokens: CACHED_TOKENS,
    expectedCost: 0.405,
    workings: '150k x $2/M in + 50k x $0.5/M cache read + 10k x $8/M out',
  },
  {
    key: 'cache-rate-plain',
    model: 'gpt-4.1',
    provider: 'openai',
    cachedTokens: 0,
    expectedCost: 0.48,
    workings: '200k x $2/M in + 10k x $8/M out',
  },
];

/**
 * One trace carrying four LLM spans that report `usage` and **no** `total_cost`,
 * two of them reporting cached prompt tokens.
 *
 * Every span is written straight to `POST /v1/private/spans` rather than through
 * the bridge, for the reason `ModelCostSpanWriter`'s `raw-usage-rest` exists:
 * the cached-token key is the entire subject here, and the Python SDK rewrites
 * usage keys as it re-emits them. A seed whose key the bridge renamed or dropped
 * would price exactly like the no-cache half of its own pair — that is, the
 * fixture would manufacture the equality the spec is looking for. The spec reads
 * the stored `usage` back and asserts the key survived before it compares a
 * single cost, which is the other half of that guard.
 *
 * The trace is a REST write too, for a smaller reason: the bridge's `/traces`
 * route emits through `@opik.track`, which also creates a root span for the
 * tracked function. That fifth span carries no model and no usage, so it prices
 * at nothing and would not change a single cost — but it would break the "the
 * trace carries exactly the seeded spans" read the spec opens with, which is the
 * check that stops a cost from being attributed to the wrong span.
 *
 * Teardown deletes the trace (and with it its spans) here rather than in the
 * test: the trace's rolled-up cost is one of the things asserted, so a failed
 * assertion must not leave priced spans behind. It runs from a `finally` that
 * opens the moment the trace exists, because the span writes happen BEFORE
 * `use()` and a failure in any of them would otherwise skip the only cleanup.
 */
export const test = baseTest.extend<CachedTokenSpansFixtures>({
  cachedTokenSpans: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const spans: CachedTokenSpanSeed[] = SPAN_SEEDS.map((seed) => ({
      ...seed,
      name: `${testNamespace}-${seed.key}`,
    }));

    const traceId = uuid7();
    const now = new Date();
    await backendClient.createTraceWithSource({
      id: traceId,
      projectName: project.name,
      name: `${testNamespace}-cached-cost-trace`,
      source: 'sdk',
      input: { question: 'seeded cached-token cost resolution' },
      output: { answer: 'seeded cached-token cost resolution' },
      startTime: now,
      endTime: now,
    });

    try {
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
            // Only on the cached half. An explicit zero would still exercise the
            // cache-aware calculator on the control model, which is a third
            // vector's worth of behaviour and not this pair's comparison.
            ...(span.cachedTokens > 0 ? { [CACHED_TOKENS_KEY]: span.cachedTokens } : {}),
            // No `total_cost` — deliberately. See the doc comment above.
          },
        });
      }

      const ref: CachedTokenSpansRef = {
        traceId,
        spans,
        promptTokens: PROMPT_TOKENS,
        completionTokens: COMPLETION_TOKENS,
        expectedTraceCost: spans.reduce((acc, s) => acc + s.expectedCost, 0),
      };

      await testInfo.attach('opik.cachedTokenSpans', {
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
          console.warn(`[cachedTokenSpans fixture] delete warning for trace ${traceId}:`, err);
        }
      }
    }
  },
});

export { expect } from './weekly-metric-spans.fixture';
