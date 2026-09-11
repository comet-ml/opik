import { test as baseTest } from './optimization-run.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

export interface AgedExperimentRef {
  /** Experiment whose traces are all older than the Logs page's rolling window. */
  agedExperimentId: string;
  agedTraceIds: string[];
  /**
   * A present-day experiment over the same dataset. Its traces must never show
   * up in the aged experiment's Logs tab — without other traces in the project,
   * "the tab lists 4 rows" would also hold for a tab listing everything.
   */
  freshExperimentId: string;
  freshTraceIds: string[];
  datasetId: string;
  datasetName: string;
  /** How many days back the aged traces are stamped. */
  ageDays: number;
}

export interface AgedExperimentFixtures {
  agedExperiment: AgedExperimentRef;
}

const AGE_DAYS = 60;

const AGED_ITEMS = [
  { input: 'aged question one', expected_output: 'aged answer one' },
  { input: 'aged question two', expected_output: 'aged answer two' },
  { input: 'aged question three', expected_output: 'aged answer three' },
  { input: 'aged question four', expected_output: 'aged answer four' },
];

const FRESH_ITEM_COUNT = 2;

/**
 * Two experiments over one dataset: an aged one whose traces are stamped
 * ~60 days ago, and a present-day one.
 *
 * The backdating is exact because the backend windows time-filtered reads on the
 * trace id's embedded UUIDv7 timestamp rather than on `start_time`.
 *
 * Teardown lives here so it survives a mid-test failure. Experiments and
 * datasets do not cascade with project deletion, so both are deleted explicitly;
 * the traces go with the project fixture.
 */
export const test = baseTest.extend<AgedExperimentFixtures>({
  agedExperiment: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const datasetName = `${testNamespace}-ds`;

    const dataset = await sdkClient.python.createDataset({
      project_name: project.name,
      name: datasetName,
      description: 'aged vs fresh experiment logs',
      items: AGED_ITEMS as unknown as Array<Record<string, unknown>>,
    });

    const items = await backendClient.getDatasetItems(dataset.id);
    const datasetItemIds = items.map((i) => i.id);

    const agedTraceIds: string[] = [];
    for (let i = 0; i < AGED_ITEMS.length; i++) {
      const created = await sdkClient.python.createNestedTrace({
        project_name: project.name,
        name: `${testNamespace}-aged-${i + 1}`,
        input: { question: AGED_ITEMS[i].input },
        output: { answer: AGED_ITEMS[i].expected_output },
        age_days: AGE_DAYS,
        spans: [],
      });
      agedTraceIds.push(created.id);
    }

    const freshTraceIds: string[] = [];
    for (let i = 0; i < FRESH_ITEM_COUNT; i++) {
      const created = await sdkClient.python.createNestedTrace({
        project_name: project.name,
        name: `${testNamespace}-fresh-${i + 1}`,
        input: { question: `fresh question ${i + 1}` },
        output: { answer: `fresh answer ${i + 1}` },
        spans: [],
      });
      freshTraceIds.push(created.id);
    }

    const agedExperimentId = uuid7();
    const freshExperimentId = uuid7();

    const linkExperiment = async (
      experimentId: string,
      name: string,
      traceIds: string[],
    ): Promise<void> => {
      await backendClient.createExperiment({
        id: experimentId,
        name,
        datasetName,
        projectName: project.name,
      });
      await backendClient.createExperimentItems(
        traceIds.map((traceId, i) => ({
          experimentId,
          datasetItemId: datasetItemIds[i],
          traceId,
        })),
      );
    };

    await linkExperiment(agedExperimentId, `${testNamespace}-aged-exp`, agedTraceIds);
    await linkExperiment(freshExperimentId, `${testNamespace}-fresh-exp`, freshTraceIds);

    const ref: AgedExperimentRef = {
      agedExperimentId,
      agedTraceIds,
      freshExperimentId,
      freshTraceIds,
      datasetId: dataset.id,
      datasetName,
      ageDays: AGE_DAYS,
    };
    await testInfo.attach('opik.agedExperiment', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      const safe = async (what: string, fn: () => Promise<unknown>): Promise<void> => {
        try {
          await fn();
        } catch (err) {
          console.warn(`[agedExperiment fixture] delete warning for ${what}:`, err);
        }
      };
      // Experiments before the dataset they reference.
      await safe(`experiment ${agedExperimentId}`, () =>
        backendClient.deleteExperiment(agedExperimentId),
      );
      await safe(`experiment ${freshExperimentId}`, () =>
        backendClient.deleteExperiment(freshExperimentId),
      );
      await safe(`dataset ${datasetName}`, () => backendClient.deleteDataset(dataset.id));
    }
  },
});

export { expect } from './optimization-run.fixture';
