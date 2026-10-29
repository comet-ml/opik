import { test as baseTest } from './cache-write-ttl-spans.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';
import type { OtelSpanSeed } from '../core/backend';

/**
 * How a dialect's span has to reach the backend.
 *
 * `rest` writes the usage map verbatim to `POST /v1/private/spans`, which is
 * the only way a specific key SPELLING can be seeded: the Python SDK
 * normalises whatever it is handed, so a dotted or camelCase key sent through
 * the bridge arrives under some other name and the vector would be testing a
 * dialect nobody sends.
 *
 * `otlp` goes through `POST /v1/private/otel/v1/traces` as `gen_ai.usage.*`
 * attributes. That one cannot be faked by a REST write and is the half the
 * backend's own unit tests structurally cannot see: OTLP ingestion rewrites
 * `input_tokens` to `prompt_tokens` and `output_tokens` to `completion_tokens`
 * on the way in while leaving the dotted cache keys alone, so which variant
 * the usage then matches is decided by the ingestion mapping rather than by
 * what the instrumentation sent.
 */
export type CacheDialectWriter = 'rest' | 'otlp';

export interface CacheDialectSpanSeed {
  /** Stable, namespace-free handle a spec looks a vector up by. */
  key: string;
  name: string;
  /** The client or convention this spelling comes from, for assertion messages. */
  dialect: string;
  writer: CacheDialectWriter;
  /**
   * The usage map exactly as it must reach the backend, for `writer: 'rest'`.
   *
   * Empty for the OTLP vector, whose usage is derived from its OTel attributes
   * by the ingestion mapper and so cannot be stated here.
   */
  usage: Record<string, number>;
  /**
   * The WHOLE usage map as it must come back out of storage.
   *
   * Asserted as an equality, not a subset: "stored verbatim" is the claim, and
   * a subset check would pass over a write path that silently added a
   * normalised duplicate of every key — which would make the cost assertion
   * below evidence about the duplicate rather than about the spelling.
   *
   * For a REST vector this is the sent map, spelling and case intact. For the
   * OTLP one it is the shape AFTER ingestion's own rewrite, which is the point
   * of stating it separately: that rewrite is part of what the equal cost is
   * evidence about.
   */
  storedUsage: Record<string, number>;
}

export interface CacheDialectSpansRef {
  traceId: string;
  spans: CacheDialectSpanSeed[];
  /** The one cost every dialect must resolve to. */
  expectedCost: number;
  /** The arithmetic behind `expectedCost`, for the assertion message. */
  workings: string;
  /** Sum of `expectedCost` across every span — the trace's rolled-up total. */
  expectedTraceCost: number;
}

export interface CacheDialectSpansFixtures {
  cacheDialectSpans: CacheDialectSpansRef;
}

/**
 * `claude-haiku-4-5` / `anthropic`, whose published rates make every bucket of
 * the semantic usage below bill at a rate of its own:
 *
 *   input_cost_per_token             1e-06
 *   cache_read_input_token_cost      1e-07
 *   cache_creation_input_token_cost  1.25e-06
 *   output_cost_per_token            5e-06
 *
 * Four distinct rates matter: a dialect whose cache-read count landed in the
 * fresh-input bucket, or whose write landed in the read bucket, prices
 * differently from one that read them correctly. A model that priced reads and
 * writes the same would make several of these spellings indistinguishable.
 */
const MODEL = 'claude-haiku-4-5';
const PROVIDER = 'anthropic';

/**
 * ONE semantic usage, which every vector below expresses in its own spelling:
 * 400 fresh input tokens, 500 read from the cache, 100 written to it at the
 * 5-minute TTL, 200 of output.
 *
 * Anthropic reports the cache OUTSIDE `input_tokens`, so the prompt total is
 * 400 + 500 + 100 = 1000 — comfortably below the model's tier boundary, which
 * keeps every expectation on the base rates and the whole comparison about key
 * names rather than about tiers.
 */
const FRESH_INPUT = 400;
const CACHE_READ = 500;
const CACHE_WRITE_5M = 100;
const OUTPUT = 200;
/** The prompt total every dialect must resolve to, cache included. */
const PROMPT_TOTAL = FRESH_INPUT + CACHE_READ + CACHE_WRITE_5M;

/**
 * 400 x 1e-06 + 500 x 1e-07 + 100 x 1.25e-06 + 200 x 5e-06 = $0.001575.
 *
 * Under `formatCost`'s $0.01 floor, deliberately: this spec's subject is the
 * resolved amount, and holding every one of eight vectors to a rendered
 * `<$0.01` would assert nothing. The panel half below drives only the two
 * vectors whose rendering is worth a browser, and it reads the API's own value.
 */
const EXPECTED_COST = 0.001575;
const WORKINGS = '400 x $1/M in + 500 x $0.1/M cache read + 100 x $1.25/M 5m cache write + 200 x $5/M out';

/** The vector whose rendering the UI half of the spec reads. */
export const CACHE_DIALECT_NATIVE_KEY = 'anthropic-native';
/** The OTLP-ingested vector, the only one no REST write could stand in for. */
export const CACHE_DIALECT_OTLP_KEY = 'otlp-ingested';

/**
 * Eight spellings of the identical usage, one per client convention Opik
 * receives.
 *
 * Each targets a specific `InputVariant` of `AnthropicUsageFormat`, which are
 * checked in order, so the set is also a statement about which variant each
 * spelling lands on:
 *
 *   anthropic-native     anthropic_native  Anthropic's Messages API as-is
 *   litellm              litellm           LiteLLM's prompt_tokens + details
 *   otel-genai-dotted    otel_genai        OTel GenAI's dotted cache keys
 *   openinference        openinference      OpenInference's prompt/completion
 *   sdk-original-usage   anthropic_native  the Python SDK's original_usage.* nesting
 *   camel-case           anthropic_native  Bedrock Converse's camelCase
 *   gen-ai-namespaced    otel_genai        the gen_ai.usage. namespace left on
 *   otlp-ingested        otel_genai        real OTLP ingestion
 *
 * The LiteLLM, OTel-GenAI, OpenInference and namespaced vectors report a
 * prompt of 1000 that INCLUDES the cache, because that is what those clients
 * send; the native, SDK-nested and camelCase ones report 400 with the cache
 * beside it. Recovering one convention from the other is `VariantInput`'s job,
 * and the fact that all eight come out at the same amount is the assertion.
 *
 * Why this is worth a spec at all: the backend's unit tests cover the variants
 * in isolation, but nothing covered whether a dotted or camelCase key SURVIVES
 * the write path into ClickHouse and back out. A key renamed in transit prices
 * exactly like a key that was never read — so the spec asserts the stored
 * usage first, and only then the cost.
 */
const SPAN_SEEDS: Array<Omit<CacheDialectSpanSeed, 'name' | 'storedUsage'> &
  Partial<Pick<CacheDialectSpanSeed, 'storedUsage'>>> = [
  {
    key: CACHE_DIALECT_NATIVE_KEY,
    dialect: "Anthropic's Messages API, as-is",
    writer: 'rest',
    usage: {
      input_tokens: FRESH_INPUT,
      cache_read_input_tokens: CACHE_READ,
      cache_creation_input_tokens: CACHE_WRITE_5M,
      'cache_creation.ephemeral_5m_input_tokens': CACHE_WRITE_5M,
      output_tokens: OUTPUT,
    },
  },
  {
    key: 'litellm',
    dialect: 'LiteLLM — prompt_tokens with the prompt details beside it',
    writer: 'rest',
    usage: {
      prompt_tokens: PROMPT_TOTAL,
      'prompt_tokens_details.cached_tokens': CACHE_READ,
      cache_creation_input_tokens: CACHE_WRITE_5M,
      completion_tokens: OUTPUT,
    },
  },
  {
    key: 'otel-genai-dotted',
    dialect: 'OTel GenAI — dotted cache_read.input_tokens',
    writer: 'rest',
    usage: {
      input_tokens: PROMPT_TOTAL,
      'cache_read.input_tokens': CACHE_READ,
      'cache_creation.input_tokens': CACHE_WRITE_5M,
      output_tokens: OUTPUT,
    },
  },
  {
    key: 'openinference',
    dialect: 'OpenInference — prompt / completion with prompt_details',
    writer: 'rest',
    usage: {
      prompt: PROMPT_TOTAL,
      'prompt_details.cache_read': CACHE_READ,
      'prompt_details.cache_write': CACHE_WRITE_5M,
      completion: OUTPUT,
    },
  },
  {
    key: 'sdk-original-usage',
    dialect: "the Python SDK's original_usage.* nesting, with its own totals outside",
    writer: 'rest',
    usage: {
      // The SDK's own OpenAI-shaped totals, which is what makes this nesting
      // the shape it is: the provider's usage is kept verbatim underneath.
      prompt_tokens: PROMPT_TOTAL,
      completion_tokens: OUTPUT,
      total_tokens: PROMPT_TOTAL + OUTPUT,
      'original_usage.input_tokens': FRESH_INPUT,
      'original_usage.cache_read_input_tokens': CACHE_READ,
      'original_usage.cache_creation_input_tokens': CACHE_WRITE_5M,
      'original_usage.cache_creation.ephemeral_5m_input_tokens': CACHE_WRITE_5M,
      'original_usage.output_tokens': OUTPUT,
    },
  },
  {
    key: 'camel-case',
    dialect: 'Bedrock Converse — camelCase key names',
    writer: 'rest',
    usage: {
      inputTokens: FRESH_INPUT,
      cacheReadInputTokens: CACHE_READ,
      cacheCreationInputTokens: CACHE_WRITE_5M,
      outputTokens: OUTPUT,
    },
  },
  {
    key: 'gen-ai-namespaced',
    dialect: 'an instrumentation that left the gen_ai.usage. namespace on every key',
    writer: 'rest',
    usage: {
      'gen_ai.usage.input_tokens': PROMPT_TOTAL,
      'gen_ai.usage.cache_read.input_tokens': CACHE_READ,
      'gen_ai.usage.cache_creation.input_tokens': CACHE_WRITE_5M,
      'gen_ai.usage.output_tokens': OUTPUT,
    },
  },
  {
    key: CACHE_DIALECT_OTLP_KEY,
    dialect: 'real OTLP ingestion of gen_ai.usage.* attributes',
    writer: 'otlp',
    usage: {},
    // Ingestion's own rewrite, which is why this is the one vector whose stored
    // shape has to be spelled out: `input_tokens` and `output_tokens` are
    // stored under the OpenAI names and a `total_tokens` is derived, while the
    // dotted cache keys are left exactly as the instrumentation sent them. The
    // namespace is stripped from all four. Asserting that is what makes this
    // vector's equal cost evidence about the ingestion mapping rather than an
    // assumption about it.
    storedUsage: {
      prompt_tokens: PROMPT_TOTAL,
      completion_tokens: OUTPUT,
      total_tokens: PROMPT_TOTAL + OUTPUT,
      'cache_read.input_tokens': CACHE_READ,
      'cache_creation.input_tokens': CACHE_WRITE_5M,
    },
  },
];

/**
 * One trace carrying eight LLM spans that all report the SAME semantic usage in
 * eight different spellings, with `usage` and **no** `total_cost`.
 *
 * Seven are REST writes, because a key spelling is the whole subject and the
 * Python SDK would normalise it away (see `CacheDialectWriter`). The eighth
 * goes through OTLP ingestion, which no REST write can stand in for: it is the
 * producer that genuinely sends dotted `gen_ai.usage.*` attributes, and the
 * mapping it applies on the way in is part of what the equal cost is evidence
 * about.
 *
 * The trace is created first and handed to the OTLP export as `opik.trace_id`,
 * the way `otelProviderAliasSpans` does, so all eight spans land on one trace
 * and a single `listSpanCosts({ traceId })` read covers both producers.
 *
 * Teardown deletes the trace, and with it its spans, from a `finally` that
 * opens the moment the id exists: every write runs BEFORE `use()`, so a failure
 * in any one would otherwise skip the only cleanup and leave an already-priced
 * trace behind.
 */
export const test = baseTest.extend<CacheDialectSpansFixtures>({
  cacheDialectSpans: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const spans: CacheDialectSpanSeed[] = SPAN_SEEDS.map((seed) => ({
      ...seed,
      name: `${testNamespace}-${seed.key}`,
      // A REST write is stored verbatim, so its sent map IS its expected
      // stored map — stating it twice per vector would only create a way for
      // the two to drift. Only the OTLP vector, whose usage is rewritten on
      // the way in, declares its own.
      storedUsage: seed.storedUsage ?? seed.usage,
    }));

    const traceId = uuid7();
    const now = new Date();

    try {
      await backendClient.createTraceWithSource({
        id: traceId,
        projectName: project.name,
        name: `${testNamespace}-cache-dialect-trace`,
        source: 'sdk',
        input: { question: 'seeded cache usage dialect parity' },
        output: { answer: 'seeded cache usage dialect parity' },
        startTime: now,
        endTime: now,
      });

      for (const span of spans.filter((s) => s.writer === 'rest')) {
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

      const otlpSpans: OtelSpanSeed[] = spans
        .filter((s) => s.writer === 'otlp')
        .map((span) => ({
          name: span.name,
          attributes: {
            // `gen_ai.provider.name` and not `gen_ai.system`: the deprecated
            // attribute stays authoritative, so a seed carrying both would
            // resolve down a different branch.
            'gen_ai.provider.name': PROVIDER,
            'gen_ai.request.model': MODEL,
            // Numbers, so they arrive as OTLP `intValue` — `extractUsageField`
            // reads `hasIntValue()` and skips a count that arrived as a string,
            // which would leave this span unpriced and the equality below
            // trivially unsatisfiable.
            'gen_ai.usage.input_tokens': PROMPT_TOTAL,
            'gen_ai.usage.cache_read.input_tokens': CACHE_READ,
            'gen_ai.usage.cache_creation.input_tokens': CACHE_WRITE_5M,
            'gen_ai.usage.output_tokens': OUTPUT,
          },
        }));
      if (otlpSpans.length > 0) {
        await backendClient.postOtelSpans({
          projectName: project.name,
          opikTraceId: traceId,
          spans: otlpSpans,
        });
      }

      const ref: CacheDialectSpansRef = {
        traceId,
        spans,
        expectedCost: EXPECTED_COST,
        workings: WORKINGS,
        expectedTraceCost: EXPECTED_COST * spans.length,
      };

      await testInfo.attach('opik.cacheDialectSpans', {
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
          console.warn(`[cacheDialectSpans fixture] delete warning for trace ${traceId}:`, err);
        }
      }
    }
  },
});

export { expect } from './cache-write-ttl-spans.fixture';
