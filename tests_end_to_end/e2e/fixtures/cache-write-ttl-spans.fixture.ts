import { test as baseTest } from './open-router-native-models.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/**
 * The `usage` keys Anthropic splits a cache WRITE by TTL under, as the Messages
 * API reports them and as `AnthropicUsageFormat.fromNative` reads them.
 *
 * Both are sent on every vector — one of them zero — rather than only the one
 * the vector is about. `CacheWrites.of` derives the 5-minute count as
 * `max(5m key, reported total - 1h key)`, so a vector that omitted the other
 * key would exercise a different branch of that derivation than a real
 * Anthropic payload does.
 */
export const CACHE_WRITE_5M_KEY = 'cache_creation.ephemeral_5m_input_tokens';
export const CACHE_WRITE_1H_KEY = 'cache_creation.ephemeral_1h_input_tokens';
/** Anthropic's own total across both TTLs, reported alongside the split. */
export const CACHE_WRITE_TOTAL_KEY = 'cache_creation_input_tokens';

/** Which TTL a vector's cache write was made at. */
export type CacheWriteTtl = '5m' | '1h';

/** Which side of the model's 200k prompt-token price tier a vector sits on. */
export type PromptTier = 'below' | 'above';

export interface CacheWriteTtlSpanSeed {
  /** Stable, namespace-free handle a spec looks a vector up by. */
  key: string;
  name: string;
  ttl: CacheWriteTtl;
  tier: PromptTier;
  /** Fresh input tokens, which Anthropic reports OUTSIDE the cache counts. */
  inputTokens: number;
  /** Tokens written to the cache at this vector's TTL. */
  cacheWriteTokens: number;
  /**
   * The cost the shipped price table says this vector costs.
   *
   * Never sent: every span is logged with `usage` and no `total_cost`, so the
   * number under test is the backend's own arithmetic and this is only what it
   * is compared against.
   */
  expectedCost: number;
  /** The arithmetic behind `expectedCost`, for the assertion message. */
  workings: string;
}

export interface CacheWriteTtlSpansRef {
  traceId: string;
  spans: CacheWriteTtlSpanSeed[];
  /** Completion tokens, identical on every vector. */
  outputTokens: number;
  /** The prompt total at which the model's `above_200k_tokens` rates take over. */
  tierThreshold: number;
}

export interface CacheWriteTtlSpansFixtures {
  cacheWriteTtlSpans: CacheWriteTtlSpansRef;
}

/**
 * `claude-sonnet-4-5` is not an interchangeable pick — it is one of the 16
 * shipped price rows that publish BOTH an `above_200k_tokens` cache-creation
 * rate and an `above_1hr` one, which is the only combination where the
 * behaviour below is observable at all:
 *
 *   input_cost_per_token                                      3e-06
 *   input_cost_per_token_above_200k_tokens                     6e-06
 *   output_cost_per_token                                    1.5e-05
 *   output_cost_per_token_above_200k_tokens                 2.25e-05
 *   cache_creation_input_token_cost                         3.75e-06
 *   cache_creation_input_token_cost_above_200k_tokens        7.5e-06
 *   cache_creation_input_token_cost_above_1hr                  6e-06
 *   cache_creation_input_token_cost_above_1hr_above_200k     1.2e-05
 *
 * The whole prompt — fresh input plus everything cached — decides the tier
 * (`SpanCostCalculator.tierBasis`), so each pair's prompt total is what puts it
 * on one side of 200k or the other.
 */
const MODEL = 'claude-sonnet-4-5';
const PROVIDER = 'anthropic';

/**
 * The two cache-WRITE rates the vectors below discriminate, per tier, exactly
 * as `claude-sonnet-4-5` publishes them.
 *
 * Declared rather than written inline in the spec because the spec asserts the
 * premium WITHIN each pair as the difference between the pair's two rates — the
 * assertion that says the 1h vector is dearer for the right reason rather than
 * by some other margin. Only the write rates are here: the input and output
 * terms are identical on both halves of a pair, so they cancel out of every
 * comparison and appear only in the absolute amounts.
 */
export const CACHE_WRITE_RATES = {
  below: { fiveMinutes: 3.75e-6, oneHour: 6e-6 },
  above: { fiveMinutes: 7.5e-6, oneHour: 1.2e-5 },
} as const satisfies Record<PromptTier, { fiveMinutes: number; oneHour: number }>;

/** The prompt total above which the `above_200k_tokens` rates apply. */
const TIER_THRESHOLD = 200_000;

/**
 * Completion tokens, identical on every vector.
 *
 * Small relative to the prompt on purpose: the output term is the same shape on
 * both sides of each pair, so it cancels out of every comparison and exists
 * only so the absolute amounts are those of a real LLM span rather than of a
 * prompt with no answer.
 */
const OUTPUT_TOKENS = 1_000;

/** Below the tier: 100k fresh + 50k written = a 150k prompt. */
const BELOW_INPUT = 100_000;
const BELOW_WRITE = 50_000;
/** Above the tier: 200k fresh + 100k written = a 300k prompt. */
const ABOVE_INPUT = 200_000;
const ABOVE_WRITE = 100_000;

/**
 * Two pairs of LLM spans, each pair differing ONLY in the TTL its cache write
 * was made at.
 *
 * A 1-hour cache write is the more expensive one — Anthropic charges more to
 * hold a prefix for an hour than for five minutes — so within each pair the 1h
 * vector must cost MORE than its 5m twin, at every prompt size. That ordering
 * is the durable claim; the absolute amounts beside it are what pin the
 * arithmetic to the shipped rates rather than merely to itself, so that two
 * spans agreeing on a wrong number cannot satisfy the comparison.
 *
 *   below-tier-5m   100k x 3e-06 + 50k  x 3.75e-06 + 1k x 1.5e-05  -> $0.5025
 *   below-tier-1h   100k x 3e-06 + 50k  x 6e-06    + 1k x 1.5e-05  -> $0.6150
 *   above-tier-5m   200k x 6e-06 + 100k x 7.5e-06  + 1k x 2.25e-05 -> $1.9725
 *   above-tier-1h   200k x 6e-06 + 100k x 1.2e-05  + 1k x 2.25e-05 -> $2.4225
 *
 * WHAT THE ABOVE-TIER PAIR IS FOR, and why its spec is currently expected to
 * fail. `cache_creation_input_token_cost_above_1hr` is read RAW
 * (`SpanCostCalculator.anthropicCost` calls `cacheCreationInputTokenPriceAbove1hr()`
 * with no tier basis) while the 5-minute rate goes through
 * `effectiveCacheCreationInputTokenPrice(tierBasis)` — and `ModelCostData`
 * declares no field for `cache_creation_input_token_cost_above_1hr_above_200k_tokens`
 * at all, so the tiered 1h rate the price file publishes is never read. Above
 * 200k the 1h bucket therefore bills at the base 6e-06 instead of 1.2e-05,
 * which makes `above-tier-1h` come out at $1.8225: half the table's own rate,
 * and BELOW its 5-minute twin. The longer, dearer write renders as the cheaper
 * span. The expectations here are the correct ones, so the spec goes red until
 * the product is fixed and green the moment it is.
 */
const SPAN_SEEDS: Array<Omit<CacheWriteTtlSpanSeed, 'name'>> = [
  {
    key: 'below-tier-5m',
    ttl: '5m',
    tier: 'below',
    inputTokens: BELOW_INPUT,
    cacheWriteTokens: BELOW_WRITE,
    expectedCost: 0.5025,
    workings: '100k x $3/M in + 50k x $3.75/M 5m cache write + 1k x $15/M out',
  },
  {
    key: 'below-tier-1h',
    ttl: '1h',
    tier: 'below',
    inputTokens: BELOW_INPUT,
    cacheWriteTokens: BELOW_WRITE,
    expectedCost: 0.615,
    workings: '100k x $3/M in + 50k x $6/M 1h cache write + 1k x $15/M out',
  },
  {
    key: 'above-tier-5m',
    ttl: '5m',
    tier: 'above',
    inputTokens: ABOVE_INPUT,
    cacheWriteTokens: ABOVE_WRITE,
    expectedCost: 1.9725,
    workings:
      '200k x $6/M in (above-200k tier) + 100k x $7.5/M 5m cache write (above-200k tier) + 1k x $22.5/M out',
  },
  {
    key: 'above-tier-1h',
    ttl: '1h',
    tier: 'above',
    inputTokens: ABOVE_INPUT,
    cacheWriteTokens: ABOVE_WRITE,
    expectedCost: 2.4225,
    workings:
      '200k x $6/M in (above-200k tier) + 100k x $12/M 1h cache write (above-200k tier) + 1k x $22.5/M out',
  },
];

/**
 * One trace carrying four LLM spans that report `usage` and **no**
 * `total_cost`, so the backend has to price them itself.
 *
 * Written straight to `POST /v1/private/spans` rather than through the bridge,
 * for the reason `cachedTokenSpans` does the same: the TTL-split cache keys are
 * the entire subject, they arrive under dotted names, and the Python SDK
 * rewrites usage keys as it re-emits them. A seed whose 1h key the bridge
 * renamed or dropped would price exactly like its own 5-minute twin — that is,
 * the fixture would manufacture the equality the spec is looking for. The spec
 * reads the stored `usage` back and asserts both keys survived before it
 * compares a single cost, which is the other half of that guard.
 *
 * The trace is a REST write too: the bridge's `/traces` route emits through
 * `@opik.track`, which also creates a root span for the tracked function. That
 * fifth span carries no model and no usage, so it would price at nothing and
 * change no cost — but it would break the "the trace carries exactly the
 * seeded spans" read the spec opens with, which is the check that stops a cost
 * from being attributed to the wrong span.
 *
 * Teardown deletes the trace, and with it its spans, from a `finally` that
 * opens the moment the id exists: every write happens BEFORE `use()`, so a
 * failure in any one of them would otherwise skip the only cleanup there is and
 * leave an already-priced trace behind — and priced spans are exactly what
 * poisons the next run's cost assertions.
 */
export const test = baseTest.extend<CacheWriteTtlSpansFixtures>({
  cacheWriteTtlSpans: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const spans: CacheWriteTtlSpanSeed[] = SPAN_SEEDS.map((seed) => ({
      ...seed,
      name: `${testNamespace}-${seed.key}`,
    }));

    // Client-generated, and generated BEFORE the write: the id teardown needs
    // therefore exists whatever the write does. A POST that commits
    // server-side but loses its response would otherwise leave a priced trace
    // behind with nothing to delete it.
    const traceId = uuid7();
    const now = new Date();

    try {
      await backendClient.createTraceWithSource({
        id: traceId,
        projectName: project.name,
        name: `${testNamespace}-cache-ttl-trace`,
        source: 'sdk',
        input: { question: 'seeded cache-write TTL cost resolution' },
        output: { answer: 'seeded cache-write TTL cost resolution' },
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
          model: MODEL,
          provider: PROVIDER,
          usage: {
            input_tokens: span.inputTokens,
            cache_read_input_tokens: 0,
            [CACHE_WRITE_TOTAL_KEY]: span.cacheWriteTokens,
            [CACHE_WRITE_5M_KEY]: span.ttl === '5m' ? span.cacheWriteTokens : 0,
            [CACHE_WRITE_1H_KEY]: span.ttl === '1h' ? span.cacheWriteTokens : 0,
            output_tokens: OUTPUT_TOKENS,
            // No `total_cost` — deliberately. See the doc comment above.
          },
        });
      }

      const ref: CacheWriteTtlSpansRef = {
        traceId,
        spans,
        outputTokens: OUTPUT_TOKENS,
        tierThreshold: TIER_THRESHOLD,
      };

      await testInfo.attach('opik.cacheWriteTtlSpans', {
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
          console.warn(`[cacheWriteTtlSpans fixture] delete warning for trace ${traceId}:`, err);
        }
      }
    }
  },
});

export { expect } from './open-router-native-models.fixture';
