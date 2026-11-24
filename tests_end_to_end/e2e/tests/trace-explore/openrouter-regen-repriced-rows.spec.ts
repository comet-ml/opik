import { test, expect } from '@e2e/fixtures';
import type { OpenRouterRegenRepricedSeed, OpenRouterRegenRepricedSpansRef } from '@e2e/fixtures';
import { OPENROUTER_REGEN_CATALOG_PROVIDER } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { displayedCost } from '@e2e/core/money';
import type { BackendClient, SpanCostRef } from '@e2e/core/backend';

/**
 * The OpenRouter rows the 2.3.1 price regen (opik#9733) moved: two re-priced
 * rows bill at their new published rates, and the row it dropped bills at
 * nothing while still being servable.
 *
 * Money a user reads to decide their LLM spend, changed for every existing
 * customer with no opt-in and no announcement. The three vectors are the three
 * things a regen can do to a row, and the seed carries one of each:
 *
 *   openrouter/deepseek/deepseek-v4-pro-0813   re-priced in BOTH directions  $8.19
 *   openrouter/z-ai/glm-5.1                    re-priced upward              $5.80
 *   openrouter/xiaomi/mimo-v2.5-pro            row removed, label removed    nothing
 *
 * ## Why this is a separate spec from the two OpenRouter cost specs beside it
 *
 * `openrouter-repriced-rows.spec.ts` (opik#9180) and
 * `openrouter-removed-rows-unpriced.spec.ts` (opik#9364/9365) each pin three or
 * four OTHER ids, and each asserts that its trace carries EXACTLY its own spans
 * and that its priced rows resolve distinct amounts — so adding vectors to
 * either trace would make its own assertions wrong. More to the point, neither
 * names a single id this regen touched, so both stay green whatever these three
 * rows do. That is precisely the gap: the estate had four OpenRouter cost specs
 * and none of them could see this release's price change.
 *
 * ## What makes this a finding rather than a tautology
 *
 * Every span is logged with `usage` and **no** `total_cost`, so each amount is
 * the server's own lookup against the shipped price file rather than something
 * the seed asserted back to itself.
 *
 * The deepseek row is the load-bearing vector. Its input rate fell about 7x
 * while its output rate DOUBLED, so there is no single multiplier that turns the
 * old amount into the new one: a build that shipped a stale copy of the file,
 * read the previous release's rates, or fell through to the undated
 * `deepseek/deepseek-v4-pro` row lands on a different number in one direction or
 * the other. A row re-priced in one direction alone — which glm-5.1 is — cannot
 * discriminate that, which is why both are here.
 *
 * The de-listed row is the mirror of it, and its two assertions pull in opposite
 * directions on purpose: it must be attributed NO cost, and it must still be
 * SERVED by the catalog. An id nothing can run is not the money-to-zero case; an
 * id that is runnable, unselectable (the same regen dropped its `label`) and
 * silently free is. Its own `mimo-v2.5` and `mimo-v2.6-*` siblings keep their
 * price rows, so "this vendor prefix stopped resolving" is ruled out.
 *
 * ## Why the amounts are pinned here rather than read from the price file
 *
 * The amounts track a generated artifact and are expected to need updating — the
 * fixture header says where to look and what to check. Reading the shipped JSON
 * at run time would be less work and strictly weaker: the spec would then agree
 * with whatever the file said, including a file that shipped empty or stale,
 * which is one of the failures it exists to catch. Pinning the absolute figures
 * is the only thing that ties the server's answer to the table the release
 * actually published. Every figure lives in the fixture and nowhere else, so a
 * regen is one edit.
 *
 * ## Not claimed
 *
 * Whether a run against a de-listed model is refused or warned about anywhere.
 * That is the design question the release report raised, not a behaviour the API
 * or the panel exposes. The picker's own treatment of an unlabelled id is
 * covered by `playground-unlisted-model-hydration.spec.ts`.
 */

const priced = (spans: readonly OpenRouterRegenRepricedSeed[]): OpenRouterRegenRepricedSeed[] =>
  spans.filter((s) => s.expectedCost !== null);

const delisted = (spans: readonly OpenRouterRegenRepricedSeed[]): OpenRouterRegenRepricedSeed[] =>
  spans.filter((s) => s.expectedCost === null);

/**
 * Every seeded span, by name, once all of them are queryable.
 *
 * The full set is read and its length asserted rather than only the spans a step
 * needs: one vector is asserted to have NO cost, which is also what a span that
 * never landed looks like, and reading the whole trace first is what tells those
 * two apart.
 */
async function readSeededSpans(
  backendClient: BackendClient,
  projectId: string,
  seed: OpenRouterRegenRepricedSpansRef,
): Promise<Map<string, SpanCostRef>> {
  await expect
    .poll(
      async () => (await backendClient.listSpanCosts({ projectId, traceId: seed.traceId })).length,
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
 * A vector the fixture failed to seed would otherwise surface as an `undefined`
 * the assertions below had to code around, which is the shape that turns a
 * broken seed into a green run.
 */
function spanFor(
  byName: Map<string, SpanCostRef>,
  seed: OpenRouterRegenRepricedSpansRef,
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
 * Asserts the cost is present before returning it: for these, an absent cost is
 * a regression, and a `?? 0` would report it as a comparison between two zeros.
 */
function costOf(
  byName: Map<string, SpanCostRef>,
  seed: OpenRouterRegenRepricedSpansRef,
  key: string,
): number {
  const span = spanFor(byName, seed, key);
  expect(
    span.totalEstimatedCost,
    `${key} must be priced server-side — an absent cost is a failure here, not a zero`,
  ).not.toBeNull();
  return span.totalEstimatedCost!;
}

test.describe(
  'Span cost — the OpenRouter rows the 2.3.1 price regen moved (opik#9733)',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'each re-priced OpenRouter row bills at its own new rates, and the row the regen dropped bills at nothing',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ openRouterRegenRepricedSpans, project, backendClient }) => {
        // No page. The subject here is the amount the price lookup resolves; the
        // panel renders whatever came out of it, so driving a browser would add
        // a rendering failure mode to an assertion about arithmetic. The panel
        // is the second test.
        const seed = openRouterRegenRepricedSpans;

        const byName = await test.step(
          'Read the seeded spans back once all of them are queryable',
          async () => readSeededSpans(backendClient, project.id, seed),
        );

        await test.step('Each span really carries the model id and token counts under test (seed shape)', async () => {
          // Without this the cost assertions could pass over a seed that
          // silently dropped or altered `model` — and the id arriving character
          // for character is the subject here, since the price-key parse splits
          // on the first slash and all three ids are three-segment.
          for (const vector of seed.spans) {
            const span = spanFor(byName, seed, vector.key);
            expect(span.model, `model id logged for ${vector.key}`).toBe(vector.model);
            expect(span.provider, `provider logged for ${vector.key}`).toBe(
              OPENROUTER_REGEN_CATALOG_PROVIDER,
            );
            const usage = span.usage;
            expect(usage, `usage logged for ${vector.key}`).not.toBeNull();
            expect(usage!['prompt_tokens'], `prompt_tokens for ${vector.key}`).toBe(
              seed.promptTokens,
            );
            expect(usage!['completion_tokens'], `completion_tokens for ${vector.key}`).toBe(
              seed.completionTokens,
            );
          }
        });

        await test.step('Every re-priced row is billed at its own new rate, and the two rates differ', async () => {
          // Absolute amounts from the shipped file's own published rates, which
          // is what pins this to the table 9733 generated rather than to itself.
          // Asserted together with their distinctness: a resolver that returned
          // one fixed number for every OpenRouter span satisfies at most one of
          // the two.
          //
          // deepseek is the vector that makes this discriminating. Its two rates
          // moved in OPPOSITE directions, so a stale file, the previous
          // release's rates, or a fall-through to the undated
          // `deepseek/deepseek-v4-pro` row all land somewhere else.
          const vectors = priced(seed.spans);
          expect(vectors, 'the fixture seeds two re-priced rows').toHaveLength(2);
          for (const vector of vectors) {
            expect(
              costOf(byName, seed, vector.key),
              `${vector.key} (${vector.model}): ${vector.workings}`,
            ).toBeCloseTo(vector.expectedCost!, 9);
          }
          const amounts = vectors.map((vector) => costOf(byName, seed, vector.key));
          expect(
            new Set(amounts).size,
            `the two re-priced rows must resolve two distinct amounts, got ${JSON.stringify(amounts)}`,
          ).toBe(amounts.length);
        });

        await test.step('The de-listed row is attributed no cost at all — not zero, nothing', async () => {
          // `toBeNull`, deliberately. Null and 0 are different claims about the
          // product: 0 is what a genuinely FREE model resolves to, and a reader
          // cannot tell the two apart on screen. The answer the API gives for a
          // row that no longer exists is "no figure", so that is what is pinned.
          //
          // The step above is what makes this a finding rather than a tautology:
          // same provider, same token counts, same release, same request — and
          // two of the three ARE priced, so "no cost" here cannot be explained
          // by a build that had lost the price file wholesale.
          const controls = delisted(seed.spans);
          expect(controls, 'one de-listed row').toHaveLength(1);
          for (const vector of controls) {
            expect(
              spanFor(byName, seed, vector.key).totalEstimatedCost,
              `${vector.key}: ${vector.workings}`,
            ).toBeNull();
          }
        });

        await test.step("The trace's rolled-up cost is the sum of its priced spans alone", async () => {
          // The number a user actually reads, and the one a de-listed row makes
          // plausible rather than obviously absent: the trace rolls up to the
          // two priced rows' sum where three priced spans would have given more,
          // and that sum is a perfectly ordinary figure. It comes from the
          // fixture, not a literal here, because the rates come from a generated
          // file.
          const traceCost = await backendClient.getTraceCost(seed.traceId);
          expect(traceCost, 'the trace must report a rolled-up cost').not.toBeNull();
          expect(
            traceCost!,
            'trace total across the two re-priced rows, with nothing from the de-listed one',
          ).toBeCloseTo(seed.expectedTraceCost, 9);
        });

        await test.step('All three ids are still SERVED by the catalog, and only the de-listed one lost its label', async () => {
          // What turns "this span is unpriced" into the user-facing finding: the
          // id is still runnable. A row the catalog had dropped entirely would
          // make an unpriced span unremarkable — nothing can produce one — so
          // this is the step that says a saved playground prompt or
          // online-evaluation rule pinned to mimo-v2.5-pro still resolves.
          //
          // The label is the other half: 9734's registry sync dropped it, so the
          // id is no longer offered by any picker while remaining usable from a
          // stored config. Both re-priced rows keep theirs, which is what the
          // de-labelling is contrasted against.
          //
          // Catalog ids are TWO-segment under their provider while the price file
          // keys them three-segment with an `openrouter/` prefix; the fixture
          // carries both spellings rather than deriving one from the other,
          // because the two being different is a real property here.
          const catalog = await backendClient.getLlmCatalogModels(
            OPENROUTER_REGEN_CATALOG_PROVIDER,
          );
          const byId = new Map(catalog.map((m) => [m.id, m]));
          for (const vector of seed.spans) {
            const row = byId.get(vector.catalogId);
            expect(
              row,
              `the catalog must still serve '${vector.catalogId}' — an id nothing can run is ` +
                'not the money-to-zero case this spec is about',
            ).toBeDefined();
            if (vector.catalogLabelled) {
              // Asserted as a non-empty string rather than against a literal:
              // the label is display copy regenerated with the catalog, so its
              // VALUE is not a contract. Its presence is — the pickers list
              // labelled rows and skip unlabelled ones.
              expect(
                row!.label,
                `'${vector.catalogId}' must still carry a catalog label, or there is nothing ` +
                  'for the unlabelled vector to be contrasted against',
              ).not.toBeNull();
              expect(row!.label, `'${vector.catalogId}' label is not blank`).not.toBe('');
            } else {
              expect(
                row!.label,
                `'${vector.catalogId}' carries no catalog label, so no picker offers it while ` +
                  'the id stays runnable from a saved config',
              ).toBeNull();
            }
          }
        });
      },
    );

    test(
      'the trace panel shows each re-priced amount against its own model, no figure for the de-listed row, and a roll-up that omits it',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ openRouterRegenRepricedSpans, project, backendClient, page }) => {
        // An ingestion poll, a panel walk over three spans, and a listing read
        // with a navigation between them do not fit the 90s default once the
        // workspace traces budget is being shed.
        test.setTimeout(240_000);

        const seed = openRouterRegenRepricedSpans;
        const pricedVectors = priced(seed.spans);
        const delistedVectors = delisted(seed.spans);

        const byName = await test.step(
          'The server really priced the two re-priced rows before the page is read for those amounts',
          async () => {
            // A UI assertion over a seed that never got priced is a test that
            // cannot fail: the amounts below would simply never appear, and any
            // wait for them would be indistinguishable from a rendering bug.
            // Polled to EXACTLY the two priced vectors — the third must stay
            // unpriced, which the test above asserts and this one relies on.
            await expect
              .poll(
                async () => {
                  const spans = await backendClient.listSpanCosts({
                    projectId: project.id,
                    traceId: seed.traceId,
                  });
                  return spans.filter(
                    (s) => s.totalEstimatedCost !== null && s.totalEstimatedCost > 0,
                  ).length;
                },
                {
                  message:
                    `the server to price the ${pricedVectors.length} re-priced vectors on trace ` +
                    `${seed.traceId} — a timeout here means the seed never resolved a price, not ` +
                    'that the panel rendered the wrong amount',
                  timeout: 60_000,
                  intervals: [500, 1_000, 2_000],
                },
              )
              .toBe(pricedVectors.length);

            return readSeededSpans(backendClient, project.id, seed);
          },
        );

        await test.step('The two priced amounts render as two different strings', async () => {
          // What makes the per-span assertions below discriminating: without it,
          // a panel stuck on one span's cost could satisfy both.
          const rendered = pricedVectors.map((s) => displayedCost(s.expectedCost!));
          expect(
            new Set(rendered).size,
            `the two re-priced rows must render as two distinct strings, got ${JSON.stringify(rendered)}`,
          ).toBe(rendered.length);
        });

        const logs = new LogsPage(page);

        const panel = await test.step('Open the seeded trace in the Logs panel', async () => {
          await logs.goto(project.id);
          const opened = await logs.openTraceById(seed.traceId);
          await opened.waitForFullyLoaded();
          return opened;
        });

        await test.step('Each re-priced span shows its own resolved cost against its own model', async () => {
          for (const vector of pricedVectors) {
            await panel.selectSpan(vector.name);
            // Pin the assertions to the span that is actually selected.
            // `selectSpan` only waits for SOME span to be in the URL, and every
            // span here shares a provider — so a click that left the viewer on
            // the previous span could otherwise satisfy both reads.
            await expect
              .poll(() => new URL(page.url()).searchParams.get('span'), {
                message: `the panel must be showing '${vector.name}' itself, not whichever span was selected before it`,
              })
              .toBe(byName.get(vector.name)!.id);
            await expect(panel.spanModelChip).toContainText(vector.model);
            await expect(panel.estimatedCost(displayedCost(vector.expectedCost!))).toBeVisible();
          }
        });

        await test.step('The de-listed id renders no dollar amount at all', async () => {
          // Asserted as the absence of an AMOUNT rather than as a literal "-":
          // `formatCost` happens to render its `noValue` dash, but a dash is a
          // default that could reasonably become an em dash or a blank. The step
          // above is what makes this discriminating — the same viewer rendered
          // two amounts a moment ago, so "no amount" cannot be explained by the
          // locator never matching anything.
          for (const vector of delistedVectors) {
            await panel.selectSpan(vector.name);
            await expect
              .poll(() => new URL(page.url()).searchParams.get('span'), {
                message: `the panel must be showing the control '${vector.name}' itself`,
              })
              .toBe(byName.get(vector.name)!.id);
            await expect(panel.spanModelChip).toContainText(vector.model);
            await expect(
              panel.costAmountsInViewer,
              `${vector.key} is priced by no row since 9733 removed it, so the viewer must show ` +
                'no dollar figure for it — not even the <$0.01 floor marker, which would mean it WAS priced',
            ).toHaveCount(0);
          }
        });

        await test.step("The Logs table's Estimated cost cell agrees with the trace's roll-up", async () => {
          // The column a user scans before opening anything, and a separate
          // render path from the panel's header. It is also where the quiet
          // failure is visible: the cell names a smaller number than the spans
          // below it would have summed to, and says nothing about why.
          const traceCost = await backendClient.getTraceCost(seed.traceId);
          expect(traceCost, 'the trace must report a rolled-up cost').not.toBeNull();
          expect(
            traceCost!,
            'the roll-up is the sum the test above pins, and so an amount the column can render',
          ).toBeCloseTo(seed.expectedTraceCost, 9);

          await page.goBack();
          // Waited through the POM rather than straight into the cell: on
          // staging the listing this return re-issues can be answered 429 by the
          // per-workspace traces limit, and the FE keeps that empty answer
          // without refetching — so the table stays rowless and the cell can
          // never appear, while the roll-up just above read back fine.
          await logs.waitForTraceRow(seed.traceId);
          const cell = logs.estimatedCostCell(seed.traceId);
          await expect(cell, 'exactly one Estimated cost cell for the seeded trace').toHaveCount(1);
          // Matched as the node whose OWN text is the amount rather than as the
          // cell's full text content: where the Ollie assistant bridge is up —
          // staging is — this cell also holds the hover "Explain" trigger, so
          // its text content reads "$13.99Explain" and a whole-cell match fails
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
