import { test as baseTest } from './filterable-traces.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

export interface OptimizationTrialRef {
  /** Label the trials table renders for this candidate — "Baseline" or "Trial #N". */
  label: string;
  experimentId: string;
  /** Trace ids linked to this trial, and the exact set its Logs overlay must list. */
  traceIds: string[];
}

export interface OptimizationRunRef {
  optimizationId: string;
  datasetId: string;
  datasetName: string;
  datasetItemIds: string[];
  projectId: string;
  projectName: string;
  /** In seeded order: the step-0 baseline first, then the numbered trials. */
  trials: OptimizationTrialRef[];
  /**
   * Optimization-sourced traces in the same project attributed to NO trial. A
   * scoped view that leaks shows these, so a spec asserting scope must be able
   * to name them.
   */
  decoyTraceIds: string[];
}

export interface OptimizationRunFixtures {
  optimizationRun: OptimizationRunRef;
}

const OBJECTIVE = 'equals';

const DATASET_ITEMS = [
  { text: 'first review', label: 'positive' },
  { text: 'second review', label: 'negative' },
  { text: 'third review', label: 'positive' },
];

/**
 * Trace counts per trial, baseline first. Deliberately DISTINCT so a row set
 * swapped between two trials cannot pass on the count alone — a spec asserting
 * scope needs the counts to disagree.
 */
const TRIAL_TRACE_COUNTS = [3, 2];

/** Unattributed optimization traces. If a scope lock breaks, these flood the view. */
const DECOY_TRACE_COUNT = 7;

/**
 * A completed optimization run with a baseline and one numbered trial, each
 * owning a distinct set of traces, plus unattributed decoys in the same project.
 *
 * Seeded through the REST client rather than the SDK bridge on purpose: the
 * bridge only emits `source=sdk`, and the trial Logs overlay filters on
 * `source=optimization`, so bridge-built traces would be invisible there for the
 * wrong reason. Ids are minted up front with `uuid7()` because the REST writes
 * answer 204 with no body and callers assert on exact ids.
 *
 * The run is seeded, not launched — what these specs assert is which traces a
 * trial claims, which is independent of how an optimizer arrived at them. That
 * keeps the fixture deterministic and LLM-free.
 *
 * Teardown is here rather than in the spec so it survives a mid-test failure,
 * and it deletes more than the project does: `ProjectService.delete` removes only
 * the project row, so traces do NOT cascade, and the run-prefix sweep in
 * global-teardown does not know about optimizations.
 */
export const test = baseTest.extend<OptimizationRunFixtures>({
  optimizationRun: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const datasetName = `${testNamespace}-ds`;
    const optimizationId = uuid7();

    const dataset = await sdkClient.python.createDataset({
      project_name: project.name,
      name: datasetName,
      description: 'optimization trial logs scoping',
      items: DATASET_ITEMS as unknown as Array<Record<string, unknown>>,
    });

    const items = await backendClient.getDatasetItems(dataset.id);
    const datasetItemIds = items.map((i) => i.id);

    await backendClient.createOptimization({
      id: optimizationId,
      name: `${testNamespace}-opt`,
      datasetName,
      projectName: project.name,
      objectiveName: OBJECTIVE,
      status: 'completed',
    });

    const seedTraces = async (prefix: string, count: number): Promise<string[]> => {
      const ids: string[] = [];
      for (let i = 0; i < count; i++) {
        const id = uuid7();
        await backendClient.createTraceWithSource({
          id,
          projectName: project.name,
          name: `${testNamespace}-${prefix}-${i + 1}`,
          source: 'optimization',
          input: { text: `${prefix} input ${i + 1}` },
          output: { label: `${prefix} output ${i + 1}` },
        });
        ids.push(id);
      }
      return ids;
    };

    const trials: OptimizationTrialRef[] = [];
    for (let t = 0; t < TRIAL_TRACE_COUNTS.length; t++) {
      // step_index 0 is the run's baseline (rendered "Baseline", no trial
      // number); step_index 1 is the first numbered trial. candidate_id is what
      // the trials table groups rows on.
      const isBaseline = t === 0;
      const experimentId = uuid7();
      const slug = isBaseline ? 'baseline' : `trial${t}`;
      const traceIds = await seedTraces(slug, TRIAL_TRACE_COUNTS[t]);

      await backendClient.createExperiment({
        id: experimentId,
        name: isBaseline ? `${testNamespace}-baseline` : `${testNamespace}-trial-${t}`,
        datasetName,
        projectName: project.name,
        type: 'trial',
        optimizationId,
        metadata: {
          step_index: t,
          candidate_id: `${testNamespace}-cand-${isBaseline ? 'baseline' : t}`,
          parent_candidate_ids: isBaseline ? [] : [`${testNamespace}-cand-baseline`],
        },
      });
      await backendClient.createExperimentItems(
        traceIds.map((traceId, i) => ({
          experimentId,
          datasetItemId: datasetItemIds[i % datasetItemIds.length],
          traceId,
        })),
      );

      trials.push({
        label: isBaseline ? 'Baseline' : `Trial #${t}`,
        experimentId,
        traceIds,
      });
    }

    const decoyTraceIds = await seedTraces('decoy', DECOY_TRACE_COUNT);

    const ref: OptimizationRunRef = {
      optimizationId,
      datasetId: dataset.id,
      datasetName,
      datasetItemIds,
      projectId: project.id,
      projectName: project.name,
      trials,
      decoyTraceIds,
    };
    await testInfo.attach('opik.optimizationRun', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      const safe = async (what: string, fn: () => Promise<unknown>): Promise<void> => {
        try {
          await fn();
        } catch (err) {
          console.warn(`[optimizationRun fixture] delete warning for ${what}:`, err);
        }
      };
      // Experiments before the optimization they belong to, traces before the
      // dataset, so nothing is removed from under a still-referencing parent.
      for (const trial of trials) {
        await safe(`experiment ${trial.experimentId}`, () =>
          backendClient.deleteExperiment(trial.experimentId),
        );
      }
      await safe(`optimization ${optimizationId}`, () =>
        backendClient.deleteOptimization(optimizationId),
      );
      const allTraceIds = [...trials.flatMap((t) => t.traceIds), ...decoyTraceIds];
      await safe(`${allTraceIds.length} traces`, () =>
        backendClient.deleteTraces(allTraceIds),
      );
      await safe(`dataset ${datasetName}`, () => backendClient.deleteDataset(dataset.id));
    }
  },
});

export { expect } from './filterable-traces.fixture';
