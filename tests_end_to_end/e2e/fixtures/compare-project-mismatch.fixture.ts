import { test as baseTest } from './id-aged-spans.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/** How many rows the comparison holds. Small: the subject is per-row data, not paging. */
const ITEM_COUNT = 4;

/** Token usage on each span, so the rows carry a non-null cost as well as a duration. */
const SPAN_USAGE = { prompt_tokens: 12, completion_tokens: 8, total_tokens: 20 };

/** A model with a published price, so `total_estimated_cost` resolves to a real number. */
const SPAN_MODEL = 'gpt-3.5-turbo';
const SPAN_PROVIDER = 'openai';

/** Fixed durations, so a blanked cell is distinguishable from a zero one. */
const TRACE_DURATION_MS = 1_500;

export interface MismatchedItemRef {
  datasetItemId: string;
  traceId: string;
  input: Record<string, string>;
  output: Record<string, string>;
}

export interface CompareProjectMismatchRef {
  datasetId: string;
  datasetName: string;
  experimentId: string;
  experimentName: string;
  /** Where the traces were actually logged. */
  tracesProjectId: string;
  tracesProjectName: string;
  /** The project the experiment ITEMS name, and where nothing was ever logged. */
  namedOnlyProjectId: string;
  namedOnlyProjectName: string;
  items: MismatchedItemRef[];
}

export interface CompareProjectMismatchFixtures {
  compareProjectMismatch: CompareProjectMismatchRef;
}

const QUERYABLE_TIMEOUT_MS = 120_000;
const QUERYABLE_POLL_MS = 2_000;

/**
 * An experiment whose items name a DIFFERENT project from the one their traces
 * were logged in.
 *
 * Not a contrived state: `ExperimentItemService` fills
 * `experiment_items.project_id` from the trace only when the item named no
 * project of its own, so an SDK caller that passes a project name gets exactly
 * this — the item's project and its trace's project disagree, legitimately and
 * permanently.
 *
 * It matters because of what OPIK-8274 added underneath the compare read: a
 * cached set of "target projects" that `traces`, `spans` and `comments` are
 * pruned by. The set is derived from the TRACES table
 * (`SELECT DISTINCT project_id FROM traces WHERE id IN <the items' trace ids>`),
 * which is the right source. Deriving it from the denormalized
 * `experiment_items.project_id` instead would produce a set containing only the
 * named-only project — where nothing was ever logged — and every row's trace
 * data would be pruned away. That failure does not error: the rows still come
 * back, with the right ids and the right count, and their input, output,
 * duration and cost cells are simply empty.
 *
 * The named-only project is deliberately left EMPTY. If it held traces of its
 * own, a read that pruned by the wrong set might still find something, and the
 * assertion would lose its edge.
 *
 * Each trace carries a span with usage and a priced model, so the rows have a
 * non-null cost and duration to lose — an assertion on input and output alone
 * would miss a pruning that only reached the `spans` join.
 *
 * The experiment is created `running` so it stays off the aggregated branch,
 * which is where OPIK-8274's new lookup lives.
 *
 * Teardown deletes the experiment, the dataset, the traces and the extra
 * project; only the `project` fixture's own project is cleaned up for us.
 */
export const test = baseTest.extend<CompareProjectMismatchFixtures>({
  compareProjectMismatch: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const datasetName = `${testNamespace}-mismatch-ds`;
    const experimentName = `${testNamespace}-mismatch-exp`;
    const namedOnlyProjectName = `${testNamespace}-named-only-proj`;
    const experimentId = uuid7();

    const items: MismatchedItemRef[] = Array.from({ length: ITEM_COUNT }, (_, i) => ({
      datasetItemId: uuid7(),
      traceId: uuid7(),
      input: { q: `question ${i}` },
      // Keyed `output`, not something arbitrary: the compare grid derives a
      // dynamic column per output key, so the key decides the column id the
      // spec addresses (`output_output`). Matching the estate's other
      // comparison fixtures keeps that id the conventional one.
      output: { output: `answer ${i}` },
    }));

    await backendClient.createProject(namedOnlyProjectName);
    const namedOnly = (await backendClient.listProjectsWithPrefix(namedOnlyProjectName))[0];
    if (!namedOnly) {
      throw new Error(
        `[compareProjectMismatch fixture] the named-only project ${namedOnlyProjectName} ` +
          'was created but does not list — cannot assert on a project id it does not have',
      );
    }

    const dataset = await sdkClient.python.createDataset({
      project_name: project.name,
      name: datasetName,
      description: 'experiment items naming a project other than their traces\'',
    });

    let seededTraces = false;
    try {
      await backendClient.writeDatasetItemsBatch({
        datasetId: dataset.id,
        items: items.map((item) => ({ id: item.datasetItemId, data: item.input })),
      });

      const base = Date.now() - 60_000;
      await backendClient.createTracesBatch({
        projectName: project.name,
        traces: items.map((item, i) => ({
          id: item.traceId,
          name: `${testNamespace}-mismatch-trace-${i}`,
          input: item.input,
          output: item.output,
          startTime: new Date(base),
          endTime: new Date(base + TRACE_DURATION_MS),
        })),
      });
      seededTraces = true;

      // One priced LLM span per trace, so the row has a cost to lose as well as
      // a duration. Written into the TRACES' project, which is the whole point.
      for (const [i, item] of items.entries()) {
        await backendClient.createSpan({
          id: uuid7(),
          traceId: item.traceId,
          projectName: project.name,
          name: `${testNamespace}-mismatch-span-${i}`,
          source: 'sdk',
          type: 'llm',
          input: item.input,
          output: item.output,
          model: SPAN_MODEL,
          provider: SPAN_PROVIDER,
          usage: SPAN_USAGE,
          startTime: new Date(base),
          endTime: new Date(base + TRACE_DURATION_MS),
        });
      }

      await backendClient.createExperiment({
        id: experimentId,
        name: experimentName,
        datasetName,
        projectName: project.name,
        status: 'running',
      });

      // The mismatch itself: every item names the EMPTY project, while its
      // trace lives in the fixture project.
      await backendClient.createExperimentItems(
        items.map((item) => ({
          experimentId,
          datasetItemId: item.datasetItemId,
          traceId: item.traceId,
          projectName: namedOnlyProjectName,
        })),
      );

      // Prove the mismatch is real before any assertion depends on it.
      //
      // Two distinct projects, and the named-only one genuinely empty: if the
      // traces had landed there too, or if the two names had resolved to one
      // project, the spec below would pass without ever exercising the pruning
      // it exists for — and would read as coverage forever.
      if (namedOnly.id === project.id) {
        throw new Error(
          '[compareProjectMismatch fixture] the named-only project resolved to the same id as ' +
            'the traces\' project; there is no mismatch to test',
        );
      }
      const strayTraces = await backendClient.listTraceIds({ projectId: namedOnly.id });
      if (strayTraces.length !== 0) {
        throw new Error(
          `[compareProjectMismatch fixture] the named-only project ${namedOnlyProjectName} must ` +
            `hold no traces, it holds ${strayTraces.length}`,
        );
      }

      await waitForRows(backendClient, dataset.id, experimentId, ITEM_COUNT);

      const ref: CompareProjectMismatchRef = {
        datasetId: dataset.id,
        datasetName,
        experimentId,
        experimentName,
        tracesProjectId: project.id,
        tracesProjectName: project.name,
        namedOnlyProjectId: namedOnly.id,
        namedOnlyProjectName,
        items,
      };

      await testInfo.attach('opik.compareProjectMismatch', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo)) {
        const safe = async (what: string, fn: () => Promise<unknown>): Promise<void> => {
          try {
            await fn();
          } catch (err) {
            console.warn(`[compareProjectMismatch fixture] delete warning for ${what}:`, err);
          }
        };
        await safe(`experiment ${experimentName}`, () =>
          backendClient.deleteExperiment(experimentId),
        );
        await safe(`dataset ${datasetName}`, () => backendClient.deleteDataset(dataset.id));
        if (seededTraces) {
          await safe(`${items.length} traces`, () =>
            backendClient.deleteTraces(items.map((i) => i.traceId)),
          );
        }
        await safe(`project ${namedOnlyProjectName}`, () =>
          backendClient.deleteProject(namedOnly.id),
        );
      }
    }
  },
});

/** Block until the comparison reports exactly `expected` rows. */
async function waitForRows(
  backendClient: {
    compareItemsPage: (args: {
      datasetId: string;
      experimentIds: string[];
      size?: number;
    }) => Promise<{ total: number }>;
  },
  datasetId: string,
  experimentId: string,
  expected: number,
): Promise<void> {
  const start = Date.now();
  let seen: number | string = 'no answer yet';
  while (Date.now() - start < QUERYABLE_TIMEOUT_MS) {
    seen = (
      await backendClient.compareItemsPage({ datasetId, experimentIds: [experimentId], size: 1 })
    ).total;
    if (seen === expected) return;
    await new Promise((r) => setTimeout(r, QUERYABLE_POLL_MS));
  }
  throw new Error(
    `[compareProjectMismatch fixture] experiment ${experimentId} reported ${seen} rows, ` +
      `expected ${expected}, after ${Date.now() - start}ms`,
  );
}

export { expect } from './id-aged-spans.fixture';
