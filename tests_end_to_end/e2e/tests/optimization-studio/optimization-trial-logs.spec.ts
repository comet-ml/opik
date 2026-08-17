import { test, expect } from '@e2e/fixtures';
import { uuid7 } from '@e2e/core/backend';
import { OptimizationStudioPage } from '@e2e/pom/optimization-studio.page';

/**
 * A trial's Logs overlay is locked to that trial (OPIK-6739 / OPIK-7842).
 *
 * The overlay narrows the project's traces to the open trial's experiments via
 * a *locked* scope — one the filter bar cannot express and therefore cannot
 * hold if it is ever routed through the editable filter key again. The failure
 * mode that matters is attribution: if the lock stops holding, the overlay
 * presents every optimization trace in the project as this trial's, and there
 * is nothing on screen that says otherwise.
 *
 * So this asserts exact trace ids, not a row count, and seeds decoys —
 * same project, same `source=optimization`, no experiment — so a leak fails
 * loudly instead of passing on a lucky count.
 *
 * Deterministic and LLM-free: the run is seeded through the REST API rather
 * than launched, because what is under test is which traces a trial claims,
 * which has nothing to do with how the optimizer got there.
 */

const OBJECTIVE = 'equals';
const DATASET_ITEMS = [
  { text: 'first review', label: 'positive' },
  { text: 'second review', label: 'negative' },
  { text: 'third review', label: 'positive' },
];

/** Baseline gets 3 traces, Trial #1 gets 2 — distinct counts, so a row set
 *  swapped between the two cannot pass on the count alone. */
const BASELINE_TRACE_COUNT = 3;
const TRIAL_ONE_TRACE_COUNT = 2;
/** Unattributed optimization traces in the same project. If the lock breaks,
 *  these are what flood the overlay. */
const DECOY_TRACE_COUNT = 7;

test.describe('Optimization trial logs — CUJ', { tag: ['@t2-cuj', '@area:optimization-studio'] }, () => {
  test('each trial\'s Logs overlay lists exactly that trial\'s traces', { tag: ['@cap:optimization-studio.trial-detail'] }, async ({
    sdkClient,
    backendClient,
    project,
    testNamespace,
    page,
  }) => {
    test.setTimeout(300_000);

    const datasetName = `${testNamespace}-ds`;
    const optimizationId = uuid7();
    const baselineExperimentId = uuid7();
    const trialOneExperimentId = uuid7();

    const dataset = await test.step('Seed a dataset for the run', async () =>
      sdkClient.python.createDataset({
        project_name: project.name,
        name: datasetName,
        description: 'optimization trial logs scoping',
        items: DATASET_ITEMS as unknown as Array<Record<string, unknown>>,
      }));

    const datasetItemIds = await test.step('Read the dataset item ids back', async () => {
      const items = await backendClient.getDatasetItems(dataset.id);
      expect(items, 'dataset items are queryable').toHaveLength(DATASET_ITEMS.length);
      return items.map((i) => i.id);
    });

    await test.step('Seed a completed optimization run', async () => {
      await backendClient.createOptimization({
        id: optimizationId,
        name: `${testNamespace}-opt`,
        datasetName,
        projectName: project.name,
        objectiveName: OBJECTIVE,
        status: 'completed',
      });
    });

    /** Traces must carry `source=optimization`: the overlay passes
     *  `logsSource=optimization`, which the read turns into a source filter, so
     *  an sdk-sourced trace would be invisible there for the wrong reason. */
    const seedOptimizationTraces = async (prefix: string, count: number): Promise<string[]> => {
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

    const baselineTraceIds = await test.step(
      `Seed the baseline's ${BASELINE_TRACE_COUNT} traces`,
      async () => seedOptimizationTraces('baseline', BASELINE_TRACE_COUNT),
    );
    const trialOneTraceIds = await test.step(
      `Seed trial #1's ${TRIAL_ONE_TRACE_COUNT} traces`,
      async () => seedOptimizationTraces('trial1', TRIAL_ONE_TRACE_COUNT),
    );
    const decoyTraceIds = await test.step(
      `Seed ${DECOY_TRACE_COUNT} unattributed optimization traces (the decoys)`,
      async () => seedOptimizationTraces('decoy', DECOY_TRACE_COUNT),
    );

    await test.step('Create the baseline and trial #1 experiments and link their traces', async () => {
      // step_index 0 is the run's baseline (rendered "Baseline", no trial
      // number); step_index 1 is the first numbered trial. candidate_id is what
      // the trials table groups rows on.
      await backendClient.createExperiment({
        id: baselineExperimentId,
        name: `${testNamespace}-baseline`,
        datasetName,
        projectName: project.name,
        type: 'trial',
        optimizationId,
        metadata: {
          step_index: 0,
          candidate_id: `${testNamespace}-cand-baseline`,
          parent_candidate_ids: [],
        },
      });
      await backendClient.createExperimentItems(
        baselineTraceIds.map((traceId, i) => ({
          experimentId: baselineExperimentId,
          datasetItemId: datasetItemIds[i % datasetItemIds.length],
          traceId,
        })),
      );

      await backendClient.createExperiment({
        id: trialOneExperimentId,
        name: `${testNamespace}-trial-1`,
        datasetName,
        projectName: project.name,
        type: 'trial',
        optimizationId,
        metadata: {
          step_index: 1,
          candidate_id: `${testNamespace}-cand-1`,
          parent_candidate_ids: [`${testNamespace}-cand-baseline`],
        },
      });
      await backendClient.createExperimentItems(
        trialOneTraceIds.map((traceId, i) => ({
          experimentId: trialOneExperimentId,
          datasetItemId: datasetItemIds[i % datasetItemIds.length],
          traceId,
        })),
      );
    });

    await test.step('The decoys really are in scope for the project (fixture sanity)', async () => {
      // If the decoys were not visible to an unscoped optimization read, the
      // whole leak-detection premise of this test would be vacuous.
      await expect
        .poll(
          async () => {
            const ids = await backendClient.listTraceIds({ projectId: project.id });
            return decoyTraceIds.filter((d) => ids.includes(d)).length;
          },
          { timeout: 120_000, intervals: [1_000, 2_000, 5_000] },
        )
        .toBe(DECOY_TRACE_COUNT);
    });

    const studio = new OptimizationStudioPage(page, project.id);

    await test.step('Open the run and its Trials tab', async () => {
      await studio.gotoDetail(optimizationId);
      await studio.openTrialsTab();
      await expect
        .poll(async () => studio.trialRowCount(), {
          timeout: 60_000,
          intervals: [1_000, 2_000, 5_000],
        })
        .toBe(2);
    });

    await test.step('The baseline\'s Logs overlay lists exactly its 3 traces', async () => {
      await studio.openTrialByLabel('Baseline');
      const overlay = await studio.openTrialLogs();

      await overlay.waitForTraceRows(BASELINE_TRACE_COUNT);
      const listed = await overlay.readTraceIds();
      expect([...listed].sort(), 'the overlay lists exactly the baseline traces').toEqual(
        [...baselineTraceIds].sort(),
      );
      for (const id of [...decoyTraceIds, ...trialOneTraceIds]) {
        expect(listed, `trace ${id} belongs to another scope and must not appear`).not.toContain(id);
      }

      await expect(
        overlay.scopeChip('Baseline'),
        'the locked-scope chip names the trial — without it the narrowing is silent',
      ).toBeVisible();

      await overlay.close();
    });

    await test.step('Trial #1\'s Logs overlay lists exactly its 2 traces', async () => {
      await studio.openTrialByLabel('Trial #1');
      const overlay = await studio.openTrialLogs();

      await overlay.waitForTraceRows(TRIAL_ONE_TRACE_COUNT);
      const listed = await overlay.readTraceIds();
      expect([...listed].sort(), 'the overlay lists exactly trial #1\'s traces').toEqual(
        [...trialOneTraceIds].sort(),
      );
      for (const id of [...decoyTraceIds, ...baselineTraceIds]) {
        expect(listed, `trace ${id} belongs to another scope and must not appear`).not.toContain(id);
      }

      await expect(overlay.scopeChip('Trial #1'), 'the locked-scope chip names the trial')
        .toBeVisible();
    });

    await test.step('Cleanup: the run, its experiments and the dataset', async () => {
      await backendClient.deleteExperiment(baselineExperimentId);
      await backendClient.deleteExperiment(trialOneExperimentId);
      await backendClient.deleteOptimization(optimizationId);
      await backendClient.deleteDataset(dataset.id);
    });
  });
});
