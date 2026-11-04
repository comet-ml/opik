import { test, expect } from '@e2e/fixtures';
import {
  BEDROCK_CACHE_SURCHARGE,
  GEMINI_THOUGHTS_COST,
  GEMINI_CACHE_DISCOUNT,
} from '@e2e/fixtures';
import type { NativeUsageShapeSeed, NativeUsageShapeSpansRef } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { displayedCost } from '@e2e/core/money';
import type { BackendClient, SpanCostRef } from '@e2e/core/backend';

/**
 * A provider reporting usage in its OWN native dialect is priced from it
 * (opik#8941, opik#8987).
 *
 * The axis here is the one no existing fixture moves. `crossShapeCacheSpans`
 * varies the usage shape for a FIXED provider — deliberately, because its
 * subject is a shape the provider did not write — and
 * `otelProviderAliasSpans` varies the provider at a plain prompt/completion
 * token level. Neither seeds a provider sending the cache and reasoning counts
 * its own SDK actually emits, which is what every real Bedrock Converse and
 * Gemini client does. `span-cache-write-ttl-tier.spec.ts` names `bedrock` and
 * `bedrock_converse` among the rows its defect affects, but seeds only
 * `anthropic`, so the sibling-group lookup below is asserted nowhere.
 *
 * Two silent failure modes, and both render as ordinary dollar amounts:
 *
 *  - A native key the format detection does not recognise is not found, so the
 *    tokens it counted are billed at the wrong rate or not at all. Nothing on
 *    the page says a count was dropped; the Token usage block renders the raw
 *    usage map, so the user sees the key they sent and a cost computed without
 *    it.
 *  - `bedrock` and `bedrock_converse` are one sibling group over a single set
 *    of price rows. A span logged under the sibling that does not own the row
 *    resolving nothing — or resolving a different row — is invisible beside an
 *    identical span logged under the other.
 *
 * So every vector is asserted three ways, and no one of them is the claim on
 * its own: the usage map is read back to prove the key reached the server at
 * all, the absolute amount pins the arithmetic to the shipped rates rather than
 * to itself, and a twin dropping exactly one key pins which bucket that key's
 * tokens landed in. The twin deltas are SIGNED, and the two signs differ on
 * purpose — Anthropic reports the cache outside the input count (a surcharge),
 * Gemini inside it (a discount) — so a calculator that read a key but applied
 * the other convention prices one of the two pairs backwards.
 */

/**
 * Every seeded span, by name, once all of them are queryable.
 *
 * The count is asserted, not just the lookup: a read that also returned spans
 * from another trace would still find every seeded one and pass, and the trace
 * total asserted in the UI test is a sum over exactly these.
 */
async function readSeededSpans(
  backendClient: BackendClient,
  projectId: string,
  seed: NativeUsageShapeSpansRef,
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

/** The vector seeded under `key`, asserted to be in the fixture at all. */
function vectorFor(seed: NativeUsageShapeSpansRef, key: string): NativeUsageShapeSeed {
  const vector = seed.spans.find((s) => s.key === key);
  expect(vector, `the fixture must seed a '${key}' vector`).toBeDefined();
  return vector!;
}

/** The span seeded for `key`, asserted to have landed before it is read. */
function spanFor(
  byName: Map<string, SpanCostRef>,
  seed: NativeUsageShapeSpansRef,
  key: string,
): SpanCostRef {
  const vector = vectorFor(seed, key);
  const span = byName.get(vector.name);
  expect(span, `span ${vector.name} was seeded`).toBeDefined();
  return span!;
}

/**
 * The cost the server attributed to a vector.
 *
 * Asserts the cost is present before returning it. An absent cost is one of the
 * two regressions this spec exists for, and a `?? 0` would report it as a
 * comparison between two zeros — green, having verified nothing.
 */
function costOf(
  byName: Map<string, SpanCostRef>,
  seed: NativeUsageShapeSpansRef,
  key: string,
): number {
  const span = spanFor(byName, seed, key);
  expect(
    span.totalEstimatedCost,
    `${key} must be priced server-side — an absent cost is a failure this spec exists for, not a zero`,
  ).not.toBeNull();
  return span.totalEstimatedCost!;
}

/**
 * The usage map, the model and the provider really landed as logged.
 *
 * Runs before any cost assertion, for the reason the estate's guidelines give:
 * a cost assertion over a seed whose key was dropped in transit is
 * indistinguishable from one over a span that reported no such count, so
 * "the key was priced" and "the key never arrived" would be the same green.
 */
function assertSeedShape(
  byName: Map<string, SpanCostRef>,
  seed: NativeUsageShapeSpansRef,
  keys: readonly string[],
): void {
  for (const key of keys) {
    const vector = vectorFor(seed, key);
    const span = spanFor(byName, seed, key);
    expect(span.model, `model logged for ${key}`).toBe(vector.model);
    expect(span.provider, `provider logged for ${key}`).toBe(vector.provider);
    expect(
      span.usage,
      `${key} (${vector.shape}) must store the usage it was logged with, verbatim and spelling intact`,
    ).toEqual(vector.usage);
  }
}

test.describe(
  'Span cost — each provider in its own native usage dialect',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      "Bedrock Converse camelCase is priced, and the same usage under the `bedrock` sibling resolves the identical amount",
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ nativeUsageShapeSpans, project, backendClient }) => {
        // No page. The subject is which rate the backend multiplied each token
        // bucket by, and the panel renders whatever number came out — driving a
        // browser here would add a rendering failure mode to an assertion about
        // arithmetic. The panel's own rendering is the third test.
        const byName = await test.step('Read the seeded spans back once all of them are queryable', async () =>
          readSeededSpans(backendClient, project.id, nativeUsageShapeSpans));

        const keys = ['bedrock-converse-camel', 'bedrock-invoke-snake', 'bedrock-no-cache'];

        await test.step('All three Bedrock vectors really carry the usage they claim (seed shape)', async () => {
          assertSeedShape(byName, nativeUsageShapeSpans, keys);
        });

        await test.step("Each Bedrock vector is priced at the table's own amount", async () => {
          for (const key of keys) {
            const vector = vectorFor(nativeUsageShapeSpans, key);
            expect(
              costOf(byName, nativeUsageShapeSpans, key),
              `${key} (${vector.shape}): ${vector.workings}`,
            ).toBeCloseTo(vector.expectedCost, 9);
          }
        });

        await test.step('The two spellings agree to the cent, across two sibling providers', async () => {
          // The discriminator for the sibling group. These two spans differ in
          // nothing a bill should depend on: same model, same four counts, one
          // spelled camelCase under `bedrock_converse` and one snake_case under
          // `bedrock`. A build that found no row for `bedrock`, or read only
          // one of the two spellings, separates them here while both absolute
          // assertions above could still be satisfied by a single working path.
          expect(
            costOf(byName, nativeUsageShapeSpans, 'bedrock-invoke-snake'),
            'the InvokeModel spelling under `bedrock` must price exactly as the Converse spelling under `bedrock_converse` — one sibling group, one set of price rows',
          ).toBeCloseTo(costOf(byName, nativeUsageShapeSpans, 'bedrock-converse-camel'), 9);
        });

        await test.step('Dropping both cache keys removes exactly their two buckets', async () => {
          const withCache = costOf(byName, nativeUsageShapeSpans, 'bedrock-converse-camel');
          const withoutCache = costOf(byName, nativeUsageShapeSpans, 'bedrock-no-cache');
          expect(
            withCache,
            'Anthropic reports the cache OUTSIDE the input count, so the cache keys ADD tokens nobody had billed — the cache-bearing span is the dearer of the two',
          ).toBeGreaterThan(withoutCache);
          expect(
            withCache - withoutCache,
            'the surcharge is 5000 cache-read tokens at $0.3/M plus 2000 cache-write tokens at $3.75/M — not some other rate that happens to be dearer',
          ).toBeCloseTo(BEDROCK_CACHE_SURCHARGE, 9);
        });
      },
    );

    test(
      'Gemini usageMetadata prices its cache and its thinking tokens, and the google_ai alias resolves the identical amount',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ nativeUsageShapeSpans, project, backendClient }) => {
        const byName = await test.step('Read the seeded spans back once all of them are queryable', async () =>
          readSeededSpans(backendClient, project.id, nativeUsageShapeSpans));

        const keys = [
          'gemini-native',
          'google-ai-alias',
          'gemini-no-thoughts',
          'gemini-no-cache',
        ];

        await test.step('All four Gemini vectors really carry the usage they claim (seed shape)', async () => {
          assertSeedShape(byName, nativeUsageShapeSpans, keys);
        });

        await test.step("Each Gemini vector is priced at the table's own amount", async () => {
          for (const key of keys) {
            const vector = vectorFor(nativeUsageShapeSpans, key);
            expect(
              costOf(byName, nativeUsageShapeSpans, key),
              `${key} (${vector.shape}): ${vector.workings}`,
            ).toBeCloseTo(vector.expectedCost, 9);
          }
        });

        await test.step('The provider alias resolves the same row', async () => {
          expect(
            costOf(byName, nativeUsageShapeSpans, 'google-ai-alias'),
            "the SDK's `google_ai` alias and the table's own `gemini` must reach one price row",
          ).toBeCloseTo(costOf(byName, nativeUsageShapeSpans, 'gemini-native'), 9);
        });

        await test.step('The thinking tokens are billed rather than dropped', async () => {
          // Scoped claim, and the scope matters: `SpanCostCalculator` falls back
          // from the reasoning rate to the plain output rate, and on this row
          // the two are both $2.5/M. So this delta proves the 50k thinking
          // tokens were billed AT ALL — the regression worth catching, since an
          // unrecognised key bills them at nothing — but it cannot say which of
          // the two coincident rates was applied. No amount on this row could.
          const withThoughts = costOf(byName, nativeUsageShapeSpans, 'gemini-native');
          const withoutThoughts = costOf(byName, nativeUsageShapeSpans, 'gemini-no-thoughts');
          expect(
            withThoughts,
            'thinking tokens are reported outside the candidates count, so reporting them costs more than not reporting them',
          ).toBeGreaterThan(withoutThoughts);
          expect(
            withThoughts - withoutThoughts,
            'the difference is 50k thinking tokens at $2.5/M',
          ).toBeCloseTo(GEMINI_THOUGHTS_COST, 9);
        });

        await test.step("The cached tokens are discounted out of Gemini's cache-inclusive prompt count", async () => {
          // The sign is the whole assertion. Gemini's prompt_token_count
          // INCLUDES the cache, so naming the cache moves 800k tokens from the
          // $0.3/M bucket to the $0.03/M one and the span gets CHEAPER — the
          // opposite direction from the Bedrock pair above. A calculator that
          // read the key but treated the prompt as cache-exclusive would make
          // this pair equal, and one that added the cache on top would make it
          // dearer; both fail here and neither fails the absolute amounts alone.
          const withCache = costOf(byName, nativeUsageShapeSpans, 'gemini-native');
          const withoutCache = costOf(byName, nativeUsageShapeSpans, 'gemini-no-cache');
          expect(
            withCache,
            "naming part of the prompt as cached must reduce the bill, because Gemini's prompt count already contained those tokens",
          ).toBeLessThan(withoutCache);
          expect(
            withCache - withoutCache,
            'the discount is 800k tokens moving from the $0.3/M input rate to the $0.03/M cache-read rate',
          ).toBeCloseTo(GEMINI_CACHE_DISCOUNT, 9);
        });
      },
    );

    test(
      'the trace panel and the Logs cost column render the amounts the API resolved',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ nativeUsageShapeSpans, project, backendClient, page }) => {
        const byName = await test.step('The server really priced every span before the page is read for those amounts', async () => {
          // A UI assertion over a seed that never got priced is a test that
          // cannot fail: the amounts below would simply never appear, and a
          // wait for them would be indistinguishable from a rendering bug.
          // Asserted as all seven, since the trace total is a sum over all of
          // them and one unpriced span would make that total unreachable rather
          // than wrong.
          await expect
            .poll(
              async () => {
                const spans = await backendClient.listSpanCosts({
                  projectId: project.id,
                  traceId: nativeUsageShapeSpans.traceId,
                });
                return spans.filter((s) => s.totalEstimatedCost !== null && s.totalEstimatedCost > 0)
                  .length;
              },
              { timeout: 60_000, intervals: [500, 1_000, 2_000] },
            )
            .toBe(nativeUsageShapeSpans.spans.length);

          return readSeededSpans(backendClient, project.id, nativeUsageShapeSpans);
        });

        const logs = new LogsPage(page);

        const panel = await test.step('Open the seeded trace in the Logs panel', async () => {
          await logs.goto(project.id);
          const opened = await logs.openTraceById(nativeUsageShapeSpans.traceId);
          await opened.waitForFullyLoaded();
          return opened;
        });

        await test.step('The trace header shows the rolled-up total', async () => {
          await expect(
            panel.estimatedCost(displayedCost(nativeUsageShapeSpans.expectedTraceCost)),
          ).toBeVisible();
        });

        await test.step('Every span shows its own resolved cost against its own model', async () => {
          // Walked span by span, each pinned to the span id the URL carries.
          // That guard is not optional here the way it is in a spec whose
          // amounts are all distinct: two of these seven pairs render the
          // IDENTICAL string by design (the two parity pairs), so a click that
          // left the viewer on the previous span would satisfy the next
          // assertion and the iteration could not fail.
          for (const seed of nativeUsageShapeSpans.spans) {
            await panel.selectSpan(seed.name);
            await expect
              .poll(() => new URL(page.url()).searchParams.get('span'), {
                message: `the panel must be showing '${seed.name}' itself, not whichever span was selected before it`,
              })
              .toBe(byName.get(seed.name)!.id);
            await expect(panel.spanModelChip).toContainText(seed.model);
            await expect(panel.estimatedCost(displayedCost(seed.expectedCost))).toBeVisible();
          }
        });

        await test.step("The Logs table's Estimated cost cell agrees with the trace total", async () => {
          // A separate render path from the panel's header — the table formats
          // the trace's own rolled-up cost per row — and the column a user
          // scans before opening anything.
          await page.goBack();
          const cell = logs.estimatedCostCell(nativeUsageShapeSpans.traceId);
          await expect(cell, 'exactly one Estimated cost cell for the seeded trace').toHaveCount(1);
          // Matched as the node whose OWN text is the amount, not as the cell's
          // full text. On a deployment where the Ollie bridge is up, this cell
          // also holds the hover "Explain" trigger, so its text content reads
          // "$3.28Explain" — and `exact` is kept rather than dropped to a
          // substring check so that "$3.2" cannot pass for "$3.28".
          await expect(
            cell.getByText(displayedCost(nativeUsageShapeSpans.expectedTraceCost), {
              exact: true,
            }),
            'the Estimated cost cell names the trace total exactly once',
          ).toHaveCount(1);
        });
      },
    );
  },
);
