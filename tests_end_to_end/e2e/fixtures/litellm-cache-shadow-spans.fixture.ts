import { test as baseTest } from './cross-shape-cache-spans.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

export interface LitellmCacheShadowSpanSeed {
  /** Stable, namespace-free handle a spec looks a vector up by. */
  key: string;
  name: string;
  /** What this vector reports, in words, for the assertion message. */
  reports: string;
  /** The usage map exactly as it must reach the backend. */
  usage: Record<string, number>;
  /**
   * The cost the shipped price table says this vector costs.
   *
   * Never sent: every span is logged with `usage` and no `total_cost`.
   */
  expectedCost: number;
  /** The arithmetic behind `expectedCost`, for the assertion message. */
  workings: string;
}

export interface LitellmCacheShadowSpansRef {
  traceId: string;
  spans: LitellmCacheShadowSpanSeed[];
  /** The cached-token count every cache-bearing vector reports. */
  cachedTokens: number;
}

export interface LitellmCacheShadowSpansFixtures {
  litellmCacheShadowSpans: LitellmCacheShadowSpansRef;
}

/**
 * `claude-haiku-4-5` / `anthropic` — $1/M in, $0.1/M cache read, $5/M out.
 *
 * The provider has to be one with its own usage format, because the LiteLLM
 * input variant this fixture targets lives in `AnthropicUsageFormat`: that is
 * where three different LiteLLM names for the same cache-read count are read
 * together, and therefore where one of them being a present-but-zero could
 * shadow the others.
 */
const MODEL = 'claude-haiku-4-5';
const PROVIDER = 'anthropic';

/** The cached-token count every cache-bearing vector here reports. */
const CACHED_TOKENS = 200;

/**
 * Four vectors over one model, differing only in how a cache-read count is
 * reported — or not.
 *
 * The fix in opik#8987 replaced a `keys.first(...)` over three LiteLLM
 * cache-read names with a `max(...)` over them. `first()` returns the first
 * name PRESENT, and a client that saw no cache reads still sends its zero: so a
 * span carrying `cache_read_input_tokens: 0` next to another client's
 * `prompt_tokens_details.cached_tokens: 200` had the 200 shadowed by the 0 and
 * billed every cached token at the full input rate. It is the sharpest
 * regression guard in the PR and the cheapest thing to get wrong again, because
 * the two spellings are read in a single expression.
 *
 *   zero-shadow          cached_tokens 200 AND cache_read_input_tokens 0 -> $0.00132
 *   no-shadow            cached_tokens 200, no zero key beside it        -> $0.00132
 *   genuinely-no-cache   cached_tokens 0 and nothing else                -> $0.0015
 *   double-named         200 under BOTH names, on a 1200 prompt          -> $0.00152
 *
 * Each of the three comparisons the spec makes needs a different one of these,
 * and none is redundant:
 *
 *  - `zero-shadow` equal to `no-shadow` is the fix itself: adding the explicit
 *    zero must be provably a no-op.
 *  - `genuinely-no-cache` is what separates "the zero was honoured" from "the
 *    cache is always billed regardless" — without it, a backend that ignored
 *    every cache key would satisfy the equality above.
 *  - `double-named` pins the other direction of `max()`: one read reported
 *    under two names must bill ONCE. Its prompt is 1200 rather than 1000 so
 *    that billing it twice — once at the cache rate and once by failing to take
 *    it out of the input — comes out at $0.00172 rather than at an amount that
 *    happens to collide with one of the others.
 */
const SPAN_SEEDS: Array<Omit<LitellmCacheShadowSpanSeed, 'name'>> = [
  {
    key: 'zero-shadow',
    reports: "200 cached tokens under LiteLLM's prompt-details name, with a bare zero beside it",
    usage: {
      'original_usage.prompt_tokens': 1000,
      'original_usage.completion_tokens': 100,
      'original_usage.prompt_tokens_details.cached_tokens': CACHED_TOKENS,
      // The whole subject. Present and zero, which is what a client that saw no
      // cache reads sends — and what `first()` used to return in preference to
      // the 200 above.
      'original_usage.cache_read_input_tokens': 0,
    },
    expectedCost: 0.00132,
    workings: '800 x $1/M in + 200 x $0.1/M cache read + 100 x $5/M out',
  },
  {
    key: 'no-shadow',
    reports: 'the same 200 cached tokens with no zero key beside them',
    usage: {
      'original_usage.prompt_tokens': 1000,
      'original_usage.completion_tokens': 100,
      'original_usage.prompt_tokens_details.cached_tokens': CACHED_TOKENS,
    },
    expectedCost: 0.00132,
    workings: '800 x $1/M in + 200 x $0.1/M cache read + 100 x $5/M out',
  },
  {
    key: 'genuinely-no-cache',
    reports: 'no cached tokens at all — a reported zero and no other cache key',
    usage: {
      'original_usage.prompt_tokens': 1000,
      'original_usage.completion_tokens': 100,
      'original_usage.prompt_tokens_details.cached_tokens': 0,
    },
    expectedCost: 0.0015,
    workings: '1000 x $1/M in + 100 x $5/M out — the whole prompt at the full input rate',
  },
  {
    key: 'double-named',
    reports: 'one 200-token read under BOTH LiteLLM cache-read names, on a 1200-token prompt',
    usage: {
      'original_usage.prompt_tokens': 1200,
      'original_usage.completion_tokens': 100,
      'original_usage.prompt_tokens_details.cached_tokens': CACHED_TOKENS,
      'original_usage.cache_read_input_tokens': CACHED_TOKENS,
    },
    expectedCost: 0.00152,
    workings: '1000 x $1/M in + 200 x $0.1/M cache read + 100 x $5/M out',
  },
];

/**
 * One trace carrying four LLM spans that report `usage` and **no**
 * `total_cost`.
 *
 * Written straight to `POST /v1/private/spans`. An explicit `0` is the entire
 * subject of the first vector, and the Python SDK's usage builders are under no
 * obligation to re-emit a zero they consider meaningless — a seed whose zero
 * the bridge dropped would be identical to `no-shadow` and the fixture would
 * manufacture the equality the spec is looking for. The spec reads the stored
 * usage back and asserts the zero survived before it compares a single cost,
 * which is the other half of that guard.
 *
 * Teardown deletes the trace, and with it its spans, from a `finally` that
 * opens the moment the id exists: every write runs BEFORE `use()`, so a failure
 * in any one would otherwise skip the only cleanup and leave priced spans
 * behind.
 */
export const test = baseTest.extend<LitellmCacheShadowSpansFixtures>({
  litellmCacheShadowSpans: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const spans: LitellmCacheShadowSpanSeed[] = SPAN_SEEDS.map((seed) => ({
      ...seed,
      name: `${testNamespace}-${seed.key}`,
    }));

    const traceId = uuid7();
    const now = new Date();

    try {
      await backendClient.createTraceWithSource({
        id: traceId,
        projectName: project.name,
        name: `${testNamespace}-litellm-cache-shadow-trace`,
        source: 'sdk',
        input: { question: 'seeded LiteLLM cache-read shadowing' },
        output: { answer: 'seeded LiteLLM cache-read shadowing' },
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
          usage: span.usage,
          // No `total_cost` — deliberately. See the doc comment above.
        });
      }

      const ref: LitellmCacheShadowSpansRef = {
        traceId,
        spans,
        cachedTokens: CACHED_TOKENS,
      };

      await testInfo.attach('opik.litellmCacheShadowSpans', {
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
            `[litellmCacheShadowSpans fixture] delete warning for trace ${traceId}:`,
            err,
          );
        }
      }
    }
  },
});

export { expect } from './cross-shape-cache-spans.fixture';
