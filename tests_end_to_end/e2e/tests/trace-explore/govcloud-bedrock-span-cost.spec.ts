import { test, expect } from '@e2e/fixtures';
import type { GovCloudBedrockCostSeed, GovCloudBedrockCostSpansRef } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { displayedCost } from '@e2e/core/money';
import type { BackendClient, SpanCostRef } from '@e2e/core/backend';

/**
 * GovCloud Bedrock Claude Sonnet 4.5 is billed at the GovCloud premium under
 * the spelling LiteLLM emits, while every `bedrock/<region>/…` spelling
 * resolves no cost at all — whether or not the price table still carries a row
 * for it.
 *
 * ## What this answers
 *
 * opik#9607 regenerated `model_prices_and_context_window.json` and its only two
 * removals of 6036 rows were
 * `bedrock/us-gov-{east,west}-1/claude-sonnet-4-5-20250929-v1:0`. The release
 * report ranked that first: silent wrongness about money, irreversible for
 * already-ingested spans, and a deployment reading $0 with no notice.
 *
 * The answer is that the deletion changed nothing, and the spec is built to
 * show it rather than assert it: the two region-path rows this release KEPT
 * (`bedrock/us-gov-{east,west}-1/anthropic.claude-sonnet-4-5-…`, which carry the GovCloud
 * rate in the shipped file) resolve exactly as much as the deleted ones — which
 * is nothing. `ModelPriceTable.buildRuntimeKey` refuses every `bedrock` row
 * whose model name still holds a `/`, at load time, so the deleted keys were
 * unreachable before they were deleted.
 *
 * GovCloud spend is reachable and correct under `us-gov.anthropic.…`
 * (`litellm_provider: bedrock_converse`), at $3.6/M in and $18/M out against
 * the standard row's $3/$15 — and the spec asserts both halves of that pair, so
 * the premium itself is pinned rather than just "some bedrock row was found".
 *
 * ## The pre-existing gap this does NOT claim to be about
 *
 * A customer who logs bedrock models under the region-path spelling gets no
 * cost, silently, and has since long before 2.2.148. That is real and it is
 * what the unpriced vectors pin — but it is not this release's doing, and
 * whether the product should warn about it is a design question no surface
 * exposes. Nothing here should be read as a verdict on it.
 *
 * ## Why a separate spec rather than more vectors on a neighbour
 *
 * `span-new-provider-pricing.spec.ts` is the lead spec for
 * `traces.span-model-cost-tokens` and asserts that its trace carries EXACTLY
 * its own spans and that its priced rows resolve distinct amounts, so adding
 * six vectors to its fixture would make its own assertions wrong. It is also
 * one of the specs this release's targeted run uses as its cost gate, and
 * editing a spec under test to add coverage is how a gate stops being one.
 * `openrouter-removed-rows-unpriced.spec.ts` (opik#9364) is the same SHAPE as
 * this — removed rows against kept ones — on a different provider whose
 * removals really were the cause of their missing amounts; here the point is
 * the opposite, that they were not.
 *
 * ## What makes this a finding rather than a tautology
 *
 * Every span is logged with `usage` and no `total_cost`, so each amount is the
 * server's own lookup against the shipped file rather than something the seed
 * asserted back to itself. Two priced vectors at two different amounts are what
 * stop the unpriced assertions passing on a build that priced nothing: a
 * missing or stale price file fails on them while leaving every "no cost"
 * assertion green.
 *
 * No provider is ever called — the spans are written straight to the REST API
 * and priced server-side from token counts.
 */

const priced = (spans: readonly GovCloudBedrockCostSeed[]): GovCloudBedrockCostSeed[] =>
  spans.filter((s) => s.expectedCost !== null);

const unpriced = (spans: readonly GovCloudBedrockCostSeed[]): GovCloudBedrockCostSeed[] =>
  spans.filter((s) => s.expectedCost === null);

/**
 * Every seeded span, by name, once all of them are queryable.
 *
 * The full set is read and its length asserted rather than only the spans a
 * step needs: four vectors are asserted to have NO cost, which is also what a
 * span that never landed looks like, and reading the whole trace first is what
 * tells those two apart.
 */
async function readSeededSpans(
  backendClient: BackendClient,
  projectId: string,
  seed: GovCloudBedrockCostSpansRef,
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
 * The fixture's own vector for `key`.
 *
 * Read rather than re-stated, so an amount that tracks a generated price file
 * lives in exactly one place.
 */
function seedFor(
  seed: GovCloudBedrockCostSpansRef,
  key: string,
): GovCloudBedrockCostSeed {
  const vector = seed.spans.find((s) => s.key === key);
  expect(vector, `the fixture must seed a '${key}' vector`).toBeDefined();
  return vector!;
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
  seed: GovCloudBedrockCostSpansRef,
  key: string,
): SpanCostRef {
  const vector = seedFor(seed, key);
  const span = byName.get(vector.name);
  expect(span, `span ${vector.name} was seeded`).toBeDefined();
  return span!;
}

/**
 * The cost the server attributed to a vector that must be priced.
 *
 * Asserts a cost is present before returning it: for these an absent cost is a
 * regression, and a `?? 0` would report it as a comparison between two zeros.
 */
function costOf(
  byName: Map<string, SpanCostRef>,
  seed: GovCloudBedrockCostSpansRef,
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
  'Span cost — GovCloud Bedrock spellings and the rows 2.2.148 removed',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'every bedrock region-path spelling resolves no cost whether its row was kept or deleted, while the us-gov. alias bills at the GovCloud premium',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ govCloudBedrockCostSpans, project, backendClient }) => {
        // No page. The subject is the amount the price lookup resolves; the
        // panel renders whatever came out of it, so driving a browser here
        // would add a rendering failure mode to an assertion about arithmetic.
        // The panel is the second test.

        const byName = await test.step(
          'Read the seeded spans back once all of them are queryable',
          async () => readSeededSpans(backendClient, project.id, govCloudBedrockCostSpans),
        );

        await test.step('Each span really carries the model id and token counts under test (seed shape)', async () => {
          // Without this the cost assertions could pass over a seed that
          // silently dropped or altered `model` — and the id arriving character
          // for character is the whole subject here, since the rule under test
          // keys off whether the name contains a slash.
          for (const seed of govCloudBedrockCostSpans.spans) {
            const span = spanFor(byName, govCloudBedrockCostSpans, seed.key);
            expect(span.model, `model id logged for ${seed.key}`).toBe(seed.model);
            expect(span.provider, `provider logged for ${seed.key}`).toBe('bedrock');
            const usage = span.usage;
            expect(usage, `usage logged for ${seed.key}`).not.toBeNull();
            expect(usage!['prompt_tokens'], `prompt_tokens for ${seed.key}`).toBe(
              govCloudBedrockCostSpans.promptTokens,
            );
            expect(usage!['completion_tokens'], `completion_tokens for ${seed.key}`).toBe(
              govCloudBedrockCostSpans.completionTokens,
            );
          }
        });

        await test.step('Both priceable spellings are billed at their own rates, and the two differ by the GovCloud premium', async () => {
          // Asserted FIRST among the cost claims, deliberately. These two are
          // what make every "no cost" assertion below a finding rather than a
          // tautology: a build that shipped no price file, read a stale copy, or
          // stopped resolving bedrock ids at all fails here while leaving all
          // four unpriced assertions green.
          const vectors = priced(govCloudBedrockCostSpans.spans);
          expect(vectors, 'the fixture seeds two priceable spellings').toHaveLength(2);
          for (const seed of vectors) {
            expect(
              costOf(byName, govCloudBedrockCostSpans, seed.key),
              `${seed.key} (${seed.model}): ${seed.workings}`,
            ).toBeCloseTo(seed.expectedCost!, 9);
          }

          // The premium as a RELATION as well as two absolute amounts. The
          // absolutes pin the lookup to the shipped table; the relation is the
          // claim a human cares about — that GovCloud costs more than standard,
          // and by how much. A regen that moved both rows together would keep
          // this true and fail the absolutes, which is the right way round:
          // the relation is the product rule, the amounts are generated data.
          const govCloud = costOf(byName, govCloudBedrockCostSpans, 'govcloud-alias-priced');
          const standard = costOf(byName, govCloudBedrockCostSpans, 'standard-alias-priced');
          expect(
            govCloud,
            'GovCloud Sonnet 4.5 must cost MORE than the standard row at identical token counts ' +
              '— that premium is the only reason the us-gov. rows exist',
          ).toBeGreaterThan(standard);
        });

        await test.step('No bedrock region-path spelling resolves any cost — not zero, nothing', async () => {
          // `toBeNull`, deliberately, not `?? 0`. Null and 0 are different
          // claims about the product: 0 is what a genuinely free model resolves
          // to, and the release report's question was precisely whether a
          // reader can tell the two apart. The answer the API gives is "no
          // figure", so that is what is pinned.
          const vectors = unpriced(govCloudBedrockCostSpans.spans);
          expect(vectors, 'three region-path spellings and the no-row control').toHaveLength(4);
          for (const seed of vectors) {
            expect(
              spanFor(byName, govCloudBedrockCostSpans, seed.key).totalEstimatedCost,
              `${seed.key}: ${seed.workings}`,
            ).toBeNull();
          }
        });

        await test.step('…and the rows 9607 KEPT are among them, which is what makes the deletion inert', async () => {
          // The release item's actual answer, stated as its own step so it is
          // the thing that fails if it ever stops being true. These two keys are
          // in the shipped price table, at the GovCloud rate, and resolve
          // nothing — so the two keys the release deleted cannot have been the
          // cause of a missing amount.
          //
          // `inPriceTable` is read from the fixture rather than re-derived here:
          // if a later regen drops one of these keys the fixture's premise has
          // changed and its own self-checks say so, instead of this step
          // quietly asserting something weaker.
          for (const key of ['region-path-kept-east', 'region-path-kept-west']) {
            const seed = seedFor(govCloudBedrockCostSpans, key);
            expect(
              seed.inPriceTable,
              `'${key}' is in this spec because the shipped table still carries its row — ` +
                'without that, "unpriced" would say nothing about the deletion',
            ).toBe(true);
            expect(
              spanFor(byName, govCloudBedrockCostSpans, key).totalEstimatedCost,
              `${key}: a row that EXISTS and still prices nothing. ${seed.workings}`,
            ).toBeNull();
          }

          const deleted = seedFor(govCloudBedrockCostSpans, 'region-path-deleted');
          expect(
            deleted.inPriceTable,
            "the deleted row must really be absent from the build under test, or this spec is " +
              'not about 9607 at all',
          ).toBe(false);
          expect(
            spanFor(byName, govCloudBedrockCostSpans, 'region-path-deleted').totalEstimatedCost,
            `region-path-deleted: ${deleted.workings}`,
          ).toBeNull();
        });

        await test.step('Token counts still render for the unpriced spans', async () => {
          // What separates "this span is unpriced" from "this span was never
          // ingested properly". The usage is read back on every vector in the
          // seed-shape step above; here it is the CLAIM — an unpriced bedrock
          // span is a fully-formed span whose cost alone is missing, which is
          // exactly why the gap is silent.
          for (const seed of unpriced(govCloudBedrockCostSpans.spans)) {
            const span = spanFor(byName, govCloudBedrockCostSpans, seed.key);
            expect(
              span.usage!['total_tokens'],
              `${seed.key} carries its token counts even with no cost resolved`,
            ).toBe(
              govCloudBedrockCostSpans.promptTokens + govCloudBedrockCostSpans.completionTokens,
            );
          }
        });

        await test.step("The trace's rolled-up cost is the sum of its two priced spans alone", async () => {
          // The number a user actually reads, and the one an unpriceable
          // spelling makes plausible rather than obviously absent: the trace
          // rolls up to two spans' sum where six priced spans would have given
          // far more, and that sum is a perfectly ordinary figure.
          const traceCost = await backendClient.getTraceCost(govCloudBedrockCostSpans.traceId);
          expect(traceCost, 'the trace must report a rolled-up cost').not.toBeNull();
          expect(
            traceCost!,
            'trace total across the two priced spellings, with nothing from the four unpriced',
          ).toBeCloseTo(govCloudBedrockCostSpans.expectedTraceCost, 9);
        });
      },
    );

    test(
      'the trace panel shows each priced spelling its own amount, no dollar figure for a region-path one, and a Logs roll-up that omits them',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ govCloudBedrockCostSpans, project, backendClient, page }) => {
        // Room for an ingestion poll, a panel walk over six spans and a listing
        // read with a navigation between them, which do not fit the 90s default
        // once the workspace traces budget is being shed.
        test.setTimeout(300_000);

        const pricedVectors = priced(govCloudBedrockCostSpans.spans);
        const unpricedVectors = unpriced(govCloudBedrockCostSpans.spans);

        const byName = await test.step(
          'The server really priced the two priceable spellings before the page is read for those amounts',
          async () => {
            // A UI assertion over a seed that never got priced is a test that
            // cannot fail: the amounts below would simply never appear, and any
            // wait for them would be indistinguishable from a rendering bug.
            // Polled to EXACTLY the two priced vectors — the other four must
            // stay unpriced, which the test above asserts and this one relies on.
            await expect
              .poll(
                async () => {
                  const spans = await backendClient.listSpanCosts({
                    projectId: project.id,
                    traceId: govCloudBedrockCostSpans.traceId,
                  });
                  return spans.filter(
                    (s) => s.totalEstimatedCost !== null && s.totalEstimatedCost > 0,
                  ).length;
                },
                {
                  message:
                    `the server to price the ${pricedVectors.length} priceable vectors on trace ` +
                    `${govCloudBedrockCostSpans.traceId} — a timeout here means the seed never ` +
                    'resolved a price, not that the panel rendered the wrong amount',
                  timeout: 60_000,
                  intervals: [500, 1_000, 2_000],
                },
              )
              .toBe(pricedVectors.length);

            return readSeededSpans(backendClient, project.id, govCloudBedrockCostSpans);
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
          const opened = await logs.openTraceById(govCloudBedrockCostSpans.traceId);
          await opened.waitForFullyLoaded();
          return opened;
        });

        await test.step('Each priced spelling shows its own resolved cost against its own model', async () => {
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

        await test.step('No region-path spelling renders a dollar amount at all, while still showing its tokens', async () => {
          // Asserted as the absence of an AMOUNT rather than as a literal "-":
          // `formatCost` happens to render its `noValue` dash, but a dash is a
          // default that could reasonably become an em dash or a blank. The step
          // above is what makes this discriminating — the same viewer rendered
          // two amounts a moment ago, so "no amount" cannot be explained by the
          // locator never matching anything.
          for (const control of unpricedVectors) {
            await panel.selectSpan(control.name);
            await expect
              .poll(() => new URL(page.url()).searchParams.get('span'), {
                message: `the panel must be showing the control '${control.name}' itself`,
              })
              .toBe(byName.get(control.name)!.id);
            await expect(panel.spanModelChip).toContainText(control.model);
            await expect(
              panel.costAmountsInViewer,
              `${control.key} is priced by no usable row, so the viewer must show no dollar ` +
                'figure for it — not even the <$0.01 floor marker, which would mean it WAS priced',
            ).toHaveCount(0);
          }
        });

        await test.step("The Logs table's Estimated cost cell agrees with the trace's roll-up", async () => {
          // The column a user scans before opening anything, and a separate
          // render path from the panel's header. It is also where the quiet
          // failure is visible: the cell names a smaller number than the spans
          // below it would have summed to, and says nothing about why.
          const traceCost = await backendClient.getTraceCost(govCloudBedrockCostSpans.traceId);
          expect(traceCost, 'the trace must report a rolled-up cost').not.toBeNull();
          expect(
            traceCost!,
            'the roll-up is the sum the test above pins, and so an amount the column can render',
          ).toBeCloseTo(govCloudBedrockCostSpans.expectedTraceCost, 9);

          await page.goBack();
          // Waited through the POM rather than straight into the cell: on
          // staging the listing this return re-issues can be answered 429 by
          // the per-workspace traces limit, and the FE keeps that empty answer
          // without refetching — so the table stays rowless and the cell can
          // never appear, while the roll-up just above read back fine.
          await logs.waitForTraceRow(govCloudBedrockCostSpans.traceId);
          const cell = logs.estimatedCostCell(govCloudBedrockCostSpans.traceId);
          await expect(cell, 'exactly one Estimated cost cell for the seeded trace').toHaveCount(1);
          // Matched as the node whose OWN text is the amount rather than as the
          // cell's full text content: where the Ollie assistant bridge is up —
          // staging is — this cell also holds the hover "Explain" trigger, so
          // its text content reads "$2.64Explain" and a whole-cell match fails
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
