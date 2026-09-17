import { test, expect } from '@e2e/fixtures';
import { ProjectsPage } from '@e2e/pom/projects.page';

/**
 * The Projects list's "(30d)" columns, against a trace whose UUIDv7 id sits far
 * outside the range a 16-bit date can hold (OPIK-7791).
 *
 * `projects-list-stats.spec.ts` already guards the ordinary windowing: seed
 * inside and outside 30 days, assert the numbers move. It seeds no far-future
 * id, though, and that is a different class of failure — the id's week bound
 * wrapped back into range, so the row was counted as *recent*. The number the
 * user reads goes up, not down; nothing errors, nothing looks empty. This spec
 * is the gate for that class, at the one age no spec in the estate reaches.
 *
 * What the two reads must answer:
 *
 *                 traces   errors
 *   30-day window     10        8
 *   unwindowed        11        8
 *
 * The unwindowed trace count is load-bearing rather than decorative. Without
 * it, "the far-future trace is excluded" and "the far-future trace was never
 * written" are the same observation, and the spec would pass just as happily
 * against a seed that silently failed.
 *
 * The error count reading 8 under *both* is the sharper half, and the reason
 * this spec asserts the unwindowed read at all. The two counts are bounded
 * differently: `trace_count` honours the caller's window, while the error stat
 * carries its own — `TraceDAO` sums errors whose id-derived instant falls
 * before `now64(9)`, split at `startOfDay(now - 7d)`, and never consults
 * `from_time`/`to_time`. So an errored trace in mid-2200 is outside the error
 * count at every window there is, and that upper bound is precisely what the
 * release corrected: when the id's week bound wrapped back into range, the row
 * was counted as a *recent* error and the number the user reads went up.
 *
 * Deliberately silent on the deviation percentage rendered beside the error
 * count. `StatsMapper.getStatsErrorCount` divides two longs before scaling by
 * 100, so the value can only ever be a multiple of 100 and reads 0 — "No change
 * since last week" — whenever this week is quieter than last. Asserting the
 * honest percentage would fail until that is fixed; asserting the truncated one
 * would pin the bug in place. It is worth a second case once it is corrected.
 */

const DAY_MS = 24 * 60 * 60 * 1000;

/** now-30d .. now — the window the table itself asks for. */
function thirtyDayWindow() {
  const toTime = new Date();
  return { fromTime: new Date(toTime.getTime() - 30 * DAY_MS), toTime };
}

test.describe('Projects list — far-future trace ids', { tag: ['@area:projects'] }, () => {
  /** Stat columns collapse out of the viewport on a narrow window. */
  test.use({ viewport: { width: 1600, height: 900 } });

  test(
    'a trace whose id is in the far future is excluded from the 30-day counts but not from the project',
    { tag: ['@t2-cuj', '@cap:projects.list-projects'] },
    async ({ farFutureErrorTraces, project, backendClient }) => {
      await test.step('An unwindowed read sees every seeded trace, but not the far-future error', async () => {
        // Ingestion is eventually consistent: poll the aggregate rather than
        // sleeping. This is also the step that proves the far-future trace
        // landed, which is what makes its absence below an exclusion.
        await expect
          .poll(
            async () => {
              const [stats] = await backendClient.getProjectStats({ name: project.name });
              return stats?.traceCount ?? null;
            },
            { timeout: 60_000 },
          )
          .toBe(farFutureErrorTraces.unwindowed.traceCount);

        const [stats] = await backendClient.getProjectStats({ name: project.name });
        // 8 with no window asked for at all — the error stat's upper bound is
        // the server's `now64(9)`, not the caller's. A mid-2200 id that wrapped
        // back into range would be counted here as a recent error, and there is
        // no window a user could pick that would exclude it.
        expect(stats.errorCount, 'errors with no window requested').toBe(
          farFutureErrorTraces.unwindowed.errorCount,
        );
      });

      await test.step('The 30-day window drops the far-future trace from the trace count too', async () => {
        const [stats] = await backendClient.getProjectStats({
          name: project.name,
          ...thirtyDayWindow(),
        });
        // 10, down from 11: unlike the error count, this one does honour the
        // requested window, so the same row leaves the answer for a second and
        // independent reason.
        expect(stats.traceCount, 'traces over the 30-day window').toBe(
          farFutureErrorTraces.windowed.traceCount,
        );
        expect(stats.errorCount, 'errors over the 30-day window').toBe(
          farFutureErrorTraces.windowed.errorCount,
        );
      });
    },
  );

  test(
    'the Projects table renders the windowed error and trace counts',
    { tag: ['@t2-cuj', '@cap:projects.list-projects'] },
    async ({ farFutureErrorTraces, project, backendClient, testNamespace, page }) => {
      await test.step('The windowed stats are settled before the page is opened', async () => {
        // Otherwise a slow ingest renders a lower number and this fails as a UI
        // defect, which it would not be.
        await expect
          .poll(
            async () => {
              const [stats] = await backendClient.getProjectStats({
                name: project.name,
                ...thirtyDayWindow(),
              });
              return stats?.traceCount ?? null;
            },
            { timeout: 60_000 },
          )
          .toBe(farFutureErrorTraces.windowed.traceCount);
      });

      const projects = new ProjectsPage(page);

      await test.step('Open the Projects page, narrowed to this run', async () => {
        await projects.goto();
        await projects.waitForReady();
        await projects.search(testNamespace);
        await expect(projects.projectRow(project.name)).toBeVisible();
      });

      await test.step('The cells carry the windowed counts, not the all-time ones', async () => {
        await expect(
          projects.statCell(project.name, 'trace_count'),
          'the Trace count (30d) cell',
        ).toHaveText(String(farFutureErrorTraces.windowed.traceCount));

        const errorCell = projects.statCell(project.name, 'error_count');
        await expect(errorCell, 'the Errors (30d) cell').toContainText(
          String(farFutureErrorTraces.windowed.errorCount),
        );
        await expect(errorCell, 'the Errors (30d) cell names what it counts').toContainText(
          'errors',
        );
        // The number a wrapped bound would have produced — one more than the
        // truth. Asserting its absence is what stops "contains 8" from being
        // satisfied by a cell that actually reads 9.
        await expect(errorCell, 'the far-future error is not counted').not.toContainText(
          String(farFutureErrorTraces.windowed.errorCount + 1),
        );
      });
    },
  );
});
