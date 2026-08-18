import { test, expect } from '@e2e/fixtures';
import type { BackendFilter } from '@e2e/core/backend';
import { uuid7 } from '@e2e/core/backend';
import { ExperimentsPage } from '@e2e/pom/experiments.page';

/**
 * An experiment older than the Logs page's rolling date window still lists its
 * traces (OPIK-7842).
 *
 * Entity-scoped log views inherited the project Logs page's "Past 30 days"
 * default. An experiment run more than 30 days ago therefore showed an empty
 * Logs tab — which reads to a user as "nothing was recorded", not as an error.
 * That silence is the whole reason this is worth a permanent test: there is no
 * failed request, no toast, and no empty-state copy that distinguishes it from
 * a genuinely traceless run.
 *
 * `experiments.logs-tab` is already covered by experiments-smoke, but only for
 * row *scoping* on a freshly-created run. This spec covers the date window, and
 * deliberately lives in its own file so the two failure modes stay separable.
 *
 * Determinism: the backend windows on the trace id's embedded UUIDv7 timestamp,
 * not on `start_time`, so backdating is exact. The fixture asserts its own
 * discriminating power (4 all-time, 0 in a 30-day window) before touching the
 * UI — without that check a passing tab would prove nothing.
 */

const AGE_DAYS = 60;
const AGED_ITEMS = [
  { input: 'aged question one', expected_output: 'aged answer one' },
  { input: 'aged question two', expected_output: 'aged answer two' },
  { input: 'aged question three', expected_output: 'aged answer three' },
  { input: 'aged question four', expected_output: 'aged answer four' },
];
/** A second, present-day experiment over the same dataset. Its traces must never
 *  appear in the aged experiment's tab — that keeps this a scope test as well as
 *  a date-window one. */
const FRESH_ITEM_COUNT = 2;

const experimentIdsFilter = (experimentId: string): BackendFilter[] => [
  { field: 'experiment_ids', type: 'string', operator: 'in', value: experimentId },
];

test.describe('Experiment logs date window — CUJ', { tag: ['@t2-cuj', '@area:experiments'] }, () => {
  test('an experiment older than 30 days lists all of its traces in the Logs tab', { tag: ['@cap:experiments.logs-tab'] }, async ({
    sdkClient,
    backendClient,
    project,
    testNamespace,
    page,
  }) => {
    test.setTimeout(300_000);

    const datasetName = `${testNamespace}-ds`;

    const dataset = await test.step('Seed one dataset shared by both experiments', async () =>
      sdkClient.python.createDataset({
        project_name: project.name,
        name: datasetName,
        description: 'aged vs fresh experiment logs',
        items: AGED_ITEMS as unknown as Array<Record<string, unknown>>,
      }));

    const datasetItemIds = await test.step('Read the dataset item ids back', async () => {
      const items = await backendClient.getDatasetItems(dataset.id);
      expect(items, 'dataset items are queryable').toHaveLength(AGED_ITEMS.length);
      return items.map((i) => i.id);
    });

    const agedTraceIds = await test.step(`Seed ${AGED_ITEMS.length} traces stamped ~${AGE_DAYS} days ago`, async () => {
      const ids: string[] = [];
      for (let i = 0; i < AGED_ITEMS.length; i++) {
        const created = await sdkClient.python.createNestedTrace({
          project_name: project.name,
          name: `${testNamespace}-aged-${i + 1}`,
          input: { question: AGED_ITEMS[i].input },
          output: { answer: AGED_ITEMS[i].expected_output },
          age_days: AGE_DAYS,
          spans: [],
        });
        ids.push(created.id);
      }
      return ids;
    });

    const freshTraceIds = await test.step(`Seed ${FRESH_ITEM_COUNT} present-day traces`, async () => {
      const ids: string[] = [];
      for (let i = 0; i < FRESH_ITEM_COUNT; i++) {
        const created = await sdkClient.python.createNestedTrace({
          project_name: project.name,
          name: `${testNamespace}-fresh-${i + 1}`,
          input: { question: `fresh question ${i + 1}` },
          output: { answer: `fresh answer ${i + 1}` },
          spans: [],
        });
        ids.push(created.id);
      }
      return ids;
    });

    const agedExperimentId = uuid7();
    const freshExperimentId = uuid7();

    await test.step('Create the aged and fresh experiments over that dataset and link their traces', async () => {
      await backendClient.createExperiment({
        id: agedExperimentId,
        name: `${testNamespace}-aged-exp`,
        datasetName,
        projectName: project.name,
      });
      await backendClient.createExperimentItems(
        agedTraceIds.map((traceId, i) => ({
          experimentId: agedExperimentId,
          datasetItemId: datasetItemIds[i],
          traceId,
        })),
      );

      await backendClient.createExperiment({
        id: freshExperimentId,
        name: `${testNamespace}-fresh-exp`,
        datasetName,
        projectName: project.name,
      });
      await backendClient.createExperimentItems(
        freshTraceIds.map((traceId, i) => ({
          experimentId: freshExperimentId,
          datasetItemId: datasetItemIds[i],
          traceId,
        })),
      );
    });

    await test.step('The fixture discriminates: 4 traces all-time, 0 inside a 30-day window', async () => {
      // Poll the all-time read: experiment-item linkage is eventually consistent.
      await expect
        .poll(
          async () =>
            (
              await backendClient.listTraceIds({
                projectId: project.id,
                filters: experimentIdsFilter(agedExperimentId),
              })
            ).length,
          { timeout: 120_000, intervals: [1_000, 2_000, 5_000] },
        )
        .toBe(agedTraceIds.length);

      const allTime = await backendClient.listTraceIds({
        projectId: project.id,
        filters: experimentIdsFilter(agedExperimentId),
      });
      expect([...allTime].sort(), 'all-time read returns exactly the aged traces').toEqual(
        [...agedTraceIds].sort(),
      );

      const windowed = await backendClient.listTraceIds({
        projectId: project.id,
        filters: experimentIdsFilter(agedExperimentId),
        fromTime: new Date(Date.now() - 30 * 24 * 60 * 60 * 1000),
      });
      expect(
        windowed,
        `a trailing 30-day window must exclude every ${AGE_DAYS}-day-old trace — ` +
          'if it does not, this fixture cannot tell a fixed date window from a working one',
      ).toEqual([]);
    });

    // Record every experiment-scoped traces request the tab issues, so the
    // regression can be asserted at its cause (a from_time on the request) and
    // not only at its symptom (an empty table).
    const scopedTraceRequests: string[] = [];
    page.on('request', (request) => {
      const url = request.url();
      if (url.includes('/v1/private/traces') && url.includes('experiment_ids')) {
        scopedTraceRequests.push(url);
      }
    });

    const detail = await test.step('Open the aged experiment', async () => {
      const experiments = new ExperimentsPage(page);
      await experiments.goto(project.id);
      await experiments.waitForReady();
      return experiments.openExperimentById(agedExperimentId);
    });

    await test.step('The Logs tab lists exactly the aged experiment\'s 4 traces', async () => {
      await detail.openLogsTab();
      await detail.waitForLogsTraceRows(agedTraceIds.length);

      const rendered = await detail.logsTraceRows.evaluateAll((rows) =>
        rows.map((r) => r.getAttribute('data-row-id') ?? ''),
      );
      expect([...rendered].sort(), 'the tab lists exactly the aged traces').toEqual(
        [...agedTraceIds].sort(),
      );
      for (const freshId of freshTraceIds) {
        expect(rendered, `the other experiment's trace ${freshId} must not leak in`).not.toContain(
          freshId,
        );
      }
    });

    await test.step('No experiment-scoped traces read carries a from_time', async () => {
      expect(
        scopedTraceRequests.length,
        'the Logs tab issued at least one experiment-scoped traces request',
      ).toBeGreaterThan(0);
      const windowed = scopedTraceRequests.filter((url) => url.includes('from_time'));
      expect(
        windowed,
        'an experiment Logs tab must span the experiment\'s whole life, so it must send no from_time',
      ).toEqual([]);
    });

    await test.step('Cleanup: the experiments and the dataset (the project fixture cascades the rest)', async () => {
      await backendClient.deleteExperiment(agedExperimentId);
      await backendClient.deleteExperiment(freshExperimentId);
      await backendClient.deleteDataset(dataset.id);
    });
  });
});
