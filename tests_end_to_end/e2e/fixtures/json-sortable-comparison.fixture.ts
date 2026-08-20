import { test as baseTest } from './evaluated-thread.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/** The plain JSON key every evaluation-task output carries. */
export const PLAIN_OUTPUT_KEY = 'output';
/** A key carrying a single quote — the character that used to break the SQL. */
export const QUOTED_OUTPUT_KEY = "a'b";
/** A key carrying a backslash — the other character an interpolated key mangles. */
export const BACKSLASHED_OUTPUT_KEY = 'c\\d';

export const JSON_SORTABLE_OUTPUT_KEYS = [
  PLAIN_OUTPUT_KEY,
  QUOTED_OUTPUT_KEY,
  BACKSLASHED_OUTPUT_KEY,
] as const;

export interface JsonSortableItemSeed {
  input: string;
  expected_output: string;
  /** The evaluation-task output JSON, written identically to both experiments. */
  output: Record<string, string>;
}

export interface JsonSortableExperimentRef {
  experimentId: string;
  experimentName: string;
}

export interface JsonSortableComparisonRef {
  datasetId: string;
  datasetName: string;
  projectName: string;
  items: JsonSortableItemSeed[];
  /** Dataset item ids, aligned by index with `items`. */
  itemIds: string[];
  /** Evaluation-task output JSON keyed by dataset item id. */
  outputByItemId: Record<string, Record<string, string>>;
  /**
   * Item ids in the order an ascending sort on that output key must produce,
   * derived from the seed rather than hand-written so the expectation cannot
   * drift away from the values actually written.
   */
  ascOrderByKey: Record<string, string[]>;
  experiments: JsonSortableExperimentRef[];
}

export interface JsonSortableComparisonFixtures {
  jsonSortableComparison: JsonSortableComparisonRef;
}

/**
 * Four shared dataset items, two experiments, and three JSON keys per
 * evaluation-task output. The values are chosen so each key induces a
 * *different* ordering, and none of them is the order the items were created
 * in:
 *
 *   item   output     a'b       c\d        asc by key
 *   q1     delta      kilo      romeo      output -> q2 q4 q3 q1
 *   q2     alpha      zulu      papa       a'b    -> q3 q1 q4 q2
 *   q3     charlie    hotel     tango      c\d    -> q2 q4 q1 q3
 *   q4     bravo      yankee    quebec
 *
 * That is what stops a sort which silently fell back to item id, insertion
 * order or the wrong JSON key from passing by luck.
 *
 * Both experiments write the SAME output for a given item on purpose: the
 * comparison DAO picks one experiment's last trial with `argMax(..., created_at)`
 * to sort on, and identical values make that pick irrelevant to the expected
 * order instead of a race.
 */
const SEED_ITEMS: JsonSortableItemSeed[] = [
  {
    input: 'q1',
    expected_output: 'A',
    output: { [PLAIN_OUTPUT_KEY]: 'delta', [QUOTED_OUTPUT_KEY]: 'kilo', [BACKSLASHED_OUTPUT_KEY]: 'romeo' },
  },
  {
    input: 'q2',
    expected_output: 'B',
    output: { [PLAIN_OUTPUT_KEY]: 'alpha', [QUOTED_OUTPUT_KEY]: 'zulu', [BACKSLASHED_OUTPUT_KEY]: 'papa' },
  },
  {
    input: 'q3',
    expected_output: 'C',
    output: { [PLAIN_OUTPUT_KEY]: 'charlie', [QUOTED_OUTPUT_KEY]: 'hotel', [BACKSLASHED_OUTPUT_KEY]: 'tango' },
  },
  {
    input: 'q4',
    expected_output: 'D',
    output: { [PLAIN_OUTPUT_KEY]: 'bravo', [QUOTED_OUTPUT_KEY]: 'yankee', [BACKSLASHED_OUTPUT_KEY]: 'quebec' },
  },
];

const EXPERIMENT_SUFFIXES = ['expA', 'expB'];

/**
 * Two experiments over one dataset whose evaluation-task outputs are sortable
 * by JSON key.
 *
 * Seeded through the SDK bridge (dataset + traces) and then linked with the
 * backend client, the same split `aged-experiment.fixture.ts` uses: the bridge's
 * `compare-seed` route writes one fixed `output` key per item, and this fixture
 * needs several keys per item — including keys the public SDK can carry but that
 * route cannot express.
 *
 * Teardown lives here rather than in the spec so it still runs when an
 * assertion throws. Experiments and datasets do not cascade with the project,
 * so both are deleted explicitly; the traces go with the project fixture.
 */
export const test = baseTest.extend<JsonSortableComparisonFixtures>({
  jsonSortableComparison: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const datasetName = `${testNamespace}-jsonsort-ds`;

    const dataset = await sdkClient.python.createDataset({
      project_name: project.name,
      name: datasetName,
      description: 'evaluation-task outputs with sortable JSON keys',
      items: SEED_ITEMS.map(({ input, expected_output }) => ({ input, expected_output })),
    });

    const stored = await backendClient.getDatasetItems(dataset.id);
    const idByInput: Record<string, string> = {};
    for (const item of stored) {
      idByInput[String(item.data.input)] = item.id;
    }
    const missing = SEED_ITEMS.filter((item) => !idByInput[item.input]).map((item) => item.input);
    if (missing.length > 0) {
      throw new Error(
        `[jsonSortableComparison] dataset ${datasetName} is missing seeded item(s) ${missing.join(', ')} — ` +
          `stored inputs: ${stored.map((s) => String(s.data.input)).join(', ')}`,
      );
    }
    const itemIds = SEED_ITEMS.map((item) => idByInput[item.input]);

    const experiments: JsonSortableExperimentRef[] = [];
    for (const suffix of EXPERIMENT_SUFFIXES) {
      const experimentId = uuid7();
      const experimentName = `${testNamespace}-jsonsort-${suffix}`;

      const links: Array<{ experimentId: string; datasetItemId: string; traceId: string }> = [];
      for (let i = 0; i < SEED_ITEMS.length; i++) {
        const trace = await sdkClient.python.createNestedTrace({
          project_name: project.name,
          name: `${experimentName}-${SEED_ITEMS[i].input}`,
          input: { question: SEED_ITEMS[i].input },
          output: SEED_ITEMS[i].output,
          spans: [],
        });
        links.push({ experimentId, datasetItemId: itemIds[i], traceId: trace.id });
      }

      await backendClient.createExperiment({
        id: experimentId,
        name: experimentName,
        datasetName,
        projectName: project.name,
      });
      await backendClient.createExperimentItems(links);

      experiments.push({ experimentId, experimentName });
    }

    const outputByItemId: Record<string, Record<string, string>> = {};
    SEED_ITEMS.forEach((item, i) => {
      outputByItemId[itemIds[i]] = item.output;
    });

    // Codepoint order, not localeCompare: ClickHouse orders the extracted JSON
    // values bytewise, and every seeded value is lowercase ASCII, so the two
    // agree — a locale-aware comparison would not necessarily.
    const ascOrderByKey: Record<string, string[]> = {};
    for (const key of JSON_SORTABLE_OUTPUT_KEYS) {
      ascOrderByKey[key] = [...itemIds].sort((a, b) => {
        const left = outputByItemId[a][key];
        const right = outputByItemId[b][key];
        return left < right ? -1 : left > right ? 1 : 0;
      });
    }

    const ref: JsonSortableComparisonRef = {
      datasetId: dataset.id,
      datasetName,
      projectName: project.name,
      items: SEED_ITEMS,
      itemIds,
      outputByItemId,
      ascOrderByKey,
      experiments,
    };
    await testInfo.attach('opik.jsonSortableComparison', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      const safe = async (what: string, fn: () => Promise<unknown>): Promise<void> => {
        try {
          await fn();
        } catch (err) {
          console.warn(`[jsonSortableComparison fixture] delete warning for ${what}:`, err);
        }
      };
      // Experiments before the dataset they reference.
      for (const experiment of experiments) {
        await safe(`experiment ${experiment.experimentName}`, () =>
          backendClient.deleteExperiment(experiment.experimentId),
        );
      }
      await safe(`dataset ${datasetName}`, () => backendClient.deleteDataset(dataset.id));
    }
  },
});

export { expect } from './evaluated-thread.fixture';
