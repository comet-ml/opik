import { test as baseTest } from './studio-allowlist-runs.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/**
 * The instrumentation scope Claude Code / the Claude Agent SDK export under —
 * `OpenTelemetryMappingRuleFactory.CLAUDE_CODE_INSTRUMENTATION`.
 *
 * Sent so the export is the shape a real client produces. It is NOT what
 * selects the Claude Code rules: `isClaudeCodeSpan` decides that per span, from
 * the span NAME's `claude_code.` prefix, because one OTLP batch can mix scopes.
 * Both are asserted — the scope through the stored `metadata.integration`, the
 * prefix through the usage keys the rules promote.
 */
export const CLAUDE_CODE_SCOPE = 'com.anthropic.claude_code.tracing';

/**
 * The one span name Claude Code gives an LLM call.
 *
 * Every vector below carries it VERBATIM, which is why each gets its own trace:
 * the name is the Claude Code routing's only trigger, so suffixing it to make
 * two spans distinguishable inside one trace would be testing a name no client
 * ever emits. One span per trace keeps the name exact and makes the read back
 * unambiguous — and "this trace holds exactly one span" is a stronger guard
 * than a name lookup inside a shared trace.
 */
export const CLAUDE_CODE_LLM_SPAN_NAME = 'claude_code.llm_request';

/**
 * The model Claude Code reports, and the row the amounts below come from.
 *
 * The BARE `claude-sonnet-4-5` deliberately, not the Bedrock-dated id
 * `cacheWriteTtlSpans` moved to: Claude Code talks to Anthropic directly and
 * reports the plain model id, and `OpenTelemetryMapper` hard-sets the provider
 * to `anthropic` for every Claude Code span because no attribute carries one.
 * The bare row no longer publishes a 200K tier, which does not matter here —
 * every vector is a ~1.3k-token prompt, far below any tier, so the only rates
 * in play are the base ones:
 *
 *   input_cost_per_token                       3e-06
 *   output_cost_per_token                    1.5e-05
 *   cache_read_input_token_cost                3e-07
 *   cache_creation_input_token_cost         3.75e-06   <- the 5-minute rate
 *   cache_creation_input_token_cost_above_1hr  6e-06   <- the 1-hour rate
 */
export const CLAUDE_CODE_MODEL = 'claude-sonnet-4-5';

/**
 * The attribute names Claude Code reports usage under — its own short spellings,
 * which `OpenTelemetryMappingUtils.USAGE_KEYS_MAPPING` normalises on the way in
 * (`input_tokens`→`prompt_tokens`, `output_tokens`→`completion_tokens`,
 * `cache_read_tokens`→`cache_read_input_tokens`,
 * `cache_creation_tokens`→`cache_creation_input_tokens`).
 *
 * Named here because the spec asserts the normalisation itself: the cache-write
 * amount can only come out right if the count landed in the bucket
 * `SpanCostCalculator` reads, and a count left under the short name would be
 * billed nothing while the span still looked complete.
 */
export const CLAUDE_CODE_USAGE_ATTR = {
  input: 'input_tokens',
  output: 'output_tokens',
  cacheRead: 'cache_read_tokens',
  cacheCreation: 'cache_creation_tokens',
} as const;

/**
 * The usage key an explicit 1-hour TTL arrives under, as `CacheWrites.of` reads
 * it, carried on the control vector through the global `gen_ai.usage.` prefix
 * rule (Claude Code's own rule set promotes only its four short names, and
 * unmapped attributes on a Claude Code span fall into metadata rather than
 * usage).
 */
export const CACHE_CREATION_1H_USAGE_KEY = 'cache_creation.ephemeral_1h_input_tokens';

/**
 * A Claude Code attribute NO mapping rule names, carried on every vector.
 *
 * It is the handle on the one routing decision that is specific to this
 * integration: for a `claude_code.`-prefixed span the default bucket for an
 * unmapped attribute is METADATA, where for every other producer it is INPUT.
 * `permission_mode` is a real Claude Code attribute and matches no rule in any
 * rule set, exact or prefix — so where it lands is decided purely by that
 * default.
 */
export const CLAUDE_CODE_UNMAPPED_ATTR = 'permission_mode';
export const CLAUDE_CODE_UNMAPPED_VALUE = 'acceptEdits';

/** Per-token rates for a cache WRITE on `claude-sonnet-4-5`, by reported TTL. */
export const CLAUDE_CODE_CACHE_WRITE_RATES = {
  fiveMinutes: 3.75e-6,
  oneHour: 6e-6,
} as const;

const INPUT_TOKENS = 1_234;
const OUTPUT_TOKENS = 26;
const CACHE_READ_TOKENS = 100;
const CACHE_CREATION_TOKENS = 50;

/** Everything in the bill that is identical across the pair, in dollars. */
const SHARED_TERMS =
  INPUT_TOKENS * 3e-6 + OUTPUT_TOKENS * 1.5e-5 + CACHE_READ_TOKENS * 3e-7;

export interface ClaudeCodeOtelSpanSeed {
  /** Stable, namespace-free handle a spec looks a vector up by. */
  key: string;
  /** The Opik trace this vector's single span was exported onto. */
  traceId: string;
  /**
   * The `session.id` the export reported — Claude Code's thread grouping key,
   * which its rule set maps to `metadata.thread_id` rather than leaving in
   * place.
   */
  sessionId: string;
  /**
   * Whether this vector reported a TTL at all. `none` is the Claude Code
   * payload under test; `1h` is the control.
   */
  reportedTtl: 'none' | '1h';
  /**
   * The cost the shipped price table says this vector costs.
   *
   * Never sent: the export carries token counts and no `gen_ai.usage.cost`, so
   * the price is the server's own answer and this is only what it is compared
   * against.
   */
  expectedCost: number;
  /** The arithmetic behind `expectedCost`, for the assertion message. */
  workings: string;
  /** The `usage` map the span must be STORED with, after ingestion's rewrite. */
  storedUsage: Record<string, number>;
}

export interface ClaudeCodeOtelCacheSpansRef {
  spans: ClaudeCodeOtelSpanSeed[];
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
  cacheCreationTokens: number;
}

export interface ClaudeCodeOtelCacheSpansFixtures {
  claudeCodeOtelCacheSpans: ClaudeCodeOtelCacheSpansRef;
}

/** The stored usage every vector shares, before its TTL key is added. */
const BASE_STORED_USAGE: Record<string, number> = {
  prompt_tokens: INPUT_TOKENS,
  completion_tokens: OUTPUT_TOKENS,
  // Derived by ingestion when the export omits it, not reported.
  total_tokens: INPUT_TOKENS + OUTPUT_TOKENS,
  cache_read_input_tokens: CACHE_READ_TOKENS,
  cache_creation_input_tokens: CACHE_CREATION_TOKENS,
};

/**
 * The Claude Code payload, and the explicit-1h control beside it.
 *
 * Claude Code reports a single `cache_creation_tokens` count and NO TTL at all.
 * `CacheWrites.of` therefore finds neither TTL key and attributes the whole
 * total to the 5-minute bucket — the cheaper of the two rates. That is the
 * behaviour opik#9068 settled on, and it settled on it by reverting a change
 * made earlier in the same PR: pricing every Claude Code write at the 1-hour
 * rate overcharged per-token users by 60% on the write term.
 *
 * Which is why the control is not optional. "Writes bill at the 5-minute rate"
 * and "the 1-hour rate is never applied to anything" produce the identical
 * number on the vector under test, and only the second is a bug. The control
 * differs from it in ONE attribute — an explicit
 * `cache_creation.ephemeral_1h_input_tokens` — and must come out dearer by
 * exactly the written tokens times the rate difference.
 *
 *   no-ttl        1234 x $3/M + 26 x $15/M + 100 x $0.3/M + 50 x $3.75/M -> $0.0043095
 *   explicit-1h   1234 x $3/M + 26 x $15/M + 100 x $0.3/M + 50 x $6/M    -> $0.0044220
 *
 * Both amounts are far under `formatCost`'s `<$0.01` floor, so the panel
 * renders them identically and no UI assertion could tell the two rates apart —
 * confirmed on screen during the 2.2.131 staging pass. That is why the spec
 * driving this fixture opens no page.
 */
const SPAN_SEEDS: Array<Omit<ClaudeCodeOtelSpanSeed, 'traceId' | 'sessionId'>> = [
  {
    key: 'no-ttl',
    reportedTtl: 'none',
    expectedCost: SHARED_TERMS + CACHE_CREATION_TOKENS * CLAUDE_CODE_CACHE_WRITE_RATES.fiveMinutes,
    workings:
      '1234 x $3/M in + 26 x $15/M out + 100 x $0.3/M cache read + 50 x $3.75/M cache write ' +
      'at the 5-MINUTE rate, because no TTL was reported',
    storedUsage: { ...BASE_STORED_USAGE },
  },
  {
    key: 'explicit-1h',
    reportedTtl: '1h',
    expectedCost: SHARED_TERMS + CACHE_CREATION_TOKENS * CLAUDE_CODE_CACHE_WRITE_RATES.oneHour,
    workings:
      '1234 x $3/M in + 26 x $15/M out + 100 x $0.3/M cache read + 50 x $6/M cache write ' +
      'at the 1-HOUR rate, which this vector reports explicitly',
    storedUsage: {
      ...BASE_STORED_USAGE,
      [CACHE_CREATION_1H_USAGE_KEY]: CACHE_CREATION_TOKENS,
    },
  },
];

/**
 * Two Claude Code OTel spans, each on its own trace, exported through
 * `POST /v1/private/otel/v1/traces` with token counts and **no**
 * `gen_ai.usage.cost`, so the backend has to price them itself.
 *
 * Real OTLP ingestion rather than a REST span write, and that is the whole
 * point: `POST /v1/private/spans` takes `model`, `provider` and a finished
 * `usage` map as literal fields, which skips every mapping under test here —
 * the `claude_code.` name gate, the hard-set `anthropic` provider, the short
 * usage names being normalised, and the default-bucket choice that keeps
 * Claude Code's session attributes out of `input`. A REST write would assert
 * the price table and nothing else.
 *
 * One trace per vector, for the reason {@link CLAUDE_CODE_LLM_SPAN_NAME} gives.
 * Both traces are created up front and torn down together: every write happens
 * BEFORE `use()`, so a failure in any one of them would otherwise skip the only
 * cleanup there is and leave priced spans behind — and priced spans are exactly
 * what poisons the next run's cost assertions.
 */
export const test = baseTest.extend<ClaudeCodeOtelCacheSpansFixtures>({
  claudeCodeOtelCacheSpans: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    // Client-generated, and generated BEFORE any write: the ids teardown needs
    // therefore exist whatever the writes do. A POST that commits server-side
    // but loses its response would otherwise leave priced spans behind with
    // nothing to delete them.
    const spans: ClaudeCodeOtelSpanSeed[] = SPAN_SEEDS.map((seed) => ({
      ...seed,
      traceId: uuid7(),
      sessionId: `${testNamespace}-${seed.key}-session`,
    }));

    try {
      for (const span of spans) {
        await backendClient.createTraceWithSource({
          id: span.traceId,
          projectName: project.name,
          name: `${testNamespace}-claude-code-${span.key}`,
          source: 'sdk',
          input: { question: 'seeded Claude Code cache-write TTL cost resolution' },
          output: { answer: 'seeded Claude Code cache-write TTL cost resolution' },
        });

        await backendClient.postOtelSpans({
          projectName: project.name,
          opikTraceId: span.traceId,
          scopeName: CLAUDE_CODE_SCOPE,
          spans: [
            {
              name: CLAUDE_CODE_LLM_SPAN_NAME,
              attributes: {
                [CLAUDE_CODE_USAGE_ATTR.input]: INPUT_TOKENS,
                [CLAUDE_CODE_USAGE_ATTR.output]: OUTPUT_TOKENS,
                [CLAUDE_CODE_USAGE_ATTR.cacheRead]: CACHE_READ_TOKENS,
                [CLAUDE_CODE_USAGE_ATTR.cacheCreation]: CACHE_CREATION_TOKENS,
                // A string, as the real attribute is: the MODEL rule reads
                // `value.getStringValue()`, so an int would resolve to "" and
                // leave the span unpriced.
                model: CLAUDE_CODE_MODEL,
                // Claude Code's thread grouping key, which its rule set maps to
                // `metadata.thread_id`. Sent on every vector so it cannot
                // explain a difference between them.
                'session.id': span.sessionId,
                // An attribute no rule names, so where it lands is decided by
                // the integration's default bucket alone.
                [CLAUDE_CODE_UNMAPPED_ATTR]: CLAUDE_CODE_UNMAPPED_VALUE,
                // The control's one extra attribute. Namespaced, because
                // Claude Code's rule set promotes only its four short usage
                // names and anything it does not know lands in metadata — the
                // global `gen_ai.usage.` prefix rule is what carries this into
                // the usage map, under the dotted key `CacheWrites` reads.
                ...(span.reportedTtl === '1h'
                  ? { [`gen_ai.usage.${CACHE_CREATION_1H_USAGE_KEY}`]: CACHE_CREATION_TOKENS }
                  : {}),
              },
            },
          ],
        });
      }

      const ref: ClaudeCodeOtelCacheSpansRef = {
        spans,
        inputTokens: INPUT_TOKENS,
        outputTokens: OUTPUT_TOKENS,
        cacheReadTokens: CACHE_READ_TOKENS,
        cacheCreationTokens: CACHE_CREATION_TOKENS,
      };

      await testInfo.attach('opik.claudeCodeOtelCacheSpans', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      // Swallow-and-warn, never rethrow: a delete that fails here would replace
      // whichever seeding or assertion error actually broke the test.
      if (!shouldLeaveArtifacts(testInfo)) {
        try {
          await backendClient.deleteTraces(spans.map((s) => s.traceId));
        } catch (err) {
          console.warn('[claudeCodeOtelCacheSpans fixture] delete warning:', err);
        }
      }
    }
  },
});

export { expect } from './studio-allowlist-runs.fixture';
