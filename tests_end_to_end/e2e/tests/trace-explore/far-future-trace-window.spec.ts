import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * A trace whose UUIDv7 id is stamped centuries from now must still be returned
 * by the time-bounded reads that span it (OPIK-7791, PR #8096).
 *
 * Opik windows a trace read on the timestamp inside the id, and pairs every id
 * bound with a week-start bound on the partition key "as a strict consequence
 * of the id range" — a pruning aid that is not supposed to filter. `toMonday`
 * returns a 16-bit Date that wraps past 2149, so a far-future row cleared the id
 * bound, folded into a past Monday and failed the week bound. It disappeared
 * from reads it belonged in, with no error and no empty state: the count simply
 * came back smaller. The fix measured 12.68M such rows across 39,038 projects on
 * production.
 *
 * The upstream regression test covers `TraceDAO` alone, and the same predicate
 * landed in `ThreadDAO`, `KpiCardDAO` and `ProjectMetricsDAO`. This drives what
 * a user can actually reach: the paged list, the metrics-summary cards above it,
 * and the Logs table that renders both.
 *
 * Every assertion names the whole expected set rather than looking for the
 * far-future row in the answer. A read that ignored its window entirely would
 * also contain it — the failure this exists to catch is a *set* that is wrong,
 * in either direction.
 *
 * NOTE on tags: the `kpi-cards` read has no capability of its own in
 * taxonomy.yaml (the metrics-summary strip is listed under neither `traces` nor
 * `threads`), so it is asserted here without claiming one. `@cap:` names what
 * this spec covers, not everything it checks.
 */

/** A window that opens before 2200 and closes after it. */
const FAR_WINDOW_START = new Date('2200-01-01T00:00:00.000Z');
const FAR_WINDOW_END = new Date('2201-01-01T00:00:00.000Z');

/** The `count` card of a kpi-cards answer, required rather than optional. */
const countCard = (stats: Array<{ type: string; currentValue: number | null }>): number => {
  const card = stats.find((s) => s.type === 'count');
  expect(card, 'the kpi-cards answer carries a "count" card').toBeDefined();
  // null is this endpoint's answer for "nothing matched", which is a different
  // fact from zero — but either way it is not a number, and coercing one to the
  // other here would let an empty answer satisfy a count assertion.
  expect(card!.currentValue, 'the count card carries a current value').not.toBeNull();
  return card!.currentValue!;
};

test.describe('Far-future trace windowing — CUJ', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  // The fixture writes five traces and blocks until every one is queryable.
  test.slow();

  test(
    'every windowed traces read returns exactly the rows its window spans, far-future row included',
    { tag: ['@cap:traces.list-traces'] },
    async ({ farFutureTraces, project, backendClient }) => {
      const { farFuture, present, allIdsNewestFirst, windowStart } = farFutureTraces;
      const presentIds = present.map((p) => p.id);

      await test.step('The unwindowed list returns every seeded trace', async () => {
        const ids = await backendClient.listTraceIds({ projectId: project.id });
        expect(ids.sort(), 'the whole project, no window').toEqual([...allIdsNewestFirst].sort());
      });

      await test.step('A window spanning 2200 returns the far-future row alongside the ordinary ones', async () => {
        const ids = await backendClient.listTraceIds({
          projectId: project.id,
          fromTime: windowStart,
          toTime: FAR_WINDOW_END,
        });
        // The regression, stated directly: before the fix this answer was
        // missing the far-future row and nothing said so.
        expect(ids.sort(), 'a week ago -> 2201 spans every seeded trace').toEqual(
          [...allIdsNewestFirst].sort(),
        );
      });

      await test.step('A window that closes now excludes it, and keeps every ordinary row', async () => {
        const ids = await backendClient.listTraceIds({
          projectId: project.id,
          fromTime: windowStart,
          toTime: new Date(),
        });
        // Both halves matter. Without the far-future row the read is honest
        // about its upper bound; with all four ordinary rows it is not simply
        // dropping everything.
        expect(ids.sort(), 'a week ago -> now excludes only the far-future row').toEqual(
          [...presentIds].sort(),
        );
      });

      await test.step('A window wholly inside 2200 returns the far-future row alone', async () => {
        const ids = await backendClient.listTraceIds({
          projectId: project.id,
          fromTime: FAR_WINDOW_START,
          toTime: FAR_WINDOW_END,
        });
        expect(ids, '2200 -> 2201 holds exactly the far-future trace').toEqual([farFuture.id]);
      });

      await test.step('The metrics-summary card counts it under the same intervals', async () => {
        // A separate DAO from the list above (KpiCardDAO), carrying the same
        // week-bound predicate. It is also the read the Logs strip makes, and
        // unlike the list it always sends an explicit interval_end — so it is
        // the upper-bound half of the wrap that the list never exercises.
        const spanning = await backendClient.projectKpiCards({
          projectId: project.id,
          entityType: 'traces',
          intervalStart: FAR_WINDOW_START,
          intervalEnd: FAR_WINDOW_END,
        });
        expect(spanning.status, `kpi-cards over 2200 -> 2201: ${spanning.message}`).toBe(200);
        expect(countCard(spanning.stats), 'traces counted in 2200 -> 2201').toBe(1);

        const recent = await backendClient.projectKpiCards({
          projectId: project.id,
          entityType: 'traces',
          intervalStart: windowStart,
          intervalEnd: new Date(),
        });
        expect(recent.status, `kpi-cards over the past week: ${recent.message}`).toBe(200);
        expect(countCard(recent.stats), 'traces counted in the past week').toBe(present.length);
      });
    },
  );

  test(
    'the Logs table renders the far-future trace alongside the present-day ones',
    { tag: ['@cap:traces.list-traces'] },
    async ({ farFutureTraces, project, page }) => {
      const logs = new LogsPage(page);
      const { farFuture, present, allIdsNewestFirst } = farFutureTraces;

      await test.step('Open Logs on the Traces tab for the seeded project', async () => {
        // Explicitly the Traces tab: every seeded trace carries a thread, and
        // `useLogsType` opens Logs on Threads for any project that has one.
        await logs.gotoTraces(project.id);
        await logs.waitForReady();
      });

      await test.step('Every seeded trace has a row, and nothing else does', async () => {
        // The Logs list sends a from_time from its date-range preset and no
        // to_time, so the upper bound is open and the far-future row belongs on
        // the page. Asserting the count as well as each row means a read that
        // silently widened — or a preset that dropped the present-day rows —
        // fails here rather than passing on a subset.
        await expect(logs.traceRows).toHaveCount(allIdsNewestFirst.length);
        await expect(
          logs.traceRow(farFuture.id),
          'the 2200-dated trace is rendered in the Traces table',
        ).toBeVisible();
        for (const trace of present) {
          await expect(logs.traceRow(trace.id)).toBeVisible();
        }
      });

      // Deliberately NOT asserted here: the number on the metrics-summary strip
      // (`logs.countTraces()`). On this page the strip and the table answer two
      // different requests for one range — the list leaves its upper bound open
      // while kpi-cards sends interval_end — so with a far-future row seeded
      // they legitimately disagree today. That disagreement is reported
      // separately; pinning it here would assert a contract that has not been
      // settled, and asserting it *matches* would ship a red spec.
    },
  );
});
