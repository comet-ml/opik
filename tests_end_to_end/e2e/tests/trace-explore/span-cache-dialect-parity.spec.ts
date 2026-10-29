import {
  test,
  expect,
  CACHE_DIALECT_NATIVE_KEY,
  CACHE_DIALECT_OTLP_KEY,
} from '@e2e/fixtures';
import type { CacheDialectSpanSeed, CacheDialectSpansRef } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { displayedCost } from '@e2e/core/money';
import type { BackendClient, SpanCostRef } from '@e2e/core/backend';

/**
 * One Anthropic cache usage prices identically whichever client dialect logged
 * it (opik#8941).
 *
 * The PR rewrote usage reading around per-provider formats with a list of input
 * variants: the same semantic usage — some fresh input, some read from the
 * cache, some written to it — arrives spelled a dozen different ways depending
 * on whether LiteLLM, an OTel GenAI instrumentation, OpenInference, the Python
 * SDK or Bedrock Converse produced it, and every spelling must come out at the
 * same price.
 *
 * The estate's existing cache spec seeds exactly one spelling
 * (`original_usage.prompt_tokens_details.cached_tokens`) and varies the model
 * instead. Nothing varied the SHAPE of the usage for a fixed model, which is
 * the axis this PR moves — so a variant that stopped matching, or one that read
 * a cache count into the fresh-input bucket, would pass the whole estate today
 * while quietly billing a different number.
 *
 * The write path is the half the backend's own unit tests structurally cannot
 * see. Those tests hand `UsageFormats` a map in memory; they say nothing about
 * whether a dotted `cache_read.input_tokens` or a camelCase `inputTokens`
 * survives the trip into ClickHouse and back. And it matters here more than
 * usual, because a key that was renamed in transit prices EXACTLY like a key
 * the backend never read — so this spec asserts the stored usage map first, as
 * an equality, and only then compares a cost.
 *
 * The eighth vector goes through real OTLP ingestion rather than
 * `POST /v1/private/spans`. No REST write can stand in for it: ingestion
 * rewrites `input_tokens` to `prompt_tokens` on the way in, so which variant
 * the usage then matches is decided by the ingestion mapping — and that mapping
 * is a thing to assert, not to assume.
 */

/**
 * Every seeded span, by name, once all of them are queryable.
 *
 * The count is asserted rather than only the lookup: a read that also returned
 * spans from another trace would still find every seeded one and pass. It is
 * also what the OTLP half of the seed needs — an export the backend accepted
 * with a 200 but then dropped shows up here rather than as a missing cost
 * further down.
 */
async function readSeededSpans(
  backendClient: BackendClient,
  projectId: string,
  seed: CacheDialectSpansRef,
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
 * the assertions below had to code around, which is the shape that turns a
 * broken seed into a green run.
 */
function spanFor(
  byName: Map<string, SpanCostRef>,
  seed: CacheDialectSpansRef,
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
 * price table resolves, so an absent cost is the regression, and a `?? 0` would
 * turn "this dialect was not read at all" into a comparison between two zeros —
 * which is precisely the failure this spec exists to catch.
 */
function costOf(
  byName: Map<string, SpanCostRef>,
  seed: CacheDialectSpansRef,
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
  'Span cost — one Anthropic cache usage in every client dialect',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      "every dialect's usage survives the write verbatim and resolves the same price",
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ cacheDialectSpans, project, backendClient }) => {
        // No page. The subject is which usage variant the backend matched and
        // what it multiplied by; the panel renders whatever number came out, so
        // driving a browser for all eight would add a rendering failure mode to
        // an assertion about arithmetic — and every amount here is under
        // `formatCost`'s $0.01 floor, so all eight would render the identical
        // "<$0.01" and the comparison would assert nothing. The two vectors
        // whose rendering is worth a browser are the second test below.

        const byName = await test.step('Read the seeded spans back once all of them are queryable', async () =>
          readSeededSpans(backendClient, project.id, cacheDialectSpans));

        await test.step('Every dialect reached storage with its keys spelled exactly as sent', async () => {
          // The write-path assertion, and it comes FIRST by necessity rather
          // than by taste: a key the write path renamed or dropped prices
          // identically to a key the reader never looked at, so "every dialect
          // agrees" would be satisfied by a backend that read none of them.
          //
          // An equality over the whole map, not a per-key subset check: a
          // subset would pass over a write path that stored a normalised
          // duplicate beside each key, and a duplicate is what would make the
          // cost below evidence about the normalisation rather than about the
          // spelling.
          for (const seed of cacheDialectSpans.spans) {
            const span = spanFor(byName, cacheDialectSpans, seed.key);
            expect(span.model, `model logged for ${seed.key}`).toBe('claude-haiku-4-5');
            expect(span.provider, `provider logged for ${seed.key}`).toBe('anthropic');
            expect(
              span.usage,
              `${seed.key} (${seed.dialect}) must store the usage it was logged with — a renamed key prices exactly like an unread one`,
            ).toEqual(seed.storedUsage);
          }
        });

        await test.step('Every dialect is priced at the hand-computed table amount', async () => {
          // The absolute amount first: it is what pins the arithmetic to
          // `claude-haiku-4-5`'s own shipped rates rather than merely to
          // itself. Eight spans agreeing on a wrong number would satisfy the
          // parity step below and nothing else.
          for (const seed of cacheDialectSpans.spans) {
            expect(
              costOf(byName, cacheDialectSpans, seed.key),
              `${seed.key} (${seed.dialect}): ${cacheDialectSpans.workings}`,
            ).toBeCloseTo(cacheDialectSpans.expectedCost, 9);
          }
        });

        await test.step('The dialects agree with each other, not merely with the table', async () => {
          // Stated as its own assertion rather than left implied by the loop
          // above: this is the claim a reader of the PR cares about, and the
          // one whose failure message should name the two spellings that
          // disagreed rather than one row of a table.
          const costs = new Map(
            cacheDialectSpans.spans.map((seed) => [
              seed.key,
              costOf(byName, cacheDialectSpans, seed.key),
            ]),
          );
          const distinct = new Set([...costs.values()].map((cost) => cost.toFixed(9)));
          expect(
            distinct.size,
            `all ${costs.size} spellings of the same usage must resolve one price, got ${JSON.stringify(Object.fromEntries(costs))}`,
          ).toBe(1);
        });

        await test.step("The trace's rolled-up cost is the sum of its spans", async () => {
          const traceCost = await backendClient.getTraceCost(cacheDialectSpans.traceId);
          expect(traceCost, 'the trace must report a rolled-up cost').not.toBeNull();
          expect(
            traceCost!,
            'trace total across every seeded dialect — eight spans at the same amount',
          ).toBeCloseTo(cacheDialectSpans.expectedTraceCost, 9);
        });
      },
    );

    test(
      'the trace panel renders the resolved cost for the native and the OTLP-ingested vector',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ cacheDialectSpans, project, backendClient, page }) => {
        // Two vectors, not eight. These are the two whose producers differ
        // most — Anthropic's own keys written straight to the REST API, and the
        // same usage arriving through OTLP ingestion — and six more identical
        // chips would assert nothing further about rendering.
        //
        // The amounts are under `formatCost`'s $0.01 floor, so what the chip
        // reads is the floor marker rather than a number. That is still worth
        // asserting: a span the backend failed to price renders no amount at
        // all, and "<$0.01" versus nothing is exactly the distinction a user
        // would be reading the panel for. The resolved amounts themselves are
        // the first test's job.

        const byName = await test.step('The server really priced both vectors before the panel is read for them', async () => {
          // A UI assertion over a seed that never got priced is a test that
          // cannot fail: the chip below would simply never appear, and the wait
          // for it would be indistinguishable from a rendering bug.
          await expect
            .poll(
              async () => {
                const spans = await backendClient.listSpanCosts({
                  projectId: project.id,
                  traceId: cacheDialectSpans.traceId,
                });
                return spans.filter((s) => s.totalEstimatedCost !== null && s.totalEstimatedCost > 0)
                  .length;
              },
              { timeout: 60_000, intervals: [500, 1_000, 2_000] },
            )
            .toBe(cacheDialectSpans.spans.length);

          return readSeededSpans(backendClient, project.id, cacheDialectSpans);
        });

        const rendered: CacheDialectSpanSeed[] = [
          CACHE_DIALECT_NATIVE_KEY,
          CACHE_DIALECT_OTLP_KEY,
        ].map((key) => {
          const seed = cacheDialectSpans.spans.find((s) => s.key === key);
          expect(seed, `the fixture must seed a '${key}' vector`).toBeDefined();
          return seed!;
        });

        const logs = new LogsPage(page);

        const panel = await test.step('Open the seeded trace in the Logs panel', async () => {
          await logs.goto(project.id);
          const opened = await logs.openTraceById(cacheDialectSpans.traceId);
          await opened.waitForFullyLoaded();
          return opened;
        });

        await test.step('Each of the two shows the cost the API resolved for it', async () => {
          for (const seed of rendered) {
            await panel.selectSpan(seed.name);
            // Pin the assertions below to the span that is actually selected.
            // `selectSpan` only waits for SOME span to be in the URL, and all
            // eight of these share a model id and an expected amount — so a
            // click that left the viewer on the previous span would satisfy
            // both assertions outright, giving an iteration that cannot fail.
            await expect
              .poll(() => new URL(page.url()).searchParams.get('span'), {
                message: `the panel must be showing '${seed.name}' itself, not whichever span was selected before it`,
              })
              .toBe(byName.get(seed.name)!.id);
            await expect(panel.spanModelChip).toContainText('claude-haiku-4-5');
            await expect(
              panel.estimatedCost(
                displayedCost(costOf(byName, cacheDialectSpans, seed.key)),
              ),
            ).toBeVisible();
          }
        });
      },
    );
  },
);
