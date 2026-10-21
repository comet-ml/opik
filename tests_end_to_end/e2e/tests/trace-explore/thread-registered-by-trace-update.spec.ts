import { test, expect } from '@e2e/fixtures';
import { uuid7 } from '@e2e/core/backend';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * A thread first named by a trace UPDATE (opik#8529 / OPIK-8325).
 *
 * `trace_threads` rows are materialised from an event the trace write
 * publishes. Before this fix only the CREATE path published it, so a thread
 * whose id arrived on a `PATCH` existed in `traces` and in no thread table at
 * all — and the Threads list inner-joins `trace_threads` whenever a time range
 * is set. The result was the quiet kind: the thread was missing from the
 * filtered list that the Threads tab always shows, while opening it directly
 * still resolved it perfectly.
 *
 * Nothing in the estate reaches that path. `thread-id-prefilter`,
 * `thread-logging-smoke`, `thread-time-window-boundary` and `conversation`-
 * seeded specs all set `thread_id` at trace creation;
 * `trace-partial-update-merge.spec.ts` updates tags. The PR's own coverage is
 * two Java tests.
 *
 * Both surfaces, because they fail differently. The API assertion is the
 * precise one — the windowed read is the inner-join branch, and it must return
 * the same set as the unwindowed one. The UI assertion is what a user would
 * actually meet, and it additionally proves the page really issued a windowed
 * read rather than quietly falling back to the unfiltered branch, which would
 * make a green UI mean nothing.
 *
 * A trace MOVED between threads is seeded alongside, because "the update
 * registered a thread" and "the update re-pointed a trace" are the same write:
 * a fix that registered the new thread while leaving the old one holding a
 * trace it no longer owns would pass every assertion about the new thread.
 */

/** Wide enough to hold everything this test seeds, and the tab's own preset. */
const TIME_RANGE = 'past7days';

test.describe('Threads — registered by a trace update', { tag: ['@t2-cuj', '@area:threads'] }, () => {
  test(
    'a thread whose id arrives on a PATCH is listed under a time-range filter',
    { tag: ['@cap:threads.list-threads'] },
    async ({ project, backendClient, testNamespace, page }) => {
      test.setTimeout(300_000);

      const threadAtCreate = `${testNamespace}-thread-at-create`;
      const threadFromUpdate = `${testNamespace}-thread-from-update`;
      const threadMovedTo = `${testNamespace}-thread-moved-to`;
      const expectedThreadIds = [threadAtCreate, threadFromUpdate, threadMovedTo].sort();

      const traces = await test.step('Seed three traces on one thread and one with no thread at all', async () => {
        const seeded = {
          stays: [uuid7(), uuid7()],
          sourceless: uuid7(),
          moves: uuid7(),
        };
        await backendClient.createTracesBatch({
          projectName: project.name,
          traces: [
            { id: seeded.stays[0], name: `${testNamespace}-stays-1`, input: {}, output: {}, threadId: threadAtCreate },
            { id: seeded.stays[1], name: `${testNamespace}-stays-2`, input: {}, output: {}, threadId: threadAtCreate },
            // No threadId: the trace this test is really about.
            { id: seeded.sourceless, name: `${testNamespace}-sourceless`, input: {}, output: {} },
            { id: seeded.moves, name: `${testNamespace}-moves`, input: {}, output: {}, threadId: threadAtCreate },
          ],
        });
        return seeded;
      });

      // The discriminator. If the seed had silently landed the sourceless trace
      // on a thread already, every assertion below would hold over a scenario
      // that never exercised the update path.
      await test.step('Only the create-time thread exists before any update', async () => {
        await expect
          .poll(
            async () => {
              const { threads } = await backendClient.listThreads({ projectId: project.id });
              return threads.map((t) => t.id).sort();
            },
            { timeout: 120_000, intervals: [1_000, 2_000, 5_000] },
          )
          .toEqual([threadAtCreate]);
      });

      await test.step('PATCH one trace into a brand-new thread, and move another out', async () => {
        await backendClient.updateTraceThreadId({
          traceId: traces.sourceless,
          projectName: project.name,
          threadId: threadFromUpdate,
        });
        await backendClient.updateTraceThreadId({
          traceId: traces.moves,
          projectName: project.name,
          threadId: threadMovedTo,
        });
      });

      await test.step('All three threads are listed, with and without a time window', async () => {
        await expect
          .poll(
            async () => {
              const { threads } = await backendClient.listThreads({ projectId: project.id });
              return threads.map((t) => t.id).sort();
            },
            { timeout: 120_000, intervals: [1_000, 2_000, 5_000] },
          )
          .toEqual(expectedThreadIds);

        const unwindowed = await backendClient.listThreads({ projectId: project.id });
        // `total` as well as the ids: the tab renders it as the thread count,
        // and a read that returned the right rows under a wrong total is a
        // regression a membership check alone would not see.
        expect(unwindowed.total, 'the unwindowed read counts every seeded thread').toBe(
          expectedThreadIds.length,
        );

        // The branch under test: a window present means the query inner-joins
        // `trace_threads`, which is where an unregistered thread went missing.
        const windowed = await backendClient.listThreads({
          projectId: project.id,
          fromTime: new Date(Date.now() - 7 * 24 * 60 * 60 * 1000),
          toTime: new Date(Date.now() + 60 * 60 * 1000),
        });
        expect(windowed.total, 'the windowed read counts every seeded thread').toBe(
          expectedThreadIds.length,
        );
        expect(
          windowed.threads.map((t) => t.id).sort(),
          'a windowed read must return the same threads as an unwindowed one',
        ).toEqual(expectedThreadIds);
      });

      await test.step('The move left the original thread holding only its own traces', async () => {
        const { threads } = await backendClient.listThreads({ projectId: project.id });
        const byId = new Map(threads.map((t) => [t.id, t]));

        // Each trace contributes an input message and an output message.
        for (const [threadId, traceCount] of [
          [threadAtCreate, traces.stays.length],
          [threadFromUpdate, 1],
          [threadMovedTo, 1],
        ] as const) {
          const row = byId.get(threadId);
          expect(row, `the list returned ${threadId}`).toBeDefined();
          expect(
            row!.numberOfMessages,
            `${threadId} holds exactly its own ${traceCount} trace(s)`,
          ).toBe(traceCount * 2);
        }
      });

      await test.step('The Threads tab shows the PATCH-created thread under a time range', async () => {
        const logs = new LogsPage(page);

        // Collected before navigating: the read fires on mount, so a listener
        // attached afterwards would miss it and the assertion below would pass
        // by never having looked.
        const windowedReads: string[] = [];
        page.on('request', (request) => {
          const url = new URL(request.url());
          if (url.pathname.endsWith('/v1/private/traces/threads') && url.searchParams.has('from_time')) {
            windowedReads.push(url.search);
          }
        });

        await logs.gotoThreads(project.id, { timeRange: TIME_RANGE });
        // Gated on the PATCH-created thread's own row rather than on any row:
        // the two threads that existed before the update would satisfy "a row
        // appeared" while the one under test was still missing.
        await logs.waitForThreadsReady(threadFromUpdate);

        await expect(
          logs.threadRow(threadFromUpdate),
          'the thread a PATCH created has a row on the Threads tab',
        ).toHaveCount(1);
        await expect(logs.threadRow(threadMovedTo)).toHaveCount(1);
        await expect(logs.threadRow(threadAtCreate)).toHaveCount(1);

        // Without this the UI assertion proves nothing about the fix: the
        // unwindowed branch resolved the thread even before opik#8529, so a
        // page that quietly dropped the range would render a perfect list.
        expect(
          windowedReads.length,
          `the Threads tab must read with a from_time under ${TIME_RANGE} — ` +
            'only a windowed read takes the trace_threads inner-join branch',
        ).toBeGreaterThan(0);
      });
    },
  );
});
