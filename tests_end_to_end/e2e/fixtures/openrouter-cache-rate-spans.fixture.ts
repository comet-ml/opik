import { expect } from '@playwright/test';
import { test as baseTest } from './prompt-mixed-source-experiments.fixture';
import { CACHED_TOKENS_KEY } from './cached-token-spans.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { displayedCost } from '../core/money';
import { uuid7 } from '../core/backend';

/**
 * One half of a cached/plain pair on an OpenRouter row, seeded as an LLM span.
 */
export interface OpenRouterCacheRateSpanSeed {
  /** Stable, namespace-free handle a spec looks a vector up by. */
  key: string;
  name: string;
  /** The model id as a client logs it — the three-segment spelling the file keys. */
  model: string;
  /** Cached prompt tokens this vector reports, or 0 for the plain half of a pair. */
  cachedTokens: number;
  /**
   * The amount the shipped price file's own rates give at the seeded counts.
   *
   * Never sent: every span is logged with `usage` and no `total_cost`, so the
   * amount is the server's own lookup and this is only what it is compared to.
   */
  expectedCost: number;
  /**
   * What the span would cost if cached prompt tokens were silently DROPPED
   * instead of billed at the input rate — i.e. the other plausible wrong
   * answer, present only on a cached half whose row publishes no cache rate.
   *
   * The pair's equality alone cannot tell "billed at the input rate" from
   * "the cached count never reached the calculator"; this is the amount that
   * has to NOT appear for the equality to mean the former.
   */
  droppedCachedCost?: number;
  /** The arithmetic behind `expectedCost`, for the assertion message. */
  workings: string;
}

export interface OpenRouterCacheRateSpansRef {
  traceId: string;
  spans: OpenRouterCacheRateSpanSeed[];
  promptTokens: number;
  completionTokens: number;
  cachedTokens: number;
  /** Sum of `expectedCost` — what the trace must roll up to. */
  expectedTraceCost: number;
}

export interface OpenRouterCacheRateSpansFixtures {
  openRouterCacheRateSpans: OpenRouterCacheRateSpansRef;
}

/**
 * A million prompt and a million completion tokens, half the prompt cached.
 *
 * The same counts `openRouterRepricedSpans` uses, for the same reason:
 * OpenRouter's rows are cheap, and at realistic token counts every amount here
 * would fall under `formatCost`'s $0.01 floor and render as `<$0.01`, so the
 * panel half could not tell one span from another. Half the prompt cached
 * rather than a token sliver so that a dropped-cached-token bug moves the
 * amount by a third of the input charge, well clear of any rounding.
 */
const PROMPT_TOKENS = 1_000_000;
const COMPLETION_TOKENS = 1_000_000;
const CACHED_TOKENS = 500_000;

/** Every seeded model id carries the `provider` a real OpenRouter call would. */
const PROVIDER = 'openrouter';

/**
 * Three cached/plain pairs on OpenRouter rows, differing within each pair ONLY
 * in whether the span reports cached prompt tokens.
 *
 * opik#9834 regenerated `model_prices_openrouter.json` and **two rows lost
 * `cache_read_input_token_cost` outright** — `openrouter/google/gemma-3-4b-it`
 * and `openrouter/moonshotai/kimi-k2.5`. That removal is a routing change, not
 * a rate change: `SpanCostCalculator.openAiCost` prices cached prompt tokens at
 * `orInputPrice(cacheReadPrice, inputPrice)`, so a row with no cache rate bills
 * every cached token at the **full input rate** and a span reporting cache hits
 * costs exactly what its uncached twin costs. Nothing in the estate asserted
 * this for OpenRouter — `span-cached-token-cost.spec.ts` carries only an OpenAI
 * pair — and `openrouter` is not in `UsageFormats.FORMAT_BY_PROVIDER`, so it
 * reads its usage through the OpenAI format and the OpenAI branch is the one
 * that prices it.
 *
 * As 2.3.7 ships the file:
 *
 *   openrouter/google/gemma-3-4b-it        $0.05/M in,  $0.10/M out, NO cache rate
 *   openrouter/moonshotai/kimi-k2.5        $0.60/M in,  $3.00/M out, NO cache rate
 *   openrouter/minimax/minimax-m3.1-flash  $0.075/M in, $0.30/M out, $0.0075/M cache read
 *
 * At 1M prompt + 1M completion with 500k of the prompt cached:
 *
 *   gemma cached    1M x 0.05/M (cache billed as input) + 1M x 0.10/M  -> $0.15
 *   gemma plain     1M x 0.05/M + 1M x 0.10/M                          -> $0.15   (identical)
 *   kimi cached     1M x 0.60/M (cache billed as input) + 1M x 3.00/M  -> $3.60
 *   kimi plain      1M x 0.60/M + 1M x 3.00/M                          -> $3.60   (identical)
 *   minimax cached  500k x 0.075/M + 500k x 0.0075/M + 1M x 0.30/M     -> $0.34125
 *   minimax plain   1M x 0.075/M + 1M x 0.30/M                         -> $0.375
 *
 * `minimax-m3.1-flash` is the control, and it is what makes the two equalities
 * a statement about the price table rather than about the seed: it still
 * publishes a cache-read rate a tenth of its input rate, so its cached half
 * must come out strictly cheaper, by exactly 500k times the rate difference.
 * Without it, "cached tokens bought no discount" would be indistinguishable
 * from "the cached-token count never reached the calculator at all".
 *
 * `droppedCachedCost` closes the same gap from the other side. If the removal
 * had made the backend drop cached tokens instead of billing them as input,
 * gemma's cached half would be $0.125 and kimi's $3.30 — both perfectly
 * ordinary-looking amounts. The spec asserts those do NOT appear.
 *
 * **These amounts track a GENERATED artifact and are expected to need
 * updating.** `model_prices_openrouter.json` is regenerated from OpenRouter's
 * own model list by `scripts/update_openrouter_model_prices.py`. When a pair
 * goes red, read the row out of
 * `apps/opik-backend/src/main/resources/model_prices_openrouter.json` BEFORE
 * treating a changed amount as a finding — and if one of the two no-cache rows
 * regains a `cache_read_input_token_cost`, that pair SHOULD go red. Move the
 * vector onto whichever row then publishes none and keep the comparison, which
 * is the durable behaviour. (`openrouter-repriced-spans.fixture.ts` carries the
 * same warning, and both of its pinned amounts needed exactly this treatment
 * in this release.)
 */
const SPAN_SEEDS: Array<Omit<OpenRouterCacheRateSpanSeed, 'name'>> = [
  {
    key: 'gemma-no-cache-rate-cached',
    model: 'openrouter/google/gemma-3-4b-it',
    cachedTokens: CACHED_TOKENS,
    expectedCost: 0.15,
    droppedCachedCost: 0.125,
    workings:
      '500k x $0.05/M in + 500k cached x $0.05/M (no cache rate, so billed as input) + ' +
      '1.0M x $0.10/M out',
  },
  {
    key: 'gemma-no-cache-rate-plain',
    model: 'openrouter/google/gemma-3-4b-it',
    cachedTokens: 0,
    expectedCost: 0.15,
    workings: '1.0M x $0.05/M in + 1.0M x $0.10/M out',
  },
  {
    key: 'kimi-no-cache-rate-cached',
    model: 'openrouter/moonshotai/kimi-k2.5',
    cachedTokens: CACHED_TOKENS,
    expectedCost: 3.6,
    droppedCachedCost: 3.3,
    workings:
      '500k x $0.60/M in + 500k cached x $0.60/M (no cache rate, so billed as input) + ' +
      '1.0M x $3.00/M out',
  },
  {
    key: 'kimi-no-cache-rate-plain',
    model: 'openrouter/moonshotai/kimi-k2.5',
    cachedTokens: 0,
    expectedCost: 3.6,
    workings: '1.0M x $0.60/M in + 1.0M x $3.00/M out',
  },
  {
    key: 'cache-rate-cached',
    model: 'openrouter/minimax/minimax-m3.1-flash',
    cachedTokens: CACHED_TOKENS,
    expectedCost: 0.34125,
    workings:
      '500k x $0.075/M in + 500k cached x $0.0075/M cache read + 1.0M x $0.30/M out ' +
      '(the control: this row still publishes a cache rate)',
  },
  {
    key: 'cache-rate-plain',
    model: 'openrouter/minimax/minimax-m3.1-flash',
    cachedTokens: 0,
    expectedCost: 0.375,
    workings: '1.0M x $0.075/M in + 1.0M x $0.30/M out',
  },
];

/** The two pairs whose row publishes no cache-read rate, cached half first. */
export const NO_CACHE_RATE_PAIRS: Array<{ cached: string; plain: string; model: string }> = [
  {
    cached: 'gemma-no-cache-rate-cached',
    plain: 'gemma-no-cache-rate-plain',
    model: 'openrouter/google/gemma-3-4b-it',
  },
  {
    cached: 'kimi-no-cache-rate-cached',
    plain: 'kimi-no-cache-rate-plain',
    model: 'openrouter/moonshotai/kimi-k2.5',
  },
];

/**
 * One trace carrying six LLM spans that report `usage` and **no** `total_cost`,
 * three of them reporting cached prompt tokens.
 *
 * Written straight to `POST /v1/private/spans` rather than through the bridge,
 * for the reason `cachedTokenSpans` does it: the cached-token key is the entire
 * subject, and the Python SDK rewrites usage keys as it re-emits them. A seed
 * whose key the bridge renamed or dropped would price exactly like the plain
 * half of its own pair — that is, the fixture would MANUFACTURE the equality the
 * spec is looking for. The spec reads the stored `usage` back and asserts the
 * key survived before it compares a single cost, which is the other half of
 * that guard. The three-segment model spelling matters for the same reason:
 * the price-key parse splits on the first slash, so every character has to
 * arrive exactly as written.
 *
 * The trace is a REST write too: the bridge's `/traces` route emits through
 * `@opik.track`, which would add a root span carrying no model and no usage and
 * break the "the trace carries exactly the seeded spans" read.
 *
 * Teardown deletes the trace, and with it its spans, from a `finally` that
 * opens the moment the id exists: every write runs BEFORE `use()`, and the
 * trace's own rolled-up cost is one of the things asserted, so a failure must
 * not leave priced spans behind.
 */
export const test = baseTest.extend<OpenRouterCacheRateSpansFixtures>({
  openRouterCacheRateSpans: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const spans: OpenRouterCacheRateSpanSeed[] = SPAN_SEEDS.map((seed) => ({
      ...seed,
      name: `${testNamespace}-${seed.key}`,
    }));

    const byKey = new Map(spans.map((s) => [s.key, s]));
    const seedFor = (key: string): OpenRouterCacheRateSpanSeed => {
      const seed = byKey.get(key);
      // Thrown rather than non-null-asserted: a typo in a pair above would
      // otherwise surface as an `undefined.expectedCost` deep in the checks
      // below, where it reads like a broken assertion instead of a broken seed.
      if (!seed) throw new Error(`[openRouterCacheRateSpans fixture] no seed keyed '${key}'`);
      return seed;
    };

    // The premises every per-span assertion rests on, asserted before anything
    // is written. A seed that quietly failed any of these would leave the spec
    // comparing two numbers that agree for the wrong reason and passing.
    for (const pair of NO_CACHE_RATE_PAIRS) {
      const cached = seedFor(pair.cached);
      const plain = seedFor(pair.plain);
      expect(cached.model, `${pair.cached} is on ${pair.model}`).toBe(pair.model);
      expect(plain.model, `both halves of the ${pair.model} pair are the same row`).toBe(
        cached.model,
      );
      expect(cached.cachedTokens, `${pair.cached} must report cached tokens`).toBe(CACHED_TOKENS);
      expect(plain.cachedTokens, `${pair.plain} must report none`).toBe(0);
      expect(
        cached.expectedCost,
        `${pair.model} publishes no cache-read rate, so the pair must expect the same amount`,
      ).toBeCloseTo(plain.expectedCost, 9);
      expect(
        cached.droppedCachedCost,
        `${pair.cached} must pin the dropped-token amount, or the pair's equality cannot ` +
          'tell "billed at the input rate" from "the cached count never arrived"',
      ).toBeDefined();
      expect(
        cached.droppedCachedCost!,
        `the dropped-token amount must DIFFER from the expected one for ${pair.model}, ` +
          'otherwise asserting it away proves nothing',
      ).not.toBeCloseTo(cached.expectedCost, 9);
    }

    const controlCached = seedFor('cache-rate-cached');
    const controlPlain = seedFor('cache-rate-plain');
    expect(controlCached.model, 'both halves of the control pair are the same row').toBe(
      controlPlain.model,
    );
    expect(
      controlCached.expectedCost,
      'the control row still publishes a cache-read rate, so its cached half must expect LESS',
    ).toBeLessThan(controlPlain.expectedCost);
    expect(
      controlCached.droppedCachedCost,
      'the control pair asserts a discount, not the absence of one, so it pins no dropped amount',
    ).toBeUndefined();

    // The amounts the panel half reads. Within a no-cache pair both halves
    // render the SAME string — that is the finding — so the spec pins which
    // span is selected by id; ACROSS the three rows they must differ, or the
    // panel half could not tell one row's amount from another's.
    const perRow = [
      seedFor('gemma-no-cache-rate-cached').expectedCost,
      seedFor('kimi-no-cache-rate-cached').expectedCost,
      controlCached.expectedCost,
      controlPlain.expectedCost,
    ];
    expect(
      new Set(perRow.map((cost) => displayedCost(cost))).size,
      'the three rows must RENDER as distinct strings (displayedCost floors to cents)',
    ).toBe(perRow.length);

    // Client-generated, and generated BEFORE the write, so the id teardown
    // needs exists whatever the write does.
    const traceId = uuid7();
    const now = new Date();

    try {
      await backendClient.createTraceWithSource({
        id: traceId,
        projectName: project.name,
        name: `${testNamespace}-openrouter-cache-rate-trace`,
        source: 'sdk',
        input: { question: 'seeded OpenRouter cache-rate removal' },
        output: { answer: 'seeded OpenRouter cache-rate removal' },
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
          provider: PROVIDER,
          usage: {
            prompt_tokens: PROMPT_TOKENS,
            completion_tokens: COMPLETION_TOKENS,
            total_tokens: PROMPT_TOKENS + COMPLETION_TOKENS,
            // Only on the cached half. An explicit zero would still route the
            // control model through its cache branch, which is a third
            // vector's worth of behaviour and not this pair's comparison.
            ...(span.cachedTokens > 0 ? { [CACHED_TOKENS_KEY]: span.cachedTokens } : {}),
            // No `total_cost` — deliberately. See the doc comment above.
          },
        });
      }

      const ref: OpenRouterCacheRateSpansRef = {
        traceId,
        spans,
        promptTokens: PROMPT_TOKENS,
        completionTokens: COMPLETION_TOKENS,
        cachedTokens: CACHED_TOKENS,
        expectedTraceCost: spans.reduce((acc, s) => acc + s.expectedCost, 0),
      };

      await testInfo.attach('opik.openRouterCacheRateSpans', {
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
            `[openRouterCacheRateSpans fixture] delete warning for trace ${traceId}:`,
            err,
          );
        }
      }
    }
  },
});

export { expect } from './prompt-mixed-source-experiments.fixture';
