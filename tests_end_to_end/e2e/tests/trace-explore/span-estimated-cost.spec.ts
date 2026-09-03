import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import type { EstimatedCostSpanSeed } from '@e2e/fixtures';
import type { SpanCostRef } from '@e2e/core/backend';

/**
 * The server-side cost estimate for a model whose name carries a compact
 * `YYYYMMDD` date suffix (OPIK-8242, comet-ml/opik#8139).
 *
 * `CostService` normalises a model name before looking it up in the price
 * table, and this change taught it to strip a trailing compact date so
 * `claude-4.5-haiku-20251001` resolves to the same price row as
 * `claude-haiku-4-5`. Before it, such a name missed the table and fell through
 * to `DEFAULT_COST` — the user-visible symptom being a span with **no** cost at
 * all, since the API omits `total_estimated_cost` for an unpriced span and
 * `formatCost` renders undefined as `-`.
 *
 * WHY THIS IS NOT ALREADY COVERED. `traces.span-model-cost-tokens` is marked
 * covered by `trace-spans-depth.spec.ts`, but that spec seeds
 * `total_cost: 0.00042` on its LLM span. A client-supplied cost is stored
 * verbatim and short-circuits the estimate, so `CostService` never runs there
 * and the price-table lookup is untested in either direction. Everything below
 * seeds spans with **no** `total_cost`, which is the only way to observe it.
 *
 * WHY THE ASSERTIONS ARE COMPARISONS, NOT DOLLAR FIGURES. The price table is
 * synced from LiteLLM daily, so any absolute amount asserted here would rot.
 * What must hold regardless of the prices of the day is that a compact-dated
 * name prices *identically to the model it names*, and that an 8-digit suffix
 * which is not a date prices as nothing rather than as some other model.
 *
 * The negative half is the direction with no coverage anywhere: a regex
 * loosened to `-\d{8}$` would silently bill an arbitrary build number at a
 * different model's rate, and nothing today would notice.
 */

/** The span in a `GET /v1/private/spans` answer that carries a given seed's name. */
const spanFor = (spans: SpanCostRef[], seed: EstimatedCostSpanSeed): SpanCostRef => {
  const found = spans.find((s) => s.name === seed.name);
  expect(found, `the answer carries a span for "${seed.model}"`).toBeDefined();
  return found!;
};

/**
 * The estimate on a span that must have one.
 *
 * Asserted present and above zero before it is returned, rather than defaulted:
 * an absent estimate is the failure this spec exists to catch, and a `?? 0`
 * would turn it into a comparison of two zeros that passes.
 */
const estimateOf = (spans: SpanCostRef[], seed: EstimatedCostSpanSeed): number => {
  const span = spanFor(spans, seed);
  expect(span.totalEstimatedCost, `"${seed.model}" carries a cost estimate`).toBeDefined();
  expect(span.totalEstimatedCost!, `"${seed.model}" is priced above zero`).toBeGreaterThan(0);
  return span.totalEstimatedCost!;
};

/**
 * How the front end renders a cost above its $0.01 floor — a mirror of
 * `formatCost` in `apps/opik-frontend/src/lib/money.ts`, which floors to two
 * decimals behind a `$`.
 *
 * The exponent-shifting round-trip is lodash's own `floor(value, 2)`
 * implementation, reproduced so that a value like 1.575 floors the same way
 * here as it does in the browser rather than through binary floating point.
 * The seeded usage is deliberately large enough that neither the `<$0.01` nor
 * the `-` branch of `formatCost` can be reached.
 */
const asRendered = (value: number): string => `$${Number(`${Math.floor(Number(`${value}e2`))}e-2`)}`;

test.describe('Span estimated cost — compact date suffixes', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  // Seven spans are seeded per test and the UI half opens two views of them;
  // the default budget is tight against a remote deployment.
  test.slow();

  test(
    'a compact-dated model name is priced as the model it names, over the API and in the UI',
    { tag: ['@cap:traces.span-model-cost-tokens'] },
    async ({ estimatedCostSpans, project, backendClient, page }) => {
      const { anthropic, openai, all, traceId } = estimatedCostSpans;

      const spans = await test.step('Read the seeded spans back over the API', async () => {
        const read = await backendClient.listSpanCosts({
          projectId: project.id,
          traceId,
        });
        // The whole answer, not just the rows being compared: a read that also
        // returned spans from elsewhere would mean the trace scope leaked, and
        // every comparison below would be against an arbitrary population.
        expect(read.length, 'the trace-scoped read returns exactly the seeded spans').toBe(
          all.length,
        );
        expect(new Set(read.map((s) => s.name))).toEqual(new Set(all.map((s) => s.name)));
        return read;
      });

      await test.step('The compact-dated name prices identically to its base model', async () => {
        // Two vendors, because a fix that only normalised Anthropic aliases
        // would still pass a single-vendor check. The two spans of each pair
        // differ in nothing but the model string — same provider, same usage —
        // so an inequality can only come from the price-table lookup.
        expect(
          estimateOf(spans, anthropic.compactDated),
          `${anthropic.compactDated.model} vs ${anthropic.base.model}`,
        ).toBe(estimateOf(spans, anthropic.base));
        expect(
          estimateOf(spans, openai.compactDated),
          `${openai.compactDated.model} vs ${openai.control.model}`,
        ).toBe(estimateOf(spans, openai.control));
      });

      const logs = new LogsPage(page);

      await test.step('The trace panel renders the same amount on both span tree nodes', async () => {
        await logs.goto(project.id);
        const panel = await logs.openTraceById(traceId);
        await panel.waitForFullyLoaded();

        // The user-visible symptom of the bug was a tree node with no cost at
        // all, so assert the amount is really rendered before comparing it.
        const expected = asRendered(estimateOf(spans, anthropic.base));
        await expect(panel.spanTreeNodeCost(anthropic.base.model)).toHaveText(expected);
        await expect(panel.spanTreeNodeCost(anthropic.compactDated.model)).toHaveText(expected);
      });

      await test.step('The Spans view Cost column agrees with the API, row for row', async () => {
        await logs.gotoSpans(project.id);
        await logs.waitForSpansReady();

        for (const seed of [anthropic.base, anthropic.compactDated, openai.control, openai.compactDated]) {
          const span = spanFor(spans, seed);
          await expect(
            logs.spanCostCell(span.id),
            `Cost cell for ${seed.model}`,
          ).toHaveText(asRendered(estimateOf(spans, seed)));
        }

        // The unpriced spans share the table with the priced ones, so the
        // column really is distinguishing between them rather than stamping the
        // same amount on every row.
        for (const seed of openai.invalidSuffixes) {
          const span = spanFor(spans, seed);
          await expect(logs.spanCostCell(span.id), `Cost cell for ${seed.model}`).toHaveText('-');
        }
      });
    },
  );

  test(
    'an 8-digit suffix that is not a date leaves the span unpriced rather than priced as another model',
    { tag: ['@cap:traces.span-model-cost-tokens'] },
    async ({ estimatedCostSpans, project, backendClient }) => {
      const { openai, all, traceId } = estimatedCostSpans;

      const spans = await test.step('Read the seeded spans back over the API', async () => {
        const read = await backendClient.listSpanCosts({
          projectId: project.id,
          traceId,
        });
        expect(read.length, 'the trace-scoped read returns exactly the seeded spans').toBe(
          all.length,
        );
        return read;
      });

      await test.step('The priced siblings really are priced', async () => {
        // Without this the negative assertions below would pass just as well
        // against a typo'd model name, an unseeded provider, or a price table
        // that priced nothing at all.
        expect(
          estimateOf(spans, openai.compactDated),
          `${openai.compactDated.model} vs ${openai.control.model}`,
        ).toBe(estimateOf(spans, openai.control));
      });

      await test.step('Each invalid 8-digit suffix comes back with no estimate at all', async () => {
        for (const seed of openai.invalidSuffixes) {
          // `toBeUndefined`, not `toBe(0)`: the API omits the field for a span
          // it could not price, and "unpriced" and "priced at nothing" are
          // different answers. It is also strictly stronger than asserting the
          // span did not collapse onto the control's price row, which is the
          // regression this guards — a value equal to the control's would fail
          // here first.
          expect(
            spanFor(spans, seed).totalEstimatedCost,
            `"${seed.model}" must not resolve to a price row`,
          ).toBeUndefined();
        }
      });
    },
  );
});
