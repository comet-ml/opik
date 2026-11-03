import { test, expect } from '@e2e/fixtures';
import type { CrossShapeCacheSpansRef, CrossShapeCacheVector } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { displayedCost } from '@e2e/core/money';
import type { BackendClient, SpanCostRef } from '@e2e/core/backend';

/**
 * Cache tokens are found and priced from any usage shape, whatever provider
 * wrote the span (opik#8987, issue-7773).
 *
 * The shapes a client reports a cache count in do not stay with the vendor
 * whose format named them. The OpenAI Agents SDK driving Claude sends
 * OpenAI-Responses keys on an Anthropic span; a Bedrock wrapper sends
 * camelCase; a Gemini-shaped usage turns up on an OpenAI-compatible gateway.
 * Before this PR each of those cache counts was simply not found, so the span
 * billed its cached tokens at the full input rate — a plausible dollar amount
 * several times the right one, on the page people read to decide their spend.
 *
 * The estate's existing cache spec (`span-cached-token-cost.spec.ts`) varies
 * the provider and the price table; nothing varied the SHAPE of the usage keys
 * for a fixed provider, which is exactly the axis this PR moves.
 *
 * Every vector is paired with a cache-free twin, and both halves of the pair
 * are asserted. The absolute amount pins the rates; the delta pins which bucket
 * the cached tokens landed in. A pair on its own could not tell "cache read
 * priced" from "the price table moved", and an absolute amount on its own could
 * not tell it from "priced at some other rate that happens to be close".
 *
 * The delta's SIGN is load-bearing. A cache read reported inside the input is a
 * discount; a cache write reported outside it — Anthropic's own convention — is
 * a surcharge, because those tokens were in no bucket until the write key was
 * read. Asserting a magnitude would let a read priced as a write pass.
 */

/**
 * Every span of one seeded trace, by name, once all of them are queryable.
 *
 * The count is asserted rather than only the lookup: a read that also returned
 * spans from another trace would still find every seeded one and pass.
 */
async function readSeededSpans(
  backendClient: BackendClient,
  projectId: string,
  traceId: string,
  expected: readonly CrossShapeCacheVector[],
): Promise<Map<string, SpanCostRef>> {
  await expect
    .poll(
      async () => (await backendClient.listSpanCosts({ projectId, traceId })).length,
      { timeout: 60_000, intervals: [500, 1_000, 2_000] },
    )
    .toBe(expected.length);

  const spans = await backendClient.listSpanCosts({ projectId, traceId });
  expect(spans, `trace ${traceId} carries exactly the seeded spans`).toHaveLength(expected.length);
  return new Map(spans.map((s) => [s.name, s]));
}

/**
 * The span seeded for `vector`, asserted to have landed before it is used.
 *
 * A vector the fixture failed to seed would otherwise surface as an `undefined`
 * the arithmetic below had to code around, which is the shape that turns a
 * broken seed into a green run.
 */
function spanFor(
  byName: Map<string, SpanCostRef>,
  vector: CrossShapeCacheVector,
): SpanCostRef {
  const span = byName.get(vector.name);
  expect(span, `span ${vector.name} (${vector.key}) was seeded`).toBeDefined();
  return span!;
}

/**
 * The cost the server attributed to `vector`.
 *
 * Asserts a cost is present before returning it: every vector is on a model the
 * price table resolves, so an absent cost is the regression, and a `?? 0` would
 * report it as a comparison between two zeros.
 */
function costOf(byName: Map<string, SpanCostRef>, vector: CrossShapeCacheVector): number {
  const span = spanFor(byName, vector);
  expect(
    span.totalEstimatedCost,
    `${vector.key} must be priced server-side — an absent cost is a failure, not a zero`,
  ).not.toBeNull();
  return span.totalEstimatedCost!;
}

/** The vector `key` names, asserted to be in the seeded set. */
function vectorFor(vectors: readonly CrossShapeCacheVector[], key: string): CrossShapeCacheVector {
  const vector = vectors.find((v) => v.key === key);
  expect(vector, `the fixture must seed a '${key}' vector`).toBeDefined();
  return vector!;
}

test.describe(
  'Span cost — cache keys read from a shape their provider did not write',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'each cross-shape cache key survives the write, is priced at the table rate, and discounts against its own twin by exactly the rate difference',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ crossShapeCacheSpans, project, backendClient }) => {
        // No page. Every amount here falls under `formatCost`'s $0.01 floor, so
        // all six would render the identical "<$0.01" and a UI assertion would
        // add a rendering failure mode while asserting nothing about the
        // arithmetic. The one vector whose amounts a browser can discriminate
        // is the second test below.

        const byName = await test.step('Read the seeded spans back once all of them are queryable', async () =>
          readSeededSpans(
            backendClient,
            project.id,
            crossShapeCacheSpans.apiTraceId,
            crossShapeCacheSpans.spans,
          ));

        await test.step('Every cache key reached storage spelled exactly as sent', async () => {
          // First by necessity rather than by taste: a key the write path
          // renamed or dropped prices IDENTICALLY to a key the reader never
          // looked for, so "the cache was priced" would be satisfied by a
          // backend that found none of these shapes. An equality over the whole
          // map rather than a per-key check, so a write path that stored a
          // normalised duplicate beside each key fails here too.
          for (const vector of crossShapeCacheSpans.spans) {
            const span = spanFor(byName, vector);
            expect(span.model, `model logged for ${vector.key}`).toBe(vector.model);
            expect(span.provider, `provider logged for ${vector.key}`).toBe(vector.provider);
            expect(
              span.usage,
              `${vector.key} (${vector.shape}) must store the usage it was logged with, camelCase and all`,
            ).toEqual(vector.usage);
          }
        });

        await test.step('Every vector is priced at the hand-computed table amount', async () => {
          // Absolute amounts first: they pin the arithmetic to the shipped
          // rates rather than merely to itself. Two spans agreeing on a wrong
          // number would satisfy the delta step below and nothing else.
          for (const vector of crossShapeCacheSpans.spans) {
            expect(
              costOf(byName, vector),
              `${vector.key} (${vector.shape}): ${vector.workings}`,
            ).toBeCloseTo(vector.expectedCost, 9);
          }
        });

        await test.step('Each pair differs from its twin by exactly the rate difference, in the right direction', async () => {
          // The discriminating half, and the reason every vector is seeded
          // twice over. The two halves of a pair differ in nothing but the
          // cache key, so a backend that never found the key would price them
          // identically — a delta of zero, which no sign could satisfy.
          const withTwins = crossShapeCacheSpans.spans.filter((v) => v.twin !== undefined);
          expect(
            withTwins.length,
            'the fixture must seed a twin for each of the three shapes under test',
          ).toBe(3);

          for (const vector of withTwins) {
            const twin = vectorFor(crossShapeCacheSpans.spans, vector.twin!.key);
            expect(
              costOf(byName, vector) - costOf(byName, twin),
              `${vector.key} vs ${twin.key}: ${vector.twin!.deltaWorkings}`,
            ).toBeCloseTo(vector.twin!.delta, 9);
          }
        });

        await test.step("The trace's rolled-up cost is the sum of its six spans", async () => {
          const traceCost = await backendClient.getTraceCost(crossShapeCacheSpans.apiTraceId);
          expect(traceCost, 'the trace must report a rolled-up cost').not.toBeNull();
          expect(traceCost!, 'trace total across the three pairs').toBeCloseTo(
            crossShapeCacheSpans.expectedTraceCost,
            9,
          );
        });
      },
    );

    test(
      'the trace panel renders the cache-aware cost on two spans reporting identical token totals',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ crossShapeCacheSpans, project, backendClient, page }) => {
        // The production usage this PR's issue was reported against — a
        // Gemini-shaped usage logged under Bedrock — scaled x10 so both halves
        // clear `formatCost`'s $0.01 floor.
        //
        // Worth a browser for one specific reason: the panel's Token usage
        // block renders the raw usage map and nothing else, so it shows no
        // normalised split and no per-bucket rate. The resolved COST is the
        // only place the cache-aware arithmetic becomes visible to a user at
        // all — which is why these two spans, reporting the identical 560,970
        // tokens, must render amounts almost 7x apart.

        const byName = await test.step('The server really priced both spans before the panel is read for them', async () => {
          // A UI assertion over a seed that never got priced is a test that
          // cannot fail: the amounts below would simply never appear, and any
          // wait for them would be indistinguishable from a rendering bug.
          await expect
            .poll(
              async () => {
                const spans = await backendClient.listSpanCosts({
                  projectId: project.id,
                  traceId: crossShapeCacheSpans.panelTraceId,
                });
                return spans.filter((s) => s.totalEstimatedCost !== null && s.totalEstimatedCost > 0)
                  .length;
              },
              { timeout: 60_000, intervals: [500, 1_000, 2_000] },
            )
            .toBe(crossShapeCacheSpans.panelSpans.length);

          return readSeededSpans(
            backendClient,
            project.id,
            crossShapeCacheSpans.panelTraceId,
            crossShapeCacheSpans.panelSpans,
          );
        });

        await test.step('Both spans really report the same token total, differing only in the cache key', async () => {
          // What makes the two rendered amounts a finding rather than a
          // coincidence. If the fixture had quietly given them different token
          // counts, two different costs would be the expected outcome and the
          // panel assertions below would prove nothing about cache pricing.
          for (const vector of crossShapeCacheSpans.panelSpans) {
            const usage = spanFor(byName, vector).usage;
            expect(usage, `usage logged for ${vector.key}`).not.toBeNull();
            expect(
              usage!['total_tokens'],
              `${vector.key} must report the same ${crossShapeCacheSpans.panelTotalTokens}-token total as its twin`,
            ).toBe(crossShapeCacheSpans.panelTotalTokens);
          }

          const cached = vectorFor(crossShapeCacheSpans.panelSpans, 'panel-cached');
          const plain = vectorFor(crossShapeCacheSpans.panelSpans, 'panel-plain');
          expect(
            spanFor(byName, cached).usage!['original_usage.cached_content_token_count'],
            'the cached half must really carry the Gemini cache key — it is the only difference between the two',
          ).toBe(547_190);
          expect(
            spanFor(byName, plain).usage!['original_usage.cached_content_token_count'],
            'the plain half must carry no cache key at all',
          ).toBeUndefined();
          expect(
            costOf(byName, cached),
            'reporting 547,190 of the prompt as cache reads must cost far LESS than reporting the same prompt with none cached',
          ).toBeLessThan(costOf(byName, plain));
        });

        const logs = new LogsPage(page);

        const panel = await test.step('Open the seeded trace in the Logs panel', async () => {
          await logs.goto(project.id);
          const opened = await logs.openTraceById(crossShapeCacheSpans.panelTraceId);
          await opened.waitForFullyLoaded();
          return opened;
        });

        await test.step('The trace header shows the sum of the two', async () => {
          await expect(
            panel.estimatedCost(displayedCost(crossShapeCacheSpans.expectedPanelTraceCost)),
          ).toBeVisible();
        });

        await test.step('Each span renders its own resolved cost', async () => {
          for (const vector of crossShapeCacheSpans.panelSpans) {
            await panel.selectSpan(vector.name);
            // Pin the assertion below to the span that is actually selected.
            // `selectSpan` only waits for SOME span to be in the URL, and these
            // two share a model id — so a click that left the viewer on the
            // previous span would assert the wrong span's amount.
            await expect
              .poll(() => new URL(page.url()).searchParams.get('span'), {
                message: `the panel must be showing '${vector.name}' itself, not whichever span was selected before it`,
              })
              .toBe(byName.get(vector.name)!.id);
            await expect(panel.spanModelChip).toContainText(vector.model);
            await expect(panel.estimatedCost(displayedCost(vector.expectedCost))).toBeVisible();
          }
        });
      },
    );
  },
);
