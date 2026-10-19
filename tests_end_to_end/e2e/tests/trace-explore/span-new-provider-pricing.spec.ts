import { test, expect } from '@e2e/fixtures';
import type { NewProviderCostSpanSeed, NewProviderCostSpansRef } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { displayedCost } from '@e2e/core/money';
import type { BackendClient, SpanCostRef } from '@e2e/core/backend';

/**
 * Providers outside the removed `PROVIDERS_MAPPING` whitelist resolve a price
 * and render it (opik#8941).
 *
 * This is the headline behaviour change of that PR: `CostService` used to look
 * a price up only for providers present in a hard-coded map of about a dozen
 * names, and 91 `litellm_provider` values in the shipped price table were not
 * in it. A span logged against one of them resolved no cost at all — beside
 * identical siblings that did, on the page people read to decide what their LLM
 * spend is. That is the silent wrongness worth pinning: not a wrong number, but
 * no number.
 *
 * The existing cost specs seed only `openai`, `anthropic`, `perplexity`,
 * `mistral` and `google_ai`, every one of which was ON the old whitelist. So a
 * regression that reinstated any provider filter would pass the whole estate as
 * it stands.
 *
 * Six providers, one control, and every expected amount hand-computed from the
 * row's own published rates — see the fixture, which also explains why two of
 * the model ids are spelled as their full price-table keys. The control names a
 * provider and model in no price row and must stay unpriced; without it, "these
 * six resolve a price" could not be told apart from a lookup that had started
 * inventing rows.
 *
 * Asserted at both surfaces, because both are places the change has to land:
 * the API is where the resolution happens, the panel is where a human would
 * notice a span with tokens and no cost, and the Logs table's Estimated cost
 * column is the one a user scans before opening anything.
 */

/** The vectors the price table must resolve, and the one it must not. */
const priced = (spans: readonly NewProviderCostSpanSeed[]): NewProviderCostSpanSeed[] =>
  spans.filter((s) => s.expectedCost > 0);
const controls = (spans: readonly NewProviderCostSpanSeed[]): NewProviderCostSpanSeed[] =>
  spans.filter((s) => s.expectedCost === 0);

/**
 * Every seeded span, by name, once all of them are queryable.
 *
 * The count is asserted rather than only the lookup, and here it carries more
 * than usual: the control is asserted to have NO cost, which is also what a
 * span that never landed would look like. Reading the full set first is what
 * tells those two apart.
 */
async function readSeededSpans(
  backendClient: BackendClient,
  projectId: string,
  seed: NewProviderCostSpansRef,
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
  seed: NewProviderCostSpansRef,
  key: string,
): SpanCostRef {
  const vector = seed.spans.find((s) => s.key === key);
  expect(vector, `the fixture must seed a '${key}' vector`).toBeDefined();
  const span = byName.get(vector!.name);
  expect(span, `span ${vector!.name} was seeded`).toBeDefined();
  return span!;
}

/**
 * The cost the server attributed to a priced vector.
 *
 * Asserts a cost is present before returning it: for these six, an absent cost
 * is the exact regression the spec exists for, and a `?? 0` would report it as
 * a comparison between two zeros. The control goes through `attributedCost`
 * instead, where absent and zero are the same answer.
 */
function costOf(
  byName: Map<string, SpanCostRef>,
  seed: NewProviderCostSpansRef,
  key: string,
): number {
  const span = spanFor(byName, seed, key);
  expect(
    span.totalEstimatedCost,
    `${key} must be priced server-side — an absent cost is the failure this spec exists for, not a zero`,
  ).not.toBeNull();
  return span.totalEstimatedCost!;
}

/**
 * A span's cost as the server attributed it, for the control only.
 *
 * Absent and zero are the same answer for a provider the price table must not
 * match — "nothing was billed" — and only the control is allowed to collapse
 * them.
 */
const attributedCost = (span: SpanCostRef): number => span.totalEstimatedCost ?? 0;

test.describe(
  'Span cost — providers outside the removed whitelist',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'six providers the old whitelist dropped are each priced from the table, and one in no price row is not',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ newProviderCostSpans, project, backendClient }) => {
        // No page. The subject is whether the price lookup reaches a row at
        // all; the panel renders whatever came out, so driving a browser here
        // would add a rendering failure mode to an assertion about a lookup.
        // The panel and the Logs table are the second test below.

        const byName = await test.step('Read the seeded spans back once all of them are queryable', async () =>
          readSeededSpans(backendClient, project.id, newProviderCostSpans));

        await test.step('Each span really carries the provider and model id under test (seed shape)', async () => {
          // Without this the cost assertions could pass over a seed that
          // silently dropped or altered `model` — and the model id arriving
          // character for character is half of what two of these vectors
          // exercise, since the price-key parse splits on its first slash.
          for (const seed of newProviderCostSpans.spans) {
            const span = spanFor(byName, newProviderCostSpans, seed.key);
            expect(span.model, `model id logged for ${seed.key}`).toBe(seed.model);
            expect(span.provider, `provider logged for ${seed.key}`).toBe(seed.provider);
            const usage = span.usage;
            expect(usage, `usage logged for ${seed.key}`).not.toBeNull();
            expect(usage!['prompt_tokens'], `prompt_tokens for ${seed.key}`).toBe(
              newProviderCostSpans.promptTokens,
            );
            expect(usage!['completion_tokens'], `completion_tokens for ${seed.key}`).toBe(
              newProviderCostSpans.completionTokens,
            );
          }
        });

        await test.step("Every newly-reachable provider is priced at its row's own rates", async () => {
          for (const seed of priced(newProviderCostSpans.spans)) {
            expect(
              costOf(byName, newProviderCostSpans, seed.key),
              `${seed.key} (${seed.provider} / ${seed.model}): ${seed.workings}`,
            ).toBeCloseTo(seed.expectedCost, 9);
          }
        });

        await test.step('The control stays unpriced', async () => {
          // The discriminating half. Same token counts as the six, so a cost
          // here would mean the lookup resolved something for a provider and
          // model that appear nowhere in the price file.
          for (const seed of controls(newProviderCostSpans.spans)) {
            expect(
              attributedCost(spanFor(byName, newProviderCostSpans, seed.key)),
              `${seed.key}: ${seed.workings}`,
            ).toBe(0);
          }
        });

        await test.step("The trace's rolled-up cost is the sum of its spans", async () => {
          // The roll-up is its own claim: six prices that are individually
          // right can still aggregate wrong, and the trace total is what the
          // Logs table and the trace header both render.
          const traceCost = await backendClient.getTraceCost(newProviderCostSpans.traceId);
          expect(traceCost, 'the trace must report a rolled-up cost').not.toBeNull();
          expect(
            traceCost!,
            'trace total across the six priced spans, with nothing from the control',
          ).toBeCloseTo(newProviderCostSpans.expectedTraceCost, 9);
        });
      },
    );

    test(
      'the panel and the Logs cost column render what the API resolved, and the unpriced control shows no amount',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ newProviderCostSpans, project, backendClient, page }) => {
        const byName = await test.step('The server really priced the six before the page is read for those amounts', async () => {
          // A UI assertion over a seed that never got priced is a test that
          // cannot fail: the amounts below would simply never appear, and any
          // wait for them would be indistinguishable from a rendering bug.
          // Asserted as exactly six, not "at least one": a seventh priced span
          // would mean the control was billed, which would make the
          // no-dollar-amount assertion below unreachable rather than failing.
          await expect
            .poll(
              async () => {
                const spans = await backendClient.listSpanCosts({
                  projectId: project.id,
                  traceId: newProviderCostSpans.traceId,
                });
                return spans.filter((s) => s.totalEstimatedCost !== null && s.totalEstimatedCost > 0)
                  .length;
              },
              { timeout: 60_000, intervals: [500, 1_000, 2_000] },
            )
            .toBe(priced(newProviderCostSpans.spans).length);

          return readSeededSpans(backendClient, project.id, newProviderCostSpans);
        });

        await test.step('The six amounts render as six different strings', async () => {
          // What makes each chip assertion below discriminating. The panel is
          // walked span by span, so if two vectors rendered the same text a
          // click that left the viewer on the previous span would satisfy the
          // next assertion too — an iteration that cannot fail. The span-id
          // check below guards the same thing through the URL; this guards it
          // without depending on the URL.
          const rendered = priced(newProviderCostSpans.spans).map((s) =>
            displayedCost(s.expectedCost),
          );
          expect(
            new Set(rendered).size,
            `the six priced vectors must render as six distinct strings, got ${JSON.stringify(rendered)}`,
          ).toBe(rendered.length);
        });

        const logs = new LogsPage(page);

        const panel = await test.step('Open the seeded trace in the Logs panel', async () => {
          await logs.goto(project.id);
          const opened = await logs.openTraceById(newProviderCostSpans.traceId);
          await opened.waitForFullyLoaded();
          return opened;
        });

        await test.step('The trace header shows the rolled-up total', async () => {
          await expect(
            panel.estimatedCost(displayedCost(newProviderCostSpans.expectedTraceCost)),
          ).toBeVisible();
        });

        await test.step('Each priced span shows its own resolved cost against its own model', async () => {
          for (const seed of priced(newProviderCostSpans.spans)) {
            await panel.selectSpan(seed.name);
            // Pin the assertions below to the span that is actually selected.
            // `selectSpan` only waits for SOME span to be in the URL, and two
            // of these vectors share a provider — so a click that left the
            // viewer on the previous span could satisfy the model chip.
            await expect
              .poll(() => new URL(page.url()).searchParams.get('span'), {
                message: `the panel must be showing '${seed.name}' itself, not whichever span was selected before it`,
              })
              .toBe(byName.get(seed.name)!.id);
            await expect(panel.spanModelChip).toContainText(seed.model);
            await expect(panel.estimatedCost(displayedCost(seed.expectedCost))).toBeVisible();
          }
        });

        await test.step('The control renders no dollar amount at all', async () => {
          // Asserted as the absence of an AMOUNT rather than as a literal "-".
          // `formatCost` happens to render its `noValue` dash here, but a dash
          // is a default that could reasonably become an em dash or a blank;
          // what a user would actually be reading the panel for is whether a
          // figure is shown. The previous step is what makes this
          // discriminating — the same viewer rendered six amounts a moment
          // ago, so "no amount" cannot be explained by the locator never
          // matching anything.
          const control = controls(newProviderCostSpans.spans)[0];
          expect(control, 'the fixture must seed an unpriced control').toBeDefined();
          await panel.selectSpan(control.name);
          await expect
            .poll(() => new URL(page.url()).searchParams.get('span'), {
              message: `the panel must be showing the control '${control.name}' itself`,
            })
            .toBe(byName.get(control.name)!.id);
          await expect(panel.spanModelChip).toContainText(control.model);
          await expect(
            panel.costAmountsInViewer,
            `${control.key} is priced by no row, so the viewer must show no dollar figure for it — not even the <$0.01 floor marker, which would mean it WAS priced`,
          ).toHaveCount(0);
        });

        await test.step("The Logs table's Estimated cost cell agrees with the trace total", async () => {
          // The column a user scans before opening anything, and a separate
          // render path from the panel's header: the table formats the trace's
          // own rolled-up cost per row.
          await page.goBack();
          const cell = logs.estimatedCostCell(newProviderCostSpans.traceId);
          await expect(cell, 'exactly one Estimated cost cell for the seeded trace').toHaveCount(1);
          await expect(cell).toHaveText(
            displayedCost(newProviderCostSpans.expectedTraceCost),
          );
        });
      },
    );
  },
);
