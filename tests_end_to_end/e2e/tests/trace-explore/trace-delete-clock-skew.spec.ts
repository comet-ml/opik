import { test, expect } from '@e2e/fixtures';
import type { ClockSkewedTraceRef } from '@e2e/fixtures';
import type { BackendClient } from '@e2e/core/backend';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * Deleting traces whose UUIDv7 ids sit outside the present-day partition
 * (OPIK-7983).
 *
 * The flag that kept partition pruning off the trace-delete path is gone, so
 * every install now emits the partition predicate. The failure mode that buys
 * is silent: a DELETE that answers 204, prunes to the wrong partition and
 * removes nothing — or prunes too wide and removes a neighbour. Neither shows
 * up as an error, and neither is reversible.
 *
 * `trace-delete.spec.ts` already closes the loop for present-day ids. What is
 * not covered anywhere is the class the change's own Javadoc names: a producer
 * with a skewed clock stamping ids in ~2201. The fixture mints ids at three
 * instants — now, the year 2201, and 400 days ago — so a delete has to reach
 * across partitions and stop at the right rows.
 *
 * Assertions are by-id lookups rather than table reads on purpose: the Logs
 * table windows on the id's embedded timestamp (default "Past 30 days", with an
 * implicit upper bound of now), so the out-of-range traces are legitimately
 * invisible there whether they exist or not.
 */

/** Assert a trace is gone, and say which one when it is not. */
async function expectDeleted(backendClient: BackendClient, trace: ClockSkewedTraceRef): Promise<void> {
  expect(await backendClient.getTrace(trace.id), `${trace.name} should have been deleted`).toBeNull();
}

/** Assert a trace the test never targeted is still there. */
async function expectSurvives(backendClient: BackendClient, trace: ClockSkewedTraceRef): Promise<void> {
  expect(await backendClient.getTrace(trace.id), `${trace.name} was not targeted and must survive`)
    .not.toBeNull();
}

test.describe('Trace deletion — clock-skewed ids', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  /** Six trace writes, then a UI round trip, against a cloud backend. */
  test.slow();

  test.beforeEach(async ({ clockSkewedTraces, backendClient }) => {
    await test.step('All six seeded traces are readable by id before anything is deleted', async () => {
      // The REST write answers 201 before the row is queryable, so this is a
      // precondition, not an assertion about the product. Without it a delete
      // could "succeed" against a row that was never there.
      for (const trace of clockSkewedTraces.all) {
        await expect
          .poll(async () => (await backendClient.getTrace(trace.id)) !== null, {
            timeout: 60_000,
            intervals: [500, 1_000, 2_000],
          })
          .toBe(true);
      }
    });
  });

  test('Bulk-deleting from the Logs table removes only the selected rows, leaving out-of-range traces intact', { tag: ['@cap:traces.delete-traces'] }, async ({
    clockSkewedTraces,
    project,
    backendClient,
    page,
  }) => {
    const { ordinaryDoomed, ordinarySurvivor, futureDoomed, futureSurvivor, agedDoomed, withinDefaultLogsWindow } =
      clockSkewedTraces;
    const logs = new LogsPage(page);

    await test.step('Logs lists the present-day traces only', async () => {
      await logs.goto(project.id);
      await logs.waitForReady();
      // The three out-of-range traces exist — the beforeEach just read them all
      // back by id — but the table's default window excludes them at both ends.
      await expect(logs.traceRows, 'rows inside the default "Past 30 days" window')
        .toHaveCount(withinDefaultLogsWindow.length);
      for (const trace of withinDefaultLogsWindow) {
        await expect(logs.traceRow(trace.id), `${trace.name} is listed`).toHaveCount(1);
      }
    });

    await test.step('Select the two present-day traces and bulk-delete them', async () => {
      for (const trace of ordinaryDoomed) {
        await logs.selectTrace(trace.id);
      }
      await expect(logs.bulkDeleteButton).toBeEnabled();
      await logs.bulkDeleteSelected();
    });

    await test.step('The table now lists only the untouched present-day trace', async () => {
      for (const trace of ordinaryDoomed) {
        await expect(logs.traceRow(trace.id), `${trace.name} is gone from the table`).toHaveCount(0);
      }
      await expect(logs.traceRow(ordinarySurvivor.id)).toBeVisible();
      await expect(logs.traceRows).toHaveCount(1);
    });

    await test.step('Exactly the two selected traces were deleted server-side', async () => {
      for (const trace of ordinaryDoomed) {
        await expectDeleted(backendClient, trace);
      }
      // The three the UI could not even show are the point: a delete that
      // pruned to the wrong partition, or to none, would have taken them too.
      for (const trace of [ordinarySurvivor, futureDoomed, futureSurvivor, agedDoomed]) {
        await expectSurvives(backendClient, trace);
      }
    });
  });

  test('POST /traces/delete removes far-future and year-old ids, and only those', { tag: ['@cap:traces.delete-traces-api'] }, async ({
    clockSkewedTraces,
    backendClient,
  }) => {
    const { ordinaryDoomed, ordinarySurvivor, futureDoomed, futureSurvivor, agedDoomed } =
      clockSkewedTraces;

    await test.step('Delete one year-2201 trace and one 400-day-old trace by id', async () => {
      await backendClient.deleteTraces([futureDoomed.id, agedDoomed.id]);
    });

    await test.step('Both are gone — the delete reached across partitions', async () => {
      // This is the half that fails as a silent no-op if the partition
      // predicate is wrong: the call answers 204 either way.
      await expectDeleted(backendClient, futureDoomed);
      await expectDeleted(backendClient, agedDoomed);
    });

    await test.step('The second year-2201 trace and both present-day traces survive', async () => {
      // futureSurvivor shares a partition with futureDoomed, so it is what
      // distinguishes a correctly scoped delete from one that took the
      // partition.
      await expectSurvives(backendClient, futureSurvivor);
      await expectSurvives(backendClient, ordinarySurvivor);
      for (const trace of ordinaryDoomed) {
        await expectSurvives(backendClient, trace);
      }
    });
  });
});
