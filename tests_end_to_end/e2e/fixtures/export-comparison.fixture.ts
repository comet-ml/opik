import { test as baseTest } from './bystander.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/**
 * How much filler each seeded payload carries. Has to be comfortably above the
 * backend's `responseFormatting.truncationSize` (1000 by default) so a
 * truncated read and a complete one are unmistakably different — the spec
 * asserts that difference rather than trusting this number.
 */
const FILLER_LENGTH = 4000;

/** The dataset column the grid sorts by, and the export echoes as `dataset.label`. */
export const EXPORT_LABEL_COLUMN = 'label';

/**
 * Four items whose label order, insertion order and search-matching are three
 * independent things.
 *
 *   insertion:      item-2  item-4  item-1  item-3
 *   matches search: yes     yes     yes     no      (item-3 is the decoy)
 *   matching, by label DESC: item-4  item-2  item-1
 *
 * That is the whole design. The matching subset in label-DESC order is not the
 * insertion order and not the label-ASC order, so an export that dropped the
 * grid's `sorting` — or applied it to the wrong field — cannot produce the
 * expected sequence by accident. And the decoy is what makes "the search
 * reached the export" falsifiable: without it, an export that ignored `search`
 * would return the same rows.
 */
const SEED_LABELS = ['item-2', 'item-4', 'item-1', 'item-3'] as const;
const DECOY_LABEL = 'item-3';

export interface ExportComparisonExperimentRef {
  experimentId: string;
  experimentName: string;
  /** The complete evaluation-task output, keyed by dataset item id. */
  outputsByItemId: Record<string, string>;
}

export interface ExportComparisonRef {
  datasetId: string;
  datasetName: string;
  /** Dataset item ids keyed by their `label` column value. */
  itemIdByLabel: Record<string, string>;
  /** The term the grid searches for; present in every row but the decoy. */
  searchTerm: string;
  /** The label of the row the search term must exclude. */
  decoyLabel: string;
  /** Every label, in the order the items were inserted. */
  insertionOrderLabels: string[];
  /** Labels the search matches, in the order a `data.label` DESC sort returns them. */
  matchingLabelsByLabelDesc: string[];
  /** The complete dataset `input` value, keyed by dataset item id. */
  datasetInputsByItemId: Record<string, string>;
  experiments: ExportComparisonExperimentRef[];
}

export interface ExportComparisonFixtures {
  exportComparison: ExportComparisonRef;
}

/**
 * Two experiments over four shared dataset items, every payload several
 * kilobytes long and every row tagged either with a search marker or with a
 * decoy marker.
 *
 * This is the shape the experiment-items export needs to be provable: big
 * enough that truncation is visible, ordered so a dropped sort is visible, and
 * with a row that must not survive the search.
 *
 * Traces are written through the REST client rather than the SDK bridge for the
 * same reason `jsonOutputExperiment` does it: the bridge's nested-trace helper
 * issues a `search_traces` per call to resolve the id it returns, and eight of
 * those in a row are reliably rate-limited. Writing the id means no read-back.
 *
 * Teardown lives here so it survives a mid-test failure: experiments, datasets
 * and traces all outlive the project they were created under.
 */
export const test = baseTest.extend<ExportComparisonFixtures>({
  exportComparison: async ({ sdkClient, backendClient, project, testNamespace }, use, testInfo) => {
    const datasetName = `${testNamespace}-export-ds`;
    const searchTerm = `${testNamespace}-match`;
    const decoyTerm = `${testNamespace}-decoy`;

    const markerFor = (label: string): string => (label === DECOY_LABEL ? decoyTerm : searchTerm);

    /**
     * Marker first and label last, with the filler in between: a value cut at
     * the truncation boundary keeps the marker and loses the tail, so a
     * truncated payload fails on content and not only on length.
     */
    const payload = (label: string, slot: string): string =>
      `${markerFor(label)}-${slot}-${'x'.repeat(FILLER_LENGTH)}-end-of-${slot}-${label}`;

    const dataset = await sdkClient.python.createDataset({
      project_name: project.name,
      name: datasetName,
      description: 'Experiment-items export: sort, search and untruncated payloads',
      items: SEED_LABELS.map((label) => ({
        [EXPORT_LABEL_COLUMN]: label,
        input: payload(label, 'input'),
        expected_output: payload(label, 'expected'),
      })) as unknown as Array<Record<string, unknown>>,
    });

    // Read the ids back rather than minting them: every expectation below is
    // keyed on the label, never on a position in the insert.
    const storedItems = await backendClient.getDatasetItems(dataset.id);
    const itemIdByLabel: Record<string, string> = {};
    for (const item of storedItems) {
      const label = item.data[EXPORT_LABEL_COLUMN];
      if (typeof label !== 'string') {
        throw new Error(
          `[exportComparison fixture] dataset item ${item.id} has no string "${EXPORT_LABEL_COLUMN}"`,
        );
      }
      itemIdByLabel[label] = item.id;
    }
    if (Object.keys(itemIdByLabel).length !== SEED_LABELS.length) {
      throw new Error(
        `[exportComparison fixture] expected ${SEED_LABELS.length} distinct labels, ` +
          `got ${Object.keys(itemIdByLabel).length}`,
      );
    }

    const traceIds: string[] = [];
    const experiments: ExportComparisonExperimentRef[] = [];

    for (const slot of ['a', 'b']) {
      const experimentName = `${testNamespace}-export-exp-${slot}`;
      const experimentId = uuid7();
      const outputsByItemId: Record<string, string> = {};

      const links: Array<{ experimentId: string; datasetItemId: string; traceId: string }> = [];
      for (const label of SEED_LABELS) {
        const answer = payload(label, `output-${slot}`);
        const traceId = await backendClient.createTraceWithSource({
          id: uuid7(),
          projectName: project.name,
          name: `${testNamespace}-trace-${slot}-${label}`,
          source: 'sdk',
          input: { question: payload(label, `question-${slot}`) },
          output: { answer },
        });
        traceIds.push(traceId);
        outputsByItemId[itemIdByLabel[label]] = answer;
        links.push({ experimentId, datasetItemId: itemIdByLabel[label], traceId });
      }

      await backendClient.createExperiment({
        id: experimentId,
        name: experimentName,
        datasetName,
        projectName: project.name,
      });
      await backendClient.createExperimentItems(links);

      experiments.push({ experimentId, experimentName, outputsByItemId });
    }

    const matchingLabels = SEED_LABELS.filter((label) => label !== DECOY_LABEL);

    const ref: ExportComparisonRef = {
      datasetId: dataset.id,
      datasetName,
      itemIdByLabel,
      searchTerm,
      decoyLabel: DECOY_LABEL,
      insertionOrderLabels: [...SEED_LABELS],
      matchingLabelsByLabelDesc: [...matchingLabels].sort((a, b) => b.localeCompare(a)),
      datasetInputsByItemId: Object.fromEntries(
        SEED_LABELS.map((label) => [itemIdByLabel[label], payload(label, 'input')]),
      ),
      experiments,
    };

    await testInfo.attach('opik.exportComparison', {
      // The payloads are kilobytes of filler; the attachment is for reading the
      // ids and the expected order, so record their lengths instead.
      body: JSON.stringify(
        {
          ...ref,
          datasetInputsByItemId: Object.fromEntries(
            Object.entries(ref.datasetInputsByItemId).map(([id, v]) => [id, `${v.length} chars`]),
          ),
          experiments: ref.experiments.map((e) => ({
            experimentId: e.experimentId,
            experimentName: e.experimentName,
            outputsByItemId: Object.fromEntries(
              Object.entries(e.outputsByItemId).map(([id, v]) => [id, `${v.length} chars`]),
            ),
          })),
        },
        null,
        2,
      ),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      const safe = async (what: string, fn: () => Promise<unknown>): Promise<void> => {
        try {
          await fn();
        } catch (err) {
          console.warn(`[exportComparison fixture] delete warning for ${what}:`, err);
        }
      };
      // Experiments before the dataset they reference; traces last, because they
      // do not go away with either.
      for (const experiment of experiments) {
        await safe(`experiment ${experiment.experimentName}`, () =>
          backendClient.deleteExperiment(experiment.experimentId),
        );
      }
      await safe(`dataset ${datasetName}`, () => backendClient.deleteDataset(dataset.id));
      await safe('traces', () => backendClient.deleteTraces(traceIds));
    }
  },
});

export { expect } from './bystander.fixture';
