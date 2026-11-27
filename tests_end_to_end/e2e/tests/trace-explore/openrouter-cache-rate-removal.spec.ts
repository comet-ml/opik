import { test, expect, CACHED_TOKENS_KEY, NO_CACHE_RATE_PAIRS } from '@e2e/fixtures';
import type { OpenRouterCacheRateSpansRef } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { displayedCost } from '@e2e/core/money';
import type { BackendClient, SpanCostRef } from '@e2e/core/backend';

/**
 * Cached prompt tokens on the two OpenRouter rows that LOST their cache-read
 * rate in 2.3.7 (opik#9834).
 *
 * The release regenerated `model_prices_openrouter.json`; 11 existing rows were
 * repriced and **two lost `cache_read_input_token_cost` outright** —
 * `openrouter/google/gemma-3-4b-it` and `openrouter/moonshotai/kimi-k2.5`.
 * Dropping a cache rate is a routing change rather than a rate change:
 * `SpanCostCalculator.openAiCost` prices cached prompt tokens at
 * `orInputPrice(cacheReadPrice, inputPrice)`, so with no cache rate published
 * every cached token bills at the **full input rate** and a span reporting cache
 * hits costs exactly what its uncached twin costs.
 *
 * Nothing in the estate asserted this for OpenRouter. `span-cached-token-cost.spec.ts`
 * covers the same branch but carries only an OpenAI pair (`gpt-5.5-pro` /
 * `gpt-4.1`), and the OpenRouter path reaches the arithmetic differently:
 * `openrouter` is absent from `UsageFormats.FORMAT_BY_PROVIDER`, so it falls
 * back to the OpenAI usage format and its three-segment model id has to survive
 * the price-key parse before any of this is reached. Both halves of that are
 * assumptions this spec pins.
 *
 * The failure mode is silent by construction: every amount involved renders as
 * a perfectly ordinary figure in the Logs Cost column, which is where people
 * read what their LLM spend is. Only the PAIR shows anything, which is why
 * every vector is seeded twice over and asserted against its own twin — and why
 * a third row that still publishes a cache rate is seeded as the control.
 *
 * WHAT THIS SPEC CLAIMS. It pins the shipped price table as 2.3.7 ships it,
 * deliberately. If either row regains a `cache_read_input_token_cost`, its pair
 * SHOULD go red — that is this spec working. Read the row out of
 * `apps/opik-backend/src/main/resources/model_prices_openrouter.json`, then move
 * the vector onto whichever row then publishes none and keep the comparison.
 * See the fixture's own note; both pinned amounts in the sibling
 * `openrouter-repriced-spans.fixture.ts` needed exactly that in this release.
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
  seed: OpenRouterCacheRateSpansRef,
): Promise<Map<string, SpanCostRef>> {
  await expect
    .poll(
      async () => (await backendClient.listSpanCosts({ projectId, traceId: seed.traceId })).length,
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
  seed: OpenRouterCacheRateSpansRef,
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
 * Asserts a cost is present before returning it: every row here is one the
 * price table resolves, so an absent cost is the regression — and a `?? 0`
 * would report it as a comparison between two zeros.
 */
function costOf(
  byName: Map<string, SpanCostRef>,
  seed: OpenRouterCacheRateSpansRef,
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
  'Span cost — the OpenRouter rows that lost their cache-read rate in 2.3.7',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'an OpenRouter row publishing no cache-read rate bills cached prompt tokens at the full input price, while one that still publishes a rate discounts them',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ openRouterCacheRateSpans, project, backendClient }) => {
        // No page. The subject is which rate the backend applied to the cached
        // half of each prompt; the panel renders whatever number came out, so
        // driving a browser here would add a rendering failure mode to an
        // assertion about arithmetic. The panel's rendering of these same six
        // spans is the second test below.

        const byName = await test.step('Read the seeded spans back once all of them are queryable', async () =>
          readSeededSpans(backendClient, project.id, openRouterCacheRateSpans));

        await test.step('Each span carries the model, provider and cached-token count its vector is about (seed shape)', async () => {
          // The guard that stops this spec from passing for the wrong reason.
          // `cached_tokens` arrives under a dotted key the Python SDK rewrites,
          // which is why the fixture writes straight to REST — but "the key was
          // dropped in transit" and "cached tokens bought no discount" produce
          // the IDENTICAL cost, so the key has to be read back off the stored
          // span before any cost is compared. The model id matters for the same
          // reason: the price lookup splits on the first slash, so a normalised
          // or truncated id would resolve to a different row, or to none.
          for (const seed of openRouterCacheRateSpans.spans) {
            const span = spanFor(byName, openRouterCacheRateSpans, seed.key);
            expect(span.model, `model id logged for ${seed.key}`).toBe(seed.model);
            expect(span.provider, `provider logged for ${seed.key}`).toBe('openrouter');

            const usage = span.usage;
            expect(usage, `usage logged for ${seed.key}`).not.toBeNull();
            expect(usage!['prompt_tokens'], `prompt_tokens for ${seed.key}`).toBe(
              openRouterCacheRateSpans.promptTokens,
            );
            expect(usage!['completion_tokens'], `completion_tokens for ${seed.key}`).toBe(
              openRouterCacheRateSpans.completionTokens,
            );
            if (seed.cachedTokens > 0) {
              expect(
                usage![CACHED_TOKENS_KEY],
                `${seed.key} must really report cached tokens under ${CACHED_TOKENS_KEY}, or its cost is not evidence of anything`,
              ).toBe(seed.cachedTokens);
            } else {
              expect(
                usage![CACHED_TOKENS_KEY],
                `${seed.key} is the plain half of its pair and must report no cached tokens`,
              ).toBeUndefined();
            }
          }
        });

        await test.step('Every vector is priced at the hand-computed amount the shipped file gives', async () => {
          // Absolute amounts first: they are what pins the arithmetic to the
          // shipped rates rather than merely to itself. Two spans agreeing on a
          // wrong number would satisfy every comparison below and nothing else.
          for (const seed of openRouterCacheRateSpans.spans) {
            expect(
              costOf(byName, openRouterCacheRateSpans, seed.key),
              `${seed.key}: ${seed.workings}`,
            ).toBeCloseTo(seed.expectedCost, 6);
          }
        });

        await test.step('With no cache-read rate published, reporting cached tokens changes nothing', async () => {
          // The finding, stated as a comparison. Both halves report identical
          // prompt and completion totals and differ only in the cached count, so
          // a cache discount of any size would separate them.
          for (const pair of NO_CACHE_RATE_PAIRS) {
            expect(
              costOf(byName, openRouterCacheRateSpans, pair.cached),
              `${pair.model} publishes no cache_read_input_token_cost as 2.3.7 ships it, so a span reporting ${openRouterCacheRateSpans.cachedTokens} cached prompt tokens must cost exactly what an identical span reporting none costs — every cached token billed at the full input rate`,
            ).toBeCloseTo(costOf(byName, openRouterCacheRateSpans, pair.plain), 6);
          }
        });

        await test.step('...and the cached tokens were billed, not silently dropped', async () => {
          // The other way the equality above could come about: a backend that
          // subtracted the cached count and charged for nothing in its place
          // would ALSO make both halves agree — on a lower number. Both
          // candidates are ordinary-looking amounts ($0.125 and $3.30), so each
          // is asserted away by name rather than left to the absolute check to
          // catch incidentally.
          for (const pair of NO_CACHE_RATE_PAIRS) {
            const seed = openRouterCacheRateSpans.spans.find((s) => s.key === pair.cached);
            expect(seed, `the fixture must seed a '${pair.cached}' vector`).toBeDefined();
            expect(
              seed!.droppedCachedCost,
              `${pair.cached} must pin the dropped-token amount for this assertion to mean anything`,
            ).toBeDefined();
            expect(
              costOf(byName, openRouterCacheRateSpans, pair.cached),
              `${pair.model}'s cached half must NOT be the amount it would cost if the cached tokens had been dropped from the prompt instead of billed at the input rate`,
            ).not.toBeCloseTo(seed!.droppedCachedCost!, 6);
          }
        });

        await test.step('The control row does discount its cached tokens', async () => {
          // The half that makes the two equalities above statements about the
          // price table rather than about the seed. minimax-m3.1-flash still
          // publishes $0.0075/M cache read against $0.075/M input, so its cached
          // span must come out strictly cheaper — and by exactly the rate
          // difference on the cached tokens.
          const cached = costOf(byName, openRouterCacheRateSpans, 'cache-rate-cached');
          const plain = costOf(byName, openRouterCacheRateSpans, 'cache-rate-plain');
          expect(
            cached,
            'minimax-m3.1-flash publishes a cache_read rate, so its cached span must be cheaper than its uncached twin — if this fails, the two equalities above prove only that the cached count never reached the calculator',
          ).toBeLessThan(plain);
          expect(
            plain - cached,
            'the discount is the cached tokens at the difference between $0.075/M input and $0.0075/M cache read',
          ).toBeCloseTo(openRouterCacheRateSpans.cachedTokens * (7.5e-8 - 7.5e-9), 6);
        });

        await test.step("The trace's rolled-up cost is the sum of its spans", async () => {
          const traceCost = await backendClient.getTraceCost(openRouterCacheRateSpans.traceId);
          expect(traceCost, 'the trace must report a rolled-up cost').not.toBeNull();
          expect(traceCost!, 'trace total across every seeded span').toBeCloseTo(
            openRouterCacheRateSpans.expectedTraceCost,
            6,
          );
        });
      },
    );

    test(
      'the trace panel renders the same OpenRouter cached-token costs the API resolved',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ openRouterCacheRateSpans, project, backendClient, page }) => {
        const byName = await test.step('The server really priced every span before the panel is read for those amounts', async () => {
          // A UI assertion over a seed that never got priced is a test that
          // cannot fail: the amounts below would simply never appear, and any
          // wait for them would be indistinguishable from a rendering bug.
          await expect
            .poll(
              async () => {
                const spans = await backendClient.listSpanCosts({
                  projectId: project.id,
                  traceId: openRouterCacheRateSpans.traceId,
                });
                return spans.filter((s) => (s.totalEstimatedCost ?? 0) > 0).length;
              },
              { timeout: 60_000, intervals: [500, 1_000, 2_000] },
            )
            .toBe(openRouterCacheRateSpans.spans.length);

          // Read back for the ids, which are what pin each panel assertion
          // below to the span it is supposed to be about.
          return readSeededSpans(backendClient, project.id, openRouterCacheRateSpans);
        });

        const logs = new LogsPage(page);

        const panel = await test.step('Open the seeded trace in the Logs panel', async () => {
          await logs.goto(project.id);
          const opened = await logs.openTraceById(openRouterCacheRateSpans.traceId);
          await opened.waitForFullyLoaded();

          // Wait for the tree to list all six spans BEFORE selecting any of
          // them. The panel fills the tree from one `GET /v1/private/spans`,
          // which is rate limited per workspace; when parallel specs have spent
          // the budget that call is shed with a 429, the empty answer is cached
          // and nothing refetches, so the tree renders `Spans (0)` permanently
          // and a click on a node can never succeed however long it waits.
          // `waitForSpanTreeNodes` supplies the retry the browser has not got.
          // Observed here: this test flaked exactly this way at 4 workers
          // against staging, while passing 4x in a row on its own.
          await opened.waitForSpanTreeNodes(openRouterCacheRateSpans.spans.map((s) => s.name));
          return opened;
        });

        await test.step('The trace header shows the rolled-up total', async () => {
          await expect(
            panel.estimatedCost(displayedCost(openRouterCacheRateSpans.expectedTraceCost)),
          ).toBeVisible();
        });

        await test.step('Each span shows its own resolved cost against its own model', async () => {
          for (const seed of openRouterCacheRateSpans.spans) {
            await panel.selectSpan(seed.name);
            // Pin the two assertions below to the span that is actually
            // selected. `selectSpan` only waits for SOME span to be in the URL,
            // and within a no-cache pair both halves share a model id AND an
            // expected amount — so a click that left the viewer on the previous
            // span would satisfy both, giving an iteration that cannot fail.
            await expect
              .poll(() => new URL(page.url()).searchParams.get('span'), {
                message: `the panel must be showing '${seed.name}' itself, not whichever span was selected before it`,
              })
              .toBe(byName.get(seed.name)!.id);
            await expect(panel.spanModelChip).toContainText(seed.model);
            await expect(panel.estimatedCost(displayedCost(seed.expectedCost))).toBeVisible();
          }
        });
      },
    );
  },
);
