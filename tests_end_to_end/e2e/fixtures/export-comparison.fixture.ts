import { test as baseTest } from './grouped-dataset.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/** The page size the export specs drive the compare grid at. */
export const EXPORT_PAGE_SIZE = 5;

/** The feedback score the grid is sorted by. No underscore: the front end maps
 * the tanstack column id `feedback_scores_<name>` back to the wire field
 * `feedback_scores.<name>` by replacing the FIRST underscore after the prefix,
 * so a score name containing one would not survive the round trip. */
export const EXPORT_SCORE_NAME = 'accuracy';

/** The term the search half of the spec types into the toolbar. */
export const EXPORT_SEARCH_TERM = 'mango';

interface ExportSeedRow {
  /** Goes into the item's `input`, and so into the export's `dataset.input`. */
  label: string;
  /** The only field the search term matches. */
  topic: string;
  /** This row's `accuracy` score, identical on both experiments. */
  accuracy: number;
}

/**
 * Twelve dataset items — three pages at `EXPORT_PAGE_SIZE` — shaped so that a
 * selection made in a searched or sorted grid is provably absent from the page
 * the grid would have fetched without the search or the sort.
 *
 * Two independent properties are seeded, and both are re-checked against the
 * live API below rather than assumed:
 *
 *  - `mango` tags exactly two rows, and they are the 6th and 7th items inserted.
 *    Whichever direction the backend's default item order runs in, position 6
 *    and 7 of twelve are off page 1 — so the unsearched page-1 window can never
 *    contain them.
 *  - `accuracy` descending is a different permutation from the default order,
 *    chosen so page 2 of the score-sorted grid shares no row with page 2 of the
 *    default grid.
 *
 * Both experiments score every item identically, so the grid's sort is a total
 * order over the rows no matter how the backend folds two experiments' scores
 * into one sortable value. Only the evaluation-task outputs differ per
 * experiment, which is what makes the compare view worth exporting at all.
 */
const SEED_ROWS: ExportSeedRow[] = [
  { label: 'row-01', topic: 'apple', accuracy: 0.5 },
  { label: 'row-02', topic: 'pear', accuracy: 0.36 },
  { label: 'row-03', topic: 'plum', accuracy: 0.99 },
  { label: 'row-04', topic: 'fig', accuracy: 0.71 },
  { label: 'row-05', topic: 'kiwi', accuracy: 0.85 },
  { label: 'row-06', topic: EXPORT_SEARCH_TERM, accuracy: 0.78 },
  { label: 'row-07', topic: EXPORT_SEARCH_TERM, accuracy: 0.92 },
  { label: 'row-08', topic: 'melon', accuracy: 0.22 },
  { label: 'row-09', topic: 'peach', accuracy: 0.57 },
  { label: 'row-10', topic: 'date', accuracy: 0.29 },
  { label: 'row-11', topic: 'grape', accuracy: 0.43 },
  { label: 'row-12', topic: 'lime', accuracy: 0.64 },
];

const EXPERIMENT_SUFFIXES = ['a', 'b'] as const;

export interface ExportComparisonExperimentRef {
  experimentId: string;
  experimentName: string;
}

export interface ExportComparisonRef {
  datasetId: string;
  datasetName: string;
  projectName: string;
  experiments: ExportComparisonExperimentRef[];
  /** Dataset item id per seeded `label`. */
  itemIdByLabel: Record<string, string>;
  /** Seeded `label` per dataset item id — the inverse, for reading row order. */
  labelByItemId: Record<string, string>;
  /** Labels in the order the grid renders them with no search and no sort. */
  defaultOrderLabels: string[];
  /** Labels in the order the grid renders them sorted by `accuracy` descending. */
  scoreSortedLabels: string[];
  /** The two labels the search term matches, in default order. */
  searchMatchLabels: string[];
  /**
   * Two labels that sit on page 2 of the score-sorted grid and on no page-2 of
   * the default grid — i.e. rows the pre-fix export refetch (which kept `page`
   * but dropped `sorting`) could not have returned.
   */
  sortedPageTwoSelectionLabels: string[];
}

export interface ExportComparisonFixtures {
  exportComparison: ExportComparisonRef;
}

/** Bounded retry for a read that has to wait on analytics-store consistency. */
async function pollFor<T>(
  what: string,
  read: () => Promise<T>,
  isReady: (value: T) => boolean,
  timeoutMs = 60_000,
): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  let last: T = await read();
  while (!isReady(last)) {
    if (Date.now() > deadline) {
      throw new Error(
        `[exportComparison fixture] timed out waiting for ${what}; last read: ${JSON.stringify(last)}`,
      );
    }
    await new Promise((resolve) => setTimeout(resolve, 1_000));
    last = await read();
  }
  return last;
}

/**
 * One dataset and two experiments over it, sized and scored so the experiment
 * comparison grid's *export* can be told apart from a broken one.
 *
 * Seeded through the SDK bridge (the dataset) and the REST client (traces,
 * scores, experiment linkage): the linkage and the per-item score values are
 * what the grid reads, and an evaluator run can produce neither on demand.
 */
export const test = baseTest.extend<ExportComparisonFixtures>({
  exportComparison: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const datasetName = `${testNamespace}-export-ds`;

    const dataset = await sdkClient.python.createDataset({
      project_name: project.name,
      name: datasetName,
      description: 'Export of a searched/sorted experiment comparison grid',
      items: SEED_ROWS.map((row) => ({
        input: row.label,
        topic: row.topic,
        expected_output: `answer for ${row.label}`,
      })) as unknown as Array<Record<string, unknown>>,
    });

    // Read the ids back and key everything by label: the SDK explicitly does not
    // promise that insert order is read-back order, so a position-derived id map
    // would be a coin flip.
    const storedItems = await backendClient.getDatasetItems(dataset.id, {
      size: SEED_ROWS.length * 2,
    });
    const itemIdByLabel: Record<string, string> = {};
    const labelByItemId: Record<string, string> = {};
    for (const item of storedItems) {
      const label = item.data.input;
      if (typeof label !== 'string') {
        throw new Error(
          `[exportComparison fixture] dataset item ${item.id} has no string "input"`,
        );
      }
      itemIdByLabel[label] = item.id;
      labelByItemId[item.id] = label;
    }
    if (Object.keys(itemIdByLabel).length !== SEED_ROWS.length) {
      throw new Error(
        `[exportComparison fixture] expected ${SEED_ROWS.length} distinct items, got ${Object.keys(itemIdByLabel).length}`,
      );
    }

    const traceIds: string[] = [];
    const experiments: ExportComparisonExperimentRef[] = [];

    for (const suffix of EXPERIMENT_SUFFIXES) {
      const experimentName = `${testNamespace}-export-exp${suffix}`;
      const experimentId = uuid7();

      // Written through the REST client rather than the bridge's nested-trace
      // helper: that helper resolves the id it returns with a `search_traces`
      // read per call, and twenty-four of those in a row are reliably rate
      // limited. Writing the id means no read-back is needed.
      const rowTraceIds = new Map<string, string>();
      for (const row of SEED_ROWS) {
        const traceId = uuid7();
        await backendClient.createTraceWithSource({
          id: traceId,
          projectName: project.name,
          name: `${testNamespace}-${suffix}-${row.label}`,
          source: 'experiment',
          input: { input: row.label },
          output: { output: `out-${suffix}-${row.label}` },
        });
        rowTraceIds.set(row.label, traceId);
        traceIds.push(traceId);
      }

      await backendClient.scoreTraces(
        SEED_ROWS.map((row) => ({
          traceId: rowTraceIds.get(row.label)!,
          projectName: project.name,
          name: EXPORT_SCORE_NAME,
          value: row.accuracy,
        })),
      );

      await backendClient.createExperiment({
        id: experimentId,
        name: experimentName,
        datasetName,
        projectName: project.name,
      });
      await backendClient.createExperimentItems(
        SEED_ROWS.map((row) => ({
          experimentId,
          datasetItemId: itemIdByLabel[row.label],
          traceId: rowTraceIds.get(row.label)!,
        })),
      );

      experiments.push({ experimentId, experimentName });
    }

    const experimentIds = experiments.map((experiment) => experiment.experimentId);
    const labelsOf = (ids: string[]): string[] => ids.map((id) => labelByItemId[id] ?? id);

    // From here down the fixture proves, against the API, that the seed really
    // has the discriminating shape the specs rely on. A UI assertion over a
    // fixture that quietly failed to set up is a test that cannot fail.
    const defaultOrderIds = await pollFor(
      'all seeded items to appear in the comparison grid',
      () =>
        backendClient.listCompareItemIds({
          datasetId: dataset.id,
          experimentIds,
          size: 200,
        }),
      (ids) => ids.length === SEED_ROWS.length,
    );
    const defaultOrderLabels = labelsOf(defaultOrderIds);

    const expectedScoreSortedLabels = [...SEED_ROWS]
      .sort((a, b) => b.accuracy - a.accuracy)
      .map((row) => row.label);

    // Also the proof that every score landed: an item still missing its
    // `accuracy` value would not sort into this exact sequence.
    const scoreSortedLabels = labelsOf(
      await pollFor(
        `items to sort by ${EXPORT_SCORE_NAME} descending`,
        () =>
          backendClient.listCompareItemIds({
            datasetId: dataset.id,
            experimentIds,
            sorting: [{ field: `feedback_scores.${EXPORT_SCORE_NAME}`, direction: 'DESC' }],
            size: 200,
          }),
        (ids) =>
          labelsOf(ids).join(',') === expectedScoreSortedLabels.join(','),
      ),
    );

    const searchMatchLabels = labelsOf(
      await backendClient.listCompareItemIds({
        datasetId: dataset.id,
        experimentIds,
        search: EXPORT_SEARCH_TERM,
        size: 200,
      }),
    );
    const expectedSearchLabels = defaultOrderLabels.filter(
      (label) => SEED_ROWS.find((row) => row.label === label)?.topic === EXPORT_SEARCH_TERM,
    );
    if (searchMatchLabels.join(',') !== expectedSearchLabels.join(',')) {
      throw new Error(
        `[exportComparison fixture] search "${EXPORT_SEARCH_TERM}" matched [${searchMatchLabels}], expected [${expectedSearchLabels}]`,
      );
    }

    const defaultPageOneLabels = defaultOrderLabels.slice(0, EXPORT_PAGE_SIZE);
    const strandedBySearch = searchMatchLabels.filter(
      (label) => !defaultPageOneLabels.includes(label),
    );
    if (strandedBySearch.length !== searchMatchLabels.length) {
      throw new Error(
        `[exportComparison fixture] the searched rows [${searchMatchLabels}] must all sit outside the unsearched page 1 [${defaultPageOneLabels}] — otherwise an export that dropped the search would still return them`,
      );
    }

    const defaultPageTwoLabels = defaultOrderLabels.slice(EXPORT_PAGE_SIZE, EXPORT_PAGE_SIZE * 2);
    const sortedPageTwoLabels = scoreSortedLabels.slice(EXPORT_PAGE_SIZE, EXPORT_PAGE_SIZE * 2);
    const sortedPageTwoSelectionLabels = sortedPageTwoLabels
      .filter((label) => !defaultPageTwoLabels.includes(label))
      .slice(0, 2);
    if (sortedPageTwoSelectionLabels.length !== 2) {
      throw new Error(
        `[exportComparison fixture] sorted page 2 [${sortedPageTwoLabels}] must offer two rows absent from the unsorted page 2 [${defaultPageTwoLabels}] — otherwise an export that dropped the sort would still return them`,
      );
    }

    const ref: ExportComparisonRef = {
      datasetId: dataset.id,
      datasetName,
      projectName: project.name,
      experiments,
      itemIdByLabel,
      labelByItemId,
      defaultOrderLabels,
      scoreSortedLabels,
      searchMatchLabels,
      sortedPageTwoSelectionLabels,
    };

    await testInfo.attach('opik.exportComparison', {
      body: JSON.stringify(ref, null, 2),
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

export { expect } from './grouped-dataset.fixture';
