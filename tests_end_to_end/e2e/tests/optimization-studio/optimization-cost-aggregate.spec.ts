import { test, expect } from '@e2e/fixtures';
import { OptimizationStudioPage } from '@e2e/pom/optimization-studio.page';

/**
 * `Optimization.total_optimization_cost` is the run's whole-run spend: the sum
 * over its trial experiments PLUS the optimizer-internal traces attributed to
 * the run by tag (GEPA reflection calls, which belong to no trial). It is what
 * the run page's "Optimization cost" card renders, and nothing in the estate
 * read it before this spec.
 *
 * The aggregate can be silently wrong in two opposite directions, and the seed
 * below pins both at once:
 *
 *  - **too low** — it drops the optimizer-internal spend and reports the
 *    trial-only figure ($0.050 here), which is what 2.2.27 did;
 *  - **too high** — it double-counts a trial trace that also carries its own
 *    run's id in its tags ($0.070 here). The backend excludes exactly the
 *    (trace, optimization) pairs that are already linked through an experiment
 *    item, so this trap has to be seeded deliberately or the exclusion is
 *    untested.
 *
 * Only $0.060 satisfies both, so the assertion is an exact figure rather than a
 * range. Everything is seeded through the API with fixed span costs, so no LLM
 * runs and no number here depends on a model's behaviour.
 *
 * Asserted on both surfaces because the candidate is about a number a human
 * reads off a page: the API says what the backend computed, and the card says
 * what the user is actually shown.
 */
const TRIAL_COSTS = [0.02, 0.03];
const REFLECTION_COSTS = [0.006, 0.004];
const TRIAL_SUM = TRIAL_COSTS.reduce((a, b) => a + b, 0);
const EXPECTED_TOTAL = TRIAL_SUM + REFLECTION_COSTS.reduce((a, b) => a + b, 0);

/** As `formatAsCurrency` renders it: 3 decimals between $0.01 and $1. */
const EXPECTED_CARD = '$0.060';

/**
 * The tooltip has to name where the figure came from — the card legitimately
 * exceeds the trials listed below it, so an unexplained number reads as an
 * arithmetic error. Matching the two load-bearing phrases rather than the whole
 * sentence: "whole-run spend" is the branch that proves the *backend* aggregate
 * was used and not the client-side sum of the loaded trials.
 */
const COST_TOOLTIP = /whole-run spend[\s\S]*optimizer-internal LLM calls/i;

/** Decimal(38,12) over JSON — compare to the cent-level figures we seeded, not bit-exactly. */
const COST_PRECISION = 6;

type SdkClient = Parameters<Parameters<typeof test>[2]>[0]['sdkClient'];

/**
 * One trace with a single priced LLM span. Cost is set explicitly rather than
 * derived from tokens, so the run's total is a fixed number and the assertion
 * can be exact.
 */
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

test.describe(
  'Optimization Studio — run cost aggregate',
  { tag: ['@t2-cuj', '@area:optimization-studio'] },
  () => {
    test(
      'the run cost counts optimizer-internal spend on top of the trials, never double-counts a trial trace, and the card renders that figure',
      { tag: ['@cap:optimization-studio.kpi-cards'] },
      async ({ project, sdkClient, backendClient, testNamespace, page }) => {
        // Seeding is ~6 API round-trips against a shared cloud backend before any
        // page is opened; the default 90s budget is spent before the assertions.
        test.setTimeout(240_000);

        const datasetName = `${testNamespace}-cost-ds`;

        const seeded = await test.step('Seed a run with priced trials and priced reflection traces', async () => {
          const dataset = await sdkClient.python.createDataset({
            project_name: project.name,
            name: datasetName,
            description: 'optimization cost aggregate',
            items: TRIAL_COSTS.map((_, i) => ({
              input: `cost item ${i}`,
              expected_output: `cost output ${i}`,
            })),
          });
          const datasetItems = await backendClient.getDatasetItems(dataset.id);
          expect(
            datasetItems.length,
            'the seeded dataset must expose one item per trial to link',
          ).toBe(TRIAL_COSTS.length);

          // The run must exist before its traces, because the reflection traces
          // are attributed to it by carrying its id as a tag.
          const optimizationId = await backendClient.createOptimization({
            name: `${testNamespace}-cost-run`,
            datasetName,
            projectId: project.id,
          });

          const trialExperimentIds: string[] = [];
          for (const [index, cost] of TRIAL_COSTS.entries()) {
            const trace = await seedPricedTrace(sdkClient, {
              projectName: project.name,
              name: `${testNamespace}-trial-${index}`,
              cost,
              // The first trial's trace also carries the run's own id — the
              // double-count trap. It is already attributed through its
              // experiment item, so the tag must add nothing.
              tags:
                index === 0
                  ? ['GEPA', optimizationId, 'Evaluation']
                  : ['GEPA', 'Evaluation'],
            });
            const experimentId = await backendClient.createExperiment({
              name: `${testNamespace}-trial-exp-${index}`,
              datasetName,
              projectId: project.id,
              optimizationId,
              type: 'trial',
            });
            await backendClient.linkExperimentItems({
              experimentId,
              links: [{ datasetItemId: datasetItems[index].id, traceId: trace.id }],
            });
            trialExperimentIds.push(experimentId);
          }

          // Optimizer-internal spend: tagged onto the run, linked to no
          // experiment item, and therefore invisible to the trial sum.
          for (const [index, cost] of REFLECTION_COSTS.entries()) {
            await seedPricedTrace(sdkClient, {
              projectName: project.name,
              name: `${testNamespace}-reflection-${index}`,
              cost,
              tags: ['GEPA', optimizationId, 'Reflection'],
            });
          }

          return { datasetId: dataset.id, optimizationId, trialExperimentIds };
        });

        try {
          await test.step('The API aggregate is the trials plus the reflection spend, counted once', async () => {
            // toPass, not a sleep: the aggregate is computed at read time, so
            // this waits for the seeded rows to become visible to the query. The
            // asserted value is exact — the retry is about read-after-write
            // settling, not about tolerating a different number.
            await expect(async () => {
              const run = await backendClient.getOptimization(seeded.optimizationId);
              expect(run, 'the seeded run must be readable').not.toBeNull();
              expect(
                run!.totalOptimizationCost,
                'total_optimization_cost must be reported, not omitted',
              ).not.toBeNull();
              expect(
                run!.totalOptimizationCost as number,
                `expected $${EXPECTED_TOTAL} — $${TRIAL_SUM} would mean the ` +
                  'optimizer-internal spend was dropped, and $0.07 would mean the ' +
                  "trial trace tagged with its own run's id was counted twice",
              ).toBeCloseTo(EXPECTED_TOTAL, COST_PRECISION);
            }).toPass({ timeout: 60_000, intervals: [1_000, 2_000, 5_000] });
          });

          await test.step('It exceeds what the run\'s own trials cost', async () => {
            const trials = await Promise.all(
              seeded.trialExperimentIds.map((id) => backendClient.getExperiment(id)),
            );
            const trialSum = trials.reduce((sum, t) => sum + (t?.totalEstimatedCost ?? 0), 0);
            // Read back rather than trusting the seed: this compares two numbers
            // the backend computed, so a run whose trials priced differently than
            // intended can't make the comparison pass by luck.
            expect(trialSum, 'the trials priced as seeded').toBeCloseTo(
              TRIAL_SUM,
              COST_PRECISION,
            );
            for (const trial of trials) {
              expect(trial?.type, 'each seeded experiment is a trial of the run').toBe('trial');
              expect(trial?.optimizationId).toBe(seeded.optimizationId);
            }

            const run = await backendClient.getOptimization(seeded.optimizationId);
            expect(run?.numTrials, 'both trials are attributed to the run').toBe(
              TRIAL_COSTS.length,
            );
            expect(
              run!.totalOptimizationCost as number,
              'whole-run spend must exceed the trial sum by the optimizer-internal spend',
            ).toBeGreaterThan(trialSum);
          });

          await test.step('The run page renders that figure on the Optimization cost card', async () => {
            const studio = new OptimizationStudioPage(page, project.id);
            await studio.gotoDetail(seeded.optimizationId);
            expect(await studio.optimizationCostCardValue()).toBe(EXPECTED_CARD);
          });

          await test.step('And its tooltip names optimizer-internal calls as the source', async () => {
            const studio = new OptimizationStudioPage(page, project.id);
            await studio.expectCostTooltip(COST_TOOLTIP);
          });
        } finally {
          // The run and its trials are not swept by prefix (global teardown
          // sweeps experiments, datasets and projects); traces and spans go with
          // the project.
          await backendClient.deleteOptimization(seeded.optimizationId);
          for (const id of seeded.trialExperimentIds) {
            await backendClient.deleteExperiment(id);
          }
          await backendClient.deleteDataset(seeded.datasetId);
        }
      },
    );
  },
);
