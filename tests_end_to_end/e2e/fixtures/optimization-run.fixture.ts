import { test as baseTest } from './filterable-traces.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { deleteTracesResilient, uuid7 } from '../core/backend';

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
 * the project row (no cascade, no event), so traces survive it. The run-prefix
 * sweep in global-teardown covers experiments and optimizations by name, but it
 * never sweeps traces, so the trace deletes below are the only thing that
 * removes them.
 */
export const test = baseTest.extend<OptimizationRunFixtures>({
  optimizationRun: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const datasetName = `${testNamespace}-ds`;
    const optimizationId = uuid7();
    // Only true once the backend accepted the optimization: the id is minted
    // above (the REST writes echo no body), so the delete in `finally` must not
    // fire on an id that was never written.
    let optimizationCreated = false;

    const trials: OptimizationTrialRef[] = [];
    const decoyTraceIds: string[] = [];
    const allTraceIds: string[] = [];
    let datasetId: string | null = null;

    // Experiments before the optimization they belong to, traces before the
    // dataset, so nothing is removed from under a still-referencing parent.
    const safe = async (what: string, fn: () => Promise<unknown>): Promise<void> => {
      try {
        await fn();
      } catch (err) {
        console.warn(`[optimizationRun fixture] delete warning for ${what}:`, err);
      }
    };

    try {
      const dataset = await sdkClient.python.createDataset({
        project_name: project.name,
        name: datasetName,
        description: 'optimization trial logs scoping',
        items: DATASET_ITEMS as unknown as Array<Record<string, unknown>>,
      });
      datasetId = dataset.id;

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
      optimizationCreated = true;

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
          allTraceIds.push(id);
          ids.push(id);
        }
        return ids;
      };

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
        // Registered on create, before the item link below: that link can throw,
        // and the experiment row would then exist with nothing tracking it.
        trials.push({
          label: isBaseline ? 'Baseline' : `Trial #${t}`,
          experimentId,
          traceIds,
        });

        await backendClient.createExperimentItems(
          traceIds.map((traceId, i) => ({
            experimentId,
            datasetItemId: datasetItemIds[i % datasetItemIds.length],
            traceId,
          })),
        );
      }

      decoyTraceIds.push(...(await seedTraces('decoy', DECOY_TRACE_COUNT)));

      const ref: OptimizationRunRef = {
        optimizationId,
        datasetId,
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
    } finally {
      // Cleanup is governed by shouldLeaveArtifacts alone, so OPIK_LEAVE_FAILURES
      // keeps one meaning across the suite. Every delete is guarded by whether
      // its resource was actually written, so a failed seed tears down what it
      // made without 404 noise beside the original error. Traces last among the
      // children, then the dataset they hang off.
      if (!shouldLeaveArtifacts(testInfo)) {
        for (const trial of trials) {
          await safe(`experiment ${trial.experimentId}`, () =>
            backendClient.deleteExperiment(trial.experimentId),
          );
        }
        if (optimizationCreated) {
          await safe(`optimization ${optimizationId}`, () =>
            backendClient.deleteOptimization(optimizationId),
          );
        }
        if (allTraceIds.length > 0) {
          await deleteTracesResilient(backendClient, allTraceIds, 'optimizationRun fixture');
        }
        if (datasetId !== null) {
          const id = datasetId;
          await safe(`dataset ${datasetName}`, () =>
            backendClient.deleteDataset(id),
          );
        }
      }
    }
  },
});

export { expect } from './filterable-traces.fixture';
