import { test as baseTest } from './version-history-seed.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/**
 * One vector of the native-usage-shape matrix.
 *
 * Every field is what it takes to make the amount checkable rather than merely
 * reproducible: the usage map as the client's own SDK spells it, the provider
 * string that selects the price row, and the arithmetic behind the expected
 * cost so a failure message says which rate moved.
 */
export interface NativeUsageShapeSeed {
  /** Stable, namespace-free handle a spec looks a vector up by. */
  key: string;
  name: string;
  /** The client whose usage spelling this is, for assertion messages. */
  shape: string;
  model: string;
  provider: string;
  /** The usage map exactly as it must reach the backend, verbatim. */
  usage: Record<string, number>;
  /** What the shipped price table says this vector costs. Never sent. */
  expectedCost: number;
  /** The arithmetic behind `expectedCost`, for the assertion message. */
  workings: string;
}

export interface NativeUsageShapeSpansRef {
  /** The one trace carrying every vector below. */
  traceId: string;
  spans: NativeUsageShapeSeed[];
  /** Sum of `expectedCost` across every span — the trace's rolled-up total. */
  expectedTraceCost: number;
}

export interface NativeUsageShapeSpansFixtures {
  nativeUsageShapeSpans: NativeUsageShapeSpansRef;
}

/**
 * Bedrock's Claude Sonnet 4.5 row, whose `litellm_provider` is
 * `bedrock_converse` — $3/M in, $0.3/M cache read, $3.75/M cache write,
 * $15/M out.
 *
 * Chosen because its four rates are all different, which is what makes the
 * absolute amount below a statement about which bucket each token count landed
 * in rather than about a single rate.
 */
const BEDROCK_MODEL = 'anthropic.claude-sonnet-4-5-20250929-v1:0';
const BEDROCK_RATES = {
  input: 3e-6,
  cacheRead: 3e-7,
  cacheWrite: 3.75e-6,
  output: 1.5e-5,
} as const;

/**
 * Gemini 2.5 Flash — $0.3/M in, $0.03/M cache read, $2.5/M out, and a
 * reasoning rate equal to its output rate.
 *
 * SCALED x100 from the counts the exploration verified (20k/8k/1k/500). The
 * rates are unchanged and so is every claim; the counts are larger only so the
 * amounts clear `formatCost`'s $0.01 floor. At the exploration's own counts
 * this vector prices at $0.00759, and the panel renders every sub-cent amount
 * as the same `<$0.01` marker — so the UI half of this spec could not tell the
 * four Gemini vectors apart, nor a correct amount from a wrong one. The API
 * half would still have worked; the panel half is the reason for the factor.
 */
const GEMINI_MODEL = 'gemini-2.5-flash';
const GEMINI_RATES = {
  input: 3e-7,
  cacheRead: 3e-8,
  output: 2.5e-6,
  /**
   * `output_cost_per_reasoning_token` on this row, which happens to equal its
   * output rate.
   *
   * Load-bearing for how the spec phrases its claim, not for the arithmetic:
   * because the two coincide here, the thoughts delta proves the thinking
   * tokens were BILLED, but cannot say whether the reasoning rate or the plain
   * output rate was the one applied. `SpanCostCalculator` falls back from one
   * to the other, so no amount on this row could separate them.
   */
  reasoning: 2.5e-6,
} as const;

const BEDROCK_TOKENS = { input: 10_000, cacheRead: 5_000, cacheWrite: 2_000, output: 1_000 } as const;
const GEMINI_TOKENS = {
  /** Gemini reports the cache INSIDE the prompt count, so this is the gross figure. */
  prompt: 2_000_000,
  cached: 800_000,
  candidates: 100_000,
  thoughts: 50_000,
} as const;

/** Non-cached prompt tokens, as `GeminiUsageFormat` derives them. */
const GEMINI_UNCACHED_PROMPT = GEMINI_TOKENS.prompt - GEMINI_TOKENS.cached;

/**
 * Four native shapes, each with a twin that drops exactly one key.
 *
 * This is the axis opik#8941 and opik#8987 moved, and the one no existing
 * fixture reaches. The estate's cache specs vary the usage shape for a FIXED
 * provider (`crossShapeCacheSpans`, deliberately: a shape its provider did not
 * write) and vary the provider at a plain token level (`otelProviderAliasSpans`).
 * Nothing seeds a provider reporting in its OWN native dialect with cache and
 * reasoning counts in it, which is what every real Bedrock Converse and Gemini
 * client actually sends.
 *
 * The pairs, and what each one can fail for:
 *
 *   bedrock-converse-camel   the camelCase Converse API spelling.
 *                            10000 x $3/M + 5000 x $0.3/M + 2000 x $3.75/M
 *                            + 1000 x $15/M                       -> $0.054
 *   bedrock-invoke-snake     the SAME four counts in the InvokeModel/Anthropic
 *                            snake_case spelling, under provider `bedrock`
 *                            rather than `bedrock_converse`       -> $0.054
 *                            Two independent things at once, and both have to
 *                            hold for the amounts to match: the snake_case keys
 *                            are read, and `bedrock` finds a row whose
 *                            litellm_provider is `bedrock_converse` through the
 *                            sibling group. Either failing shows up as an
 *                            amount that differs from its twin.
 *   bedrock-no-cache         the same span with NEITHER cache key -> $0.045
 *                            The +$0.009 difference is the two cache buckets at
 *                            their own rates. Anthropic reports the cache
 *                            OUTSIDE the input count, so this delta is a
 *                            SURCHARGE: dropping the keys makes the span
 *                            cheaper, not dearer.
 *
 *   gemini-native            prompt_token_count / cached_content_token_count /
 *                            candidates_token_count / thoughts_token_count.
 *                            1.2M x $0.3/M + 800k x $0.03/M + 100k x $2.5/M
 *                            + 50k x $2.5/M                       -> $0.759
 *   google-ai-alias          the same usage under the SDK's `google_ai`
 *                            provider alias                       -> $0.759
 *   gemini-no-thoughts       the same without the thinking count   -> $0.634
 *                            The -$0.125 difference is 50k thinking tokens that
 *                            would otherwise go unbilled entirely.
 *   gemini-no-cache          the same without the cache count      -> $0.975
 *                            DEARER, not cheaper, and the sign is the point:
 *                            Gemini's prompt count INCLUDES the cache, so
 *                            dropping the key moves 800k tokens from the
 *                            $0.03/M bucket to the $0.3/M one. A calculator
 *                            that read the key but treated the prompt as
 *                            cache-exclusive would price this pair the wrong
 *                            way round.
 */
const SPAN_SEEDS: ReadonlyArray<Omit<NativeUsageShapeSeed, 'name'>> = [
  {
    key: 'bedrock-converse-camel',
    shape: 'Bedrock Converse camelCase',
    model: BEDROCK_MODEL,
    provider: 'bedrock_converse',
    usage: {
      inputTokens: BEDROCK_TOKENS.input,
      cacheReadInputTokens: BEDROCK_TOKENS.cacheRead,
      cacheWriteInputTokens: BEDROCK_TOKENS.cacheWrite,
      outputTokens: BEDROCK_TOKENS.output,
    },
    expectedCost:
      BEDROCK_TOKENS.input * BEDROCK_RATES.input +
      BEDROCK_TOKENS.cacheRead * BEDROCK_RATES.cacheRead +
      BEDROCK_TOKENS.cacheWrite * BEDROCK_RATES.cacheWrite +
      BEDROCK_TOKENS.output * BEDROCK_RATES.output,
    workings: '10000 x $3/M + 5000 x $0.3/M + 2000 x $3.75/M + 1000 x $15/M',
  },
  {
    key: 'bedrock-invoke-snake',
    shape: 'Bedrock InvokeModel snake_case under the `bedrock` sibling provider',
    model: BEDROCK_MODEL,
    provider: 'bedrock',
    usage: {
      input_tokens: BEDROCK_TOKENS.input,
      cache_read_input_tokens: BEDROCK_TOKENS.cacheRead,
      cache_creation_input_tokens: BEDROCK_TOKENS.cacheWrite,
      output_tokens: BEDROCK_TOKENS.output,
    },
    expectedCost:
      BEDROCK_TOKENS.input * BEDROCK_RATES.input +
      BEDROCK_TOKENS.cacheRead * BEDROCK_RATES.cacheRead +
      BEDROCK_TOKENS.cacheWrite * BEDROCK_RATES.cacheWrite +
      BEDROCK_TOKENS.output * BEDROCK_RATES.output,
    workings: 'the same four counts as bedrock-converse-camel, so the same $0.054',
  },
  {
    key: 'bedrock-no-cache',
    shape: 'Bedrock Converse camelCase with no cache keys',
    model: BEDROCK_MODEL,
    provider: 'bedrock_converse',
    usage: {
      inputTokens: BEDROCK_TOKENS.input,
      outputTokens: BEDROCK_TOKENS.output,
    },
    expectedCost:
      BEDROCK_TOKENS.input * BEDROCK_RATES.input + BEDROCK_TOKENS.output * BEDROCK_RATES.output,
    workings: '10000 x $3/M + 1000 x $15/M, with no cache buckets at all',
  },
  {
    key: 'gemini-native',
    shape: 'Gemini usageMetadata',
    model: GEMINI_MODEL,
    provider: 'gemini',
    usage: {
      prompt_token_count: GEMINI_TOKENS.prompt,
      cached_content_token_count: GEMINI_TOKENS.cached,
      candidates_token_count: GEMINI_TOKENS.candidates,
      thoughts_token_count: GEMINI_TOKENS.thoughts,
    },
    expectedCost:
      GEMINI_UNCACHED_PROMPT * GEMINI_RATES.input +
      GEMINI_TOKENS.cached * GEMINI_RATES.cacheRead +
      GEMINI_TOKENS.candidates * GEMINI_RATES.output +
      GEMINI_TOKENS.thoughts * GEMINI_RATES.reasoning,
    workings: '1.2M x $0.3/M + 800k x $0.03/M + 100k x $2.5/M + 50k x $2.5/M',
  },
  {
    key: 'google-ai-alias',
    shape: "Gemini usageMetadata under the SDK's google_ai alias",
    model: GEMINI_MODEL,
    provider: 'google_ai',
    usage: {
      prompt_token_count: GEMINI_TOKENS.prompt,
      cached_content_token_count: GEMINI_TOKENS.cached,
      candidates_token_count: GEMINI_TOKENS.candidates,
      thoughts_token_count: GEMINI_TOKENS.thoughts,
    },
    expectedCost:
      GEMINI_UNCACHED_PROMPT * GEMINI_RATES.input +
      GEMINI_TOKENS.cached * GEMINI_RATES.cacheRead +
      GEMINI_TOKENS.candidates * GEMINI_RATES.output +
      GEMINI_TOKENS.thoughts * GEMINI_RATES.reasoning,
    workings: 'the same usage as gemini-native, so the same $0.759',
  },
  {
    key: 'gemini-no-thoughts',
    shape: 'Gemini usageMetadata with no thinking count',
    model: GEMINI_MODEL,
    provider: 'gemini',
    usage: {
      prompt_token_count: GEMINI_TOKENS.prompt,
      cached_content_token_count: GEMINI_TOKENS.cached,
      candidates_token_count: GEMINI_TOKENS.candidates,
    },
    expectedCost:
      GEMINI_UNCACHED_PROMPT * GEMINI_RATES.input +
      GEMINI_TOKENS.cached * GEMINI_RATES.cacheRead +
      GEMINI_TOKENS.candidates * GEMINI_RATES.output,
    workings: '1.2M x $0.3/M + 800k x $0.03/M + 100k x $2.5/M, no thinking bucket',
  },
  {
    key: 'gemini-no-cache',
    shape: 'Gemini usageMetadata with no cache count',
    model: GEMINI_MODEL,
    provider: 'gemini',
    usage: {
      prompt_token_count: GEMINI_TOKENS.prompt,
      candidates_token_count: GEMINI_TOKENS.candidates,
      thoughts_token_count: GEMINI_TOKENS.thoughts,
    },
    expectedCost:
      GEMINI_TOKENS.prompt * GEMINI_RATES.input +
      GEMINI_TOKENS.candidates * GEMINI_RATES.output +
      GEMINI_TOKENS.thoughts * GEMINI_RATES.reasoning,
    workings: 'the whole 2M prompt at $0.3/M, because no part of it is cached',
  },
];

/** The cache surcharge the two Bedrock cache keys add, at the table's own rates. */
export const BEDROCK_CACHE_SURCHARGE =
  BEDROCK_TOKENS.cacheRead * BEDROCK_RATES.cacheRead +
  BEDROCK_TOKENS.cacheWrite * BEDROCK_RATES.cacheWrite;

/** What the thinking tokens cost, and so what dropping the key must remove. */
export const GEMINI_THOUGHTS_COST = GEMINI_TOKENS.thoughts * GEMINI_RATES.reasoning;

/**
 * The cache DISCOUNT, as a negative number.
 *
 * Signed deliberately: the cached tokens are billed either way, and what the
 * key buys is the gap between the two rates. An unsigned magnitude would let a
 * build that priced the cache at the input rate — or moved the tokens the wrong
 * way — satisfy the comparison.
 */
export const GEMINI_CACHE_DISCOUNT =
  GEMINI_TOKENS.cached * (GEMINI_RATES.cacheRead - GEMINI_RATES.input);

export { GEMINI_TOKENS, BEDROCK_TOKENS };

/**
 * One trace carrying seven LLM spans that report `usage` and **no**
 * `total_cost`, so the backend has to price every one of them itself.
 *
 * Written straight to `POST /v1/private/spans` rather than through the bridge,
 * for the reason `createSpan`'s own doc comment gives: the Python SDK
 * normalises whatever usage it is handed, re-emitting unknown keys under
 * `original_usage.*`. Every vector here is defined by the exact key the
 * calculator has to read, so a seed that went through the SDK would exercise
 * the normaliser instead of the format detection and pass for the wrong reason.
 *
 * The trace itself is a REST write too: the bridge's `/traces` route emits
 * through `@opik.track`, which would add a root span carrying no model and no
 * usage. It prices at nothing, but it would break the "the trace carries
 * exactly the seeded spans" read the spec opens with.
 *
 * Teardown deletes the trace, and with it its spans, from a `finally` that
 * opens the moment the id exists: every write runs BEFORE `use()`, and the
 * trace's own rolled-up cost is one of the things asserted, so a failure must
 * not leave priced spans behind for the next run to roll up.
 */
export const test = baseTest.extend<NativeUsageShapeSpansFixtures>({
  nativeUsageShapeSpans: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const spans: NativeUsageShapeSeed[] = SPAN_SEEDS.map((seed) => ({
      ...seed,
      name: `${testNamespace}-${seed.key}`,
    }));

    const traceId = uuid7();
    const now = new Date();

    try {
      await backendClient.createTraceWithSource({
        id: traceId,
        projectName: project.name,
        name: `${testNamespace}-native-usage-shape-trace`,
        source: 'sdk',
        input: { question: 'seeded native provider usage shapes' },
        output: { answer: 'seeded native provider usage shapes' },
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
          // Verbatim, and no `total_cost`: a caller that supplies a cost is not
          // exercising server-side pricing at all.
          usage: span.usage,
        });
      }

      const ref: NativeUsageShapeSpansRef = {
        traceId,
        spans,
        expectedTraceCost: spans.reduce((acc, s) => acc + s.expectedCost, 0),
      };

      await testInfo.attach('opik.nativeUsageShapeSpans', {
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
            `[nativeUsageShapeSpans fixture] delete warning for trace ${traceId}:`,
            err,
          );
        }
      }
    }
  },
});

export { expect } from './version-history-seed.fixture';
