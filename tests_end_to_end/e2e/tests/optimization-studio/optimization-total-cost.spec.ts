import { test, expect, EXPECTED_TOTAL_COST, SPAN_COST } from '@e2e/fixtures';
import type { OptimizationCostRef } from '@e2e/fixtures';
import type { BackendClient } from '@e2e/core/backend';
import { OptimizationStudioPage } from '@e2e/pom/optimization-studio.page';

/**
 * A run's total cost follows a MUTABLE tag, and never charges one trace twice
 * (OPIK-8333, on top of OPIK-7521).
 *
 * `total_optimization_cost` sums two different attributions: every trial
 * experiment's cost, plus the optimizer-internal traces that carry the run id
 * in their `tags` and belong to no experiment item. The rewrite moved the tag
 * test AFTER the experiment-item dedup, which makes two things true that were
 * not before — a tag a later write removes stops counting, and a trace that is
 * already inside an experiment item is not charged again for also being
 * tagged.
 *
 * This is the failure mode worth a permanent test rather than a code review:
 * it is a money number, it reads as a perfectly ordinary figure when wrong,
 * and nothing in the estate opened `/projects/{id}/optimizations` at all.
 *
 * Both surfaces are asserted against the SAME expectation ($12.00), never
 * against each other: comparing the rendered cell to the API figure passes
 * happily when both are wrong in the same way, which is precisely what a
 * regression in the shared aggregate would produce.
 *
 * NOT asserted, deliberately: the week-floor drop-out, where a tagged trace
 * older than `toMonday(earliest run id) - 1 week` leaves the sum. Staging runs
 * `UuidV7TimestampValidator` in reject mode with a PT24H window, so a trace
 * that old is refused at ingestion with 400 `too_old` and the branch is
 * unreachable through the public API here. A spec for it could not run.
 */

/** What one sample of the aggregate looks like, from both projections. */
interface CostReading {
  /** How many rows the project's runs list holds for the seeded run. */
  listedCount: number;
  /** `total_optimization_cost` from the list read, or null when absent. */
  fromList: number | null;
  /** `total_optimization_cost` from the by-id read, or null when absent. */
  fromById: number | null;
}

/**
 * The cost the aggregate reports for the seeded run, from both projections.
 *
 * The list read and the by-id read are two different SQL projections over the
 * same CTE pipeline and are allowed to disagree — the list can fall through to
 * the no-experiments shape while `getById` always takes the FIND path — so a
 * spec that trusts one of them would miss the case where only the other is
 * wrong. Both are returned and both get asserted.
 *
 * Deliberately assertion-FREE: this runs inside `expect.poll`, and Playwright
 * evaluates the polled function OUTSIDE its own try/catch, so a throw here is
 * a hard failure rather than another interval. Absence is therefore returned
 * as `null` and left for the matcher to reject — `totalOptimizationCost` is
 * optional on the wire and genuinely absent while the roll-up lags, which is
 * exactly the state the poll exists to wait out.
 */
async function readCosts(
  backendClient: BackendClient,
  seed: OptimizationCostRef,
): Promise<CostReading> {
  const runs = await backendClient.listOptimizations({ projectId: seed.projectId });
  const listed = runs.filter((r) => r.id === seed.optimizationId);
  const byId = await backendClient.getOptimization(seed.optimizationId);

  return {
    listedCount: listed.length,
    fromList: listed.length === 1 ? listed[0].totalOptimizationCost : null,
    fromById: byId?.totalOptimizationCost ?? null,
  };
}

/**
 * Wait until BOTH reads settle on `expected`, then assert them.
 *
 * Polling is the only honest way to read this: the cost rolls up through
 * ClickHouse aggregates that a PATCH does not update synchronously, so reading
 * once after a write would fail on lag rather than on behaviour.
 *
 * The whole reading is polled against the whole expectation, rather than a
 * boolean predicate, so a timeout reports the two numbers it actually saw as a
 * diff instead of "expected true, received false". A null on either side fails
 * the same comparison, so an aggregate that never ran can never read as a pass.
 */
async function expectCostToSettle(
  backendClient: BackendClient,
  seed: OptimizationCostRef,
  expected: number,
): Promise<void> {
  await expect
    .poll(() => readCosts(backendClient, seed), {
      timeout: 120_000,
      intervals: [2_000, 3_000, 5_000],
      message: `both reads report a total_optimization_cost of ${expected}`,
    })
    .toEqual({ listedCount: 1, fromList: expected, fromById: expected } satisfies CostReading);
}

test.describe(
  'Optimization runs — total cost',
  { tag: ['@t2-cuj', '@area:optimization-studio'] },
  () => {
    test(
      'a run\'s total cost follows a mutable tag and never double-charges a trial trace',
      { tag: ['@cap:optimization-studio.list-optimization-runs'] },
      async ({ backendClient, optimizationCost, page }) => {
        test.setTimeout(300_000);

        await test.step(
          `Both attributions are in scope: the run costs $${EXPECTED_TOTAL_COST}.00`,
          async () => {
            await expectCostToSettle(backendClient, optimizationCost, EXPECTED_TOTAL_COST);
          },
        );

        const studio = new OptimizationStudioPage(page, optimizationCost.projectId);

        await test.step('The runs list renders that same figure', async () => {
          await studio.gotoList();
          await studio.waitForRunRow(optimizationCost.optimizationId);
          // `formatAsCurrency` renders anything at or above $1 to two decimals.
          await expect(
            studio.optimizationCostCell(optimizationCost.optimizationId),
            'the Optimization cost cell renders the expected total',
          ).toHaveText(`$${EXPECTED_TOTAL_COST}.00`, { timeout: 60_000 });
        });

        await test.step(
          'Tagging the trial trace as well does not charge it twice',
          async () => {
            // The trial trace is already in scope through its experiment item.
            // Before the dedup moved ahead of the tag test, this was $18.00.
            await backendClient.updateTraceTags({
              traceId: optimizationCost.trialTraceId,
              projectName: optimizationCost.projectName,
              tags: [optimizationCost.optimizationId],
            });
            await expectCostToSettle(backendClient, optimizationCost, EXPECTED_TOTAL_COST);
          },
        );

        await test.step(
          `Removing the optimizer trace's tag drops its $${SPAN_COST}.00`,
          async () => {
            // A later write that clears the tag is the mutable case: the trace
            // and its span are untouched, only the attribution is withdrawn.
            await backendClient.updateTraceTags({
              traceId: optimizationCost.taggedTraceId,
              projectName: optimizationCost.projectName,
              tags: [],
            });
            await expectCostToSettle(backendClient, optimizationCost, SPAN_COST);
          },
        );

        await test.step('Re-applying it restores the total', async () => {
          await backendClient.updateTraceTags({
            traceId: optimizationCost.taggedTraceId,
            projectName: optimizationCost.projectName,
            tags: [optimizationCost.optimizationId],
          });
          await expectCostToSettle(backendClient, optimizationCost, EXPECTED_TOTAL_COST);
        });
      },
    );

    test(
      'a project with no optimization runs renders its empty state',
      { tag: ['@cap:optimization-studio.list-optimization-runs'] },
      async ({ backendClient, optimizationCost, page }) => {
        // The empty project is seeded by the same fixture as the priced run, so
        // this also pins that the two projects' lists really are scoped apart:
        // a list that ignored `project_id` would show the run seeded next door
        // and the empty state would never render.
        await test.step('The API reports no runs for the empty project', async () => {
          const runs = await backendClient.listOptimizations({
            projectId: optimizationCost.emptyProjectId,
          });
          expect(runs, 'the empty project has no optimization runs').toHaveLength(0);
        });

        await test.step('The page loads and shows the empty state', async () => {
          const studio = new OptimizationStudioPage(page, optimizationCost.emptyProjectId);
          await studio.gotoList();
          await expect(studio.emptyStateHeading()).toBeVisible({ timeout: 30_000 });
        });
      },
    );
  },
);
