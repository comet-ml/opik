import { test, expect, bucketsByDay, expectBucketsByDay } from '@e2e/fixtures';
import { type KpiCardStat } from '@e2e/core/backend';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * A trace carrying the 1970 epoch sentinel in `start_time` must contribute
 * NEITHER end of its thread's duration (OPIK-8335).
 *
 * A trace row can exist before the `start_time` that belongs on it does — a span
 * ingested ahead of its trace, or a partial write, leaves exactly that. The
 * thread aggregate derives a duration as `max(end_time) - min(start_time)` over
 * the thread's traces, so such a row poisons it from both ends: its epoch start
 * would drag the thread's start back to 1970, and its `end_time` — real, and
 * whatever instant the row was last touched — would drag the thread's end
 * forward. #8373 guards both with `notEquals(t.start_time, epoch)`. The `minIf`
 * half landed first; the `maxIf` half only at the branch head.
 *
 * The failure this covers is silent, which is why it needs a test: under the
 * half-fix the card renders a plausible number in the same units, on the same
 * day, with the same thread count beside it. Nothing about the page looks wrong.
 *
 *                             duration      avg over both threads
 *   sentinel thread              2 s
 *   clean thread                10 s                  6 s
 *
 * Three answers, all reachable, all distinct after `formatDuration` truncates to
 * whole seconds:
 *
 *   6 s   correct — `maxIf` discards the sentinel row's stray end
 *   35 s  `max(end_time)` unguarded — the sentinel thread reads the stray 60 s
 *   10 s  the sentinel thread's duration resolves NULL and `avg` skips it
 *
 * ## What this deliberately does not assert
 *
 * **The Threads list's own duration.** `GET /v1/private/traces/threads` is served
 * by `ThreadDAO`, which #8373 does not touch: it still reads unguarded
 * `min(start_time)` / `max(end_time)`, so for the sentinel thread it reports a
 * duration reaching back to 1970 while the cards above it report 2 s. That
 * divergence is real and was raised by the exploration this spec came from, but
 * it is not what this PR decided — pinning either side of it here would make a
 * permanent assertion out of a question still open for the author.
 *
 * **The bucketing SQL across intervals.** #8373's own backend integration tests
 * pin that. What they cannot see is the number the Threads tab ends up
 * rendering, which is what the last test below reads.
 */

/** The `interval` the Logs page derives from its `past7days` preset. */
const DAILY = 'DAILY';

/**
 * The rendered forms of the seeded numbers.
 *
 * Spelled out rather than recomputed: re-implementing `formatDuration` in the
 * spec would make it agree with itself whatever that function did.
 *
 *   count         2 threads in the window   -> "2"
 *   avg duration  6000 ms                   -> "6s"   (formatDuration, seconds only)
 */
const EXPECTED_COUNT_CARD = '2';
const EXPECTED_AVG_DURATION_CARD = '6s';

/**
 * One card's current value, asserted present.
 *
 * Nullable in the response, and a caller that defaulted a missing one to 0 would
 * compare two absences and call it agreement — so an absence fails here rather
 * than passing silently.
 */
function currentKpi(stats: KpiCardStat[], type: KpiCardStat['type']): number {
  const stat = stats.find((s) => s.type === type);
  expect(stat, `the answer carries a "${type}" card`).toBeDefined();
  expect(stat!.currentValue, `"${type}" current_value is present`).not.toBeNull();
  return stat!.currentValue!;
}

test.describe(
  'Thread duration and the epoch sentinel — CUJ',
  { tag: ['@t2-cuj', '@area:threads'] },
  () => {
    /**
     * The Threads tab renders three cards beside the assistant sidebar, and
     * `getCardMode` drops a card's detail below 240px of width. At the suite's
     * default width the cards render in their compact form; this is the width
     * the sibling bucketing spec established for reading them.
     */
    test.use({ viewport: { width: 2200, height: 1000 } });

    /** The fixture writes three traces across two threads and the reads poll for ingestion. */
    test.slow();

    test(
      'the sentinel row stretches neither end of its thread, so the KPI average holds',
      { tag: ['@cap:threads.thread-level-metrics'] },
      async ({ threadDurationSentinel, project, backendClient }) => {
        const read = () =>
          backendClient.projectKpiCards({
            projectId: project.id,
            entityType: 'threads',
            // The window the Logs page derives from its `past7days` preset. No
            // interval_end, because the page sends none for a preset ending
            // today — the backend uses its own `now` for both ends.
            intervalStart: threadDurationSentinel.intervalStart,
          });

        await test.step('The seeded threads have been materialised', async () => {
          // A thread row appears only once its traces are ingested, and that is
          // eventually consistent — so poll the count rather than sleep. Gating
          // on the count and not the average is deliberate: the average is the
          // number under test, and waiting for it to reach the expected value
          // would be the assertion doing the waiting.
          await expect
            .poll(
              async () => {
                const { status, stats } = await read();
                if (status !== 200) return -1;
                return stats.find((s) => s.type === 'count')?.currentValue ?? -1;
              },
              { timeout: 120_000, intervals: [1_000, 2_000, 5_000] },
            )
            .toBe(threadDurationSentinel.current.threadCount);
        });

        await test.step('The cards average the two real durations and nothing else', async () => {
          const { status, message, stats } = await read();
          expect(status, `kpi-cards rejected with: ${message}`).toBe(200);
          // The whole answer, not only the card being compared: the Threads tab
          // renders no error-rate card, and an extra one would mean the
          // endpoint served a shape the page does not expect.
          expect(
            stats.map((s) => s.type).sort(),
            'one card per metric the Threads tab renders, and nothing else',
          ).toEqual(['avg_duration', 'count', 'total_cost']);

          expect(
            currentKpi(stats, 'count'),
            'both threads are in the window — the sentinel row must not take its thread out of the count',
          ).toBe(threadDurationSentinel.current.threadCount);

          expect(
            currentKpi(stats, 'avg_duration'),
            `avg duration: ${threadDurationSentinel.wrongAnswers.unguardedMaxMs} ms here would mean ` +
              'max(end_time) took the sentinel row\'s stray end; ' +
              `${threadDurationSentinel.wrongAnswers.sentinelThreadDroppedMs} ms would mean the ` +
              'sentinel thread resolved a null duration and dropped out of the average',
          ).toBeCloseTo(threadDurationSentinel.current.avgDurationMs, 3);
        });
      },
    );

    test(
      'THREAD_AVERAGE_DURATION reports the same average, on the day the threads started',
      { tag: ['@cap:threads.thread-level-metrics'] },
      async ({ threadDurationSentinel, project, backendClient }) => {
        // A separate query from the cards, carrying its own copy of the duration
        // expression — so it can be wrong while the cards are right, and is
        // worth failing on its own.
        const read = () =>
          backendClient.projectMetric({
            projectId: project.id,
            metricType: 'THREAD_AVERAGE_DURATION',
            interval: DAILY,
            intervalStart: threadDurationSentinel.intervalStart,
            intervalEnd: new Date(),
          });

        await test.step('The seeded threads have been materialised', async () => {
          await expect
            .poll(
              async () => {
                const { status, series } = await read();
                if (status !== 200) return -1;
                return Object.keys(bucketsByDay(series, 'thread_average_duration')).length;
              },
              { timeout: 120_000, intervals: [1_000, 2_000, 5_000] },
            )
            .toBe(Object.keys(threadDurationSentinel.avgDurationByDay).length);
        });

        await test.step('One bucket, on the start day, carrying the real average', async () => {
          const { status, message, series } = await read();
          expect(status, `THREAD_AVERAGE_DURATION rejected with: ${message}`).toBe(200);
          expect(
            series.map((s) => s.name).sort(),
            'one series, named for thread average duration',
          ).toEqual(['thread_average_duration']);

          const byDay = bucketsByDay(series, 'thread_average_duration');
          // Compared as a whole map: a series carrying the right value on the
          // right day AND a stray value on a day nothing started has still been
          // bucketed wrongly, and looking the one day up would never notice.
          expectBucketsByDay(
            byDay,
            threadDurationSentinel.avgDurationByDay,
            'THREAD_AVERAGE_DURATION',
          );
          expect(
            byDay[threadDurationSentinel.mintedDay],
            `today (${threadDurationSentinel.mintedDay}) is when both trace_threads rows were really ` +
              'minted; reporting the threads there is the bucketing regression',
          ).toBeUndefined();
        });
      },
    );

    test(
      'the Threads Avg duration card renders the average the sentinel row did not stretch',
      { tag: ['@cap:threads.thread-level-metrics'] },
      async ({ threadDurationSentinel, project, backendClient, page }) => {
        await test.step('The seeded threads have been materialised', async () => {
          // Before the browser, so a slow ingest fails here — where it reads as
          // what it is — rather than as a card stuck on its placeholder that
          // the page gets blamed for.
          await expect
            .poll(
              async () => {
                const { status, stats } = await backendClient.projectKpiCards({
                  projectId: project.id,
                  entityType: 'threads',
                  intervalStart: threadDurationSentinel.intervalStart,
                });
                if (status !== 200) return -1;
                return stats.find((s) => s.type === 'count')?.currentValue ?? -1;
              },
              { timeout: 120_000, intervals: [1_000, 2_000, 5_000] },
            )
            .toBe(threadDurationSentinel.current.threadCount);
        });

        const logs = new LogsPage(page);

        await test.step('Open Logs in its Threads view over the seeded window', async () => {
          await logs.gotoThreads(project.id, {
            timeRange: threadDurationSentinel.timeRangePreset,
          });
          // Gates on the count card: the cards are one query, and reading the
          // duration before it resolves would compare against the placeholder.
          await expect(
            logs.metricsCardValue('count'),
            'the Threads count card resolves to the seeded count',
          ).toHaveText(EXPECTED_COUNT_CARD, { timeout: 60_000 });
        });

        await test.step('The Avg duration card reads the real average', async () => {
          // "35s" here is the half-fix reaching the user, and "10s" is the
          // sentinel thread having dropped out of the average entirely. Both
          // render as an ordinary duration on an ordinary card, which is what
          // makes this worth asserting at the surface a human reads.
          await expect(
            logs.metricsCardValue('avg_duration'),
            'the Avg duration card',
          ).toHaveText(EXPECTED_AVG_DURATION_CARD);
        });
      },
    );
  },
);
