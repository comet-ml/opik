import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * The thread half of the far-future window fix (OPIK-7791, PR #8096).
 *
 * `ThreadDAO` took the identical week-bound rewrite as `TraceDAO`, and it is the
 * one with no regression test upstream at all — the PR's own guard runs against
 * traces. A thread is derived from its traces, so a thread whose traces carry
 * far-future ids inherits the same wrap: it cleared the id bound, folded into a
 * past Monday and vanished from the Threads view.
 *
 * Split from `far-future-trace-window.spec.ts` because a spec belongs to exactly
 * one `@area:`, and these assertions are threads. Both drive the same fixture.
 *
 * Every assertion names the whole expected set of thread ids: a read that
 * ignored its window would also contain the thread being looked for.
 */

const FAR_WINDOW_START = new Date('2200-01-01T00:00:00.000Z');
const FAR_WINDOW_END = new Date('2201-01-01T00:00:00.000Z');

/** The `count` card of a kpi-cards answer, required rather than optional. */
const countCard = (stats: Array<{ type: string; currentValue: number | null }>): number => {
  const card = stats.find((s) => s.type === 'count');
  expect(card, 'the kpi-cards answer carries a "count" card').toBeDefined();
  expect(card!.currentValue, 'the count card carries a current value').not.toBeNull();
  return card!.currentValue!;
};

test.describe('Far-future thread windowing — CUJ', { tag: ['@t2-cuj', '@area:threads'] }, () => {
  // Seeding writes five traces; thread rollup is then eventually consistent.
  test.slow();

  test(
    'a thread built from far-future traces is listed by every window that spans it',
    { tag: ['@cap:threads.list-threads'] },
    async ({ farFutureTraces, project, backendClient }) => {
      const { farFuture, presentThreadId, windowStart } = farFutureTraces;
      const bothThreads = [farFuture.threadId, presentThreadId].sort();

      await test.step('Wait for both threads to be aggregated', async () => {
        // Thread rollup lags trace ingestion, so poll for the full set rather
        // than reading once — a slow deploy is a wait, not a failure. The
        // unwindowed read is the right one to wait on: it is the only one the
        // wrap never affected, so a timeout here cannot be the bug under test.
        await expect
          .poll(
            async () => {
              const { threads } = await backendClient.listThreads({ projectId: project.id });
              return threads.map((t) => t.id).sort();
            },
            { timeout: 120_000, intervals: [1_000, 2_000, 5_000] },
          )
          .toEqual(bothThreads);
      });

      await test.step('A window spanning 2200 lists both threads', async () => {
        const { total, threads } = await backendClient.listThreads({
          projectId: project.id,
          fromTime: windowStart,
          toTime: FAR_WINDOW_END,
        });
        expect(threads.map((t) => t.id).sort(), 'a week ago -> 2201 spans both threads').toEqual(
          bothThreads,
        );
        // `total` drives the pager independently of the page body, so a read
        // that returned the rows but counted them under the old predicate would
        // still paginate wrongly.
        expect(total, 'the reported total agrees with the page').toBe(bothThreads.length);
      });

      await test.step('A window that closes now lists only the present-day thread', async () => {
        const { total, threads } = await backendClient.listThreads({
          projectId: project.id,
          fromTime: windowStart,
          toTime: new Date(),
        });
        expect(threads.map((t) => t.id), 'a week ago -> now excludes the far-future thread').toEqual(
          [presentThreadId],
        );
        expect(total, 'the reported total agrees with the page').toBe(1);
      });

      await test.step('A window wholly inside 2200 lists the far-future thread alone', async () => {
        const { total, threads } = await backendClient.listThreads({
          projectId: project.id,
          fromTime: FAR_WINDOW_START,
          toTime: FAR_WINDOW_END,
        });
        expect(threads.map((t) => t.id), '2200 -> 2201 holds exactly the far-future thread').toEqual(
          [farFuture.threadId],
        );
        expect(total, 'the reported total agrees with the page').toBe(1);
      });

      await test.step('The metrics-summary card counts threads under the same intervals', async () => {
        // KpiCardDAO's thread branch — a third DAO carrying the same predicate,
        // and the number the Threads tab's strip renders.
        const spanning = await backendClient.projectKpiCards({
          projectId: project.id,
          entityType: 'threads',
          intervalStart: windowStart,
          intervalEnd: FAR_WINDOW_END,
        });
        expect(spanning.status, `kpi-cards over a week ago -> 2201: ${spanning.message}`).toBe(200);
        expect(countCard(spanning.stats), 'threads counted in a week ago -> 2201').toBe(2);

        const recent = await backendClient.projectKpiCards({
          projectId: project.id,
          entityType: 'threads',
          intervalStart: windowStart,
          intervalEnd: new Date(),
        });
        expect(recent.status, `kpi-cards over the past week: ${recent.message}`).toBe(200);
        expect(countCard(recent.stats), 'threads counted in the past week').toBe(1);
      });
    },
  );

  test(
    'the Threads tab renders the far-future thread alongside the present-day one',
    { tag: ['@cap:threads.list-threads'] },
    async ({ farFutureTraces, project, backendClient, page }) => {
      const logs = new LogsPage(page);
      const { farFuture, presentThreadId } = farFutureTraces;

      await test.step('Both threads are aggregated before the browser opens', async () => {
        // Otherwise a slow rollup renders an empty Threads table and this fails
        // as a UI defect, which it would not be.
        await expect
          .poll(
            async () => {
              const { threads } = await backendClient.listThreads({ projectId: project.id });
              return threads.map((t) => t.id).sort();
            },
            { timeout: 120_000, intervals: [1_000, 2_000, 5_000] },
          )
          .toEqual([farFuture.threadId, presentThreadId].sort());
      });

      await test.step('Open the Threads tab and verify both rows', async () => {
        await logs.gotoThreads(project.id);
        await logs.waitForThreadsReady(farFuture.threadId);

        await expect(
          logs.threadRow(farFuture.threadId),
          'the thread whose traces are dated 2200 is rendered',
        ).toBeVisible();
        await expect(logs.threadRow(presentThreadId)).toBeVisible();
        // The whole table, not just the two rows looked for: the project is
        // fresh, so a third row would mean the view reached beyond it.
        await expect(logs.traceRows).toHaveCount(2);
      });
    },
  );
});
