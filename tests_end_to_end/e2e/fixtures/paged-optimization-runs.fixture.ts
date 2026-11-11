import { test as baseTest } from './wide-content-thread.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/**
 * The model the trial spans are priced on, and the usage that makes the price
 * exact.
 *
 * Same pair as `optimization-cost.fixture.ts`, and for the same reason:
 * `claude-haiku-4-5-20251001` bills $1/M input and $5/M output in the shipped
 * price table, so 1M + 1M is $6.00 on the nose. Every expected total here is
 * therefore a whole number of dollars rather than a float with a tolerance —
 * which matters more than usual, because the assertion is that the SAME number
 * comes back at three different page sizes, and a tolerance would hide a small
 * drift between them.
 *
 * Deliberately NO `total_cost` on the span: a seed that supplies its own cost
 * never exercises server-side pricing, and the number under test here is the
 * server's roll-up.
 */
const PRICED_MODEL = 'claude-haiku-4-5-20251001';
const PRICED_PROVIDER = 'anthropic';
const PROMPT_TOKENS = 1_000_000;
const COMPLETION_TOKENS = 1_000_000;

/** What one seeded trial trace costs once the backend resolves its price. */
export const TRIAL_COST = 6;

/**
 * How many trials each seeded run gets, in seeded order.
 *
 * Deliberately DISTINCT and consecutive from 1: run k has k trials and so
 * costs `k * TRIAL_COST`, which makes every run's pair of aggregates unique
 * across the population. That is what makes a row swapped between two runs
 * fail — a fixture where two runs shared a trial count could pass while the
 * query returned the wrong run's numbers.
 *
 * Six runs is enough to force three genuinely different pagings (6 pages at
 * size 1, 3 at size 2, 1 at size 100) while keeping the seed to 21 traces.
 */
export const TRIALS_PER_RUN = [1, 2, 3, 4, 5, 6] as const;

/** A run's two aggregates, as both the list and the by-id read report them. */
export interface PagedRunExpectation {
  optimizationId: string;
  name: string;
  /** `num_trials` — one trial experiment per seeded trial. */
  numTrials: number;
  /** `total_optimization_cost` — `numTrials * TRIAL_COST`, exactly. */
  totalCost: number;
}

export interface PagedOptimizationRunsRef {
  projectId: string;
  projectName: string;
  datasetName: string;
  datasetId: string;
  /** In seeded order, so `runs[k - 1]` has k trials. */
  runs: PagedRunExpectation[];
  /** Every trace this fixture wrote, for the spec's own diagnostics. */
  traceIds: string[];
}

export interface PagedOptimizationRunsFixtures {
  pagedOptimizationRuns: PagedOptimizationRunsRef;
}

/**
 * Several optimization runs in one project, each with a distinct trial count
 * and a cost that is an exact multiple of it (opik#9252, OPIK-9009).
 *
 * The PR moved the page `LIMIT` from the outer select into the
 * `optimization_final` CTE that feeds every per-run aggregate, in both the
 * `FIND` and the `FIND_WITHOUT_EXPERIMENTS` projections. The failure mode that
 * change guards against is a per-run aggregate computed over whatever window
 * the page happened to be, so a run's trial count and its total cost would read
 * differently depending on the page size it was listed at — a money number and
 * a count that look perfectly ordinary when wrong.
 *
 * Observing that needs a population where every run's answer is known exactly
 * and no two runs' answers are alike, which is why the trial counts are
 * consecutive rather than uniform. Each trial is one experiment owning one
 * trace carrying one priced LLM span, so run k's cost is `k * $6.00` and its
 * trial count is k.
 *
 * Seeded through the REST client rather than the SDK bridge for the same reason
 * `optimizationRun` and `optimizationCost` are: the bridge only emits
 * `source=sdk` and normalises usage keys, while these spans need
 * `source=optimization` and a verbatim `usage` map with no `total_cost`. Ids
 * are minted with `uuid7()` up front because the REST writes answer with no
 * body and the spec asserts on exact ids.
 *
 * The runs are SEEDED, not launched: what is under test is a query over rows,
 * which is independent of how an optimizer arrived at them, and launching six
 * real GEPA runs is the LLM spend `optimization-studio.spec.ts` is skipped for.
 *
 * Teardown deletes more than the project does: `ProjectService.delete` removes
 * only the project row, so traces do not cascade, and the run-prefix sweep in
 * `global-teardown.ts` does not know about optimizations.
 */
export const test = baseTest.extend<PagedOptimizationRunsFixtures>({
  pagedOptimizationRuns: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const datasetName = `${testNamespace}-ds`;
    const dataset = await sdkClient.python.createDataset({
      project_name: project.name,
      name: datasetName,
      description: 'optimization runs list paging aggregates',
      items: [{ text: 'first review', label: 'positive' }] as unknown as Array<
        Record<string, unknown>
      >,
    });
    const datasetItemIds = (await backendClient.getDatasetItems(dataset.id)).map((i) => i.id);
    if (datasetItemIds.length === 0) {
      throw new Error(
        '[pagedOptimizationRuns fixture] the dataset seeded no items — a trial experiment ' +
          'needs one to attach its trace to, and a run with no experiment item takes the ' +
          'other projection entirely',
      );
    }

    const traceIds: string[] = [];
    const experimentIds: string[] = [];
    const runs: PagedRunExpectation[] = [];

    /** One trial: an experiment owning one trace with one $6.00 LLM span. */
    const seedTrial = async (
      optimizationId: string,
      label: string,
      stepIndex: number,
    ): Promise<void> => {
      const traceId = uuid7();
      await backendClient.createTraceWithSource({
        id: traceId,
        projectName: project.name,
        name: `${testNamespace}-${label}`,
        source: 'optimization',
        input: { text: `${label} input` },
        output: { label: `${label} output` },
        endTime: new Date(),
      });
      await backendClient.createSpan({
        id: uuid7(),
        traceId,
        projectName: project.name,
        name: `${testNamespace}-${label}-llm`,
        source: 'optimization',
        type: 'llm',
        model: PRICED_MODEL,
        provider: PRICED_PROVIDER,
        usage: {
          prompt_tokens: PROMPT_TOKENS,
          completion_tokens: COMPLETION_TOKENS,
          total_tokens: PROMPT_TOKENS + COMPLETION_TOKENS,
        },
      });
      traceIds.push(traceId);

      const experimentId = uuid7();
      await backendClient.createExperiment({
        id: experimentId,
        name: `${testNamespace}-${label}`,
        datasetName,
        projectName: project.name,
        type: 'trial',
        optimizationId,
        metadata: {
          step_index: stepIndex,
          candidate_id: `${testNamespace}-${label}-cand`,
          parent_candidate_ids: [],
        },
      });
      await backendClient.createExperimentItems([
        { experimentId, datasetItemId: datasetItemIds[0], traceId },
      ]);
      experimentIds.push(experimentId);
    };

    for (const trialCount of TRIALS_PER_RUN) {
      const optimizationId = uuid7();
      // The run index is in the name so a failure diff names the run a reader
      // can find on the page, and so the rendered rows can be told apart by a
      // human looking at the screenshot.
      const name = `${testNamespace}-run-${String(trialCount).padStart(2, '0')}`;
      await backendClient.createOptimization({
        id: optimizationId,
        name,
        datasetName,
        projectName: project.name,
        objectiveName: 'equals',
        status: 'completed',
      });
      for (let t = 0; t < trialCount; t++) {
        await seedTrial(optimizationId, `run${trialCount}-trial${t + 1}`, t);
      }
      runs.push({
        optimizationId,
        name,
        numTrials: trialCount,
        totalCost: trialCount * TRIAL_COST,
      });
    }

    const ref: PagedOptimizationRunsRef = {
      projectId: project.id,
      projectName: project.name,
      datasetName,
      datasetId: dataset.id,
      runs,
      traceIds,
    };
    await testInfo.attach('opik.pagedOptimizationRuns', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      const safe = async (what: string, fn: () => Promise<unknown>): Promise<void> => {
        try {
          await fn();
        } catch (err) {
          console.warn(`[pagedOptimizationRuns fixture] delete warning for ${what}:`, err);
        }
      };
      // Experiments before the optimizations they belong to, traces before the
      // dataset, so nothing is removed from under a still-referencing parent.
      for (const experimentId of experimentIds) {
        await safe(`experiment ${experimentId}`, () => backendClient.deleteExperiment(experimentId));
      }
      for (const run of runs) {
        await safe(`optimization ${run.optimizationId}`, () =>
          backendClient.deleteOptimization(run.optimizationId),
        );
      }
      await safe(`${traceIds.length} traces`, () => backendClient.deleteTraces(traceIds));
      await safe(`dataset ${datasetName}`, () => backendClient.deleteDataset(dataset.id));
    }
  },
});

export { expect } from './wide-content-thread.fixture';
