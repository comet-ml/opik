import { test as baseTest } from './alert.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';
import type { SpanSeedUsage } from '../core/sdk';

/**
 * How a seed has to reach the backend.
 *
 * `python-sdk` is the default and what almost every span in the estate is:
 * written through the bridge with `sdkClient.python.createNestedTrace`.
 *
 * `otel-rest` exists for one reason — the Python SDK normalises usage keys.
 * A bare OTel key such as `completion_tokens_details.reasoning_tokens` is
 * re-emitted by the SDK under the `original_usage.` prefix, so a seed aimed at
 * the backend's BARE-key fallback would silently arrive on the primary key and
 * the assertion would pass for the wrong reason. Spans that genuinely carry
 * bare OTel keys arrive via OTel ingestion rather than the SDK, so those seeds
 * are written straight to `POST /v1/private/spans` instead.
 *
 * `raw-usage-rest` is the same bypass for a different reason: a usage key that
 * is not a token count at all. `input_characters` is what a per-character model
 * prices from, and the SDK's usage builders model provider token shapes — so a
 * seed sent through the bridge can arrive with the key dropped, and a span that
 * prices at nothing is indistinguishable from the negative control it is
 * supposed to be discriminated against.
 */
export type ModelCostSpanWriter = 'python-sdk' | 'otel-rest' | 'raw-usage-rest';

export interface ModelCostSpanSeed {
  /** Stable, namespace-free handle a spec can look a vector up by. */
  key: string;
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
  /**
   * Why a vector with `expectedCost: 0` must not be billed, for the assertion
   * message. The controls do not all fail the same way — one is a model id the
   * table must not match, another is a model it does match but whose priced
   * quantity is absent — and a shared message would mis-describe whichever one
   * actually broke.
   */
  zeroCostReason?: string;
  /**
   * Usage keys this span carries on top of the shared prompt/completion counts,
   * spelled exactly as they must reach the backend.
   */
  usageExtras?: Record<string, number>;
  writer: ModelCostSpanWriter;
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
 * The `usage` key an audio-speech model's price is multiplied by. The backend
 * reads it under the `original_usage.` prefix first and bare second, so a seed
 * carrying the bare key exercises the same arithmetic either way.
 */
const INPUT_CHARACTERS_KEY = 'input_characters';

/** The `usage` key SDK 1.6.0+ logs reasoning/thinking tokens under. */
const SDK_REASONING_KEY = 'original_usage.completion_tokens_details.reasoning_tokens';
/** The bare OTel GenAI key the backend falls back to when the prefixed one is absent. */
const OTEL_REASONING_KEY = 'completion_tokens_details.reasoning_tokens';

/**
 * Five vectors over one model, covering reasoning-token billing
 * (`SpanCostCalculator.textGenerationCost`).
 *
 * Reasoning tokens are a SUBSET of `completion_tokens`, so the calculator bills
 * them at `output_cost_per_reasoning_token` and subtracts them from the
 * standard-output bucket rather than billing them at both rates. Two clamps
 * guard the arithmetic: a reported count above `completion_tokens` must not
 * bill more reasoning than there are completion tokens (nor drive the standard
 * bucket negative), and a negative count must floor at zero.
 *
 * `perplexity/sonar-deep-research` is not an arbitrary pick — it is the only
 * model in the shipped price table that can observe this behaviour at all. The
 * vector needs a model that publishes `output_cost_per_reasoning_token`, has no
 * cache price (a cache price routes to `textGenerationWithCacheCost*`, which
 * does not read the reasoning rate), belongs to a provider in
 * `CostService.PROVIDERS_MAPPING` (an unmapped provider never enters the price
 * map, so its cost reads null), AND publishes a reasoning rate that DIFFERS
 * from its output rate — otherwise the split is arithmetically invisible and
 * the spec would pass with the feature removed. Of the 72 entries publishing a
 * reasoning rate, exactly one satisfies all four.
 *
 *   perplexity/sonar-deep-research  $2/M in, $8/M out, $3/M reasoning
 *
 * At 1M prompt + 1M completion tokens that gives, per vector:
 *
 *   no reasoning key   2.00 + 1.0M x 8/M                    -> $10.00
 *   400k reasoning     2.00 + 600k x 8/M + 400k x 3/M       ->  $8.00
 *   1.5M reasoning     2.00 +    0 x 8/M + 1.0M x 3/M       ->  $5.00  (clamped down)
 *   -100k reasoning    2.00 + 1.0M x 8/M                    -> $10.00  (clamped up to 0)
 *
 * The absent-key vector is the control the other four are read against: without
 * it, "reasoning tokens were billed at the reasoning rate" and "the price table
 * moved" are the same observation.
 */
const REASONING_SEEDS: Array<Omit<ModelCostSpanSeed, 'name'>> = [
  {
    key: 'reasoning-absent',
    model: 'perplexity/sonar-deep-research',
    provider: 'perplexity',
    expectedCost: 10,
    writer: 'python-sdk',
  },
  {
    key: 'reasoning-sdk-key',
    model: 'perplexity/sonar-deep-research',
    provider: 'perplexity',
    usageExtras: { [SDK_REASONING_KEY]: 400_000 },
    expectedCost: 8,
    writer: 'python-sdk',
  },
  {
    key: 'reasoning-otel-key',
    model: 'perplexity/sonar-deep-research',
    provider: 'perplexity',
    usageExtras: { [OTEL_REASONING_KEY]: 400_000 },
    expectedCost: 8,
    // Not `python-sdk`, and it cannot be: the SDK rewrites this bare key onto
    // the `original_usage.` one, which would make this vector a duplicate of
    // `reasoning-sdk-key` that reads as fallback coverage. See ModelCostSpanWriter.
    writer: 'otel-rest',
  },
  {
    key: 'reasoning-over-reported',
    model: 'perplexity/sonar-deep-research',
    provider: 'perplexity',
    // More reasoning tokens than there are completion tokens. Clamps to
    // completion_tokens: the whole output bills at the reasoning rate and the
    // standard bucket floors at zero rather than going negative.
    usageExtras: { [SDK_REASONING_KEY]: 1_500_000 },
    expectedCost: 5,
    writer: 'python-sdk',
  },
  {
    key: 'reasoning-negative',
    model: 'perplexity/sonar-deep-research',
    provider: 'perplexity',
    // Clamps up to zero, so this must bill exactly what `reasoning-absent` does.
    usageExtras: { [SDK_REASONING_KEY]: -100_000 },
    expectedCost: 10,
    writer: 'python-sdk',
  },
];

/**
 * Two vectors over one model whose price has no token term at all
 * (`SpanCostCalculator.audioSpeechCost`).
 *
 * `mistral/voxtral-mini-tts-latest` is priced at $1.6e-05 per INPUT CHARACTER
 * and publishes no input or output token rate, so its cost comes from a usage
 * key the other eleven spans do not carry. This is the class of price entry
 * `ModelCostData` has to model field by field: a key it does not declare reads
 * as no price at all, and the span renders with a token count and no cost chip
 * while the trace above it still rolls up a plausible-looking total. Release
 * 2.2.57 moved this model onto `input_cost_per_character`, which is a field the
 * backend does read; three `twelvelabs.marengo-embed-2-7` entries moved onto
 * `input_cost_per_query`, which is not — see the PR description.
 *
 * The two vectors differ ONLY in the character count:
 *
 *   1M input_characters x $1.6e-05  -> $16.00
 *   no input_characters             -> nothing billed
 *
 * Both carry the same 1M prompt + 1M completion tokens as every other seed
 * here, which is what makes the pair discriminating: a cost that came from the
 * token counts rather than the character count would price them identically.
 */
const CHARACTER_PRICE_SEEDS: Array<Omit<ModelCostSpanSeed, 'name'>> = [
  {
    key: 'characters-priced',
    model: 'mistral/voxtral-mini-tts-latest',
    provider: 'mistral',
    usageExtras: { [INPUT_CHARACTERS_KEY]: 1_000_000 },
    expectedCost: 16,
    // Not `python-sdk`: input_characters is not a token count, and a builder
    // that drops it would leave this vector indistinguishable from the control
    // below. See ModelCostSpanWriter.
    writer: 'raw-usage-rest',
  },
  {
    key: 'characters-absent',
    model: 'mistral/voxtral-mini-tts-latest',
    provider: 'mistral',
    expectedCost: 0,
    zeroCostReason:
      'this model is priced per input character and this span reports none; a cost here means the ' +
      'token counts were billed at some rate the model does not publish',
    writer: 'raw-usage-rest',
  },
];

/**
 * Twelve LLM spans: five whose model ids each exercise one step of server-side
 * price resolution (with the two ways it could go wrong), five that cover
 * reasoning-token billing over a single model, and two over a model priced per
 * input character rather than per token.
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
 *
 * See REASONING_SEEDS and CHARACTER_PRICE_SEEDS above for the other two halves.
 */
const SPAN_SEEDS: Array<Omit<ModelCostSpanSeed, 'name'>> = [
  {
    key: 'opus-prefixed',
    // Prefix strip + dot-normalise + compact-date strip + the claude-4-6-opus alias.
    model: 'anthropic/claude-4.6-opus-20260205',
    provider: 'anthropic',
    expectedCost: 30,
    writer: 'python-sdk',
  },
  {
    key: 'haiku-dotted',
    // Dot-normalise only: claude-haiku-4-5-20251001 is a price-table key as-is.
    model: 'claude-haiku-4.5-20251001',
    provider: 'anthropic',
    expectedCost: 6,
    writer: 'python-sdk',
  },
  {
    key: 'gpt-dated',
    // Compact-date strip; the old regex could not do this and read $0.
    model: 'gpt-5.2-20251217',
    provider: 'openai',
    expectedCost: 15.75,
    writer: 'python-sdk',
  },
  {
    key: 'gpt-build-number',
    // Negative control: 8 digits, but a build number, not a date.
    model: 'gpt-5.2-99999999',
    provider: 'openai',
    expectedCost: 0,
    zeroCostReason:
      'this model id has no price in the table; a cost here means the date strip ran on a build ' +
      "number and billed another model's rate",
    writer: 'python-sdk',
  },
  {
    key: 'gpt-impossible-date',
    // Negative control: 8 digits shaped like a date, but month 13 / day 45.
    model: 'gpt-5.2-20251345',
    provider: 'openai',
    expectedCost: 0,
    zeroCostReason:
      'this model id has no price in the table; a cost here means the date strip ran on a build ' +
      "number and billed another model's rate",
    writer: 'python-sdk',
  },
  ...REASONING_SEEDS,
  ...CHARACTER_PRICE_SEEDS,
];

/**
 * One trace carrying twelve LLM spans that report `usage` and **no**
 * `total_cost`, so the backend has to price them itself.
 *
 * Every other cost fixture in the estate (`tracedAgent`, the thread seeds)
 * supplies `total_cost` from the client, which means the server-side price
 * resolution path has never been exercised end to end — a wrong price there is
 * invisible, because the number still renders as a perfectly ordinary cost.
 *
 * Most spans go through the bridge; the ones whose usage key the SDK would
 * normalise or drop are written straight to `POST /v1/private/spans`
 * afterwards, because that key is exactly what they exist to test. Both land on
 * the same trace, so the rolled-up total covers all twelve either way.
 *
 * Teardown deletes the trace (and with it its spans) here rather than in the
 * test: an assertion failure must not leave priced spans behind, since the
 * project's own rolled-up cost is one of the things asserted.
 */
export const test = baseTest.extend<ModelCostSpansFixtures>({
  modelCostSpans: async ({ sdkClient, backendClient, project, testNamespace }, use, testInfo) => {
    const spans: ModelCostSpanSeed[] = SPAN_SEEDS.map((seed) => ({
      ...seed,
      name: `${testNamespace}-${seed.key}`,
    }));

    const usageFor = (span: ModelCostSpanSeed): SpanSeedUsage => ({
      prompt_tokens: PROMPT_TOKENS,
      completion_tokens: COMPLETION_TOKENS,
      total_tokens: PROMPT_TOKENS + COMPLETION_TOKENS,
      ...(span.usageExtras ?? {}),
      // No `total_cost` — deliberately. See the doc comment above.
    });

    const sdkSpans = spans.filter((s) => s.writer === 'python-sdk');
    const restSpans = spans.filter((s) => s.writer !== 'python-sdk');

    const created = await sdkClient.python.createNestedTrace({
      project_name: project.name,
      name: `${testNamespace}-cost-trace`,
      input: { question: 'seeded model cost resolution' },
      output: { answer: 'seeded model cost resolution' },
      spans: sdkSpans.map((span) => ({
        name: span.name,
        type: 'llm' as const,
        model: span.model,
        provider: span.provider,
        usage: usageFor(span),
      })),
    });

    if (created.span_count !== sdkSpans.length) {
      throw new Error(
        `[modelCostSpans fixture] expected ${sdkSpans.length} spans, bridge reported ${created.span_count}`,
      );
    }

    for (const span of restSpans) {
      await backendClient.createSpan({
        id: uuid7(),
        traceId: created.id,
        projectName: project.name,
        name: span.name,
        source: 'sdk',
        type: 'llm',
        model: span.model,
        provider: span.provider,
        usage: usageFor(span),
      });
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
