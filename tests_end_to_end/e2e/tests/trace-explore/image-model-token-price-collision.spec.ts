import { test, expect } from '@e2e/fixtures';
import type { ImageModelTokenCostSpanSeed, ImageModelTokenCostSpansRef } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { displayedCost } from '@e2e/core/money';
import type { BackendClient, SpanCostRef } from '@e2e/core/backend';

/**
 * An image-generation model bills from its own token rates rather than being
 * zeroed by a per-image price row that shares its key (opik#9068 / OPIK-8897).
 *
 * `ModelPriceTable` files every price-file key under a runtime key with
 * everything up to the first `/` stripped, so `1024-x-1024/gpt-image-1.5` —
 * and the eight other quality/size rows beside it, which publish
 * `input_cost_per_image` and no token rate at all — land under the same key as
 * `gpt-image-1.5` itself. Before opik#9068 whichever row parsed last simply
 * won, which is `HashMap` iteration order rather than a decision; when a
 * per-image row won, the model priced no token, `resolveCalculator` fell
 * through to `defaultCost`, and every span on it billed **$0**.
 *
 * That is the quietest pricing failure there is. The span is there, the model
 * and the token counts are right, and the Cost column reads nothing on the page
 * people open to decide what their LLM spend is: no error, no empty state, no
 * missing row.
 *
 * ## Why this is not already covered
 *
 * No cost spec in the estate seeds an `image_generation` model, and none seeds
 * a model whose price-file key collides with another row's.
 * `span-new-provider-pricing.spec.ts` comes closest — two of its vectors
 * exercise the price-KEY parse — but it does so for keys with extra segments
 * AFTER the provider prefix, which is the opposite direction: those are keys
 * that are only reachable under their full name, where these are keys that
 * reach a name they should not own. Its unpriced control is a model in no row
 * at all, which is a different claim from a model that has a row and loses it.
 *
 * ## The pair, and what each half rules out
 *
 * Both models are in the same shape — `mode: image_generation`, a token-priced
 * row of their own, and per-image/per-pixel rows colliding with them under the
 * same runtime key — so both go through the identical merge. Reading both says
 * the fix holds for the family rather than for one row, and rules out the two
 * explanations a single vector could not: that `image_generation` had become a
 * mode the lookup skips, and that these amounts came from the per-image rows
 * rather than from the token ones. Their expectations differ ($1.25 against
 * $0.75, because `gpt-image-1` publishes no `output_cost_per_token`), so
 * neither amount is a constant the other could be satisfied by.
 *
 * Asserted at the API, where the lookup happens, and then re-read in the trace
 * panel against the amounts the server resolved — the Cost chip is where a
 * human would see this being wrong.
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
  seed: ImageModelTokenCostSpansRef,
): Promise<Map<string, SpanCostRef>> {
  await expect
    .poll(
      async () =>
        (await backendClient.listSpanCosts({ projectId, traceId: seed.traceId })).length,
      {
        timeout: 60_000,
        intervals: [500, 1_000, 2_000],
        message:
          `all ${seed.spans.length} seeded spans of trace ${seed.traceId} to become queryable — a ` +
          'count stuck below that is span ingestion still catching up, not a pricing answer',
      },
    )
    .toBe(seed.spans.length);

  const spans = await backendClient.listSpanCosts({ projectId, traceId: seed.traceId });
  expect(spans, 'the trace carries exactly the seeded spans').toHaveLength(seed.spans.length);
  return new Map(spans.map((s) => [s.name, s]));
}

/**
 * The span seeded under `key`, asserted to have landed before it is used.
 *
 * A vector the fixture failed to seed would otherwise surface as an
 * `undefined` the assertions below had to code around, which is the shape that
 * turns a broken seed into a green run.
 */
function spanFor(
  byName: Map<string, SpanCostRef>,
  seed: ImageModelTokenCostSpansRef,
  key: string,
): SpanCostRef {
  const vector = seed.spans.find((s) => s.key === key);
  expect(vector, `the fixture must seed a '${key}' vector`).toBeDefined();
  const span = byName.get(vector!.name);
  expect(span, `span ${vector!.name} was seeded`).toBeDefined();
  return span!;
}

/**
 * The cost the server attributed, asserted present first.
 *
 * An absent cost and a $0 one are the same answer — "nothing was billed" — and
 * that answer IS the regression this spec exists for, so it must not read as a
 * zero some later comparison happens to accept.
 */
function costOf(span: SpanCostRef, key: string): number {
  expect(
    span.totalEstimatedCost,
    `${key} must be priced server-side — an absent cost is the failure this spec exists ` +
      'for, not a zero',
  ).not.toBeNull();
  return span.totalEstimatedCost!;
}

/**
 * The seeded usage really reached the server under the keys the calculator
 * reads.
 *
 * Runs before any amount is compared: a span whose counts were dropped in
 * transit would bill nothing too, and "the per-image row won" and "no tokens
 * were logged" are the same observation until the stored map is read back.
 */
function assertSeedShape(
  byName: Map<string, SpanCostRef>,
  seed: ImageModelTokenCostSpansRef,
  vectors: readonly ImageModelTokenCostSpanSeed[],
): void {
  for (const vector of vectors) {
    const span = spanFor(byName, seed, vector.key);
    expect(span.model, `${vector.key}: the model id was stored verbatim`).toBe(vector.model);
    expect(span.provider, `${vector.key}: the provider was stored verbatim`).toBe(
      vector.provider,
    );
    expect(span.usage, `${vector.key}: usage was logged`).not.toBeNull();
    expect(span.usage!['prompt_tokens'], `prompt tokens for ${vector.key}`).toBe(
      seed.promptTokens,
    );
    expect(span.usage!['completion_tokens'], `completion tokens for ${vector.key}`).toBe(
      seed.completionTokens,
    );
  }
}

test.describe(
  'Span cost — image-generation models against colliding per-image price rows',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'an image model whose key collides with per-image rows is still billed from its own token rates',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ imageModelTokenCostSpans, project, backendClient }) => {
        // No page. The subject is which price row the lookup kept for a runtime
        // key two rows claim; the panel renders whatever number came out, so
        // driving a browser here would add a rendering failure mode to an
        // assertion about a map merge. The panel's rendering of these same two
        // spans is the test below.
        const byName = await test.step('Read the seeded spans back once both are queryable', async () =>
          readSeededSpans(backendClient, project.id, imageModelTokenCostSpans));

        await test.step('Both spans carry the model, provider and counts they were logged with (seed shape)', async () => {
          assertSeedShape(byName, imageModelTokenCostSpans, imageModelTokenCostSpans.spans);
        });

        await test.step('Neither is billed nothing', async () => {
          // Stated separately from the amounts below, and first: $0 is the
          // regression, and a reader of a failure wants to see that named
          // rather than inferred from a near-miss on a dollar figure.
          for (const vector of imageModelTokenCostSpans.spans) {
            const span = spanFor(byName, imageModelTokenCostSpans, vector.key);
            expect(
              costOf(span, vector.key),
              `${vector.key}: a per-image row sharing this model's runtime key (${vector.collidingKeys[0]}, ` +
                `and ${vector.collidingKeys.length - 1} more) prices no token, so a row that ` +
                'replaced the token-priced one bills this span $0',
            ).toBeGreaterThan(0);
          }
        });

        await test.step("…and each is billed at its own row's published token rates", async () => {
          // Absolute amounts, which are what pin this to the table rather than
          // to itself: "non-zero" alone would be satisfied by a span that
          // picked up a per-image price instead of its token rates, which is
          // the same bug in the other direction.
          for (const vector of imageModelTokenCostSpans.spans) {
            const span = spanFor(byName, imageModelTokenCostSpans, vector.key);
            expect(
              costOf(span, vector.key),
              `${vector.key} (${vector.model}): ${vector.workings}`,
            ).toBeCloseTo(vector.expectedCost, 6);
          }
        });

        await test.step('The two amounts differ, so neither expectation is a constant the other satisfies', async () => {
          const amounts = imageModelTokenCostSpans.spans.map((vector) =>
            costOf(spanFor(byName, imageModelTokenCostSpans, vector.key), vector.key),
          );
          expect(
            new Set(amounts).size,
            `the two rows publish different output rates, so their bills must differ — got ${JSON.stringify(amounts)}`,
          ).toBe(amounts.length);
        });
      },
    );

    test(
      'the trace panel shows each image model the amount the API resolved, not a dash',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ imageModelTokenCostSpans, project, backendClient, page }) => {
        // Read against the API's OWN amounts rather than the fixture's expected
        // ones: this test is about the panel agreeing with the number the
        // server resolved, which is a claim that holds whether or not the
        // arithmetic above is right. The test above owns the arithmetic.
        const byName = await test.step('The server really priced both spans before the panel is read for those amounts', async () => {
          // A UI assertion over a seed that never got priced is a test that
          // cannot fail: the amounts below would simply never appear, and any
          // wait for them would be indistinguishable from a rendering bug.
          await expect
            .poll(
              async () => {
                const spans = await backendClient.listSpanCosts({
                  projectId: project.id,
                  traceId: imageModelTokenCostSpans.traceId,
                });
                return spans.filter((s) => s.totalEstimatedCost !== null && s.totalEstimatedCost > 0)
                  .length;
              },
              {
                message: 'both image-model spans must be priced server-side first',
                timeout: 60_000,
                intervals: [500, 1_000, 2_000],
              },
            )
            .toBe(imageModelTokenCostSpans.spans.length);

          return readSeededSpans(backendClient, project.id, imageModelTokenCostSpans);
        });

        const resolved = new Map(
          imageModelTokenCostSpans.spans.map((vector) => [
            vector.key,
            costOf(spanFor(byName, imageModelTokenCostSpans, vector.key), vector.key),
          ]),
        );

        await test.step('The two amounts render as two different strings', async () => {
          // What makes each chip assertion below discriminating. The panel walks
          // the spans in sequence, so if both vectors rendered the same text a
          // click that left the viewer on the previous span would satisfy the
          // next assertion too — an iteration that cannot fail.
          const rendered = [...resolved.values()].map(displayedCost);
          expect(
            new Set(rendered).size,
            `the two seeded costs must render as two distinct strings, got ${JSON.stringify(rendered)}`,
          ).toBe(rendered.length);
        });

        const logs = new LogsPage(page);

        const panel = await test.step('Open the seeded trace in the Logs panel', async () => {
          await logs.goto(project.id);
          const opened = await logs.openTraceById(imageModelTokenCostSpans.traceId);
          await opened.waitForFullyLoaded();
          return opened;
        });

        await test.step('Each span shows its own resolved cost against its own model', async () => {
          for (const vector of imageModelTokenCostSpans.spans) {
            await panel.selectSpan(vector.name);
            // Pin the assertions below to the span that is actually selected.
            // `selectSpan` only waits for SOME span to be in the URL, so a
            // click that left the viewer on the previous span would otherwise
            // be read as this one.
            await expect
              .poll(() => new URL(page.url()).searchParams.get('span'), {
                message: `the panel must be showing '${vector.name}' itself, not whichever span was selected before it`,
              })
              .toBe(byName.get(vector.name)!.id);
            // Exact-ish: `gpt-image-1` is a prefix of `gpt-image-1.5`, so the
            // chip is read for the id this vector logged and the pair is
            // asserted to be distinct above.
            await expect(panel.spanModelChip).toContainText(vector.model);
            await expect(
              panel.estimatedCost(displayedCost(resolved.get(vector.key)!)),
            ).toBeVisible();
          }
        });
      },
    );
  },
);
