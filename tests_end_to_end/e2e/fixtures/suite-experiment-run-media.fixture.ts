import { test as baseTest } from './experiment-output-attachment.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';
import {
  RED_PNG_BASE64,
  BLUE_PNG_BASE64,
  RED_PNG_URL,
  BLUE_PNG_URL,
} from './experiment-image-output.fixture';

/**
 * One run of a multi-run evaluation-suite item: its own text and its own
 * picture, so a panel that showed the other run's picture beside this run's
 * text is a failure the spec can name.
 */
export interface SuiteRunSeed {
  traceId: string;
  /** A token unique to this run, written into the output text. */
  marker: string;
  /** The whole output string as written to the trace. */
  rawOutput: string;
  /** The output text as the panel must render it, placeholder resolved. */
  expectedText: string;
  /** The data URL the run's single thumbnail must carry. */
  expectedUrl: string;
}

export interface SuiteExperimentRunMediaRef {
  datasetId: string;
  datasetName: string;
  experimentId: string;
  experimentName: string;
  projectId: string;
  /** The one dataset item both runs are linked to. */
  datasetItemId: string;
  /** The two runs, in seed order — NOT necessarily the order the tabs show. */
  runs: [SuiteRunSeed, SuiteRunSeed];
}

export interface SuiteExperimentRunMediaFixtures {
  suiteExperimentRunMedia: SuiteExperimentRunMediaRef;
}

const QUERYABLE_TIMEOUT_MS = 120_000;
const QUERYABLE_POLL_MS = 2_000;
const READABLE_TIMEOUT_MS = 30_000;

/**
 * An evaluation-suite experiment with ONE dataset item run TWICE, each run
 * carrying a different inline image in its output.
 *
 * Why `evaluation_method: 'evaluation_suite'` and not a real test-suite run:
 * the method is the only thing that decides which sidebar the Items tab mounts
 * (`isTestSuiteExperiment` reads it off the first experiment), and reaching
 * `TestSuiteExperimentPanel` through an actual suite run would mean an LLM
 * judging assertions — neither deterministic nor free. Seeding the method
 * directly puts the panel on screen with a payload written down in full.
 *
 * Why two experiment items on ONE dataset item: that is what makes
 * `MultiRunTabs` render at all (`experimentItems.length <= 1` short-circuits to
 * a single body), and the multi-run axis is the half worth pinning. This
 * sidebar is a SECOND call site of `useExperimentItemMedia`, wired differently
 * from the compare panel: it takes the project from the experiments LIST
 * (`experimentProjectIdMap`) rather than from `useExperimentById`, so one can
 * break while the other keeps working.
 *
 * Each run gets its own colour AND its own marker text. The pairing is the
 * assertion: run 2 rendering run 1's picture is silent wrongness — a perfectly
 * normal-looking panel — and only a per-run text/image pair can catch it. Two
 * runs that merely differed in text, or merely in picture, could not.
 *
 * Teardown deletes the experiment, then the dataset, then the traces — none of
 * the three cascades with the project.
 */
export const test = baseTest.extend<SuiteExperimentRunMediaFixtures>({
  suiteExperimentRunMedia: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const datasetName = `${testNamespace}-suite-ds`;
    const experimentName = `${testNamespace}-suite-exp`;
    const experimentId = uuid7();
    const datasetItemId = uuid7();

    const runs: [SuiteRunSeed, SuiteRunSeed] = [
      buildRun(uuid7(), 'alpha-run', RED_PNG_BASE64, RED_PNG_URL),
      buildRun(uuid7(), 'beta-run', BLUE_PNG_BASE64, BLUE_PNG_URL),
    ];

    const dataset = await sdkClient.python.createDataset({
      project_name: project.name,
      name: datasetName,
      description: 'multi-run output media in the evaluation-suite item sidebar',
    });

    let seededTraces = false;
    try {
      await backendClient.writeDatasetItemsBatch({
        datasetId: dataset.id,
        items: [{ id: datasetItemId, data: { input: 'run-twice' } }],
      });

      await backendClient.createTracesBatch({
        projectName: project.name,
        traces: runs.map((run) => ({
          id: run.traceId,
          name: `${testNamespace}-suite-trace-${run.marker}`,
          input: { input: 'run-twice' },
          output: { output: run.rawOutput },
        })),
      });
      seededTraces = true;

      for (const run of runs) {
        await waitForStoredOutput(backendClient, run.marker, run.traceId, run.rawOutput);
      }

      await backendClient.createExperiment({
        id: experimentId,
        name: experimentName,
        datasetName,
        projectName: project.name,
        evaluationMethod: 'evaluation_suite',
      });

      // Both runs against the SAME dataset item — this is what makes the panel
      // render run tabs instead of a single body.
      await backendClient.createExperimentItems(
        runs.map((run) => ({ experimentId, datasetItemId, traceId: run.traceId })),
      );

      // Prove the seed can discriminate before any browser opens.
      //
      // `evaluation_method` decides which of the two sidebars mounts, and
      // `project_id` is what this one hands to `useExperimentItemMedia`. If
      // either failed to land, the spec below would be asserting against the
      // WRONG PANEL, or against a panel whose media lookup is disabled — and
      // both of those read as the rendering defect this exists to catch rather
      // than as the setup fault they would be.
      const rendered = await backendClient.getExperimentRenderFields(experimentId);
      if (rendered.evaluationMethod !== 'evaluation_suite') {
        throw new Error(
          `[suiteExperimentRunMedia fixture] experiment ${experimentId} stored ` +
            `evaluation_method=${rendered.evaluationMethod}, not 'evaluation_suite' — the ` +
            'Items tab would mount CompareExperimentsPanel instead of the suite sidebar',
        );
      }
      if (rendered.projectId !== project.id) {
        throw new Error(
          `[suiteExperimentRunMedia fixture] experiment ${experimentId} stored ` +
            `project_id=${rendered.projectId}, expected ${project.id} — the sidebar sources the ` +
            'attachment project from here',
        );
      }

      await waitForRuns(backendClient, dataset.id, experimentId, datasetItemId, runs.length);

      const ref: SuiteExperimentRunMediaRef = {
        datasetId: dataset.id,
        datasetName,
        experimentId,
        experimentName,
        projectId: project.id,
        datasetItemId,
        runs,
      };

      await testInfo.attach('opik.suiteExperimentRunMedia', {
        body: JSON.stringify(
          { ...ref, runs: runs.map((r) => ({ ...r, rawOutput: '<elided>' })) },
          null,
          2,
        ),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo)) {
        const safe = async (what: string, fn: () => Promise<unknown>): Promise<void> => {
          try {
            await fn();
          } catch (err) {
            console.warn(`[suiteExperimentRunMedia fixture] delete warning for ${what}:`, err);
          }
        };
        await safe(`experiment ${experimentName}`, () =>
          backendClient.deleteExperiment(experimentId),
        );
        await safe(`dataset ${datasetName}`, () => backendClient.deleteDataset(dataset.id));
        if (seededTraces) {
          await safe('2 traces', () => backendClient.deleteTraces(runs.map((r) => r.traceId)));
        }
      }
    }
  },
});

/**
 * One run's seed.
 *
 * Both runs number their single image `[image_0]`, which is deliberate: the
 * token is held constant so that the only thing distinguishing the two tabs is
 * the picture the token resolves to, which is exactly the mapping under test.
 * Only one run is mounted at a time, so the shared alt text cannot collide.
 */
function buildRun(
  traceId: string,
  marker: string,
  base64: string,
  expectedUrl: string,
): SuiteRunSeed {
  return {
    traceId,
    marker,
    rawOutput: `${marker}: ${base64}`,
    expectedText: `${marker}: [image_0]`,
    expectedUrl,
  };
}

/** Block until one trace reads back with its output byte-for-byte as written. */
async function waitForStoredOutput(
  backendClient: {
    getTracePayload: (traceId: string) => Promise<{ output: unknown } | null>;
  },
  label: string,
  traceId: string,
  expected: string,
): Promise<void> {
  const start = Date.now();
  let stored: unknown;
  let seen = false;
  while (Date.now() - start < READABLE_TIMEOUT_MS) {
    const payload = await backendClient.getTracePayload(traceId);
    if (payload !== null) {
      seen = true;
      stored = (payload.output as { output?: unknown } | null)?.output;
      if (stored === expected) return;
    }
    await new Promise((r) => setTimeout(r, QUERYABLE_POLL_MS));
  }
  throw new Error(
    `[suiteExperimentRunMedia fixture] the ${label} trace ${traceId} ` +
      (seen
        ? `did not store its output verbatim: expected ${expected.length} chars, got ` +
          `${typeof stored === 'string' ? `${stored.length} chars` : typeof stored}`
        : 'never became readable') +
      ` after ${Date.now() - start}ms`,
  );
}

/**
 * Block until the compare read reports the one row carrying BOTH runs.
 *
 * The row count alone is not enough here: the panel renders tabs off
 * `experiment_items`, so a row that has landed with only one of its two runs
 * would show a single body and the spec would fail looking for a tab list.
 */
async function waitForRuns(
  backendClient: {
    compareItemsPairedPage: (args: {
      datasetId: string;
      experimentIds: string[];
      page: number;
      size: number;
    }) => Promise<{ rows: Array<{ id: string; experimentItems: Array<{ traceId: string }> }> }>;
  },
  datasetId: string,
  experimentId: string,
  datasetItemId: string,
  expected: number,
): Promise<void> {
  const start = Date.now();
  let seen: number | string = 'no row yet';
  while (Date.now() - start < QUERYABLE_TIMEOUT_MS) {
    const answer = await backendClient.compareItemsPairedPage({
      datasetId,
      experimentIds: [experimentId],
      page: 1,
      size: 10,
    });
    const row = answer.rows.find((r) => r.id === datasetItemId);
    if (row) {
      seen = row.experimentItems.length;
      if (seen === expected) return;
    }
    await new Promise((r) => setTimeout(r, QUERYABLE_POLL_MS));
  }
  throw new Error(
    `[suiteExperimentRunMedia fixture] dataset item ${datasetItemId} reported ${seen} runs, ` +
      `expected ${expected}, after ${Date.now() - start}ms`,
  );
}

export { expect } from './experiment-output-attachment.fixture';
