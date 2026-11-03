import { test as baseTest } from './litellm-cache-shadow-spans.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

export interface OuterPromptTokensSpanSeed {
  /** Stable, namespace-free handle a spec looks a vector up by. */
  key: string;
  name: string;
  /**
   * The SDK's converted prompt total, sent OUTSIDE `original_usage.*`.
   *
   * The only thing that differs between the two vectors, and the whole subject:
   * `VariantInput.cacheInsideInput` reads it to decide whether the provider's
   * reported input already contains the cache.
   */
  outerPromptTokens: number;
  /** What this vector's outer total says, in words, for the assertion message. */
  means: string;
  /**
   * The cost the shipped price table says this vector costs.
   *
   * Never sent: both spans are logged with `usage` and no `total_cost`.
   */
  expectedCost: number;
  /** The arithmetic behind `expectedCost`, for the assertion message. */
  workings: string;
}

export interface OuterPromptTokensSpansRef {
  traceId: string;
  spans: OuterPromptTokensSpanSeed[];
  /** The provider's reported input, identical on both vectors. */
  reportedInputTokens: number;
  /** The provider's reported cache read, identical on both vectors. */
  reportedCacheReadTokens: number;
  /** The provider's reported output, identical on both vectors. */
  reportedOutputTokens: number;
}

export interface OuterPromptTokensSpansFixtures {
  outerPromptTokensSpans: OuterPromptTokensSpansRef;
}

/**
 * `deepinfra/anthropic/claude-3-7-sonnet-latest` / `deepinfra` —
 * $3.3/M in, $0.33/M cache read, $16.5/M out.
 *
 * A gateway provider with no usage format of its own, so it reads through
 * `OpenAiUsageFormat`, whose variants all count the cache INSIDE the input
 * unless the numbers say otherwise — which is the convention the outer total
 * has to be able to overrule. The model id is spelled as its full price-table
 * key because `CostService` strips everything up to the first slash before
 * looking a price up.
 */
const MODEL = 'deepinfra/anthropic/claude-3-7-sonnet-latest';
const PROVIDER = 'deepinfra';

/**
 * The provider's own usage, byte-for-byte identical on both vectors.
 *
 * Declared once rather than per vector, because "identical `original_usage`"
 * is the premise of the whole comparison: if the two halves differed anywhere
 * else, two different prices would be the expected outcome and the spec would
 * prove nothing.
 */
const INPUT_TOKENS = 1_000;
const CACHE_READ_TOKENS = 200;
const OUTPUT_TOKENS = 100;

/**
 * Two spans with identical provider usage that must price two DIFFERENT ways,
 * decided purely by the SDK's own prompt total beside it.
 *
 * This is the whole of the `VariantInput` change in opik#8987. Clients disagree
 * about whether an input reported next to its cache already includes it —
 * LiteLLM's does, Anthropic's does not — and when the numbers can settle it,
 * they do. One of the signals is the SDK's converted `prompt_tokens`: if it
 * equals the reported input PLUS the cache, then the client clearly treated the
 * two as separate, so the input excludes the cache.
 *
 *   outer 1200 (== 1000 input + 200 cache)   input EXCLUDES the cache
 *                1000 x $3.3/M + 200 x $0.33/M + 100 x $16.5/M -> $0.005016
 *   outer 1000                               the variant's own convention holds,
 *                                            so the input INCLUDES the cache
 *                 800 x $3.3/M + 200 x $0.33/M + 100 x $16.5/M -> $0.004356
 *
 * The pair is the only vector in the PR where identical provider usage must
 * come out at two different prices, which is what makes it impossible to pass
 * by accident: a backend that ignored the outer total would price both halves
 * the same, whichever convention it then picked. The $0.00066 between them is
 * exactly the 200 contested tokens at the full input rate, so the clause
 * failing to fire in EITHER direction shows up here.
 */
const SPAN_SEEDS: Array<Omit<OuterPromptTokensSpanSeed, 'name'>> = [
  {
    key: 'outer-includes-cache',
    outerPromptTokens: INPUT_TOKENS + CACHE_READ_TOKENS,
    means:
      'the SDK converted the prompt to input PLUS cache, so the provider reported them separately and the input excludes the cache',
    expectedCost: 0.005016,
    workings: '1000 x $3.3/M in + 200 x $0.33/M cache read + 100 x $16.5/M out',
  },
  {
    key: 'outer-excludes-cache',
    outerPromptTokens: INPUT_TOKENS,
    means:
      "the SDK's total matches the reported input alone, so nothing overrules the variant's own convention and the input includes the cache",
    expectedCost: 0.004356,
    workings: '800 x $3.3/M in (the input less the cache) + 200 x $0.33/M cache read + 100 x $16.5/M out',
  },
];

/**
 * One trace carrying two LLM spans whose `original_usage` is identical and
 * whose outer `prompt_tokens` is not, both with **no** `total_cost`.
 *
 * Written straight to `POST /v1/private/spans` because the relationship between
 * the outer total and the nested provider usage IS the subject, and the Python
 * SDK computes its own outer totals from whatever it is handed — a seed sent
 * through the bridge would have the SDK decide the very thing under test, and
 * the two halves would most likely arrive identical.
 *
 * Teardown deletes the trace, and with it its spans, from a `finally` that
 * opens the moment the id exists: both writes run BEFORE `use()`, so a failure
 * in either would otherwise skip the only cleanup and leave priced spans
 * behind.
 */
export const test = baseTest.extend<OuterPromptTokensSpansFixtures>({
  outerPromptTokensSpans: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const spans: OuterPromptTokensSpanSeed[] = SPAN_SEEDS.map((seed) => ({
      ...seed,
      name: `${testNamespace}-${seed.key}`,
    }));

    const traceId = uuid7();
    const now = new Date();

    try {
      await backendClient.createTraceWithSource({
        id: traceId,
        projectName: project.name,
        name: `${testNamespace}-outer-prompt-tokens-trace`,
        source: 'sdk',
        input: { question: 'seeded input-includes-cache decision' },
        output: { answer: 'seeded input-includes-cache decision' },
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
            // The one thing that differs between the two spans.
            prompt_tokens: span.outerPromptTokens,
            'original_usage.input_tokens': INPUT_TOKENS,
            'original_usage.cache_read_input_tokens': CACHE_READ_TOKENS,
            'original_usage.output_tokens': OUTPUT_TOKENS,
            // No `total_cost` — deliberately. See the doc comment above.
            //
            // And deliberately no `total_tokens` either: `cacheInsideInput`
            // reads a reported total as a signal of its own, so sending one
            // would give the clause a second way to reach the same answer and
            // the pair would no longer isolate the outer prompt count.
          },
        });
      }

      const ref: OuterPromptTokensSpansRef = {
        traceId,
        spans,
        reportedInputTokens: INPUT_TOKENS,
        reportedCacheReadTokens: CACHE_READ_TOKENS,
        reportedOutputTokens: OUTPUT_TOKENS,
      };

      await testInfo.attach('opik.outerPromptTokensSpans', {
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
            `[outerPromptTokensSpans fixture] delete warning for trace ${traceId}:`,
            err,
          );
        }
      }
    }
  },
});

export { expect } from './litellm-cache-shadow-spans.fixture';
