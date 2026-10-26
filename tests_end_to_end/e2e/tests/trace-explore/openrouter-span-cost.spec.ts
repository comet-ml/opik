import { test, expect } from '@e2e/fixtures';
import type { OpenRouterCostSpanSeed, OpenRouterCostSpansRef } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { displayedCost } from '@e2e/core/money';
import type { BackendClient, SpanCostRef } from '@e2e/core/backend';

/**
 * An OpenRouter LLM span is priced from `model_prices_openrouter.json`
 * (opik#9132) — and must be priced under the model id Opik itself writes, not
 * only under the file's own three-segment spelling.
 *
 * 2.2.119 is the release where that file became the thing restoring OpenRouter
 * prices: LiteLLM's table no longer lists them, so this generated file is the
 * only source for 468 rows. No cost spec in the estate uses an OpenRouter model
 * at all — `span-cost-resolution.spec.ts` covers the LiteLLM half and
 * `span-new-provider-pricing.spec.ts` the removed provider whitelist — so a
 * build that stopped reading the OpenRouter file entirely would pass the whole
 * estate today.
 *
 * ## The two spellings, and why they are separate tests
 *
 * `CostService.parseModelName` strips everything up to the FIRST slash, from
 * the price-file key as well as from the span's model. The file keys its rows
 * three-segment (`openrouter/amazon/nova-lite-v1`), so a row lands in the price
 * map two-segment (`amazon/nova-lite-v1`) and only a THREE-segment span model
 * can reach it.
 *
 * The id Opik writes for its own runs is the two-segment one: the model picker
 * stores the bare registry id and `ExperimentTracePersistence` writes
 * `ctx.prompt().model()` verbatim. It strips to `nova-lite-v1` and matches
 * nothing, so an OpenRouter run started from Opik gets no cost at all — and the
 * trace above it still rolls up a number that looks like a number.
 *
 * So the first test asserts the behaviour that works (the file prices its own
 * spelling, at both surfaces, with an unpriced control beside it) and the
 * second asserts the behaviour that does not and is marked expected-to-fail.
 * Keeping them apart is what lets the working half keep failing loudly if the
 * OpenRouter file stops being read, instead of being masked by the defect.
 *
 * Both are the CORRECT behaviour. Nothing here pins "the bare spelling resolves
 * nothing": a build that priced both spellings is the fix, and a spec that
 * asserted today's answer would have to be deleted to let it land.
 *
 * ## Not introduced by this release
 *
 * LiteLLM keyed its OpenRouter rows the same way, so the lookup behaved
 * identically before — what 9132 changes is that this is now the file supplying
 * those prices, and it does not reach the spelling Opik writes. 522 of the 781
 * OpenRouter models the picker offers carry a vendor prefix that is not itself a
 * LiteLLM provider, so they cannot resolve through `findModelPrice`'s
 * provider-prefix fallback either.
 *
 * Deterministic and provider-free: the fixture supplies `usage` and no
 * `total_cost`, so every amount is the server's own lookup, and the two members
 * of each pair differ ONLY in how the model id is spelled.
 *
 * ## Not this spec's question
 *
 * Whether the rows a given release RE-PRICED are billed at the rates it
 * shipped. Both models here are ones no release has moved, deliberately, so
 * this stays a spec about the lookup. `openrouter-repriced-rows.spec.ts` pins
 * the amounts, on the rows opik#9180 changed and the id it removed.
 */

/**
 * The vectors no price row may resolve.
 *
 * There is no `priced` counterpart on purpose: which spans must be billed is
 * asked by SPELLING here, not by expected cost, because the two bare spellings
 * are expected to price too once the defect the second test pins is fixed. See
 * `bySpelling`.
 */
const controls = (spans: readonly OpenRouterCostSpanSeed[]): OpenRouterCostSpanSeed[] =>
  spans.filter((s) => s.expectedCost === 0);

const bySpelling = (
  spans: readonly OpenRouterCostSpanSeed[],
  spelling: 'prefixed' | 'bare',
): OpenRouterCostSpanSeed[] => spans.filter((s) => s.spelling === spelling);

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
  seed: OpenRouterCostSpansRef,
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
  seed: OpenRouterCostSpansRef,
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
 * the exact regression the spec exists for, and a `?? 0` would report it as a
 * comparison between two zeros. The control goes through `attributedCost`
 * instead, where absent and zero are the same answer.
 */
function costOf(
  byName: Map<string, SpanCostRef>,
  seed: OpenRouterCostSpansRef,
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
 * Absent and zero are the same answer for a model the price file must not match
 * — "nothing was billed" — and only the control is allowed to collapse them.
 */
const attributedCost = (span: SpanCostRef): number => span.totalEstimatedCost ?? 0;

test.describe(
  'Span cost — the OpenRouter price file',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      "an OpenRouter model id spelled as the price file keys it is billed at that row's rates, and a model in no row is not",
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ openRouterCostSpans, project, backendClient }) => {
        // No page. The subject is whether the price lookup reaches an
        // OpenRouter row at all; the panel renders whatever came out of it, so
        // driving a browser here would add a rendering failure mode to an
        // assertion about a lookup. The panel is the second test.

        const byName = await test.step(
          'Read the seeded spans back once all of them are queryable',
          async () => readSeededSpans(backendClient, project.id, openRouterCostSpans),
        );

        await test.step('Each span really carries the model id and token counts under test (seed shape)', async () => {
          // Without this the cost assertions could pass over a seed that
          // silently dropped or altered `model` — and the id arriving character
          // for character is the entire subject here, since the price-key parse
          // splits on its first slash.
          for (const seed of openRouterCostSpans.spans) {
            const span = spanFor(byName, openRouterCostSpans, seed.key);
            expect(span.model, `model id logged for ${seed.key}`).toBe(seed.model);
            expect(span.provider, `provider logged for ${seed.key}`).toBe(seed.provider);
            const usage = span.usage;
            expect(usage, `usage logged for ${seed.key}`).not.toBeNull();
            expect(usage!['prompt_tokens'], `prompt_tokens for ${seed.key}`).toBe(
              openRouterCostSpans.promptTokens,
            );
            expect(usage!['completion_tokens'], `completion_tokens for ${seed.key}`).toBe(
              openRouterCostSpans.completionTokens,
            );
          }
        });

        await test.step("Every three-segment spelling is billed at its row's own rates", async () => {
          // Absolute amounts, from the OpenRouter file's own published rates —
          // what pins this to the shipped table rather than merely to itself.
          // Both vectors are here, so a build that read the file for one vendor
          // prefix and not another fails.
          const prefixed = bySpelling(openRouterCostSpans.spans, 'prefixed');
          expect(
            prefixed,
            'the fixture seeds both OpenRouter models under their file spelling',
          ).toHaveLength(2);
          for (const seed of prefixed) {
            expect(
              costOf(byName, openRouterCostSpans, seed.key),
              `${seed.key} (${seed.model}): ${seed.workings}`,
            ).toBeCloseTo(seed.expectedCost, 9);
          }
        });

        await test.step('The two rows price differently, so neither amount is a constant', async () => {
          // The discriminator for the step above. Two rows, two amounts: a
          // resolver that returned one fixed number — or that billed both at
          // some other table's rate — satisfies neither "close to 0.30" nor
          // "close to 2.40", but a reader of a single absolute assertion could
          // not tell "the file was read" from "this one number happens to
          // match". The pair also covers both branches `resolveCalculator` can
          // take: aion-2.0 publishes a cache-read rate and nova-lite-v1 does
          // not, and a cache-priced row whose span reports no cached tokens
          // must still bill its plain input rate.
          expect(
            costOf(byName, openRouterCostSpans, 'aion-prefixed'),
            'the two OpenRouter rows resolve different amounts',
          ).toBeGreaterThan(costOf(byName, openRouterCostSpans, 'nova-prefixed'));
        });

        await test.step('The control stays unpriced', async () => {
          // The half that makes the amounts a finding rather than a tautology.
          // Same token counts and the same three-segment shape as the two
          // above, so a cost here would mean the lookup resolved something for
          // a vendor and model that appear nowhere in the price file.
          for (const seed of controls(openRouterCostSpans.spans)) {
            expect(
              attributedCost(spanFor(byName, openRouterCostSpans, seed.key)),
              `${seed.key}: ${seed.workings}`,
            ).toBe(0);
          }
        });
      },
    );

    test(
      'the model id Opik itself writes for an OpenRouter model is billed the same as the price file spelling',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ openRouterCostSpans, project, backendClient }) => {
        // QA: failing on staging for 2.2.119, and reproduced in the code.
        // `CostService.parseModelName` strips to the FIRST slash on the
        // price-file key and on the span's model alike, so a row keyed
        // `openrouter/amazon/nova-lite-v1` is stored `amazon/nova-lite-v1` and
        // the two-segment id Opik writes strips to `nova-lite-v1`, matching
        // nothing. An OpenRouter run started from Opik therefore gets no cost:
        // the model picker stores the bare registry id and
        // `ExperimentTracePersistence` writes it verbatim, and 522 of the 781
        // OpenRouter models the picker offers have a vendor prefix that is not
        // itself a LiteLLM provider, so they cannot resolve through
        // `findModelPrice`'s provider-prefix fallback either.
        //
        // Not introduced by opik#9132 — LiteLLM keyed the same rows the same
        // way — but 9132 is what made this file the source of OpenRouter
        // prices, so it is this release's to answer for.
        //
        // The assertions below are the CORRECT behaviour and are left intact.
        // Remove test.fail() once the bare spelling resolves; Playwright
        // reports it the moment this passes.
        test.fail();

        const byName = await test.step(
          'Read the seeded spans back once all of them are queryable',
          async () => readSeededSpans(backendClient, project.id, openRouterCostSpans),
        );

        await test.step('Each pair differs only in the spelling of its model id (seed shape)', async () => {
          // The precondition the whole test rests on: if the two members of a
          // pair carried different token counts, "they cost the same" would be
          // a claim about the counts and not about the spelling.
          for (const pair of ['nova', 'aion']) {
            const members = openRouterCostSpans.spans.filter((s) => s.pair === pair);
            expect(members, `the fixture seeds both spellings of '${pair}'`).toHaveLength(2);
            const [prefixedSeed] = members.filter((s) => s.spelling === 'prefixed');
            const [bareSeed] = members.filter((s) => s.spelling === 'bare');
            expect(prefixedSeed, `a prefixed '${pair}' vector`).toBeDefined();
            expect(bareSeed, `a bare '${pair}' vector`).toBeDefined();
            expect(
              prefixedSeed.model,
              `the prefixed '${pair}' id is the bare one under openrouter/`,
            ).toBe(`openrouter/${bareSeed.model}`);

            const prefixedSpan = spanFor(byName, openRouterCostSpans, prefixedSeed.key);
            const bareSpan = spanFor(byName, openRouterCostSpans, bareSeed.key);
            expect(
              bareSpan.usage,
              `both '${pair}' spans report the identical usage map`,
            ).toEqual(prefixedSpan.usage);
            expect(
              bareSpan.provider,
              `both '${pair}' spans name the identical provider`,
            ).toBe(prefixedSpan.provider);
          }
        });

        await test.step('Each bare spelling resolves its row, at the same amount as the prefixed one', async () => {
          // Both halves. The absolute amount pins it to the price file, and the
          // equality pins it to the ROW rather than to any particular rate — so
          // a future re-pricing moves both sides together and this keeps saying
          // the same thing.
          for (const seed of bySpelling(openRouterCostSpans.spans, 'bare')) {
            const twin = openRouterCostSpans.spans.find(
              (s) => s.pair === seed.pair && s.spelling === 'prefixed',
            );
            expect(twin, `a prefixed twin for ${seed.key}`).toBeDefined();
            expect(
              costOf(byName, openRouterCostSpans, seed.key),
              `${seed.key} (${seed.model}) is the id Opik writes and must price as ${twin!.model} does: ${seed.workings}`,
            ).toBeCloseTo(seed.expectedCost, 9);
            expect(
              costOf(byName, openRouterCostSpans, seed.key),
              `${seed.key} and ${twin!.key} name one model and must cost one amount`,
            ).toBeCloseTo(costOf(byName, openRouterCostSpans, twin!.key), 9);
          }
        });

        await test.step("The trace's rolled-up cost is the sum of its four priced spans", async () => {
          // The number a user actually reads, and the one the defect makes
          // plausible rather than obviously absent: with only the two prefixed
          // spellings priced the trace rolls up $2.70 where all four give
          // $5.40 — half the bill, and still a perfectly ordinary figure.
          const traceCost = await backendClient.getTraceCost(openRouterCostSpans.traceId);
          expect(traceCost, 'the trace must report a rolled-up cost').not.toBeNull();
          expect(
            traceCost!,
            'trace total across both spellings of both models, with nothing from the control',
          ).toBeCloseTo(openRouterCostSpans.expectedTraceCost, 9);
        });
      },
    );

    test(
      'the panel and the Logs cost column render the amount the file resolved, and the unpriced control shows none',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ openRouterCostSpans, project, backendClient, page }) => {
        const prefixed = bySpelling(openRouterCostSpans.spans, 'prefixed');

        const byName = await test.step(
          'The server really priced the file spellings before the page is read for those amounts',
          async () => {
            // A UI assertion over a seed that never got priced is a test that
            // cannot fail: the amounts below would simply never appear, and any
            // wait for them would be indistinguishable from a rendering bug.
            //
            // Polled to AT LEAST the two prefixed vectors rather than to an
            // exact count, deliberately: the two bare spellings are expected to
            // price too once the defect above is fixed, and a `toBe(2)` here
            // would turn that fix into a failure of this test. The control is
            // asserted unpriced in the first test, where "nothing was billed"
            // is unambiguous.
            await expect
              .poll(
                async () => {
                  const spans = await backendClient.listSpanCosts({
                    projectId: project.id,
                    traceId: openRouterCostSpans.traceId,
                  });
                  return spans.filter(
                    (s) => s.totalEstimatedCost !== null && s.totalEstimatedCost > 0,
                  ).length;
                },
                { timeout: 60_000, intervals: [500, 1_000, 2_000] },
              )
              .toBeGreaterThanOrEqual(prefixed.length);

            return readSeededSpans(backendClient, project.id, openRouterCostSpans);
          },
        );

        await test.step('The two amounts render as two different strings', async () => {
          // What makes the per-span assertions below discriminating in the
          // panel. Deliberately NOT asserted across all five seeds: the two
          // members of a pair are meant to render the SAME amount — that is the
          // claim of the test above — so uniqueness is only required among the
          // spans this test walks.
          const rendered = prefixed.map((s) => displayedCost(s.expectedCost));
          expect(
            new Set(rendered).size,
            `the two priced spellings must render as two distinct strings, got ${JSON.stringify(rendered)}`,
          ).toBe(rendered.length);
        });

        const logs = new LogsPage(page);

        const panel = await test.step('Open the seeded trace in the Logs panel', async () => {
          await logs.goto(project.id);
          const opened = await logs.openTraceById(openRouterCostSpans.traceId);
          await opened.waitForFullyLoaded();
          return opened;
        });

        await test.step('Each priced span shows its own resolved cost against its own model', async () => {
          for (const seed of prefixed) {
            await panel.selectSpan(seed.name);
            // Pin the assertions below to the span that is actually selected.
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

        await test.step('The control renders no dollar amount at all', async () => {
          // Asserted as the absence of an AMOUNT rather than as a literal "-":
          // `formatCost` happens to render its `noValue` dash, but a dash is a
          // default that could reasonably become an em dash or a blank. The step
          // above is what makes this discriminating — the same viewer rendered
          // two amounts a moment ago, so "no amount" cannot be explained by the
          // locator never matching anything.
          const control = controls(openRouterCostSpans.spans)[0];
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

        await test.step("The Logs table's Estimated cost cell agrees with what the API rolled up", async () => {
          // The column a user scans before opening anything, and a separate
          // render path from the panel's header.
          //
          // Compared against the trace cost the API reports rather than against
          // the fixture's expected total, because this test must stay green
          // across the defect the test above pins: today the roll-up is the two
          // prefixed spellings, tomorrow it is all four, and either way the
          // CELL must agree with the API. The total being the right NUMBER is
          // asserted in that test, which is where a wrong roll-up belongs.
          const traceCost = await backendClient.getTraceCost(openRouterCostSpans.traceId);
          expect(traceCost, 'the trace must report a rolled-up cost').not.toBeNull();
          expect(traceCost!, 'the roll-up must be an amount the column can render').toBeGreaterThan(
            0,
          );

          await page.goBack();
          const cell = logs.estimatedCostCell(openRouterCostSpans.traceId);
          await expect(cell, 'exactly one Estimated cost cell for the seeded trace').toHaveCount(1);
          // Matched as the node whose OWN text is the amount rather than as the
          // cell's full text content: where the Ollie assistant bridge is up —
          // staging is — this cell also holds the hover "Explain" trigger, so
          // its text content reads "$2.7Explain" and a whole-cell match fails
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
