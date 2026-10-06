import { test, expect } from '@e2e/fixtures';
import type { MovedTraceKey, MovedTraceThreadsRef, ThreadAggregateExpectation } from '@e2e/fixtures';
import type { BackendClient } from '@e2e/core/backend';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * `POST /v1/private/traces/threads/retrieve` after a trace changes threads
 * (opik#8735 / OPIK-8678).
 *
 * `find_thread_by_id` was split into two phases and its aggregate restricted to
 * the requested `thread_id`. Before that, a thread could report numbers
 * belonging to traces it no longer owned — the quiet kind of wrong: a header
 * reading "6 messages / $0.34" looks exactly as healthy as one reading
 * "4 messages / $0.16", and nothing on the page says which one the data
 * supports.
 *
 * Nothing in the estate reaches this read's aggregate.
 * `thread-registered-by-trace-update.spec.ts` already SEEDS the moved-trace
 * scenario, but reads it back through `listThreads` — the LIST query, which
 * #8735 did not touch — so it would stay green against a completely broken
 * `find_thread_by_id`. `thread-evaluation`, `online-evaluation-thread-scope-
 * batch-close` and `online-evaluation-thread-judge-provider-failure` do call
 * this endpoint, but assert only `feedbackScores`, which comes from a join and
 * not from the rewritten aggregate. `thread-duration-format.spec.ts` asserts
 * the panel's duration chip, and the release's own coverage report records the
 * messages and cost chips as asserted nowhere.
 *
 * ## What makes each assertion falsifiable
 *
 * - **Both threads, both sides of the move.** Asserting only the thread that
 *   LOST a trace would pass on a build that dropped the trace entirely instead
 *   of re-homing it; asserting only the one that gained it would pass on a
 *   build that double-counted.
 * - **Both truncate paths.** `truncate` is a separate branch of the same
 *   rewrite, so driving one covers half of what changed.
 * - **Cross-checked against `listThreads`.** That query was NOT rewritten and
 *   is written independently, so the two agreeing is real evidence rather than
 *   one implementation agreeing with itself.
 * - **Cost summed from the traces' own costs**, read back per trace, rather
 *   than from a hard-coded dollar figure: the claim under test is which traces
 *   a thread counted, not what gpt-4o-mini is priced at this month.
 *
 * Deterministic and provider-free: every trace and span is seeded over REST
 * with explicit times and usage, and no model is ever called.
 */

/** Long enough for the thread aggregate to catch up with a trace write. */
const AGGREGATE_TIMEOUT_MS = 120_000;

test.describe('Threads — the retrieve aggregate after a trace moves', {
  tag: ['@t2-cuj', '@area:threads'],
}, () => {
  test(
    "a trace moved between threads leaves each thread counting only its own traces",
    {
      tag: [
        '@cap:threads.thread-message-count',
        '@cap:threads.thread-level-metrics',
      ],
    },
    async ({ movedTraceThreads, backendClient, page }) => {
      test.setTimeout(300_000);

      const seed = movedTraceThreads;
      const { threadA, threadB } = seed;

      const costOf = await test.step(
        'Every seeded trace was priced, so a thread total can be compared to the sum of its own',
        async () => {
          // Read before anything is asserted about a thread, and asserted to be
          // a real number: `getTraceCost` answers null for a trace the backend
          // never priced, and letting that through as a zero would hand every
          // cost assertion below a target the seed never created.
          const byKey = new Map<MovedTraceKey, number>();
          for (const [key, trace] of Object.entries(seed.traces) as Array<
            [MovedTraceKey, (typeof seed.traces)[MovedTraceKey]]
          >) {
            const cost = await backendClient.getTraceCost(trace.id);
            expect(cost, `trace "${trace.name}" must have a server-derived cost`).not.toBeNull();
            expect(
              cost!,
              `trace "${trace.name}" must cost something — a zero makes every sum below unfalsifiable`,
            ).toBeGreaterThan(0);
            byKey.set(key, cost!);
          }
          return (keys: MovedTraceKey[]): number =>
            keys.reduce((acc, k) => acc + byKey.get(k)!, 0);
        },
      );

      await test.step('Both threads have aggregated before anything is moved', async () => {
        // The discriminator for everything that follows. Without it, a seed
        // that had not landed yet would make the post-move assertions pass for
        // the wrong reason — "A reports 4 messages" is also what a thread that
        // never saw its third trace reports.
        await waitForMessageCount(backendClient, seed, threadA, seed.before[threadA].numberOfMessages);
        await waitForMessageCount(backendClient, seed, threadB, seed.before[threadB].numberOfMessages);
      });

      await test.step('Each thread reports its own aggregate, on both truncate paths', async () => {
        for (const threadId of [threadA, threadB]) {
          for (const truncate of [false, true]) {
            await assertAggregate(backendClient, seed, threadId, seed.before[threadId], costOf, truncate);
          }
        }
      });

      await test.step('PATCH the shared trace out of thread A and into thread B', async () => {
        await backendClient.updateTraceThreadId({
          traceId: seed.traces.mover.id,
          projectName: seed.projectName,
          threadId: threadB,
        });
      });

      await test.step('Thread A drops the moved trace and thread B takes it', async () => {
        await waitForMessageCount(backendClient, seed, threadA, seed.after[threadA].numberOfMessages);
        await waitForMessageCount(backendClient, seed, threadB, seed.after[threadB].numberOfMessages);
      });

      await test.step('…and both aggregates are recomputed, on both truncate paths', async () => {
        for (const threadId of [threadA, threadB]) {
          for (const truncate of [false, true]) {
            await assertAggregate(backendClient, seed, threadId, seed.after[threadId], costOf, truncate);
          }
        }
      });

      await test.step('The unchanged list query agrees with the rewritten one', async () => {
        const { total, threads } = await backendClient.listThreads({ projectId: seed.projectId });
        // The whole answer, not just the two rows looked up: a project that had
        // also grown a third thread would mean the move split a thread rather
        // than re-homing a trace, and a membership check alone would miss it.
        expect(total, 'the project holds exactly the two seeded threads').toBe(2);
        expect(
          threads.map((t) => t.id).sort(),
          'and they are the two that were seeded',
        ).toEqual([threadA, threadB].sort());

        for (const threadId of [threadA, threadB]) {
          const row = threads.find((t) => t.id === threadId);
          expect(row, `listThreads returned ${threadId}`).toBeDefined();
          const expectation = seed.after[threadId];

          expect(
            row!.numberOfMessages,
            `${threadId}: the list query must count what the retrieve query counts`,
          ).toBe(expectation.numberOfMessages);
          expect(row!.startTime, `${threadId}: both queries must agree on the start`).toBe(
            expectation.startTime,
          );
          expect(row!.endTime, `${threadId}: both queries must agree on the end`).toBe(
            expectation.endTime,
          );
          expect(row!.usage, `${threadId}: both queries must agree on the usage`).toEqual(
            expectation.usage,
          );
          expect(
            row!.totalEstimatedCost,
            `${threadId}: both queries must agree on the cost`,
          ).not.toBeNull();
          expect(row!.totalEstimatedCost!).toBeCloseTo(costOf(expectation.traceKeys), 8);
        }
      });

      // Read from the endpoint the panel itself reads, and used only to build
      // the expected chip strings. NOT a second cost assertion: that one is
      // above, against the sum of each thread's own traces, with the tolerance
      // a float sum needs. The chip is a two-decimal FLOOR, so a sum that
      // differs from the aggregate in its last bits can fall on the other side
      // of the boundary — rendering "$0.26" where "$0.27" was predicted, which
      // says nothing about the thread panel and everything about where the
      // addition was done.
      const renderedCost = await test.step('What the panel will be handed for each thread', async () => {
        const byThread = new Map<string, number>();
        for (const threadId of [threadA, threadB]) {
          const thread = await backendClient.getThread({ projectId: seed.projectId, threadId });
          expect(
            thread.totalEstimatedCost,
            `${threadId}: the panel can only render a cost the endpoint returned`,
          ).not.toBeNull();
          byThread.set(threadId, thread.totalEstimatedCost!);
        }
        return byThread;
      });

      await test.step('Each thread panel header reads its own numbers and not the other thread’s', async () => {
        const logs = new LogsPage(page);
        await logs.gotoThreads(seed.projectId);

        for (const [threadId, otherId] of [
          [threadA, threadB],
          [threadB, threadA],
        ] as const) {
          const mine = seed.after[threadId];
          const theirs = seed.after[otherId];
          const myCost = chipCostText(renderedCost.get(threadId)!);
          const theirCost = chipCostText(renderedCost.get(otherId)!);

          // Guards the assertions below against being vacuous: if the two
          // threads rendered the same strings, "the panel shows mine and not
          // theirs" could not fail.
          expect(
            mine.numberOfMessages,
            'the two threads must differ in message count for the cross-check to mean anything',
          ).not.toBe(theirs.numberOfMessages);
          expect(myCost, 'and in rendered cost').not.toBe(theirCost);

          await logs.waitForThreadsReady(threadId);
          const panel = await logs.openThreadById(threadId);
          await panel.waitForFullyLoaded();

          // toHaveCount(1) rather than toBeVisible(): two chips carrying the
          // same string would mean the header is not the element asserted on.
          await expect(
            panel.messagesChip(mine.numberOfMessages),
            `${threadId}: the header must read "${mine.numberOfMessages} messages"`,
          ).toHaveCount(1);
          await expect(panel.messagesChip(mine.numberOfMessages)).toBeVisible();

          await expect(
            panel.costChip(myCost),
            `${threadId}: the header must read "${myCost}"; "<$0.01" here means the seed fell under the display floor`,
          ).toHaveCount(1);
          await expect(panel.costChip(myCost)).toBeVisible();

          // The cross-leak check, and the reason this step is not just a
          // re-render of the API assertion: the regression #8735 fixed shows up
          // as one thread wearing the other's aggregate.
          await expect(
            panel.messagesChip(theirs.numberOfMessages),
            `${threadId}: the header must not report the other thread's message count`,
          ).toHaveCount(0);
          await expect(
            panel.costChip(theirCost),
            `${threadId}: the header must not report the other thread's cost`,
          ).toHaveCount(0);
        }
      });
    },
  );
});

/**
 * Poll the retrieve endpoint until a thread reports `expected` messages.
 *
 * The count is the cheapest proxy for "the aggregate has caught up with the
 * writes", and it is polled rather than slept on because a trace write and the
 * thread model it feeds are eventually consistent.
 */
async function waitForMessageCount(
  backendClient: BackendClient,
  seed: MovedTraceThreadsRef,
  threadId: string,
  expected: number,
): Promise<void> {
  await expect
    .poll(
      async () =>
        (await backendClient.getThread({ projectId: seed.projectId, threadId })).numberOfMessages,
      {
        message: `thread ${threadId} should settle at ${expected} messages`,
        timeout: AGGREGATE_TIMEOUT_MS,
        intervals: [500, 1_000, 2_000, 5_000],
      },
    )
    .toBe(expected);
}

/** Assert one thread's whole retrieve aggregate on one truncate setting. */
async function assertAggregate(
  backendClient: BackendClient,
  seed: MovedTraceThreadsRef,
  threadId: string,
  expectation: ThreadAggregateExpectation,
  costOf: (keys: MovedTraceKey[]) => number,
  truncate: boolean,
): Promise<void> {
  const where = `${threadId} (truncate=${truncate})`;
  const thread = await backendClient.getThread({
    projectId: seed.projectId,
    threadId,
    truncate,
  });

  expect(thread.id, `${where}: the endpoint answered about the thread asked for`).toBe(threadId);
  expect(thread.projectId, `${where}: …in the project asked for`).toBe(seed.projectId);

  expect(
    thread.numberOfMessages,
    `${where}: two messages per owned trace, and none from a trace it does not own`,
  ).toBe(expectation.numberOfMessages);

  expect(
    thread.startTime,
    `${where}: the start is the earliest-STARTED owned trace, which is not the latest-ended one`,
  ).toBe(expectation.startTime);
  expect(
    thread.endTime,
    `${where}: the end is the latest-ENDED owned trace`,
  ).toBe(expectation.endTime);
  expect(thread.duration, `${where}: and the duration spans exactly those two`).toBeCloseTo(
    expectation.durationMs,
    3,
  );

  expect(thread.usage, `${where}: usage is summed over the owned traces only`).toEqual(
    expectation.usage,
  );

  expect(
    thread.totalEstimatedCost,
    `${where}: an absent cost is a failure, not a zero`,
  ).not.toBeNull();
  expect(
    thread.totalEstimatedCost!,
    `${where}: the cost is the sum of the owned traces' own costs`,
  ).toBeCloseTo(costOf(expectation.traceKeys), 8);

  // first/last are the turns the aggregate SELECTED, so a build that picked
  // them from the wrong rows is the same bug as a wrong start/end — and they
  // are what the Threads table's first/last-message columns render.
  const first = seed.traces[expectation.traceKeys.reduce(earliestStart(seed))];
  const last = seed.traces[expectation.traceKeys.reduce(latestEnd(seed))];
  expect(
    JSON.stringify(thread.firstMessage),
    `${where}: the first message comes from "${first.name}", the earliest-started owned trace`,
  ).toContain(`${first.name} input`);
  expect(
    JSON.stringify(thread.lastMessage),
    `${where}: the last message comes from "${last.name}", the latest-ended owned trace`,
  ).toContain(`${last.name} output`);
}

/** Reducer picking the owned trace with the smallest `start_time`. */
function earliestStart(seed: MovedTraceThreadsRef) {
  return (a: MovedTraceKey, b: MovedTraceKey): MovedTraceKey =>
    Date.parse(seed.traces[b].startTime) < Date.parse(seed.traces[a].startTime) ? b : a;
}

/** Reducer picking the owned trace with the largest `end_time`. */
function latestEnd(seed: MovedTraceThreadsRef) {
  return (a: MovedTraceKey, b: MovedTraceKey): MovedTraceKey =>
    Date.parse(seed.traces[b].endTime) > Date.parse(seed.traces[a].endTime) ? b : a;
}

/**
 * What the thread panel's cost chip must read for a given cost.
 *
 * Mirrors `formatCost(value)` in `apps/opik-frontend/src/lib/money.ts`:
 * `$` + lodash `floor(value, 2)`. The exponent-string form is lodash's own
 * `createRound` algorithm rather than `Math.floor(v * 100) / 100`, because the
 * two disagree exactly at the two-decimal boundaries this seed can land on.
 *
 * Mirroring the formatter is not the assertion — the assertion is that thread A
 * renders A's cost and not B's, and the two differ by more than a rounding
 * step.
 */
function chipCostText(cost: number): string {
  const floored = Number(`${Math.floor(Number(`${cost}e2`))}e-2`);
  return `$${floored}`;
}
