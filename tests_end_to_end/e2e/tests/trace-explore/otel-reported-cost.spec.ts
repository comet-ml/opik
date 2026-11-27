import { test, expect } from '@e2e/fixtures';
import type { OtelReportedCostSeed, OtelReportedCostSpansRef } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import type { BackendClient, SpanCostRef } from '@e2e/core/backend';

/**
 * An OTLP export that reports its OWN cost (OPIK issue-8564 / opik#9499).
 *
 * `gen_ai.usage.cost.amount` is what a provider's own billed amount arrives as,
 * with `gen_ai.usage.cost.currency` and `gen_ai.usage.cost.source` beside it
 * saying what unit it is in and who says so. opik#9499 maps the amount to the
 * span's cost directly — authoritative, bypassing `CostService` — and the other
 * two into metadata, and it orders all three AHEAD of the `gen_ai.usage.`
 * prefix rule.
 *
 * That ordering is the fix, and its absence is a loud failure in one direction
 * and a silent one in the other:
 *
 *  - **Loud.** The prefix rule buckets its value as a USAGE count and parses it
 *    as a number. A string-valued `gen_ai.usage.cost.currency` reaching it had
 *    the WHOLE export rejected — every span in the batch lost, not just the
 *    attribute. So the export being accepted at all is the first claim.
 *  - **Silent, and worse.** A regression the other way — the amount stopped
 *    being authoritative — replaces the provider's own billed figure with
 *    Opik's estimate of it. There is no error and no empty state: the span is
 *    there, the model and the counts are right, and the number on the page
 *    people open to decide what their LLM spend is is simply someone else's
 *    arithmetic.
 *
 * The release report names this as having no e2e caller at all: three specs
 * drive `POST /v1/private/otel/v1/traces` and every one deliberately sends no
 * `gen_ai.usage.cost`. It is unreachable from any other seed too —
 * `POST /v1/private/spans` takes `total_estimated_cost` as a literal field and
 * never runs the attribute rules whose order is the thing under test.
 *
 * Three spans, one model, identical token counts, differing only in how they
 * report a cost (see the fixture): the structured spelling, the single-key
 * `gen_ai.usage.cost` spelling older instrumentations emit, and a control that
 * reports none. The control is what holds the other two up. `gpt-4o-mini` at
 * 1M + 1M tokens computes to $0.75, so a reported $1.23 is unmistakably not a
 * recomputed amount — and `total_estimated_cost_version`, blank on the two
 * reported spans and stamped on the control, is the backend's own statement
 * about which of them it priced itself.
 *
 * Asserted at the API, where the mapping happens, and in the trace panel, where
 * a human would notice the figure being wrong.
 */

/** The vectors that report a cost, which must survive verbatim. */
const reporting = (spans: readonly OtelReportedCostSeed[]): OtelReportedCostSeed[] =>
  spans.filter((s) => s.costAttribute !== null);

/** The one vector that reports none, which the server must price itself. */
function controlSeed(seed: OtelReportedCostSpansRef): OtelReportedCostSeed {
  const controls = seed.spans.filter((s) => s.costAttribute === null);
  expect(
    controls,
    'the fixture must seed exactly one span with no cost attribute, or the reported amounts have no control',
  ).toHaveLength(1);
  return controls[0];
}

/**
 * `formatCost`'s rendering of an amount: floored to two decimals with trailing
 * zeros dropped, so $0.75 renders as "$0.75" and $2.00 as "$2". Written through
 * `toFixed` for the reason `span-cost-resolution.spec.ts` gives —
 * `Math.floor(v * 100) / 100` turns 51.75 into 51.74.
 */
const asDisplayed = (cost: number): string => `$${Number(cost.toFixed(2))}`;

/**
 * Every exported span, by name, once all of them are queryable.
 *
 * The count is asserted, not just the lookup: a read that also returned spans
 * from another trace would still find every seeded one and pass.
 */
async function readExportedSpans(
  backendClient: BackendClient,
  projectId: string,
  seed: OtelReportedCostSpansRef,
): Promise<Map<string, SpanCostRef>> {
  await expect
    .poll(
      async () =>
        (await backendClient.listSpanCosts({ projectId, traceId: seed.traceId })).length,
      {
        message:
          `the ${seed.spans.length} exported spans on trace ${seed.traceId} to become ` +
          'queryable — a timeout here means the OTLP export never landed, not that a span ' +
          'carried the wrong cost',
        timeout: 60_000,
        intervals: [500, 1_000, 2_000],
      },
    )
    .toBe(seed.spans.length);

  const spans = await backendClient.listSpanCosts({ projectId, traceId: seed.traceId });
  expect(spans, 'the trace carries exactly the exported spans').toHaveLength(
    seed.spans.length,
  );
  return new Map(spans.map((s) => [s.name, s]));
}

/**
 * The span the fixture exported under `key`, read back.
 *
 * Asserts the vector exists in the seed and the span came back before returning
 * it, so a seed that never landed fails here rather than turning into an
 * `undefined` the assertions below would have to code around.
 */
function spanFor(
  byName: Map<string, SpanCostRef>,
  seed: OtelReportedCostSpansRef,
  key: string,
): SpanCostRef {
  const vector = seed.spans.find((s) => s.key === key);
  expect(vector, `the fixture must seed a '${key}' vector`).toBeDefined();
  const span = byName.get(vector!.name);
  expect(span, `span ${vector!.name} reached the backend`).toBeDefined();
  return span!;
}

/**
 * The cost the span ended up with, asserted present first.
 *
 * An absent cost and a zero cost are the same answer — "nothing was billed" —
 * and no vector here is allowed to collapse them: the two reported spans must
 * carry what they reported and the control must carry what the table computes,
 * so a missing value IS the failure rather than a zero a later comparison might
 * accept.
 */
function costOf(span: SpanCostRef, key: string): number {
  expect(
    span.totalEstimatedCost,
    `${key} must carry a cost — an absent one is the failure this spec exists for, not a zero`,
  ).not.toBeNull();
  return span.totalEstimatedCost!;
}

/** The span's stored metadata, asserted present first for the reason `costOf` gives. */
function metadataOf(span: SpanCostRef, key: string): Record<string, unknown> {
  expect(
    span.metadata,
    `${key} must carry metadata — the currency and source are mapped there, so an absent map is the failure`,
  ).not.toBeNull();
  return span.metadata!;
}

const METADATA_BLOCK = 'Metadata';
const CURRENCY_KEY = 'gen_ai.usage.cost.currency';
const COST_SOURCE_KEY = 'gen_ai.usage.cost.source';

/** The model every vector reports — see the fixture, which pins its computed price. */
const MODEL = 'gpt-4o-mini';

/**
 * The keys the exported token counts are STORED under.
 *
 * `extractUsageField` renames the OTel `input_tokens`/`output_tokens` spellings
 * through `USAGE_KEYS_MAPPING`, because those are the names `SpanCostCalculator`
 * reads. Asserted on the stored names rather than the sent ones so a seed whose
 * counts landed somewhere the calculator cannot see them fails here.
 */
const INPUT_TOKENS_KEY = 'prompt_tokens';
const OUTPUT_TOKENS_KEY = 'completion_tokens';

test.describe(
  'OTel ingestion — an export that reports its own cost',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'A reported cost is stored verbatim with no price-table version, beside a control the server priced itself',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ otelReportedCostSpans, project, backendClient }) => {
        // No page: the subject is what the ingestion endpoint made of three
        // attributes. The panel renders whatever came out of it, so reading this
        // through a browser would add a rendering failure mode to an assertion
        // about a mapping. The panel is asserted in the UI test below, over
        // these same spans.
        await test.step('The export was ACCEPTED', async () => {
          // The loud half of the regression, and the reason the seed returns
          // the status instead of throwing on it: before opik#9499 the
          // string-valued `gen_ai.usage.cost.currency` fell through to the
          // `gen_ai.usage.` prefix rule, which parses its value as a number,
          // and the whole export was refused. Every assertion below would then
          // fail as "no spans landed", which reads like a seeding problem.
          expect(
            otelReportedCostSpans.exportStatus,
            'an export carrying string-valued gen_ai.usage.cost.currency/.source must be accepted ' +
              `(the endpoint answered: ${otelReportedCostSpans.exportMessage})`,
          ).toBe(200);
        });

        const byName = await test.step('Read the exported spans back once all of them are queryable', async () =>
          readExportedSpans(backendClient, project.id, otelReportedCostSpans));

        await test.step('Every span really carries the model and the token counts it was exported with (seed shape)', async () => {
          // Without this the comparisons below could pass over an export whose
          // model or usage never mapped: the control would then be unpriced, its
          // absent cost version would be indistinguishable from a reported
          // span's, and "the reported amount survived" would be asserted over a
          // span nothing could have re-priced anyway.
          for (const seed of otelReportedCostSpans.spans) {
            const span = spanFor(byName, otelReportedCostSpans, seed.key);
            expect(span.model, `model mapped for ${seed.key}`).toBe(MODEL);
            expect(span.usage, `usage mapped for ${seed.key}`).not.toBeNull();
            // `prompt_tokens`/`completion_tokens`, not the OTel spellings:
            // `extractUsageField` renames `input_tokens`/`output_tokens`
            // through `USAGE_KEYS_MAPPING` so the cost calculator can read
            // them. A seed whose counts landed under the unmapped names prices
            // at nothing, which is exactly the state that would make the
            // control's absent cost version meaningless.
            expect(
              span.usage![INPUT_TOKENS_KEY],
              `${seed.key} reports its input tokens under '${INPUT_TOKENS_KEY}', the key the calculator reads`,
            ).toBe(otelReportedCostSpans.inputTokens);
            expect(
              span.usage![OUTPUT_TOKENS_KEY],
              `${seed.key} reports its output tokens under '${OUTPUT_TOKENS_KEY}'`,
            ).toBe(otelReportedCostSpans.outputTokens);
          }
        });

        await test.step('Each reporting span carries EXACTLY the amount it reported', async () => {
          // The absolute amount, not a comparison with the control: these two
          // must not equal the control, so there is nothing to compare them to
          // except the figure the export claimed. $1.23 is deliberately nothing
          // like the $0.75 the table computes for this model and these counts.
          for (const seed of reporting(otelReportedCostSpans.spans)) {
            expect(
              costOf(spanFor(byName, otelReportedCostSpans, seed.key), seed.key),
              `${seed.key} reported ${seed.reportedCost} on '${seed.costAttribute}' and must keep it`,
            ).toBeCloseTo(seed.expectedCost, 6);
          }
        });

        await test.step('And no price-table version, because the backend did not price them', async () => {
          // The half that tells a reported amount apart from a computed one.
          // Without it, a regression that re-priced these spans back to Opik's
          // own estimate would be visible only as a different number — and a
          // provider whose billed amount happened to match the table would hide
          // it completely.
          for (const seed of reporting(otelReportedCostSpans.spans)) {
            expect(
              spanFor(byName, otelReportedCostSpans, seed.key).totalEstimatedCostVersion,
              `${seed.key}: a reported cost bypasses CostService, so no version may be stamped`,
            ).toBeNull();
          }
        });

        await test.step('The control IS priced from the table, at a different amount, and IS stamped', async () => {
          // The three claims that stop the two above passing for the wrong
          // reason. Priced at all: a backend that resolved nothing would give
          // every span an absent version and the assertion above would be
          // vacuous. At a DIFFERENT amount: equal amounts would mean the
          // reported figure was not distinguishable from a recomputed one.
          // Stamped: a backend that stopped stamping versions altogether would
          // likewise satisfy the assertion above.
          const control = controlSeed(otelReportedCostSpans);
          const span = spanFor(byName, otelReportedCostSpans, control.key);
          expect(
            costOf(span, control.key),
            `${control.key} carries no cost attribute, so it must be priced from the table: ` +
              '1M input + 1M output on gpt-4o-mini',
          ).toBeCloseTo(control.expectedCost, 6);
          expect(
            span.totalEstimatedCostVersion,
            `${control.key} was priced by CostService, so it must carry a version`,
          ).not.toBeNull();
          for (const seed of reporting(otelReportedCostSpans.spans)) {
            expect(
              costOf(spanFor(byName, otelReportedCostSpans, seed.key), seed.key),
              `${seed.key}'s reported amount must differ from what the table computes ` +
                `(${control.expectedCost}), or nothing here is discriminating`,
            ).not.toBeCloseTo(control.expectedCost, 6);
          }
        });

        await test.step("The structured vector's currency and source landed in metadata", async () => {
          // A reported amount with no unit is a figure rendered as dollars that
          // the provider billed in euros, which the amount alone cannot see.
          // Only the structured spelling carries them, which is also why the
          // legacy vector is asserted NOT to have acquired them below.
          const structured = otelReportedCostSpans.spans.find(
            (s) => s.costAttribute === 'gen_ai.usage.cost.amount',
          );
          expect(
            structured,
            'the fixture must seed the structured cost spelling',
          ).toBeDefined();
          const metadata = metadataOf(
            spanFor(byName, otelReportedCostSpans, structured!.key),
            structured!.key,
          );
          expect(
            metadata[CURRENCY_KEY],
            `${CURRENCY_KEY} must reach metadata rather than being dropped or parsed as a usage count`,
          ).toBe(otelReportedCostSpans.currency);
          expect(metadata[COST_SOURCE_KEY], `${COST_SOURCE_KEY} must reach metadata`).toBe(
            otelReportedCostSpans.costSource,
          );
        });

        await test.step('And did NOT land on the span that never reported them', async () => {
          // The negative control for the mapping itself: a currency appearing on
          // a span whose export never sent one would mean the attribute was
          // being attributed per-export rather than per-span, which would make
          // the assertion above pass on any batch containing one.
          const control = controlSeed(otelReportedCostSpans);
          const metadata =
            spanFor(byName, otelReportedCostSpans, control.key).metadata ?? {};
          expect(
            metadata[CURRENCY_KEY],
            `${control.key} reported no currency, so none may appear on it`,
          ).toBeUndefined();
          expect(
            metadata[COST_SOURCE_KEY],
            `${control.key} reported no cost source, so none may appear on it`,
          ).toBeUndefined();
        });
      },
    );

    test(
      'The trace panel renders the reported amounts and the currency the provider billed in',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ otelReportedCostSpans, project, backendClient, page }) => {
        expect(
          otelReportedCostSpans.exportStatus,
          `the export must have been accepted before the panel can render it ` +
            `(the endpoint answered: ${otelReportedCostSpans.exportMessage})`,
        ).toBe(200);

        const byName = await test.step('The server really resolved a cost for every exported span, which the panel is about to be read for', async () => {
          // A UI assertion over an export that never got priced is a test that
          // cannot fail: the amounts below would simply never appear, and any
          // wait for them would be indistinguishable from a rendering bug.
          await expect
            .poll(
              async () => {
                const spans = await backendClient.listSpanCosts({
                  projectId: project.id,
                  traceId: otelReportedCostSpans.traceId,
                });
                return spans.filter(
                  (s) => s.totalEstimatedCost !== null && s.totalEstimatedCost > 0,
                ).length;
              },
              {
                message:
                  `the server to carry a cost for all ${otelReportedCostSpans.spans.length} ` +
                  `spans on trace ${otelReportedCostSpans.traceId} — a timeout here means the ` +
                  'reported cost never reached storage, not that the panel rendered the wrong ' +
                  'amount',
                timeout: 60_000,
                intervals: [500, 1_000, 2_000],
              },
            )
            .toBe(otelReportedCostSpans.spans.length);

          return readExportedSpans(backendClient, project.id, otelReportedCostSpans);
        });

        const logs = new LogsPage(page);

        const panel = await test.step('Open the exported trace in the Logs panel', async () => {
          await logs.goto(project.id);
          const panel = await logs.openTraceById(otelReportedCostSpans.traceId);
          await panel.waitForFullyLoaded();

          // Wait for the tree to list every span BEFORE selecting any of them.
          // The panel fills the tree from one `GET /v1/private/spans`, which is
          // rate limited per workspace; when parallel specs have spent the
          // budget that call is shed with a 429, the empty answer is cached and
          // nothing refetches, so the tree renders `Spans (0)` permanently and
          // a click on a node can never succeed however long it waits.
          // `waitForSpanTreeNodes` supplies the retry the browser has not got.
          // Added during the 2.3.7 release pass, where this test flaked exactly
          // this way under a whole-branch run while passing on its own.
          await panel.waitForSpanTreeNodes(otelReportedCostSpans.spans.map((s) => s.name));
          return panel;
        });

        await test.step('Each span shows its own amount — the reported one, or the computed one for the control', async () => {
          for (const seed of otelReportedCostSpans.spans) {
            await panel.selectSpan(seed.name);
            // Pin the assertions to the span actually selected. `selectSpan`
            // only waits for SOME span to be in the URL, and the two reporting
            // vectors share a model AND an expected amount — so a click that
            // left the viewer on the previous span would satisfy both and give
            // an iteration that cannot fail.
            await expect
              .poll(() => new URL(page.url()).searchParams.get('span'), {
                message: `the panel must be showing '${seed.name}' itself, not whichever span was selected before it`,
              })
              .toBe(byName.get(seed.name)!.id);
            await expect(panel.spanModelChip).toContainText(MODEL);
            await expect(
              panel.estimatedCost(asDisplayed(seed.expectedCost)),
              `${seed.key} must render ${asDisplayed(seed.expectedCost)}`,
            ).toBeVisible();
          }
        });

        await test.step('The reporting span renders its currency and cost source in Metadata', async () => {
          // Read through the block's own `.cm-line` elements: the block is
          // CodeMirror, and `innerText` on an ancestor comes back empty even
          // while it is expanded — which reads identically to the attributes
          // having been dropped. See `attributeLine`.
          const structured = otelReportedCostSpans.spans.find(
            (s) => s.costAttribute === 'gen_ai.usage.cost.amount',
          )!;
          await panel.selectSpan(structured.name);
          await expect
            .poll(() => new URL(page.url()).searchParams.get('span'), {
              message: `the panel must be showing '${structured.name}'`,
            })
            .toBe(byName.get(structured.name)!.id);

          await expect(
            panel.attributeLine(METADATA_BLOCK, CURRENCY_KEY),
            `the ${CURRENCY_KEY} line is rendered exactly once`,
          ).toHaveCount(1);
          await expect(
            panel.attributeLine(METADATA_BLOCK, CURRENCY_KEY),
            'and carries the reported currency',
          ).toContainText(otelReportedCostSpans.currency);
          await expect(
            panel.attributeLine(METADATA_BLOCK, COST_SOURCE_KEY),
            `the ${COST_SOURCE_KEY} line is rendered exactly once`,
          ).toHaveCount(1);
          await expect(
            panel.attributeLine(METADATA_BLOCK, COST_SOURCE_KEY),
            'and carries the reported source',
          ).toContainText(otelReportedCostSpans.costSource);
        });
      },
    );
  },
);
