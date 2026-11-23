import { test, expect } from '@e2e/fixtures';
import type { OpenRouterRemovedRowSeed, OpenRouterRemovedRowSpansRef } from '@e2e/fixtures';
import { OPENROUTER_CATALOG_PROVIDER } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { displayedCost } from '@e2e/core/money';
import type { BackendClient, SpanCostRef } from '@e2e/core/backend';

/**
 * An OpenRouter id that opik#9364 dropped from the price table prices to
 * NOTHING and silently leaves the trace's roll-up, while an id that kept its row
 * keeps its amount.
 *
 * 2.2.139 regenerates `model_prices_openrouter.json` and the curated catalog
 * together, and the two moved different rows. The user-visible consequence the
 * release report flagged is money going to zero without a word: a saved
 * playground prompt or online-evaluation rule pinned to
 * `poolside/laguna-s-2.1` keeps running — the catalog still serves the id — and
 * from this release its spans carry no cost at all, not `$0.00`, nothing, while
 * the trace above them under-reports by exactly their share.
 *
 * ## Why this is a separate spec, not more vectors on the neighbours
 *
 * `openrouter-repriced-rows.spec.ts` (opik#9180, 2.2.120) already pins one
 * removed row. It is a different question and its fixture cannot absorb these:
 * that spec asserts its trace carries EXACTLY its four spans and that its three
 * priced rows resolve three distinct amounts, so adding vectors to its trace
 * would make its own assertions wrong. `openrouter-span-cost.spec.ts`
 * (opik#9132) pins which SPELLING of an id reaches a row, on models this
 * release did not touch.
 *
 * ## What makes this a finding rather than a tautology
 *
 * Every span is logged with `usage` and no `total_cost`, so each amount is the
 * server's own lookup against the shipped file rather than something the seed
 * asserted back to itself. The four vectors:
 *
 *   id                                      label   priced
 *   poolside/laguna-s-2.1                   no      no
 *   nousresearch/hermes-3-llama-3.1-405b    no      no
 *   meta-llama/llama-3.1-70b-instruct       YES     YES  ($1.44)
 *   qwen/qwen3.8-27b                        YES     YES  ($1.535)
 *
 * Both amounts come from the fixture's pins, which track a generated file and
 * move between releases — opik#9585's 2.2.146 regen re-priced BOTH the third and
 * the fourth row, and opik#9697's 2.2.154 regen re-priced the fourth again. The
 * figures here are a reader's orientation, not the assertion.
 *
 * The two priced rows are what stop this passing by accident. A build that
 * shipped no price file at all, or read a stale copy, or stopped resolving
 * OpenRouter ids, prices them wrongly and fails here — where a spec that only
 * asserted "the two removed ids are unpriced" would go green. They sit under two
 * different vendor prefixes, so a lookup that resolved one vendor and not the
 * other cannot satisfy both.
 *
 * QA: this was authored as a 2x2, with meta-llama as a de-labelled-but-billed
 * cell (opik#9365). opik#9608's provider-model sync re-labelled that row, so the
 * label axis has collapsed — both priced rows are labelled now, and the only
 * label contrast left is against the de-listed pair. The pricing claims, which
 * are what `@cap:traces.span-model-cost-tokens` names, are unaffected. See the
 * fixture's self-check for what restoring the cell would need.
 *
 * ## Not claimed
 *
 * Whether a run against a de-listed model is *refused* anywhere, and the
 * absence of any warning in the product — both are the design question the
 * release report raised, not a behaviour the API or the panel exposes. Also not
 * the picker itself: `playground-unlisted-model-hydration.spec.ts` is where a
 * saved config pinned to an unlisted id is covered.
 */

const priced = (spans: readonly OpenRouterRemovedRowSeed[]): OpenRouterRemovedRowSeed[] =>
  spans.filter((s) => s.expectedCost !== null);

const removed = (spans: readonly OpenRouterRemovedRowSeed[]): OpenRouterRemovedRowSeed[] =>
  spans.filter((s) => s.expectedCost === null);

/**
 * Every seeded span, by name, once all of them are queryable.
 *
 * The full set is read and its length asserted rather than only the spans a
 * step needs: two vectors are asserted to have NO cost, which is also what a
 * span that never landed looks like, and reading the whole trace first is what
 * tells those two apart.
 */
async function readSeededSpans(
  backendClient: BackendClient,
  projectId: string,
  seed: OpenRouterRemovedRowSpansRef,
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
 * The fixture's own vector for `key` — its pinned amount and its workings.
 *
 * Read rather than re-stated, so an amount that tracks a generated price file
 * lives in exactly one place. Asserted present for the same reason `spanFor`
 * is: a key that named no vector would otherwise come back `undefined` and the
 * comparison below would be between two of them.
 */
function seedFor(
  seed: OpenRouterRemovedRowSpansRef,
  key: string,
): OpenRouterRemovedRowSeed {
  const vector = seed.spans.find((s) => s.key === key);
  expect(vector, `the fixture must seed a '${key}' vector`).toBeDefined();
  return vector!;
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
  seed: OpenRouterRemovedRowSpansRef,
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
 * a regression, and a `?? 0` would report it as a comparison between two zeros.
 */
function costOf(
  byName: Map<string, SpanCostRef>,
  seed: OpenRouterRemovedRowSpansRef,
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
  'Span cost — the OpenRouter rows 2.2.139 removed from the price table',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'a de-listed OpenRouter id is attributed no cost at all and adds nothing to the trace, while the ids that kept their rows are still billed',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ openRouterRemovedRowSpans, project, backendClient }) => {
        // No page. The subject is the amount the price lookup resolves; the
        // panel renders whatever came out of it, so driving a browser here
        // would add a rendering failure mode to an assertion about arithmetic.
        // The panel is the second test.

        const byName = await test.step(
          'Read the seeded spans back once all of them are queryable',
          async () => readSeededSpans(backendClient, project.id, openRouterRemovedRowSpans),
        );

        await test.step('Each span really carries the model id and token counts under test (seed shape)', async () => {
          // Without this the cost assertions could pass over a seed that
          // silently dropped or altered `model` — and the id arriving character
          // for character is the subject here, since the price-key parse splits
          // on the first slash and these ids are three-segment.
          for (const seed of openRouterRemovedRowSpans.spans) {
            const span = spanFor(byName, openRouterRemovedRowSpans, seed.key);
            expect(span.model, `model id logged for ${seed.key}`).toBe(seed.model);
            expect(span.provider, `provider logged for ${seed.key}`).toBe(
              OPENROUTER_CATALOG_PROVIDER,
            );
            const usage = span.usage;
            expect(usage, `usage logged for ${seed.key}`).not.toBeNull();
            expect(usage!['prompt_tokens'], `prompt_tokens for ${seed.key}`).toBe(
              openRouterRemovedRowSpans.promptTokens,
            );
            expect(usage!['completion_tokens'], `completion_tokens for ${seed.key}`).toBe(
              openRouterRemovedRowSpans.completionTokens,
            );
          }
        });

        await test.step('Neither de-listed id is attributed any cost — not zero, nothing', async () => {
          // `toBeNull`, deliberately, and not the `?? 0` its neighbour spec
          // uses for its own control. Null and 0 are different claims about the
          // product: 0 is what a genuinely FREE model would resolve to, and the
          // report's open question was exactly whether a reader can tell the
          // two apart. The answer the API gives is "no figure", so that is what
          // is pinned.
          const controls = removed(openRouterRemovedRowSpans.spans);
          expect(controls, 'two de-listed rows, from two vendors').toHaveLength(2);
          for (const seed of controls) {
            expect(
              spanFor(byName, openRouterRemovedRowSpans, seed.key).totalEstimatedCost,
              `${seed.key}: ${seed.workings}`,
            ).toBeNull();
          }
        });

        await test.step('The surviving meta-llama id is still billed at its own shipped rates', async () => {
          // The half that makes the step above a finding rather than a
          // tautology: same provider, same token counts, same release — and
          // still priced. A build that had lost the price file wholesale fails
          // here while leaving every "unpriced" assertion green.
          //
          // The amount comes from the fixture's own pin rather than a literal
          // here. It used to be both, and the 2.2.146 price regen moved this
          // row ($0.40/M both ways to $0.72/M) — which failed on the literal
          // while the fixture was still the place the header tells a reader to
          // update. One source for a generated number.
          const control = seedFor(openRouterRemovedRowSpans, 'llama-priced-control');
          expect(
            control.expectedCost,
            'the meta-llama vector must carry a pinned amount — it is the priced control',
          ).not.toBeNull();
          expect(
            costOf(byName, openRouterRemovedRowSpans, 'llama-priced-control'),
            `meta-llama/llama-3.1-70b-instruct: still in the price table ` +
              `(${control.workings})`,
          ).toBeCloseTo(control.expectedCost!, 9);
        });

        await test.step("Every priced row is billed at its own rate, and the two rates differ", async () => {
          // Absolute amounts from the shipped file's own published rates, which
          // is what pins this to the table 9364 generated rather than to
          // itself. Asserted together with their distinctness: a resolver that
          // returned one fixed number for every OpenRouter span satisfies at
          // most one of the two.
          const vectors = priced(openRouterRemovedRowSpans.spans);
          expect(vectors, 'the fixture seeds two priced rows').toHaveLength(2);
          for (const seed of vectors) {
            expect(
              costOf(byName, openRouterRemovedRowSpans, seed.key),
              `${seed.key} (${seed.model}): ${seed.workings}`,
            ).toBeCloseTo(seed.expectedCost!, 9);
          }
          const amounts = vectors.map((seed) =>
            costOf(byName, openRouterRemovedRowSpans, seed.key),
          );
          expect(
            new Set(amounts).size,
            `the two priced rows must resolve two distinct amounts, got ${JSON.stringify(amounts)}`,
          ).toBe(amounts.length);
        });

        await test.step("The trace's rolled-up cost is the sum of its priced spans alone", async () => {
          // The number a user actually reads, and the one a de-listed row makes
          // plausible rather than obviously absent: the trace rolls up to the
          // two priced rows' sum where four priced spans would have given more,
          // and that sum is a perfectly ordinary figure. (It comes from the
          // fixture, not a literal, because the rates come from a generated
          // file — see its header.)
          const traceCost = await backendClient.getTraceCost(openRouterRemovedRowSpans.traceId);
          expect(traceCost, 'the trace must report a rolled-up cost').not.toBeNull();
          expect(
            traceCost!,
            'trace total across the two priced rows, with nothing from either de-listed one',
          ).toBeCloseTo(openRouterRemovedRowSpans.expectedTraceCost, 9);
        });

        await test.step('All four ids are still SERVED by the model catalog, and the de-listed pair carry no label', async () => {
          // What turns "these spans are unpriced" into the user-facing finding:
          // the ids are still runnable. A row the catalog had dropped entirely
          // would make an unpriced span unremarkable — nothing can produce one —
          // so this is the step that says a saved config pinned to one of these
          // still resolves.
          //
          // Catalog ids are TWO-segment under their provider, while the price
          // file keys them three-segment with an `openrouter/` prefix; the
          // fixture carries both spellings rather than deriving one from the
          // other, because the two being different is a real property here.
          const catalog = await backendClient.getLlmCatalogModels(OPENROUTER_CATALOG_PROVIDER);
          const byId = new Map(catalog.map((m) => [m.id, m]));
          for (const seed of openRouterRemovedRowSpans.spans) {
            const row = byId.get(seed.catalogId);
            expect(
              row,
              `the catalog must still serve '${seed.catalogId}' — an id nothing can run is not ` +
                'the money-to-zero case this spec is about',
            ).toBeDefined();
            if (seed.catalogLabelled) {
              // Asserted as a non-empty string rather than against a literal:
              // the label is display copy regenerated with the catalog, so its
              // VALUE is not a contract. Its presence is: the picker lists
              // labelled rows and skips unlabelled ones.
              expect(
                row!.label,
                `'${seed.catalogId}' must still carry a catalog label, or there is nothing for ` +
                  'the unlabelled vectors to be contrasted against',
              ).not.toBeNull();
              expect(row!.label, `'${seed.catalogId}' label is not blank`).not.toBe('');
            } else {
              expect(
                row!.label,
                `'${seed.catalogId}' carries no catalog label, so the picker does not offer it ` +
                  'while the id stays runnable',
              ).toBeNull();
            }
          }
        });
      },
    );

    test(
      'the trace panel shows no dollar figure for a de-listed id, its own amount for each priced one, and a roll-up that omits the de-listed pair',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ openRouterRemovedRowSpans, project, backendClient, page }) => {
        // Room for the row-reload path below: an ingestion poll, a panel walk
        // over four spans and a listing read with a navigation between them do
        // not fit the 90s default once the workspace traces budget is being
        // shed.
        test.setTimeout(240_000);

        const pricedVectors = priced(openRouterRemovedRowSpans.spans);
        const removedVectors = removed(openRouterRemovedRowSpans.spans);

        const byName = await test.step(
          'The server really priced the two priced rows before the page is read for those amounts',
          async () => {
            // A UI assertion over a seed that never got priced is a test that
            // cannot fail: the amounts below would simply never appear, and any
            // wait for them would be indistinguishable from a rendering bug.
            // Polled to EXACTLY the two priced vectors — the other two must stay
            // unpriced, which the test above asserts and this one relies on.
            await expect
              .poll(
                async () => {
                  const spans = await backendClient.listSpanCosts({
                    projectId: project.id,
                    traceId: openRouterRemovedRowSpans.traceId,
                  });
                  return spans.filter(
                    (s) => s.totalEstimatedCost !== null && s.totalEstimatedCost > 0,
                  ).length;
                },
                {
                  message:
                    `the server to price the ${pricedVectors.length} priced vectors on trace ` +
                    `${openRouterRemovedRowSpans.traceId} — a timeout here means the seed never ` +
                    'resolved a price, not that the panel rendered the wrong amount',
                  timeout: 60_000,
                  intervals: [500, 1_000, 2_000],
                },
              )
              .toBe(pricedVectors.length);

            return readSeededSpans(backendClient, project.id, openRouterRemovedRowSpans);
          },
        );

        await test.step('The two priced amounts render as two different strings', async () => {
          // What makes the per-span assertions below discriminating: without
          // it, a panel stuck on one span's cost could satisfy both.
          const rendered = pricedVectors.map((s) => displayedCost(s.expectedCost!));
          expect(
            new Set(rendered).size,
            `the two priced rows must render as two distinct strings, got ${JSON.stringify(rendered)}`,
          ).toBe(rendered.length);
        });

        const logs = new LogsPage(page);

        const panel = await test.step('Open the seeded trace in the Logs panel', async () => {
          await logs.goto(project.id);
          const opened = await logs.openTraceById(openRouterRemovedRowSpans.traceId);
          await opened.waitForFullyLoaded();
          return opened;
        });

        await test.step('Each priced span shows its own resolved cost against its own model', async () => {
          for (const seed of pricedVectors) {
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
            await expect(panel.estimatedCost(displayedCost(seed.expectedCost!))).toBeVisible();
          }
        });

        await test.step('Neither de-listed id renders a dollar amount at all', async () => {
          // Asserted as the absence of an AMOUNT rather than as a literal "-":
          // `formatCost` happens to render its `noValue` dash, but a dash is a
          // default that could reasonably become an em dash or a blank. The step
          // above is what makes this discriminating — the same viewer rendered
          // two amounts a moment ago, so "no amount" cannot be explained by the
          // locator never matching anything.
          //
          // This is the release report's open question made observable: an
          // unpriced row is indistinguishable from a genuinely free model to a
          // reader, so what the product shows here is "no figure", not "$0.00".
          for (const control of removedVectors) {
            await panel.selectSpan(control.name);
            await expect
              .poll(() => new URL(page.url()).searchParams.get('span'), {
                message: `the panel must be showing the control '${control.name}' itself`,
              })
              .toBe(byName.get(control.name)!.id);
            await expect(panel.spanModelChip).toContainText(control.model);
            await expect(
              panel.costAmountsInViewer,
              `${control.key} is priced by no row since 9364 removed it, so the viewer must show ` +
                'no dollar figure for it — not even the <$0.01 floor marker, which would mean it WAS priced',
            ).toHaveCount(0);
          }
        });

        await test.step("The Logs table's Estimated cost cell agrees with the trace's roll-up", async () => {
          // The column a user scans before opening anything, and a separate
          // render path from the panel's header. It is also where the quiet
          // failure is visible: the cell names a smaller number than the spans
          // below it would have summed to, and says nothing about why.
          const traceCost = await backendClient.getTraceCost(openRouterRemovedRowSpans.traceId);
          expect(traceCost, 'the trace must report a rolled-up cost').not.toBeNull();
          expect(
            traceCost!,
            'the roll-up is the sum the test above pins, and so an amount the column can render',
          ).toBeCloseTo(openRouterRemovedRowSpans.expectedTraceCost, 9);

          await page.goBack();
          // Waited through the POM rather than straight into the cell: on
          // staging the listing this return re-issues can be answered 429 by
          // the per-workspace traces limit, and the FE keeps that empty answer
          // without refetching — so the table stays rowless and the cell can
          // never appear, while the roll-up just above read back fine. See the
          // POM method for why a reload is the only thing that re-issues the
          // listing.
          await logs.waitForTraceRow(openRouterRemovedRowSpans.traceId);
          const cell = logs.estimatedCostCell(openRouterRemovedRowSpans.traceId);
          await expect(cell, 'exactly one Estimated cost cell for the seeded trace').toHaveCount(1);
          // Matched as the node whose OWN text is the amount rather than as the
          // cell's full text content: where the Ollie assistant bridge is up —
          // staging is — this cell also holds the hover "Explain" trigger, so
          // its text content reads "$2.82Explain" and a whole-cell match fails
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
