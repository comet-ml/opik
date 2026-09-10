import { test, expect } from '@e2e/fixtures';
import type { DurationThreadsRef, TimedThreadRef } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import type { BackendClient } from '@e2e/core/backend';

/**
 * How the thread panel renders a thread's duration (OPIK-8248).
 *
 * `formatDuration(value, false)` splits a duration into units and renders the
 * leftover seconds. The remainder is a float — `3615.3 % 3600` is
 * 15.300000000000182 — so an unrounded remainder reaches the page verbatim and
 * an hour-long thread reads "1h 15.300000000000182s". Nothing in the estate
 * asserts a formatted duration anywhere, and `src/lib/date.ts` carries no
 * `@area:` of its own, so the thread panel header is the one caller of this
 * branch a Playwright test can reach.
 *
 * Two intervals, because the obvious wrong fix for the leak is to round the
 * remainder harder, and that is invisible from the hour case alone: rounding to
 * whole seconds also turns a real 5 ms thread into "0s", losing information on
 * the sub-second branch the fix deliberately did not touch. Each is asserted at
 * the API first — the rendered string is only meaningful against a duration
 * known to be the seeded one, and a thread whose aggregate never landed would
 * otherwise fail as an indistinguishable "text not found".
 */

/**
 * The seeded threads as `GET /v1/private/traces/threads` answers, once both are
 * aggregated.
 *
 * The total is asserted, not just the two lookups: the project is fresh and
 * holds exactly these two threads, so a read that also returned a third — or
 * that merged the two into one — must fail here rather than further down.
 */
async function readSeededThreads(
  backendClient: BackendClient,
  seed: DurationThreadsRef,
): Promise<Map<string, number | null>> {
  const expectedIds = [seed.hourPlusRemainder.threadId, seed.subSecond.threadId];

  await expect
    .poll(
      async () => {
        const { threads } = await backendClient.listThreads({ projectId: seed.projectId });
        return threads.filter((t) => expectedIds.includes(t.id)).length;
      },
      { timeout: 60_000, intervals: [500, 1_000, 2_000] },
    )
    .toBe(expectedIds.length);

  const { total, threads } = await backendClient.listThreads({ projectId: seed.projectId });
  expect(total, 'the project holds exactly the two seeded threads').toBe(expectedIds.length);
  expect(threads, 'one row per seeded thread').toHaveLength(expectedIds.length);
  return new Map(threads.map((t) => [t.id, t.duration]));
}

/**
 * The duration the backend attributed to one seeded thread.
 *
 * Asserts it is there before returning it: a null duration is what this
 * endpoint answers for a thread with no usable end_time, and letting that
 * through as a zero would hand the rendering assertions a value the fixture
 * never seeded.
 */
function durationOf(
  byId: Map<string, number | null>,
  thread: TimedThreadRef,
): number {
  const duration = byId.get(thread.threadId);
  expect(duration, `thread ${thread.threadId} was aggregated`).toBeDefined();
  expect(
    duration,
    `thread ${thread.threadId} must report a duration — an absent one is a failure, not a zero`,
  ).not.toBeNull();
  return duration!;
}

test.describe('Thread duration — panel formatting', { tag: ['@t2-cuj', '@area:threads'] }, () => {
  test('A thread spanning more than an hour renders a rounded remainder, not the raw float', { tag: ['@cap:threads.thread-level-metrics'] }, async ({
    durationThreads,
    backendClient,
    page,
  }) => {
    const thread = durationThreads.hourPlusRemainder;

    await test.step('The thread really spans the seeded interval', async () => {
      const byId = await readSeededThreads(backendClient, durationThreads);
      expect(
        durationOf(byId, thread),
        'the panel assertion below is only about formatting if the duration behind it is the one seeded',
      ).toBe(thread.durationMs);
    });

    const panel = await test.step('Open the thread detail panel', async () => {
      const logs = new LogsPage(page);
      await logs.gotoThreads(durationThreads.projectId);
      await logs.waitForThreadsReady(thread.threadId);
      const panel = await logs.openThreadById(thread.threadId);
      await panel.waitForFullyLoaded();
      return panel;
    });

    await test.step('The header reads the formatted duration', async () => {
      // toHaveCount(1) before toBeVisible: two chips rendering the same string
      // would mean the header is not the element being asserted on.
      //
      // The locator is built FROM the expected string, so a miss reports "0
      // elements" and says nothing about what the header actually read — hence
      // the messages, which name the regression this count is standing in for.
      await expect(
        panel.durationChip(thread.expectedDisplay),
        `the header must read exactly '${thread.expectedDisplay}' for a ${thread.durationMs} ms thread; zero matches means the remainder reached the page unrounded`,
      ).toHaveCount(1);
      await expect(
        panel.durationChip(thread.expectedDisplay),
        'the duration chip must be on screen, not merely in the DOM',
      ).toBeVisible();
    });

    await test.step('The unrounded remainder appears nowhere in the panel', async () => {
      // The whole panel, not just the chip. The header tooltip is the static
      // string "Thread duration" and never carries the value, but the panel
      // renders per-turn durations through the same formatter, so a leak is
      // worth ruling out everywhere it could surface rather than in one chip.
      await expect(
        panel.root,
        'the raw float remainder (15.300000000000182) must not reach any duration the panel renders',
      ).not.toContainText('15.30000');
    });
  });

  test('A sub-second thread keeps its precision instead of flattening to zero', { tag: ['@cap:threads.thread-level-metrics'] }, async ({
    durationThreads,
    backendClient,
    page,
  }) => {
    const thread = durationThreads.subSecond;

    await test.step('The thread really spans the seeded interval', async () => {
      const byId = await readSeededThreads(backendClient, durationThreads);
      expect(
        durationOf(byId, thread),
        'a 5 ms thread is only a precision test if the backend agrees it is 5 ms',
      ).toBe(thread.durationMs);
    });

    const panel = await test.step('Open the thread detail panel', async () => {
      const logs = new LogsPage(page);
      await logs.gotoThreads(durationThreads.projectId);
      await logs.waitForThreadsReady(thread.threadId);
      const panel = await logs.openThreadById(thread.threadId);
      await panel.waitForFullyLoaded();
      return panel;
    });

    await test.step('The header keeps the millisecond precision', async () => {
      await expect(
        panel.durationChip(thread.expectedDisplay),
        `the header must read exactly '${thread.expectedDisplay}' for a ${thread.durationMs} ms thread; zero matches means the sub-second branch lost precision`,
      ).toHaveCount(1);
      await expect(
        panel.durationChip(thread.expectedDisplay),
        'the duration chip must be on screen, not merely in the DOM',
      ).toBeVisible();
    });

    await test.step('Nothing in the panel reads as a zero-length thread', async () => {
      // The failure mode a coarser rounding of the hour remainder would
      // introduce here: a real interval rendered as no interval at all.
      await expect(
        panel.durationChip('0s'),
        'a 5 ms thread must never render as "0s" — that is formatDuration\'s empty-result fallback, not a duration',
      ).toHaveCount(0);
    });
  });
});
