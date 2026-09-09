import { test, expect } from '@e2e/fixtures';
import type { ModelCostSpanSeed } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import type { SpanCostRef } from '@e2e/core/backend';

/**
 * Server-side LLM cost resolution for dated model ids (OPIK-8242).
 *
 * Every other cost assertion in the estate seeds `total_cost` from the client,
 * so the backend never has to look a price up — which means the resolution path
 * itself has never run in e2e. It is a silent-wrongness risk of the worst kind:
 * a model id the resolver fails to normalise reads as $0, and one it normalises
 * too eagerly reads as some other model's price. Both render as a perfectly
 * ordinary number on a page people read to decide what their LLM spend is.
 *
 * The fixture logs five LLM spans with `usage` and **no** `total_cost`. Three
 * exercise a normalisation step each (provider-prefix strip, dot-normalising,
 * compact-date strip, and an alias); two are controls whose eight trailing
 * digits are not dates and must not be stripped onto another model's row.
 *
 * The expected amounts are the shipped price table's own numbers — see the
 * fixture. They are asserted at both surfaces because that is where the two can
 * disagree: the API is where the resolution happens, and the panel is where a
 * human would notice it being wrong.
 */

/** The seeded spans the price table must resolve, and the two it must not. */
const priced = (spans: readonly ModelCostSpanSeed[]): ModelCostSpanSeed[] =>
  spans.filter((s) => s.expectedCost > 0);
const controls = (spans: readonly ModelCostSpanSeed[]): ModelCostSpanSeed[] =>
  spans.filter((s) => s.expectedCost === 0);

/**
 * `formatCost`'s rendering of a resolved price: floored to two decimals, with
 * trailing zeros dropped ($30.00 renders as "$30"). Written through `toFixed`
 * rather than `Math.floor(v * 100) / 100` because the latter turns 51.75 into
 * 51.74 — the binary representation of 51.75 * 100 is a hair under 5175.
 */
const asDisplayed = (cost: number): string => `$${Number(cost.toFixed(2))}`;

/**
 * A span's cost as the server attributed it, for the controls only.
 *
 * Absent and zero are the same answer for a model the price table must not
 * match — "nothing was billed" — and only the controls are allowed to collapse
 * them. The priced spans assert a non-null value first, so a cost that went
 * missing there fails instead of quietly reading as zero.
 */
const attributedCost = (span: SpanCostRef): number => span.totalEstimatedCost ?? 0;

test.describe('Span cost — server-side price resolution', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  test('LLM spans logged without a cost are priced from their model id, and undated look-alikes are not', { tag: ['@cap:traces.span-model-cost-tokens'] }, async ({
    modelCostSpans,
    project,
    backendClient,
  }) => {
    const byName = await test.step('Read the seeded spans back once all five are queryable', async () => {
      await expect
        .poll(
          async () => {
            const spans = await backendClient.listSpanCosts({
              projectId: project.id,
              traceId: modelCostSpans.traceId,
            });
            return spans.length;
          },
          { timeout: 60_000, intervals: [500, 1_000, 2_000] },
        )
        .toBe(modelCostSpans.spans.length);

      const spans = await backendClient.listSpanCosts({
        projectId: project.id,
        traceId: modelCostSpans.traceId,
      });
      // The count, not just the lookup: a read that also returned spans from
      // another trace would still find every seeded one and pass.
      expect(spans, 'the trace carries exactly the seeded spans').toHaveLength(
        modelCostSpans.spans.length,
      );
      return new Map(spans.map((s) => [s.name, s]));
    });

    await test.step('Each span really carries the model id under test (seed shape)', async () => {
      // Without this the cost assertions below could pass over a seed that
      // silently dropped `model`, which is the one input the resolution reads.
      for (const seed of modelCostSpans.spans) {
        const span = byName.get(seed.name);
        expect(span, `span ${seed.name} was seeded`).toBeDefined();
        expect(span!.model, `model id logged for ${seed.name}`).toBe(seed.model);
        expect(span!.provider, `provider logged for ${seed.name}`).toBe(seed.provider);
      }
    });

    await test.step('The three resolvable model ids are priced at the table rate', async () => {
      for (const seed of priced(modelCostSpans.spans)) {
        const span = byName.get(seed.name)!;
        expect(
          span.totalEstimatedCost,
          `${seed.model} must be priced server-side — an absent cost is the failure this spec exists for, not a zero`,
        ).not.toBeNull();
        expect(span.totalEstimatedCost!, `cost resolved for ${seed.model}`).toBeCloseTo(
          seed.expectedCost,
          6,
        );
      }
    });

    await test.step('The two undated look-alikes are billed nothing', async () => {
      for (const seed of controls(modelCostSpans.spans)) {
        expect(
          attributedCost(byName.get(seed.name)!),
          `${seed.model} has no price in the table; a cost here means the date strip ran on a build number and billed another model's rate`,
        ).toBe(0);
      }
    });

    await test.step("The trace's rolled-up cost is the sum of its spans", async () => {
      const traceCost = await backendClient.getTraceCost(modelCostSpans.traceId);
      expect(traceCost, 'the trace must report a rolled-up cost').not.toBeNull();
      expect(traceCost!, 'trace total across the five seeded spans').toBeCloseTo(
        modelCostSpans.expectedTraceCost,
        6,
      );
    });
  });

  test('The trace panel renders the server-resolved cost for the trace and for each priced span', { tag: ['@cap:traces.span-model-cost-tokens'] }, async ({
    modelCostSpans,
    project,
    backendClient,
    page,
  }) => {
    await test.step('The server really resolved the prices the panel is about to be read for', async () => {
      // A UI assertion over a seed that never got priced is a test that cannot
      // fail: the amounts below would simply never appear, and any wait for
      // them would be indistinguishable from a rendering bug.
      await expect
        .poll(
          async () => {
            const spans = await backendClient.listSpanCosts({
              projectId: project.id,
              traceId: modelCostSpans.traceId,
            });
            return spans.filter((s) => s.totalEstimatedCost !== null && s.totalEstimatedCost > 0)
              .length;
          },
          { timeout: 60_000, intervals: [500, 1_000, 2_000] },
        )
        .toBe(priced(modelCostSpans.spans).length);
    });

    const logs = new LogsPage(page);

    const panel = await test.step('Open the seeded trace in the Logs panel', async () => {
      await logs.goto(project.id);
      const panel = await logs.openTraceById(modelCostSpans.traceId);
      await panel.waitForFullyLoaded();
      return panel;
    });

    await test.step('The trace header shows the rolled-up total', async () => {
      await expect(panel.estimatedCost(asDisplayed(modelCostSpans.expectedTraceCost))).toBeVisible();
    });

    await test.step('Each priced span shows its own resolved cost', async () => {
      // Deliberately not asserted here: what the panel renders for the two
      // control spans. `formatCost` maps a zero cost to a bare "-", which is
      // not a claim the UI can be held to — the controls are asserted at the
      // API, where "no cost was attributed" is unambiguous.
      for (const seed of priced(modelCostSpans.spans)) {
        await panel.selectSpan(seed.name);
        await expect(panel.spanModelChip).toContainText(seed.model);
        await expect(panel.estimatedCost(asDisplayed(seed.expectedCost))).toBeVisible();
      }
    });
  });
});
