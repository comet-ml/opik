import {
  test,
  expect,
  CACHE_WRITE_5M_KEY,
  CACHE_WRITE_1H_KEY,
  CACHE_WRITE_RATES,
} from '@e2e/fixtures';
import type { CacheWriteTtlSpanSeed, CacheWriteTtlSpansRef, PromptTier } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { displayedCost } from '@e2e/core/money';
import type { BackendClient, SpanCostRef } from '@e2e/core/backend';

/**
 * Anthropic cache WRITES billed at the TTL they were made at, on both sides of
 * the model's 200k prompt-token price tier (opik#8941).
 *
 * `cache_creation_input_token_cost_above_1hr` is new in that PR, and nothing in
 * the estate had a vector for it. `span-cached-token-cost.spec.ts` covers cache
 * READS and whether a model publishes a rate for them at all; the write side is
 * a different arithmetic path (`AnthropicUsageFormat` splits a write into a
 * 5-minute and a 1-hour bucket, and `SpanCostCalculator.anthropicCost` prices
 * the two buckets at two different rates), and no existing seed reaches it.
 *
 * A 1-hour write is the dearer one — Anthropic charges more to hold a prefix
 * for an hour than for five minutes — so within each pair the 1h vector must
 * cost MORE than its 5-minute twin. Each pair is otherwise identical: same
 * model, same fresh-input count, same written-token count, same output. That is
 * what makes the comparison discriminating, and it is why the pairs are seeded
 * rather than single spans: an absolute amount on its own cannot tell "the 1h
 * rate was applied" apart from "the price table moved", and an ordering on its
 * own cannot tell it apart from "neither rate was applied".
 *
 * The failure mode is silent by construction. Both numbers render as perfectly
 * ordinary dollar amounts on the page people read to decide what their LLM
 * spend is; only the pair shows anything.
 *
 * WHY THE ABOVE-TIER TEST IS MARKED `test.fail()`. The PR's own Java unit tests
 * cover the 1h rate on a price fixture with no prompt tiers, and cover tiers
 * with the cache-creation rate pinned to zero — so neither sees the
 * combination, which is the only shape the shipped price table actually
 * contains. `ModelCostData` declares no field for
 * `cache_creation_input_token_cost_above_1hr_above_200k_tokens`, so above 200k
 * the 1h bucket bills at the untiered base rate: half the table's own, and
 * below the tiered 5-minute rate, which inverts the pair. All 16 shipped rows
 * publishing both rates are affected (Claude Sonnet 4.x across `anthropic`,
 * `bedrock`, `bedrock_converse` and `openrouter`). The assertions below are the
 * CORRECT amounts; Playwright reports the test the moment it starts passing.
 */

/**
 * Every seeded span, by name, once all of them are queryable.
 *
 * The count is asserted rather than only the lookup: a read that also returned
 * spans from another trace would still find every seeded one and pass.
 */
async function readSeededSpans(
  backendClient: BackendClient,
  projectId: string,
  seed: CacheWriteTtlSpansRef,
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
  seed: CacheWriteTtlSpansRef,
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
 * Asserts a cost is present before returning it: every vector here is on a
 * model the price table does resolve, so an absent cost is the regression, and
 * a `?? 0` would report it as a comparison between two zeros.
 */
function costOf(
  byName: Map<string, SpanCostRef>,
  seed: CacheWriteTtlSpansRef,
  key: string,
): number {
  const span = spanFor(byName, seed, key);
  expect(
    span.totalEstimatedCost,
    `${key} must be priced server-side — an absent cost is a failure, not a zero`,
  ).not.toBeNull();
  return span.totalEstimatedCost!;
}

/** The two vectors of one tier's pair, 5-minute write first. */
function pairFor(seed: CacheWriteTtlSpansRef, tier: PromptTier): [CacheWriteTtlSpanSeed, CacheWriteTtlSpanSeed] {
  const fiveMinutes = seed.spans.find((s) => s.tier === tier && s.ttl === '5m');
  const oneHour = seed.spans.find((s) => s.tier === tier && s.ttl === '1h');
  expect(fiveMinutes, `the fixture must seed a 5m vector ${tier} the tier`).toBeDefined();
  expect(oneHour, `the fixture must seed a 1h vector ${tier} the tier`).toBeDefined();
  return [fiveMinutes!, oneHour!];
}

/**
 * Both TTL keys really reached the backend on every vector, with the split the
 * vector claims.
 *
 * This runs before any cost is compared, and it is what stops the spec passing
 * for the wrong reason: "the 1h key never arrived" and "1h writes bill at the
 * 5m rate" produce the identical cost, so the keys have to be read off the
 * stored span first. The ZERO half is asserted as explicitly as the count —
 * `CacheWrites.of` derives the 5-minute bucket from the reported total minus
 * the 1h key, so a dropped zero changes the arithmetic rather than nothing.
 */
function assertSeedShape(
  byName: Map<string, SpanCostRef>,
  seed: CacheWriteTtlSpansRef,
  vectors: readonly CacheWriteTtlSpanSeed[],
): void {
  for (const vector of vectors) {
    const span = spanFor(byName, seed, vector.key);
    const usage = span.usage;
    expect(usage, `usage logged for ${vector.key}`).not.toBeNull();

    expect(usage!['input_tokens'], `fresh input tokens for ${vector.key}`).toBe(
      vector.inputTokens,
    );
    expect(usage!['output_tokens'], `output tokens for ${vector.key}`).toBe(seed.outputTokens);
    expect(
      usage![CACHE_WRITE_5M_KEY],
      `${vector.key} must report ${vector.ttl === '5m' ? vector.cacheWriteTokens : 0} tokens written at the 5-minute TTL`,
    ).toBe(vector.ttl === '5m' ? vector.cacheWriteTokens : 0);
    expect(
      usage![CACHE_WRITE_1H_KEY],
      `${vector.key} must report ${vector.ttl === '1h' ? vector.cacheWriteTokens : 0} tokens written at the 1-hour TTL`,
    ).toBe(vector.ttl === '1h' ? vector.cacheWriteTokens : 0);

    // The prompt total is what picks the tier, so the vector only sits on the
    // side of 200k it claims to if this adds up.
    const promptTokens = vector.inputTokens + vector.cacheWriteTokens;
    const expectedSide = promptTokens > seed.tierThreshold ? 'above' : 'below';
    expect(
      expectedSide,
      `${vector.key}: its ${promptTokens}-token prompt must really put it ${vector.tier} the ${seed.tierThreshold} tier, or the rates it is asserted against are the wrong ones`,
    ).toBe(vector.tier);
  }
}

test.describe(
  'Span cost — Anthropic cache writes split by TTL',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'below the 200k prompt tier, a 1-hour cache write bills at the 1-hour rate and costs more than an identical 5-minute one',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ cacheWriteTtlSpans, project, backendClient }) => {
        // No page. The subject is which rate the backend multiplied a token
        // bucket by while pricing the span; the panel renders whatever number
        // came out, so driving a browser here would add a rendering failure
        // mode to an assertion about arithmetic. The panel's rendering of these
        // same four spans is the third test below.

        const byName = await test.step('Read the seeded spans back once all of them are queryable', async () =>
          readSeededSpans(backendClient, project.id, cacheWriteTtlSpans));

        const [fiveMinutes, oneHour] = pairFor(cacheWriteTtlSpans, 'below');

        await test.step('Both vectors really carry the TTL split they claim (seed shape)', async () => {
          assertSeedShape(byName, cacheWriteTtlSpans, [fiveMinutes, oneHour]);
        });

        await test.step("Each vector is priced at the table's own amount", async () => {
          // Absolute amounts first: they are what pins the arithmetic to the
          // shipped rates rather than merely to itself. Two spans agreeing on a
          // wrong number would satisfy the comparisons below and nothing else.
          for (const vector of [fiveMinutes, oneHour]) {
            expect(
              costOf(byName, cacheWriteTtlSpans, vector.key),
              `${vector.key}: ${vector.workings}`,
            ).toBeCloseTo(vector.expectedCost, 6);
          }
        });

        await test.step('The 1-hour write is the dearer of the two', async () => {
          const oneHourCost = costOf(byName, cacheWriteTtlSpans, oneHour.key);
          const fiveMinuteCost = costOf(byName, cacheWriteTtlSpans, fiveMinutes.key);
          expect(
            oneHourCost,
            'a 1-hour cache write is more expensive than a 5-minute one, and these two spans differ in nothing else',
          ).toBeGreaterThan(fiveMinuteCost);
          expect(
            oneHourCost - fiveMinuteCost,
            'the premium is the written tokens at the difference between the $6/M 1h rate and the $3.75/M 5m rate — not some other rate that happens to be dearer',
          ).toBeCloseTo(
            fiveMinutes.cacheWriteTokens *
              (CACHE_WRITE_RATES.below.oneHour - CACHE_WRITE_RATES.below.fiveMinutes),
            6,
          );
        });
      },
    );

    test(
      'above the 200k prompt tier, a 1-hour cache write bills at the tiered 1-hour rate and still costs more than an identical 5-minute one',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ cacheWriteTtlSpans, project, backendClient }) => {
        // QA: failing against main for 2.2.105. Product defect, reproduced at
        // the API and on the page: `cache_creation_input_token_cost_above_1hr`
        // is read untiered (SpanCostCalculator.anthropicCost calls
        // cacheCreationInputTokenPriceAbove1hr() with no tier basis) and
        // ModelCostData declares no field for the table's own
        // `cache_creation_input_token_cost_above_1hr_above_200k_tokens` — so on
        // claude-sonnet-4-5 at a 300k prompt a 1h write bills $1.8225 against
        // $1.9725 for an identical 5m write. Half the published rate, and
        // inverted: the longer, dearer write renders as the cheaper span. 16
        // shipped price rows are affected. The assertions below are the correct
        // amounts; remove test.fail() once this passes, which Playwright
        // reports.
        test.fail();

        const byName = await test.step('Read the seeded spans back once all of them are queryable', async () =>
          readSeededSpans(backendClient, project.id, cacheWriteTtlSpans));

        const [fiveMinutes, oneHour] = pairFor(cacheWriteTtlSpans, 'above');

        await test.step('Both vectors really carry the TTL split they claim (seed shape)', async () => {
          assertSeedShape(byName, cacheWriteTtlSpans, [fiveMinutes, oneHour]);
        });

        await test.step("Each vector is priced at the table's own amount", async () => {
          for (const vector of [fiveMinutes, oneHour]) {
            expect(
              costOf(byName, cacheWriteTtlSpans, vector.key),
              `${vector.key}: ${vector.workings}`,
            ).toBeCloseTo(vector.expectedCost, 6);
          }
        });

        await test.step('The 1-hour write is still the dearer of the two', async () => {
          // The inversion, stated as the comparison a human would make. This is
          // the half that fails today: $1.8225 is not greater than $1.9725.
          const oneHourCost = costOf(byName, cacheWriteTtlSpans, oneHour.key);
          const fiveMinuteCost = costOf(byName, cacheWriteTtlSpans, fiveMinutes.key);
          expect(
            oneHourCost,
            'crossing the 200k prompt tier must not reverse which TTL is dearer — a 1-hour write is more expensive than a 5-minute one at every prompt size',
          ).toBeGreaterThan(fiveMinuteCost);
          expect(
            oneHourCost - fiveMinuteCost,
            'above the tier the premium is the written tokens at the difference between the $12/M tiered 1h rate and the $7.5/M tiered 5m rate',
          ).toBeCloseTo(
            fiveMinutes.cacheWriteTokens *
              (CACHE_WRITE_RATES.above.oneHour - CACHE_WRITE_RATES.above.fiveMinutes),
            6,
          );
        });
      },
    );

    test(
      'the trace panel renders the cache-write costs the API resolved, and the trace header is their sum',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ cacheWriteTtlSpans, project, backendClient, page }) => {
        // Deliberately read against the API's OWN amounts rather than the
        // fixture's expected ones. This test is about the panel agreeing with
        // the number the server resolved, which is a claim that holds whether
        // or not the arithmetic above the tier is right — the two tests above
        // own the arithmetic. Pinning the fixture's amounts here would make
        // this test fail for the product defect a second time while telling a
        // reader nothing new about rendering.

        const byName = await test.step('The server really priced every span before the panel is read for those amounts', async () => {
          // A UI assertion over a seed that never got priced is a test that
          // cannot fail: the amounts below would simply never appear, and any
          // wait for them would be indistinguishable from a rendering bug.
          await expect
            .poll(
              async () => {
                const spans = await backendClient.listSpanCosts({
                  projectId: project.id,
                  traceId: cacheWriteTtlSpans.traceId,
                });
                return spans.filter((s) => s.totalEstimatedCost !== null && s.totalEstimatedCost > 0)
                  .length;
              },
              { timeout: 60_000, intervals: [500, 1_000, 2_000] },
            )
            .toBe(cacheWriteTtlSpans.spans.length);

          return readSeededSpans(backendClient, project.id, cacheWriteTtlSpans);
        });

        const resolved = new Map(
          cacheWriteTtlSpans.spans.map((seed) => [
            seed.key,
            costOf(byName, cacheWriteTtlSpans, seed.key),
          ]),
        );

        await test.step('The four amounts render as four different strings', async () => {
          // What makes each chip assertion below discriminating. The panel
          // walks the spans in sequence, so if two vectors rendered the same
          // text a click that left the viewer on the previous span would
          // satisfy the next assertion too — an iteration that cannot fail. The
          // span-id check below guards the same thing from the other side; this
          // guards it in a way that does not depend on the URL.
          const rendered = [...resolved.values()].map(displayedCost);
          expect(
            new Set(rendered).size,
            `the four seeded costs must render as four distinct strings, got ${JSON.stringify(rendered)}`,
          ).toBe(rendered.length);
        });

        const logs = new LogsPage(page);

        const panel = await test.step('Open the seeded trace in the Logs panel', async () => {
          await logs.goto(project.id);
          const opened = await logs.openTraceById(cacheWriteTtlSpans.traceId);
          await opened.waitForFullyLoaded();
          return opened;
        });

        await test.step("The trace header shows the rolled-up total, and it is the sum of the spans'", async () => {
          const traceCost = await backendClient.getTraceCost(cacheWriteTtlSpans.traceId);
          expect(traceCost, 'the trace must report a rolled-up cost').not.toBeNull();
          expect(
            traceCost!,
            "the trace's rolled-up cost is the sum of its spans'",
          ).toBeCloseTo(
            [...resolved.values()].reduce((acc, cost) => acc + cost, 0),
            6,
          );
          await expect(panel.estimatedCost(displayedCost(traceCost!))).toBeVisible();
        });

        await test.step('Each span shows its own resolved cost against its own model', async () => {
          for (const seed of cacheWriteTtlSpans.spans) {
            await panel.selectSpan(seed.name);
            // Pin the assertions below to the span that is actually selected.
            // `selectSpan` only waits for SOME span to be in the URL, and all
            // four of these share a model id — so a click that left the viewer
            // on the previous span would satisfy the model assertion outright.
            await expect
              .poll(() => new URL(page.url()).searchParams.get('span'), {
                message: `the panel must be showing '${seed.name}' itself, not whichever span was selected before it`,
              })
              .toBe(byName.get(seed.name)!.id);
            await expect(panel.spanModelChip).toContainText('claude-sonnet-4-5');
            await expect(panel.estimatedCost(displayedCost(resolved.get(seed.key)!))).toBeVisible();
          }
        });
      },
    );
  },
);
