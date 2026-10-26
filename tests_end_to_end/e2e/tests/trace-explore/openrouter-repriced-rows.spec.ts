import { test, expect } from '@e2e/fixtures';
import type { OpenRouterRepricedSpanSeed, OpenRouterRepricedSpansRef } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { displayedCost } from '@e2e/core/money';
import type { BackendClient, SpanCostRef } from '@e2e/core/backend';

/**
 * The OpenRouter price rows opik#9180 moved are billed at the rates it shipped,
 * and the id it removed is billed at nothing.
 *
 * 2.2.120 regenerates `model_prices_openrouter.json`: nine rows re-priced,
 * `minimax/minimax-m3.1-flash:free` dropped and a paid `minimax-m3.1-flash`
 * added in its place. Nothing in the estate pinned a single OpenRouter AMOUNT
 * before this: `openrouter-span-cost.spec.ts` (opik#9132) pins which SPELLING
 * of a model id reaches a row, on two models this release did not touch, and it
 * would stay green across a generated file that moved every price in it.
 *
 * ## Why this is a separate spec rather than more vectors on that one
 *
 * They are different questions over the same machinery, and the fixtures cannot
 * be one fixture: that spec asserts a trace carries EXACTLY its five spans and
 * that its two prefixed spellings are the only priced pair, so adding four
 * vectors to its trace would make its own assertions wrong. The two also fail
 * for different reasons and should say so separately — a lookup that stopped
 * resolving OpenRouter ids at all breaks that spec, while a build shipping a
 * stale price file breaks this one and leaves that one green.
 *
 * ## What makes the amounts a finding
 *
 * Every span is logged with `usage` and no `total_cost`, so each amount is the
 * server's own lookup against the shipped file rather than something the seed
 * asserted back to itself. The three priced rows moved in three different
 * directions and by different factors (see the fixture), so no single multiplier
 * and no single constant satisfies them. And the control is an id that was a
 * real, priced OpenRouter row until this release removed it — so a stale file,
 * or a lookup that resolved `:free` to its paid sibling, prices it and fails
 * here rather than passing as "unpriced like any nonsense name".
 *
 * ## Not claimed
 *
 * The `max_input_tokens` and `deprecation_date` fields 9180 also changed: they
 * gate the model picker and the deprecation banner, not the cost pipeline, and
 * neither is reachable from a logged span. `playground-openrouter-media-gate.spec.ts`
 * is where the picker's own gating lives.
 */

const priced = (spans: readonly OpenRouterRepricedSpanSeed[]): OpenRouterRepricedSpanSeed[] =>
  spans.filter((s) => s.expectedCost > 0);

const removed = (spans: readonly OpenRouterRepricedSpanSeed[]): OpenRouterRepricedSpanSeed[] =>
  spans.filter((s) => s.expectedCost === 0);

/**
 * Every seeded span, by name, once all of them are queryable.
 *
 * The full set is read and its length asserted rather than only the spans the
 * step needs: the control is asserted to have NO cost, which is also what a
 * span that never landed would look like, and reading the whole trace first is
 * what tells those two apart.
 */
async function readSeededSpans(
  backendClient: BackendClient,
  projectId: string,
  seed: OpenRouterRepricedSpansRef,
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
 * the assertions below had to code around, which is the shape that turns a
 * broken seed into a green run.
 */
function spanFor(
  byName: Map<string, SpanCostRef>,
  seed: OpenRouterRepricedSpansRef,
  key: string,
): SpanCostRef {
  const vector = seed.spans.find((s) => s.key === key);
  expect(vector, `the fixture must seed a '${key}' vector`).toBeDefined();
  const span = byName.get(vector!.name);
  expect(span, `span ${vector!.name} was seeded`).toBeDefined();
  return span!;
}

/**
 * The cost the server attributed to a vector that must be priced.
 *
 * Asserts a cost is present before returning it: for these, an absent cost is
 * the exact regression this spec exists for, and a `?? 0` would report it as a
 * comparison between two zeros.
 */
function costOf(
  byName: Map<string, SpanCostRef>,
  seed: OpenRouterRepricedSpansRef,
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
 * A span's cost as the server attributed it, for the removed row only.
 *
 * Absent and zero are the same answer for a model no row prices any more —
 * "nothing was billed" — and only that vector is allowed to collapse them.
 */
const attributedCost = (span: SpanCostRef): number => span.totalEstimatedCost ?? 0;

test.describe(
  'Span cost — the OpenRouter rows re-priced in 2.2.120',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      "each re-priced OpenRouter row is billed at the shipped file's rates, and the id this release removed is billed at nothing",
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ openRouterRepricedSpans, project, backendClient }) => {
        // No page. The subject is the amount the price lookup resolves; the
        // panel renders whatever came out of it, so driving a browser here
        // would add a rendering failure mode to an assertion about arithmetic.
        // The panel is the second test.

        const byName = await test.step(
          'Read the seeded spans back once all of them are queryable',
          async () => readSeededSpans(backendClient, project.id, openRouterRepricedSpans),
        );

        await test.step('Each span really carries the model id and token counts under test (seed shape)', async () => {
          // Without this the cost assertions could pass over a seed that
          // silently dropped or altered `model` — and the id arriving character
          // for character is the subject here, since the removed vector differs
          // from the paid one by a `:free` suffix alone.
          for (const seed of openRouterRepricedSpans.spans) {
            const span = spanFor(byName, openRouterRepricedSpans, seed.key);
            expect(span.model, `model id logged for ${seed.key}`).toBe(seed.model);
            expect(span.provider, `provider logged for ${seed.key}`).toBe('openrouter');
            const usage = span.usage;
            expect(usage, `usage logged for ${seed.key}`).not.toBeNull();
            expect(usage!['prompt_tokens'], `prompt_tokens for ${seed.key}`).toBe(
              openRouterRepricedSpans.promptTokens,
            );
            expect(usage!['completion_tokens'], `completion_tokens for ${seed.key}`).toBe(
              openRouterRepricedSpans.completionTokens,
            );
          }
        });

        await test.step("Every re-priced row is billed at its own shipped rates", async () => {
          // Absolute amounts, from the shipped file's own published rates —
          // what pins this to the table 9180 generated rather than merely to
          // itself. All three are asserted, so a build that read the file for
          // one vendor prefix and not another fails.
          const vectors = priced(openRouterRepricedSpans.spans);
          expect(vectors, 'the fixture seeds three re-priced rows').toHaveLength(3);
          for (const seed of vectors) {
            expect(
              costOf(byName, openRouterRepricedSpans, seed.key),
              `${seed.key} (${seed.model}): ${seed.workings}`,
            ).toBeCloseTo(seed.expectedCost, 9);
          }
        });

        await test.step('The three rows resolve three different amounts, so none of them is a constant', async () => {
          // The discriminator for the step above. A resolver that returned one
          // fixed number — or that billed every OpenRouter span at some other
          // table's rate — satisfies no two of the three absolute assertions,
          // but a reader of a single one could not tell "the file was read"
          // from "this number happens to match".
          const amounts = priced(openRouterRepricedSpans.spans).map((seed) =>
            costOf(byName, openRouterRepricedSpans, seed.key),
          );
          expect(
            new Set(amounts).size,
            `the three re-priced rows must resolve three distinct amounts, got ${JSON.stringify(amounts)}`,
          ).toBe(amounts.length);
        });

        await test.step('The id this release removed stays unpriced', async () => {
          // The half that makes the amounts a finding rather than a tautology,
          // and the sharpest control available: same vendor, same model, same
          // token counts as `minimax-paid`, differing only by the `:free`
          // suffix whose row 9180 dropped. A cost here means a stale price file
          // or a lookup that fell through to the paid sibling.
          const controls = removed(openRouterRepricedSpans.spans);
          expect(controls, 'exactly one removed-row control').toHaveLength(1);
          for (const seed of controls) {
            expect(
              attributedCost(spanFor(byName, openRouterRepricedSpans, seed.key)),
              `${seed.key}: ${seed.workings}`,
            ).toBe(0);
          }
        });

        await test.step("The trace's rolled-up cost is the sum of its priced spans alone", async () => {
          // The number a user actually reads, and the one a wrong row makes
          // plausible rather than obviously absent: the removed id contributing
          // its old $0.30 would roll the trace up to $4.025 instead of $3.725,
          // and both are perfectly ordinary figures.
          const traceCost = await backendClient.getTraceCost(openRouterRepricedSpans.traceId);
          expect(traceCost, 'the trace must report a rolled-up cost').not.toBeNull();
          expect(
            traceCost!,
            'trace total across the three re-priced rows, with nothing from the removed one',
          ).toBeCloseTo(openRouterRepricedSpans.expectedTraceCost, 9);
        });
      },
    );

    test(
      'the trace panel renders each re-priced amount against its own model, and no amount at all for the removed id',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ openRouterRepricedSpans, project, backendClient, page }) => {
        const vectors = priced(openRouterRepricedSpans.spans);

        const byName = await test.step(
          'The server really priced the seeded rows before the page is read for those amounts',
          async () => {
            // A UI assertion over a seed that never got priced is a test that
            // cannot fail: the amounts below would simply never appear, and any
            // wait for them would be indistinguishable from a rendering bug.
            // Polled to exactly the three priced vectors — the fourth must stay
            // unpriced, which the test above asserts and this one relies on.
            await expect
              .poll(
                async () => {
                  const spans = await backendClient.listSpanCosts({
                    projectId: project.id,
                    traceId: openRouterRepricedSpans.traceId,
                  });
                  return spans.filter(
                    (s) => s.totalEstimatedCost !== null && s.totalEstimatedCost > 0,
                  ).length;
                },
                { timeout: 60_000, intervals: [500, 1_000, 2_000] },
              )
              .toBe(vectors.length);

            return readSeededSpans(backendClient, project.id, openRouterRepricedSpans);
          },
        );

        await test.step('The three amounts render as three different strings', async () => {
          // What makes the per-span assertions below discriminating: without
          // it, a panel stuck on one span's cost could satisfy all three.
          const rendered = vectors.map((s) => displayedCost(s.expectedCost));
          expect(
            new Set(rendered).size,
            `the three priced rows must render as three distinct strings, got ${JSON.stringify(rendered)}`,
          ).toBe(rendered.length);
        });

        const logs = new LogsPage(page);

        const panel = await test.step('Open the seeded trace in the Logs panel', async () => {
          await logs.goto(project.id);
          const opened = await logs.openTraceById(openRouterRepricedSpans.traceId);
          await opened.waitForFullyLoaded();
          return opened;
        });

        await test.step('Each re-priced span shows its own resolved cost against its own model', async () => {
          for (const seed of vectors) {
            await panel.selectSpan(seed.name);
            // Pin the assertions to the span that is actually selected.
            // `selectSpan` only waits for SOME span to be in the URL, and every
            // span here shares a provider — so a click that left the viewer on
            // the previous span could otherwise satisfy both reads.
            await expect
              .poll(() => new URL(page.url()).searchParams.get('span'), {
                message: `the panel must be showing '${seed.name}' itself, not whichever span was selected before it`,
              })
              .toBe(byName.get(seed.name)!.id);
            await expect(panel.spanModelChip).toContainText(seed.model);
            await expect(panel.estimatedCost(displayedCost(seed.expectedCost))).toBeVisible();
          }
        });

        await test.step('The removed id renders no dollar amount at all', async () => {
          // Asserted as the absence of an AMOUNT rather than as a literal "-":
          // `formatCost` happens to render its `noValue` dash, but a dash is a
          // default that could reasonably become an em dash or a blank. The step
          // above is what makes this discriminating — the same viewer rendered
          // three amounts a moment ago, so "no amount" cannot be explained by
          // the locator never matching anything.
          //
          // This is also the release-report's open question made testable: an
          // unpriced row is indistinguishable from a genuinely free model to a
          // reader, so what the product owes here is "no figure", not "$0.00".
          const control = removed(openRouterRepricedSpans.spans)[0];
          expect(control, 'the fixture must seed the removed-row control').toBeDefined();
          await panel.selectSpan(control.name);
          await expect
            .poll(() => new URL(page.url()).searchParams.get('span'), {
              message: `the panel must be showing the control '${control.name}' itself`,
            })
            .toBe(byName.get(control.name)!.id);
          await expect(panel.spanModelChip).toContainText(control.model);
          await expect(
            panel.costAmountsInViewer,
            `${control.key} is priced by no row since 9180 removed it, so the viewer must show ` +
              'no dollar figure for it — not even the <$0.01 floor marker, which would mean it WAS priced',
          ).toHaveCount(0);
        });

        await test.step("The Logs table's Estimated cost cell agrees with the trace's roll-up", async () => {
          // The column a user scans before opening anything, and a separate
          // render path from the panel's header.
          const traceCost = await backendClient.getTraceCost(openRouterRepricedSpans.traceId);
          expect(traceCost, 'the trace must report a rolled-up cost').not.toBeNull();
          expect(
            traceCost!,
            'the roll-up is the sum the test above pins, and so an amount the column can render',
          ).toBeCloseTo(openRouterRepricedSpans.expectedTraceCost, 9);

          await page.goBack();
          const cell = logs.estimatedCostCell(openRouterRepricedSpans.traceId);
          await expect(cell, 'exactly one Estimated cost cell for the seeded trace').toHaveCount(1);
          // Matched as the node whose OWN text is the amount rather than as the
          // cell's full text content: where the Ollie assistant bridge is up —
          // staging is — this cell also holds the hover "Explain" trigger, so
          // its text content reads "$3.72Explain" and a whole-cell match fails
          // for a reason that has nothing to do with the cost.
          await expect(
            cell.getByText(displayedCost(traceCost!), { exact: true }),
            'the Estimated cost cell names the trace total exactly once',
          ).toHaveCount(1);
        });
      },
    );
  },
);
