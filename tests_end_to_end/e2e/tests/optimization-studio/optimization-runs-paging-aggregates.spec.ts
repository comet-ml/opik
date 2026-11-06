import { test, expect, TRIAL_COST } from '@e2e/fixtures';
import type { PagedOptimizationRunsRef } from '@e2e/fixtures';
import type { BackendClient } from '@e2e/core/backend';
import { OptimizationStudioPage } from '@e2e/pom/optimization-studio.page';

/**
 * A run's trial count and its total cost do not depend on the page size it is
 * listed at (opik#9252, OPIK-9009).
 *
 * The PR moved the page `LIMIT` out of the outer select and into the
 * `optimization_final` CTE that feeds every per-run aggregate, in both the
 * `FIND` and the `FIND_WITHOUT_EXPERIMENTS` projections. Before that, the
 * aggregates were computed over whatever rows the page happened to hold, so
 * `num_trials` and `total_optimization_cost` could read differently for the
 * same run depending on how the list was paged — a count and a money number
 * that look perfectly ordinary when wrong, on a page a user reads to decide
 * what an optimization cost them.
 *
 * ## Why this is worth a permanent spec even though the API side is covered
 *
 * `OptimizationsResourceTest.findWithPaginationWhenRunsHaveTrials` seeds five
 * runs with distinct trial counts and asserts rows and `numTrials` across three
 * pages of two, so the Java side is genuinely well covered. What no test
 * reached is the rendered page: `optimization-studio.list-optimization-runs`
 * read `covered: true` through `optimization-total-cost.spec.ts`, which is
 * `test.describe.skip`'d (disabled Sept 2026 on LLM cost) and, even enabled,
 * reads one run unpaged. So nothing in the estate opened
 * `/projects/{id}/optimizations` with more than one run on it, and the cost
 * cell a user actually reads was asserted by nothing at all.
 *
 * ## Why the page sizes are read exhaustively
 *
 * Each size is paged to EXHAUSTION and the whole population compared, rather
 * than one page sampled: a spec that read page 1 at size 2 and found its two
 * runs correct would pass on a build where the aggregates were right for the
 * first window and wrong for every later one, which is the shape of the defect.
 * The envelope's `total` is asserted too, so a build that silently dropped runs
 * fails on the count rather than passing on a short list that happened to agree.
 *
 * ## Why the costs are whole dollars
 *
 * Every trial is one experiment owning one trace with one 1M+1M-token span on
 * `claude-haiku-4-5-20251001`, which the shipped price table bills at exactly
 * $6.00. Run k has k trials and therefore costs exactly `k * $6.00`. The
 * assertion here is that the SAME number comes back at three page sizes, so a
 * float tolerance would be free to hide a small drift between them; whole
 * dollars leave nowhere for one to hide. See the fixture.
 *
 * ## What is NOT asserted, and why
 *
 * The trial COUNT is asserted at the API only. The runs list offers eleven
 * columns (Name, Run ID, Item source, Algorithm, Metric, Start time, Status,
 * Best score, Latency, Cost, Optimization cost) and no Trials column, so there
 * is no rendered trial count on this build to hold the API's answer against —
 * the release report's "displayed trial count" has no subject here. The only
 * per-run aggregate a user reads on that page is Optimization cost, and that is
 * the one asserted in the browser.
 *
 * Also not asserted: a second UI page. The list hardcodes `size=100` and
 * disables the rows-per-page control, so a rendered page 2 needs more than 100
 * runs in the project — 95 of them filler. That is a lot of seeding for a
 * question the API half already answers at three sizes, and the aggregates are
 * computed server-side either way.
 */

/**
 * What the Optimization cost column renders for `dollars`.
 *
 * `OptimizationTotalCostCell` formats through `formatAsCurrency`, which is
 * `toFixed(2)` at or above $1 — NOT `core/money.ts`'s `displayedCost`, which
 * mirrors the floor-to-cents `formatCost` the trace and span columns use. The
 * two agree on every amount here, but the column under test is this one, so
 * this is the formatter the expectation is built from. Every amount this spec
 * asserts is a whole number of dollars at or above $6, so the sub-$1 branches
 * are deliberately not modelled: a spec that strayed into them would be
 * asserting against a formatter it had not checked.
 */
const displayedOptimizationCost = (dollars: number): string => {
  if (dollars < 1) {
    throw new Error(
      `displayedOptimizationCost(${dollars}): formatAsCurrency switches to 3 and then 4 ` +
        'decimals below $1, which this helper does not model. Seed a whole-dollar amount.',
    );
  }
  return `$${dollars.toFixed(2)}`;
};

/** One run's pair of aggregates, as one read reported them. */
interface RunAggregates {
  numTrials: number;
  /**
   * `total_optimization_cost`. `number | null` rather than `?? 0`: the roll-up
   * is genuinely absent while it lags, and collapsing that into 0 would let a
   * spec assert a cost against an aggregate that never ran.
   */
  totalCost: number | null;
}

/** Every seeded run's aggregates from one read, keyed by run id. */
type AggregatesById = Record<string, RunAggregates>;

/**
 * Page `projectId`'s optimization runs at `size` until exhausted, and return
 * the seeded runs' aggregates plus what the envelope said about the population.
 *
 * Assertion-FREE on purpose: this runs inside `expect.poll`, which evaluates
 * the polled function outside its own try/catch, so a throw here would be a
 * hard failure rather than another interval. The roll-up lags a write, which is
 * exactly the state the poll exists to wait out, so everything is returned for
 * the matcher to reject.
 *
 * `rowCount` and `total` are returned beside the aggregates so a leak or a drop
 * fails the comparison: finding the seeded runs correct in a response that also
 * carried other rows is the bug a membership-only check would miss.
 */
async function readAtSize(
  backendClient: BackendClient,
  seed: PagedOptimizationRunsRef,
  size: number,
): Promise<{ rowCount: number; total: number | null; aggregates: AggregatesById }> {
  const seededIds = new Set(seed.runs.map((r) => r.optimizationId));
  const aggregates: AggregatesById = {};
  let rowCount = 0;
  let total: number | null = null;

  // Bounded rather than `while (true)`: a build that answered a full page
  // forever would otherwise hang the poll interval instead of failing.
  const maxPages = Math.ceil(seed.runs.length / size) + 2;
  for (let page = 1; page <= maxPages; page++) {
    const read = await backendClient.listOptimizationsPage({
      projectId: seed.projectId,
      page,
      size,
    });
    if (page === 1) total = read.total;
    rowCount += read.content.length;
    for (const run of read.content) {
      if (seededIds.has(run.id)) {
        aggregates[run.id] = { numTrials: run.numTrials, totalCost: run.totalOptimizationCost };
      }
    }
    if (read.content.length < size) break;
  }

  return { rowCount, total, aggregates };
}

/** What every read must agree on, built from the fixture's own expectations. */
function expectedAggregates(seed: PagedOptimizationRunsRef): AggregatesById {
  return Object.fromEntries(
    seed.runs.map((run) => [
      run.optimizationId,
      { numTrials: run.numTrials, totalCost: run.totalCost } satisfies RunAggregates,
    ]),
  );
}

test.describe(
  'Optimization runs list — aggregates across page sizes',
  { tag: ['@t2-cuj', '@area:optimization-studio'] },
  () => {
    test(
      "every run's trial count and total cost are the same at page size 1, 2 and 100, and match the by-id read",
      { tag: ['@cap:optimization-studio.list-optimization-runs'] },
      async ({ pagedOptimizationRuns, backendClient, page }) => {
        test.setTimeout(300_000);

        const seed = pagedOptimizationRuns;
        const expected = expectedAggregates(seed);
        const runCount = seed.runs.length;

        await test.step('The seeded costs are distinct, so a swapped row cannot pass', async () => {
          // The fixture's premise, asserted before anything depends on it: if
          // two runs shared a trial count, a query returning the wrong run's
          // numbers could satisfy every comparison below.
          const trialCounts = seed.runs.map((r) => r.numTrials);
          const costs = seed.runs.map((r) => r.totalCost);
          expect(new Set(trialCounts).size, 'no two runs have the same trial count').toBe(runCount);
          expect(new Set(costs).size, 'no two runs cost the same').toBe(runCount);
        });

        await test.step(
          `All ${runCount} runs roll up to their exact cost on an unpaged read`,
          async () => {
            // Polled, because the cost rolls up through ClickHouse aggregates
            // that the seeding writes do not update synchronously — reading once
            // would fail on lag rather than on behaviour. The whole reading is
            // polled against the whole expectation so a timeout reports the
            // numbers it saw as a diff, and a null on any side fails the same
            // comparison, so an aggregate that never ran can never read as a pass.
            await expect
              .poll(() => readAtSize(backendClient, seed, 100), {
                timeout: 180_000,
                intervals: [2_000, 3_000, 5_000],
                message: `all ${runCount} runs report their seeded trial count and cost`,
              })
              .toEqual({ rowCount: runCount, total: runCount, aggregates: expected });
          },
        );

        for (const size of [1, 2]) {
          await test.step(`Page size ${size} reports the identical aggregates`, async () => {
            // Not polled: the unpaged read above already waited the roll-up out,
            // so a disagreement here is the defect rather than lag — and polling
            // it would turn a real disagreement into a timeout that reads like
            // slowness.
            const read = await readAtSize(backendClient, seed, size);
            expect(
              read,
              `paging at size ${size} changed no run's trial count or cost`,
            ).toEqual({ rowCount: runCount, total: runCount, aggregates: expected });
          });
        }

        await test.step('The by-id read agrees with the list for every run', async () => {
          // A different SQL projection over the same CTE pipeline: `getById`
          // always takes the FIND path, while the list can fall through to the
          // no-experiments shape. A spec that trusted one would miss the case
          // where only the other is wrong.
          for (const run of seed.runs) {
            const byId = await backendClient.getOptimization(run.optimizationId);
            expect(byId, `run ${run.name} reads back by id`).not.toBeNull();
            expect(
              { numTrials: byId!.numTrials, totalCost: byId!.totalOptimizationCost },
              `the by-id read of ${run.name} matches its list row`,
            ).toEqual(expected[run.optimizationId]);
          }
        });

        await test.step('The runs list renders each run’s own Optimization cost', async () => {
          const studio = new OptimizationStudioPage(page, seed.projectId);
          await studio.gotoList();

          for (const run of seed.runs) {
            await studio.waitForRunRow(run.optimizationId);
            // Against the EXPECTED amount, never against what the API just
            // returned: comparing the rendered cell to the API figure passes
            // happily when both are wrong in the same way, which is exactly
            // what a regression in the shared aggregate produces.
            await expect(
              studio.optimizationCostCell(run.optimizationId),
              `${run.name} renders its ${run.numTrials} trials' ${displayedOptimizationCost(run.totalCost)}`,
            ).toHaveText(displayedOptimizationCost(run.totalCost), { timeout: 60_000 });
          }
        });

        await test.step('…and the rendered amounts are all different', async () => {
          // The control for the step above. Every cell reading the right value
          // and every cell reading the SAME value are both consistent with the
          // loop passing if a locator ever resolved to one shared element, so
          // the distinctness the fixture guarantees is asserted on screen too.
          const studio = new OptimizationStudioPage(page, seed.projectId);
          const rendered: string[] = [];
          for (const run of seed.runs) {
            rendered.push(
              ((await studio.optimizationCostCell(run.optimizationId).textContent()) ?? '').trim(),
            );
          }
          expect(new Set(rendered).size, `${runCount} rows, ${runCount} distinct amounts`).toBe(
            runCount,
          );
          expect(rendered, 'and none of them is the empty or placeholder cell').not.toContain('-');
        });

        await test.step(
          `One trial is worth $${TRIAL_COST}.00, so the cheapest run is the one-trial run`,
          async () => {
            // Names the arithmetic the whole spec rests on, so a failure above
            // reads as "the aggregate is wrong" rather than "the fixture priced
            // something differently than I thought".
            const cheapest = seed.runs.reduce((a, b) => (a.totalCost <= b.totalCost ? a : b));
            expect(cheapest.numTrials).toBe(1);
            expect(cheapest.totalCost).toBe(TRIAL_COST);
          },
        );
      },
    );
  },
);
