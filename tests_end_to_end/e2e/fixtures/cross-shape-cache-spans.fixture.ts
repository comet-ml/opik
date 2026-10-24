import { test as baseTest } from './new-provider-cost-spans.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/**
 * One vector of the cross-shape matrix, and the cache-free twin it is read
 * against.
 *
 * The twin is not optional decoration. A single span priced at the expected
 * amount cannot tell "the cache key was read" apart from "the price table
 * moved"; a pair whose only difference is the cache key, compared by its delta,
 * cannot tell "read at the cache rate" apart from "read at some other rate".
 * Both halves together pin it: the absolute amount fixes the rates, the delta
 * fixes which bucket the cached tokens landed in.
 */
export interface CrossShapeCacheVector {
  /** Stable, namespace-free handle a spec looks a vector up by. */
  key: string;
  name: string;
  /** The client shape this spelling comes from, for assertion messages. */
  shape: string;
  model: string;
  provider: string;
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
  /**
   * The key the twin drops, and the per-token rate difference dropping it
   * makes. Present on the cache-bearing half of each pair only.
   */
  twin?: {
    /** The `key` of the vector this one is compared against. */
    key: string;
    /**
     * `expectedCost` of this vector minus the twin's, as the table's own rates
     * make it.
     *
     * SIGNED, and the sign is load-bearing. A cache READ reported inside the
     * input discounts those tokens, so the delta is negative. A cache WRITE
     * reported OUTSIDE the input (Anthropic's convention) adds tokens nobody
     * had billed yet, so the delta is positive — a surcharge. Asserting an
     * unsigned magnitude would let a read priced as a write pass.
     */
    delta: number;
    /** What the delta is, for the assertion message. */
    deltaWorkings: string;
  };
}

export interface CrossShapeCacheSpansRef {
  /** The trace carrying the six API-level vectors. */
  apiTraceId: string;
  spans: CrossShapeCacheVector[];
  /** Sum of `expectedCost` across the six — the API trace's rolled-up total. */
  expectedTraceCost: number;
  /** A second trace carrying the two spans whose amounts the panel can render. */
  panelTraceId: string;
  panelSpans: CrossShapeCacheVector[];
  /** Sum of `expectedCost` across the two panel spans. */
  expectedPanelTraceCost: number;
  /** The token total both panel spans report, identically. */
  panelTotalTokens: number;
}

export interface CrossShapeCacheSpansFixtures {
  crossShapeCacheSpans: CrossShapeCacheSpansRef;
}

/**
 * `claude-haiku-4-5` / `anthropic` — $1/M in, $0.1/M cache read,
 * $1.25/M cache write, $5/M out — and `gpt-4o` / `openai` — $2.5/M in,
 * $1.25/M cache read, $10/M out.
 *
 * Both are rows whose cache rate DIFFERS from their input rate, which is what
 * makes each pair's delta a statement about which bucket the cached tokens
 * landed in. A row pricing cache reads at the input rate would make every pair
 * here equal and the spec unable to fail.
 */
const HAIKU = { model: 'claude-haiku-4-5', provider: 'anthropic' } as const;
const GPT_4O = { model: 'gpt-4o', provider: 'openai' } as const;

/**
 * Three usage SHAPES that no longer have to come from the provider whose format
 * named them, each with a cache-free twin.
 *
 * This is what opik#8987 changed: a client can log a cache count under a key
 * belonging to some other vendor's format — the OpenAI Agents SDK driving
 * Claude, a Bedrock wrapper reporting camelCase, a Gemini-shaped usage logged
 * against an OpenAI-compatible gateway — and the count has to be found and
 * priced anyway. The estate's existing cache spec varies the provider and the
 * price table; nothing varied the usage SHAPE for a fixed provider, which is
 * the axis this PR moves.
 *
 * Every amount below is one of the PR's own `CostServiceTest` rows, which is
 * deliberate: seeding them through `POST /v1/private/spans` and reading them
 * back checks that the REST ingestion path agrees with the unit tests, rather
 * than that the code agrees with itself.
 *
 *   A  OpenAI-Responses keys on an ANTHROPIC span
 *      input_tokens 1000 + input_tokens_details.cached_tokens 200, outer
 *      prompt_tokens 1000. 800 x $1/M + 200 x $0.1/M + 100 x $5/M -> $0.00132
 *      twin: the same without the cache key                       -> $0.0015
 *      delta -$0.00018 = 200 x ($1/M - $0.1/M)
 *
 *   B  Bedrock camelCase keys on an ANTHROPIC span
 *      inputTokens 800 / cacheReadInputTokens 200 / cacheWriteInputTokens 50.
 *      800 x $1/M + 200 x $0.1/M + 50 x $1.25/M + 100 x $5/M -> $0.0013825
 *      twin: the same without the WRITE key                   -> $0.00132
 *      delta +$0.0000625 = 50 x $1.25/M — a surcharge, because Anthropic
 *      reports the cache outside input_tokens, so those 50 tokens were in no
 *      bucket until the write key was read.
 *
 *   C  Gemini keys on an OPENAI span
 *      prompt_token_count 1000 + cached_content_token_count 200, with the SDK's
 *      own prompt_tokens/completion_tokens outside.
 *      800 x $2.5/M + 200 x $1.25/M + 100 x $10/M -> $0.00325
 *      twin: the same without the cache key        -> $0.0035
 *      delta -$0.00025 = 200 x ($2.5/M - $1.25/M)
 *
 * Vector C only reaches a variant at all through the nested-usage fallback:
 * none of OpenAI's own input variants match `prompt_token_count`, so
 * `UsageFormats.normalize` re-reads the provider keys together with the SDK's
 * outer totals — which is why the outer `prompt_tokens`/`completion_tokens` are
 * part of the seed rather than noise beside it.
 */
const API_VECTORS: Array<Omit<CrossShapeCacheVector, 'name'>> = [
  {
    key: 'openai-responses-cached',
    shape: "OpenAI-Responses keys on an Anthropic span (the OpenAI Agents SDK driving Claude)",
    ...HAIKU,
    usage: {
      // The SDK's converted total, outside the provider usage. 1000 here
      // against an input of 1000 plus a cache of 200 is what tells
      // `VariantInput` the input INCLUDES the cache: were it 1200 the clause
      // would decide the other way. The `input-includes-cache` spec is where
      // that decision is the subject; here it is simply the real shape.
      prompt_tokens: 1000,
      'original_usage.input_tokens': 1000,
      'original_usage.input_tokens_details.cached_tokens': 200,
      'original_usage.output_tokens': 100,
    },
    expectedCost: 0.00132,
    workings: '800 x $1/M in + 200 x $0.1/M cache read + 100 x $5/M out',
    twin: {
      key: 'openai-responses-plain',
      delta: -0.00018,
      deltaWorkings: '200 cached tokens at the difference between $1/M input and $0.1/M cache read',
    },
  },
  {
    key: 'openai-responses-plain',
    shape: 'the same OpenAI-Responses usage with no cache key at all',
    ...HAIKU,
    usage: {
      prompt_tokens: 1000,
      'original_usage.input_tokens': 1000,
      'original_usage.output_tokens': 100,
    },
    expectedCost: 0.0015,
    workings: '1000 x $1/M in + 100 x $5/M out',
  },
  {
    key: 'bedrock-camel-write',
    shape: 'Bedrock camelCase keys on an Anthropic span, reporting a cache WRITE',
    ...HAIKU,
    usage: {
      'original_usage.inputTokens': 800,
      'original_usage.cacheReadInputTokens': 200,
      'original_usage.cacheWriteInputTokens': 50,
      'original_usage.outputTokens': 100,
    },
    expectedCost: 0.0013825,
    workings:
      '800 x $1/M in + 200 x $0.1/M cache read + 50 x $1.25/M cache write + 100 x $5/M out',
    twin: {
      key: 'bedrock-camel-nowrite',
      // Positive: see CrossShapeCacheVector.twin.delta.
      delta: 0.0000625,
      deltaWorkings:
        '50 written tokens at the $1.25/M cache-creation rate, ADDED rather than discounted — Anthropic reports the cache outside input_tokens, so nothing had billed them yet',
    },
  },
  {
    key: 'bedrock-camel-nowrite',
    shape: 'the same Bedrock camelCase usage with no cache-write key',
    ...HAIKU,
    usage: {
      'original_usage.inputTokens': 800,
      'original_usage.cacheReadInputTokens': 200,
      'original_usage.outputTokens': 100,
    },
    expectedCost: 0.00132,
    workings: '800 x $1/M in + 200 x $0.1/M cache read + 100 x $5/M out',
  },
  {
    key: 'gemini-key-cached',
    shape: "Gemini's cached_content_token_count on an OpenAI span",
    ...GPT_4O,
    usage: {
      // The SDK's own totals, which are what the nested-usage fallback reads
      // the Gemini keys together with. See the note on vector C above.
      prompt_tokens: 1000,
      completion_tokens: 100,
      'original_usage.prompt_token_count': 1000,
      'original_usage.cached_content_token_count': 200,
    },
    expectedCost: 0.00325,
    workings: '800 x $2.5/M in + 200 x $1.25/M cache read + 100 x $10/M out',
    twin: {
      key: 'gemini-key-plain',
      delta: -0.00025,
      deltaWorkings:
        '200 cached tokens at the difference between $2.5/M input and $1.25/M cache read',
    },
  },
  {
    key: 'gemini-key-plain',
    shape: 'the same Gemini-shaped usage with no cache key',
    ...GPT_4O,
    usage: {
      prompt_tokens: 1000,
      completion_tokens: 100,
      'original_usage.prompt_token_count': 1000,
    },
    expectedCost: 0.0035,
    workings: '1000 x $2.5/M in + 100 x $10/M out',
  },
];

/**
 * The one vector from this PR's matrix whose amounts a browser can read, and
 * its twin.
 *
 * Every API vector above falls under `formatCost`'s $0.01 floor and would
 * render the identical "<$0.01", so a panel assertion over them would assert
 * nothing. This pair is the production usage the PR's issue was reported
 * against — a Gemini-shaped usage logged under Bedrock — scaled x10 so both
 * halves clear the floor.
 *
 * `anthropic.claude-3-5-haiku-20241022-v1:0` / `bedrock`: $0.8/M in,
 * $0.08/M cache read, $4/M out. Both spans report the identical 560,970-token
 * total and differ ONLY in the `cached_content_token_count` key this PR taught
 * the reader to find:
 *
 *   panel-cached   9,400 x $0.8/M + 547,190 x $0.08/M + 4,380 x $4/M -> $0.0688152  "$0.06"
 *   panel-plain    556,590 x $0.8/M            + 4,380 x $4/M        -> $0.462792   "$0.46"
 *   trace header                                                       $0.5316072   "$0.53"
 *
 * That is the whole point of putting it on the page: the panel's Token usage
 * block renders the raw usage map and nothing else, so the resolved COST is the
 * only place the cache-aware arithmetic becomes visible to a user at all —
 * which is also why the two amounts differ by almost 7x on identical token
 * counts. These two sit on their own trace so the header can be asserted as
 * their sum without the six API vectors in it.
 */
const PANEL_TOTAL_TOKENS = 560_970;
const PANEL_VECTORS: Array<Omit<CrossShapeCacheVector, 'name'>> = [
  {
    key: 'panel-cached',
    shape: "Gemini's cached_content_token_count on a Bedrock Claude span, at production scale",
    model: 'anthropic.claude-3-5-haiku-20241022-v1:0',
    provider: 'bedrock',
    usage: {
      prompt_tokens: 556_590,
      completion_tokens: 4_380,
      total_tokens: PANEL_TOTAL_TOKENS,
      'original_usage.prompt_token_count': 556_590,
      'original_usage.candidates_token_count': 4_380,
      'original_usage.cached_content_token_count': 547_190,
    },
    expectedCost: 0.0688152,
    workings:
      '9,400 x $0.8/M in (the prompt less the cache) + 547,190 x $0.08/M cache read + 4,380 x $4/M out',
    twin: {
      key: 'panel-plain',
      delta: -0.3939768,
      deltaWorkings:
        '547,190 cached tokens at the difference between $0.8/M input and $0.08/M cache read',
    },
  },
  {
    key: 'panel-plain',
    shape: 'the same production usage with the cache key removed',
    model: 'anthropic.claude-3-5-haiku-20241022-v1:0',
    provider: 'bedrock',
    usage: {
      prompt_tokens: 556_590,
      completion_tokens: 4_380,
      total_tokens: PANEL_TOTAL_TOKENS,
      'original_usage.prompt_token_count': 556_590,
      'original_usage.candidates_token_count': 4_380,
    },
    expectedCost: 0.462792,
    workings: '556,590 x $0.8/M in + 4,380 x $4/M out',
  },
];

/**
 * Two traces: six LLM spans whose amounts only the API can discriminate, and
 * two whose amounts the panel can render.
 *
 * All eight report `usage` and **no** `total_cost`, so the price is the
 * backend's own answer. All eight are written straight to
 * `POST /v1/private/spans` — the usage key SPELLING is the entire subject, and
 * the Python SDK rewrites keys as it re-emits them, so a camelCase or
 * cross-vendor key sent through the bridge would arrive under some other name
 * and the vector would be testing a shape nobody sends. The spec reads the
 * stored usage back and asserts each key survived before it compares a cost,
 * which is the other half of that guard.
 *
 * Two traces rather than one so each roll-up assertion means something: the
 * six API vectors sum to an amount under the display floor, the two panel
 * vectors sum to the $0.53 the trace header must show, and one trace carrying
 * all eight could be asserted as neither.
 *
 * Teardown deletes both traces, and with them their spans, from a `finally`
 * that opens the moment the ids exist: every write runs BEFORE `use()`, and
 * both rolled-up costs are asserted, so a failure must not leave priced spans
 * behind.
 */
export const test = baseTest.extend<CrossShapeCacheSpansFixtures>({
  crossShapeCacheSpans: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const named = (seeds: Array<Omit<CrossShapeCacheVector, 'name'>>): CrossShapeCacheVector[] =>
      seeds.map((seed) => ({ ...seed, name: `${testNamespace}-${seed.key}` }));
    const spans = named(API_VECTORS);
    const panelSpans = named(PANEL_VECTORS);

    const apiTraceId = uuid7();
    const panelTraceId = uuid7();
    const now = new Date();

    try {
      for (const [traceId, label, vectors] of [
        [apiTraceId, 'cross-shape-api', spans],
        [panelTraceId, 'cross-shape-panel', panelSpans],
      ] as const) {
        await backendClient.createTraceWithSource({
          id: traceId,
          projectName: project.name,
          name: `${testNamespace}-${label}-trace`,
          source: 'sdk',
          input: { question: `seeded ${label} cache key pricing` },
          output: { answer: `seeded ${label} cache key pricing` },
          startTime: now,
          endTime: now,
        });

        for (const vector of vectors) {
          await backendClient.createSpan({
            id: uuid7(),
            traceId,
            projectName: project.name,
            name: vector.name,
            source: 'sdk',
            type: 'llm',
            model: vector.model,
            provider: vector.provider,
            usage: vector.usage,
            // No `total_cost` — deliberately. See the doc comment above.
          });
        }
      }

      const ref: CrossShapeCacheSpansRef = {
        apiTraceId,
        spans,
        expectedTraceCost: spans.reduce((acc, s) => acc + s.expectedCost, 0),
        panelTraceId,
        panelSpans,
        expectedPanelTraceCost: panelSpans.reduce((acc, s) => acc + s.expectedCost, 0),
        panelTotalTokens: PANEL_TOTAL_TOKENS,
      };

      await testInfo.attach('opik.crossShapeCacheSpans', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      // Swallow-and-warn, never rethrow: a delete that fails here would replace
      // whichever seeding or assertion error actually broke the test.
      if (!shouldLeaveArtifacts(testInfo)) {
        try {
          await backendClient.deleteTraces([apiTraceId, panelTraceId]);
        } catch (err) {
          console.warn(
            `[crossShapeCacheSpans fixture] delete warning for traces ${apiTraceId}, ${panelTraceId}:`,
            err,
          );
        }
      }
    }
  },
});

export { expect } from './new-provider-cost-spans.fixture';
