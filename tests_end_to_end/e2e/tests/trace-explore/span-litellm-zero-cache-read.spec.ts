import { test, expect } from '@e2e/fixtures';
import type { LitellmCacheShadowSpansRef } from '@e2e/fixtures';
import type { BackendClient, SpanCostRef } from '@e2e/core/backend';

/**
 * A zero cache-read reported next to another client's count does not shadow it
 * (opik#8987, issue-7773).
 *
 * LiteLLM reports a cache read under any of three names, and `CostService`
 * reads all three in one expression. It used to take the first name PRESENT —
 * and a client that saw no cache reads still sends its zero, so a span carrying
 * `cache_read_input_tokens: 0` beside another client's
 * `prompt_tokens_details.cached_tokens: 200` had the 200 shadowed by the 0 and
 * billed every cached token at the full input rate. The PR replaced `first()`
 * with `max()`.
 *
 * This is the sharpest regression guard in that change and the cheapest thing
 * to get wrong again: the two spellings sit in a single expression, so any
 * future edit to it can reintroduce the shadow, and the result is a plausible
 * dollar amount roughly 14% too high on the page people read to decide their
 * spend.
 *
 * API-only, deliberately. All four amounts fall under `formatCost`'s $0.01
 * floor, so the panel renders "<$0.01" for every one of them — a UI assertion
 * here would add a rendering failure mode while asserting nothing about which
 * key was read. `span-cross-shape-cache-keys.spec.ts` is where this PR's
 * rendering is asserted, on the one vector whose amounts a browser can
 * discriminate.
 *
 * Three comparisons, each needing a different vector:
 *
 *  - `zero-shadow` must equal `no-shadow`, which is the fix itself: adding the
 *    explicit zero is provably a no-op.
 *  - `genuinely-no-cache` must be DEARER, which is what separates "the zero was
 *    honoured" from "the cache is billed regardless of what was reported" — a
 *    backend that ignored every cache key would satisfy the equality above.
 *  - `double-named` pins the other direction of `max()`: one read reported
 *    under two names must bill once, not twice.
 */

/** The LiteLLM cache-read name whose present-but-zero value used to win. */
const BARE_CACHE_READ_KEY = 'original_usage.cache_read_input_tokens';
/** The LiteLLM prompt-details name carrying the count that must not be shadowed. */
const PROMPT_DETAILS_CACHED_KEY = 'original_usage.prompt_tokens_details.cached_tokens';

/**
 * Every seeded span, by name, once all of them are queryable.
 *
 * The count is asserted rather than only the lookup: a read that also returned
 * spans from another trace would still find every seeded one and pass.
 */
async function readSeededSpans(
  backendClient: BackendClient,
  projectId: string,
  seed: LitellmCacheShadowSpansRef,
): Promise<Map<string, SpanCostRef>> {
  await expect
    .poll(
      async () =>
        (await backendClient.listSpanCosts({ projectId, traceId: seed.traceId })).length,
      { timeout: 60_000, intervals: [500, 1_000, 2_000] },
    )
    .toBe(seed.spans.length);

  const spans = await backendClient.listSpanCosts({ projectId, traceId: seed.traceId });
  expect(spans, 'the trace carries exactly the seeded spans').toHaveLength(seed.spans.length);
  return new Map(spans.map((s) => [s.name, s]));
}

/**
 * The span seeded under `key`, asserted to have landed before it is used.
 *
 * A vector the fixture failed to seed would otherwise surface as an `undefined`
 * the arithmetic below had to code around, which is the shape that turns a
 * broken seed into a green run.
 */
function spanFor(
  byName: Map<string, SpanCostRef>,
  seed: LitellmCacheShadowSpansRef,
  key: string,
): SpanCostRef {
  const vector = seed.spans.find((s) => s.key === key);
  expect(vector, `the fixture must seed a '${key}' vector`).toBeDefined();
  const span = byName.get(vector!.name);
  expect(span, `span ${vector!.name} was seeded`).toBeDefined();
  return span!;
}

/**
 * The cost the server attributed to the vector seeded under `key`.
 *
 * Asserts a cost is present before returning it: every vector is on a model the
 * price table resolves, so an absent cost is a failure, and a `?? 0` would turn
 * the equality below into a comparison between two zeros — which a backend that
 * priced nothing at all would satisfy.
 */
function costOf(
  byName: Map<string, SpanCostRef>,
  seed: LitellmCacheShadowSpansRef,
  key: string,
): number {
  const span = spanFor(byName, seed, key);
  expect(
    span.totalEstimatedCost,
    `${key} must be priced server-side — an absent cost is a failure, not a zero`,
  ).not.toBeNull();
  return span.totalEstimatedCost!;
}

test.describe(
  'Span cost — a zero cache-read does not shadow another client\'s count',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'a cache read reported beside an explicit zero is still billed once, at the cache rate',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ litellmCacheShadowSpans, project, backendClient }) => {
        const byName = await test.step('Read the seeded spans back once all of them are queryable', async () =>
          readSeededSpans(backendClient, project.id, litellmCacheShadowSpans));

        const cost = (key: string) => costOf(byName, litellmCacheShadowSpans, key);

        await test.step('The explicit zero really reached storage (seed shape)', async () => {
          // The guard that stops this spec testing the wrong thing entirely. If
          // the write path dropped the zero, `zero-shadow` would be byte-for-byte
          // `no-shadow` and their equality would hold for a reason that has
          // nothing to do with shadowing — the spec would be green having
          // verified nothing. Asserted as `toBe(0)` rather than as falsy, so a
          // key that came back absent fails here.
          const zeroShadow = spanFor(byName, litellmCacheShadowSpans, 'zero-shadow');
          expect(zeroShadow.usage, 'usage logged for zero-shadow').not.toBeNull();
          expect(
            zeroShadow.usage![BARE_CACHE_READ_KEY],
            'zero-shadow must really store its explicit 0, or it is not testing shadowing',
          ).toBe(0);
          expect(
            zeroShadow.usage![PROMPT_DETAILS_CACHED_KEY],
            'zero-shadow must also carry the count the zero could shadow',
          ).toBe(litellmCacheShadowSpans.cachedTokens);

          // And the twin must NOT carry the zero, or the pair is two copies of
          // the same vector.
          const noShadow = spanFor(byName, litellmCacheShadowSpans, 'no-shadow');
          expect(noShadow.usage, 'usage logged for no-shadow').not.toBeNull();
          expect(
            noShadow.usage![BARE_CACHE_READ_KEY],
            'no-shadow is the half of the pair without the zero key, and must carry no such key',
          ).toBeUndefined();
        });

        await test.step('Every vector is priced at the hand-computed table amount', async () => {
          // Absolute amounts first: they pin the arithmetic to
          // `claude-haiku-4-5`'s own shipped rates rather than merely to
          // itself. Two spans agreeing on a wrong number would satisfy the
          // equality below and nothing else.
          for (const seed of litellmCacheShadowSpans.spans) {
            expect(
              cost(seed.key),
              `${seed.key} (${seed.reports}): ${seed.workings}`,
            ).toBeCloseTo(seed.expectedCost, 9);
          }
        });

        await test.step('Adding the explicit zero is provably a no-op', async () => {
          // The fix, stated as the comparison a reader of the PR would make.
          // The two spans report the identical prompt, completion and cached
          // counts and differ ONLY in the present-but-zero second name, so a
          // shadow of any size would separate them.
          expect(
            cost('zero-shadow'),
            "a cache_read_input_tokens of 0 reported beside another client's count of 200 must change nothing — reading the zero in preference to the count is the regression",
          ).toBeCloseTo(cost('no-shadow'), 9);
        });

        await test.step('And the cache really was billed as a cache, not merely billed', async () => {
          // The half that makes the equality above a statement about shadowing
          // rather than about the cache being ignored outright. Without this a
          // backend that never read a cache key would price `zero-shadow` and
          // `no-shadow` identically too — at the full input rate — and pass.
          expect(
            cost('zero-shadow'),
            'the shadowed vector must be strictly CHEAPER than an identical span that genuinely reports no cached tokens, because 200 tokens moved to the $0.1/M cache rate from the $1/M input rate',
          ).toBeLessThan(cost('genuinely-no-cache'));
          expect(
            cost('genuinely-no-cache') - cost('zero-shadow'),
            'the discount is exactly 200 tokens at the difference between $1/M input and $0.1/M cache read',
          ).toBeCloseTo(litellmCacheShadowSpans.cachedTokens * (1e-6 - 1e-7), 9);
        });

        await test.step('One read reported under two names is billed once, not twice', async () => {
          // The other direction of the same expression. `max()` over the three
          // names takes one count; a sum over them would take 400, and a reader
          // that billed the cache without taking it out of the input would come
          // out at $0.00172 instead of $0.00152. The absolute assertion above
          // already pins which of those happened — this states why that number
          // and not the other.
          const doubleNamed = spanFor(byName, litellmCacheShadowSpans, 'double-named');
          expect(doubleNamed.usage, 'usage logged for double-named').not.toBeNull();
          expect(
            doubleNamed.usage![BARE_CACHE_READ_KEY],
            'double-named must really carry the count under both names',
          ).toBe(litellmCacheShadowSpans.cachedTokens);
          expect(
            doubleNamed.usage![PROMPT_DETAILS_CACHED_KEY],
            'double-named must really carry the count under both names',
          ).toBe(litellmCacheShadowSpans.cachedTokens);

          // The two candidate answers, both stated from the table's own rates
          // rather than derived from what the server returned — a hypothetical
          // computed off the actual would move with it and compare nothing:
          //
          //   billed once   1000 x $1/M + 200 x $0.1/M + 100 x $5/M -> $0.00152
          //   billed twice  1200 x $1/M + 200 x $0.1/M + 100 x $5/M -> $0.00172
          //
          // They differ by exactly the 200 cached tokens at the full input
          // rate, which is what "left in the input bucket as well" costs.
          const BILLED_ONCE = 0.00152;
          const BILLED_TWICE = 0.00172;
          expect(
            BILLED_TWICE - BILLED_ONCE,
            'the two candidate answers must differ by the cached tokens at the input rate, or this comparison is not about double-billing',
          ).toBeCloseTo(litellmCacheShadowSpans.cachedTokens * 1e-6, 9);
          expect(
            cost('double-named'),
            `200 cached tokens named twice must bill once ($${BILLED_ONCE}), not $${BILLED_TWICE} — which is what leaving them in the input bucket as well would give`,
          ).toBeCloseTo(BILLED_ONCE, 9);
        });
      },
    );
  },
);
