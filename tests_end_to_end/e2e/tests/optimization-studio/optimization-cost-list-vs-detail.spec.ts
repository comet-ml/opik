import { test, expect } from '@e2e/fixtures';
import { OptimizationStudioPage } from '@e2e/pom/optimization-studio.page';
import type { OptimizationRef } from '@e2e/core/backend';

/**
 * A run's cost reaches the runs list and the run page through two different
 * backend queries: the list of a project whose runs have no experiments is
 * answered by a reduced projection, while the run page always goes through the
 * full one. `total_optimization_cost` is the only aggregate the reduced
 * projection still has to compute for real, because optimizer-internal spend
 * exists without any experiment — so the two paths can disagree, and the
 * user-visible symptom is a run reading $0.000 in the list while priced on its
 * own page. Nothing covered this list before.
 *
 * Three runs, chosen so each exercises one way the paths can diverge, with three
 * distinct costs so a spec cannot pass by reading the wrong run:
 *
 *  - **run A** (project A, two trials, $0.030 + $0.007 = $0.037) — the ordinary
 *    shape, and what makes project A's list take the full projection;
 *  - **run B** (project B, no experiments at all, one tagged trace at $0.004) —
 *    a run that died before its first trial. It is alone in its project, so that
 *    project's list takes the reduced projection: this is the case that used to
 *    read $0.000;
 *  - **run C** (project A, no experiments, tagged onto run A's *cheaper trial
 *    trace*, $0.007) — spend attributed to a run through another run's trial.
 *    Its cost must not depend on the scope it is queried in.
 *
 * The two costs below $0.01 and the one above also cover both branches of the
 * currency formatter (4 decimals vs 3) on both surfaces.
 */
const TRIAL_COSTS = [0.03, 0.007];
const RUN_B_COST = 0.004;
/** Run C is tagged onto run A's second trial trace, so it is worth exactly that. */
const RUN_C_COST = TRIAL_COSTS[1];
const RUN_A_COST = TRIAL_COSTS.reduce((a, b) => a + b, 0);

/** As `formatAsCurrency` renders them: 4 decimals below $0.01, 3 above. */
const RENDERED = {
  runA: '$0.037',
  runB: '$0.0040',
  runC: '$0.0070',
};

/** Decimal(38,12) over JSON — compare to the cent-level figures we seeded, not bit-exactly. */
const COST_PRECISION = 6;

type SdkClient = Parameters<Parameters<typeof test>[2]>[0]['sdkClient'];

function seedPricedTrace(
  sdkClient: SdkClient,
  args: { projectName: string; name: string; cost: number; tags: string[] },
) {
  return sdkClient.python.createNestedTrace({
    project_name: args.projectName,
    name: args.name,
    input: { prompt: 'seeded optimization-cost prompt' },
    output: { completion: 'seeded optimization-cost completion' },
    tags: args.tags,
    spans: [
      {
        name: 'llm-call',
        type: 'llm',
        model: 'gpt-4o',
        provider: 'openai',
        input: { prompt: 'seeded optimization-cost prompt' },
        output: { completion: 'seeded optimization-cost completion' },
        usage: { prompt_tokens: 10, completion_tokens: 5, total_tokens: 15 },
        total_cost: args.cost,
      },
    ],
  });
}

/** The cost one list read reports for a run, failing loudly if the run is absent. */
function costInList(list: OptimizationRef[], id: string, label: string): number {
  const found = list.find((o) => o.id === id);
  expect(found, `${label} must appear in this list`).toBeDefined();
  expect(
    found!.totalOptimizationCost,
    `${label} must report a cost through this read path, not omit it`,
  ).not.toBeNull();
  return found!.totalOptimizationCost as number;
}

test.describe(
  'Optimization Studio — run cost across the list and the run page',
  { tag: ['@t2-cuj', '@area:optimization-studio'] },
  () => {
    test(
      'a run reports the same cost through every read path, including a run with no experiments and one attributed through another run\'s trial',
      {
        tag: [
          '@cap:optimization-studio.list-optimization-runs',
          '@cap:optimization-studio.kpi-cards',
        ],
      },
      async ({ project, sdkClient, backendClient, testNamespace, page }) => {
        // Two projects, three runs, four priced traces and two trials to seed
        // before the first page load, then both surfaces of both projects.
        test.setTimeout(300_000);

        const datasetNameA = `${testNamespace}-cost-ds-a`;
        const datasetNameB = `${testNamespace}-cost-ds-b`;

        const seeded = await test.step('Seed two projects and three runs of different shapes', async () => {
          // Project A is the `project` fixture; project B has to be its own
          // project, because the reduced projection is only chosen when NO run
          // in the queried scope has an experiment.
          const projectB = await sdkClient.python.createProject({
            name: `${testNamespace}-proj-b`,
          });

          const datasetA = await sdkClient.python.createDataset({
            project_name: project.name,
            name: datasetNameA,
            description: 'optimization cost, list vs detail (project A)',
            items: TRIAL_COSTS.map((_, i) => ({
              input: `cost item ${i}`,
              expected_output: `cost output ${i}`,
            })),
          });
          const datasetB = await sdkClient.python.createDataset({
            project_name: projectB.name,
            name: datasetNameB,
            description: 'optimization cost, list vs detail (project B)',
          });
          const datasetItems = await backendClient.getDatasetItems(datasetA.id);
          expect(datasetItems.length, 'one dataset item per trial to link').toBe(
            TRIAL_COSTS.length,
          );

          // Every run is created before any trace, because attribution is by tag:
          // run A's second trial trace has to carry run C's id.
          const runA = await backendClient.createOptimization({
            name: `${testNamespace}-run-a-trials`,
            datasetName: datasetNameA,
            projectId: project.id,
          });
          const runC = await backendClient.createOptimization({
            name: `${testNamespace}-run-c-crossattr`,
            datasetName: datasetNameA,
            projectId: project.id,
          });
          const runB = await backendClient.createOptimization({
            name: `${testNamespace}-run-b-died-early`,
            datasetName: datasetNameB,
            projectId: projectB.id,
          });

          const trialExperimentIds: string[] = [];
          for (const [index, cost] of TRIAL_COSTS.entries()) {
            const trace = await seedPricedTrace(sdkClient, {
              projectName: project.name,
              name: `${testNamespace}-run-a-trial-${index}`,
              cost,
              // The cheaper trial trace is also tagged with run C's id: run C
              // owns no experiment, so this is its only spend, and it must not
              // be taken away from run A either.
              tags:
                index === 1 ? ['GEPA', runC, 'Evaluation'] : ['GEPA', 'Evaluation'],
            });
            const experimentId = await backendClient.createExperiment({
              name: `${testNamespace}-run-a-trial-exp-${index}`,
              datasetName: datasetNameA,
              projectId: project.id,
              optimizationId: runA,
              type: 'trial',
            });
            await backendClient.linkExperimentItems({
              experimentId,
              links: [{ datasetItemId: datasetItems[index].id, traceId: trace.id }],
            });
            trialExperimentIds.push(experimentId);
          }

          // Run B's only spend: a tagged trace in its own project, no experiment.
          await seedPricedTrace(sdkClient, {
            projectName: projectB.name,
            name: `${testNamespace}-run-b-reflection`,
            cost: RUN_B_COST,
            tags: ['GEPA', runB, 'Reflection'],
          });

          return {
            projectB: { id: projectB.id, name: projectB.name },
            datasetIds: [datasetA.id, datasetB.id],
            runA,
            runB,
            runC,
            trialExperimentIds,
          };
        });

        const expectedCosts: Array<{ id: string; label: string; cost: number }> = [
          { id: seeded.runA, label: 'run A (two trials)', cost: RUN_A_COST },
          { id: seeded.runB, label: 'run B (no experiments)', cost: RUN_B_COST },
          { id: seeded.runC, label: "run C (another run's trial)", cost: RUN_C_COST },
        ];

        try {
          await test.step('Each run reports its cost through getById', async () => {
            // toPass, not a sleep: the aggregate is computed at read time, so
            // this waits for the seeded rows to become visible. The values
            // asserted are exact.
            await expect(async () => {
              for (const { id, label, cost } of expectedCosts) {
                const run = await backendClient.getOptimization(id);
                expect(run, `${label} must be readable`).not.toBeNull();
                expect(run!.totalOptimizationCost, `${label} reports a cost`).not.toBeNull();
                expect(run!.totalOptimizationCost as number, label).toBeCloseTo(
                  cost,
                  COST_PRECISION,
                );
              }
            }).toPass({ timeout: 60_000, intervals: [1_000, 2_000, 5_000] });
          });

          await test.step('And the same cost through both list scopes', async () => {
            const [inProjectA, inProjectB, acrossWorkspace] = await Promise.all([
              backendClient.listOptimizations({ projectId: project.id }),
              backendClient.listOptimizations({ projectId: seeded.projectB.id }),
              // No project filter — a genuinely different scope, which is what
              // decides which projection answers. Narrowed by name only so the
              // read is not at the mercy of another run's pagination.
              backendClient.listOptimizations({ name: testNamespace }),
            ]);

            // Project B's list is the regression case: it holds one run with no
            // experiments, so it takes the reduced projection, and it used to
            // answer $0.000 while the run page priced the same run correctly.
            expect(
              costInList(inProjectB, seeded.runB, 'run B in its project list'),
              'a run that died before its first trial must still be priced in the list',
            ).toBeCloseTo(RUN_B_COST, COST_PRECISION);

            for (const { id, label, cost } of expectedCosts) {
              const list = id === seeded.runB ? inProjectB : inProjectA;
              expect(
                costInList(list, id, `${label} in its project list`),
                `${label} must cost the same in the project list as through getById`,
              ).toBeCloseTo(cost, COST_PRECISION);
              expect(
                costInList(acrossWorkspace, id, `${label} in the workspace list`),
                `${label} must not depend on the scope it is queried in`,
              ).toBeCloseTo(cost, COST_PRECISION);
            }
          });

          await test.step('Project B: the runs list and the run page agree', async () => {
            const studioB = new OptimizationStudioPage(page, seeded.projectB.id);
            await studioB.gotoList(seeded.runB);
            expect(
              await studioB.runListCost(seeded.runB),
              'the "Opt. cost" column must show the run\'s spend, not $0.000',
            ).toBe(RENDERED.runB);

            await studioB.gotoDetail(seeded.runB);
            expect(await studioB.optimizationCostCardValue()).toBe(RENDERED.runB);
          });

          await test.step('Project A: both runs agree between the list and their pages', async () => {
            const studioA = new OptimizationStudioPage(page, project.id);
            await studioA.gotoList(seeded.runA);
            expect(await studioA.runListCost(seeded.runA)).toBe(RENDERED.runA);
            expect(
              await studioA.runListCost(seeded.runC),
              "a run attributed through another run's trial must be priced in the list too",
            ).toBe(RENDERED.runC);

            await studioA.gotoDetail(seeded.runA);
            expect(await studioA.optimizationCostCardValue()).toBe(RENDERED.runA);

            await studioA.gotoDetail(seeded.runC);
            expect(await studioA.optimizationCostCardValue()).toBe(RENDERED.runC);
          });
        } finally {
          // Optimizations are not swept by prefix (global teardown sweeps
          // experiments, datasets and projects); traces go with their project.
          for (const id of [seeded.runA, seeded.runB, seeded.runC]) {
            await backendClient.deleteOptimization(id);
          }
          for (const id of seeded.trialExperimentIds) {
            await backendClient.deleteExperiment(id);
          }
          for (const id of seeded.datasetIds) {
            await backendClient.deleteDataset(id);
          }
          await backendClient.deleteProject(seeded.projectB.id);
        }
      },
    );
  },
);
