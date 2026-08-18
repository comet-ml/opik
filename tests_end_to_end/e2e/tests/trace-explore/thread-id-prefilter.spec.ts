import { test, expect } from '@e2e/fixtures';
import { numericStat, type BackendFilter, type ThreadRowRef } from '@e2e/core/backend';

/**
 * Threads read through the traces prefilter gate (OPIK-7919).
 *
 * The gate rewrites a thread read into a prefiltered `traces` scan when an
 * EQUAL filter on `id` is pushed down. Its guards upstream are rendered-SQL
 * assertions — nothing executes the query against data — so a rewrite that
 * returns the wrong rows, or the right rows with wrong aggregates, leaves the
 * suite green.
 *
 * This is a differential: the same thread read three ways (unfiltered, `id =`
 * which takes the pushdown branch, `id contains` which does not) must agree
 * field for field. Asserting the aggregates and not just the id matters — a
 * corrupt prefilter that returned the right thread with the wrong message
 * count, token usage or cost renders as a perfectly ordinary row, which no
 * page would ever show as an error.
 *
 * API-level by design: this is a backend read path, and observing it through
 * the Threads tab would be both slower and weaker — the tab's Thread ID chip
 * defaults to `contains`, so the UI does not even reach the branch under test.
 */

/** Turn counts chosen to be distinct, so a read that returned the wrong thread
 *  cannot accidentally match on the aggregates. */
const THREAD_TURN_COUNTS = [3, 5, 2, 4];

/** Two llm spans per turn, so every thread has non-trivial usage and cost to
 *  aggregate — the fields a wrong prefilter would silently corrupt. */
const SPANS_PER_TURN = [
  {
    name: 'retrieve',
    type: 'llm' as const,
    model: 'gpt-4o-mini',
    provider: 'openai',
    usage: { prompt_tokens: 12, completion_tokens: 8, total_tokens: 20 },
    total_cost: 0.00012,
  },
  {
    name: 'answer',
    type: 'llm' as const,
    model: 'gpt-4o-mini',
    provider: 'openai',
    usage: { prompt_tokens: 30, completion_tokens: 15, total_tokens: 45 },
    total_cost: 0.00031,
  },
];

const threadIdFilter = (threadId: string, operator: '=' | 'contains'): BackendFilter[] => [
  { field: 'id', type: 'string', operator, value: threadId },
];

/** The fields a wrong prefilter would corrupt, compared as one object so a
 *  mismatch reports every differing field at once rather than the first. */
const comparable = (row: ThreadRowRef) => ({
  id: row.id,
  numberOfMessages: row.numberOfMessages,
  totalEstimatedCost: row.totalEstimatedCost,
  usage: row.usage,
  duration: row.duration,
  startTime: row.startTime,
  endTime: row.endTime,
  status: row.status,
});

test.describe('Thread id prefilter — CUJ', { tag: ['@t2-cuj', '@area:threads'] }, () => {
  test('an EQUAL read on thread id returns the same row and aggregates as the unfiltered read', { tag: ['@cap:threads.list-threads'] }, async ({
    sdkClient,
    backendClient,
    project,
    testNamespace,
  }) => {
    test.setTimeout(300_000);

    const threadIds = await test.step('Seed 4 threads of 3/5/2/4 turns, each turn two llm spans', async () => {
      const ids: string[] = [];
      for (let t = 0; t < THREAD_TURN_COUNTS.length; t++) {
        const threadId = `${testNamespace}-thread-${t + 1}`;
        for (let turn = 0; turn < THREAD_TURN_COUNTS[t]; turn++) {
          await sdkClient.python.createNestedTrace({
            project_name: project.name,
            name: `${testNamespace}-t${t + 1}-turn-${turn + 1}`,
            input: { question: `question ${turn + 1} in thread ${t + 1}` },
            output: { answer: `answer ${turn + 1} in thread ${t + 1}` },
            thread_id: threadId,
            spans: SPANS_PER_TURN,
          });
        }
        ids.push(threadId);
      }
      return ids;
    });

    const unfiltered = await test.step('Wait until all 4 threads are aggregated', async () => {
      // Thread aggregation is eventually consistent — poll for the full set
      // rather than reading once, so a slow deploy is a wait and not a failure.
      await expect
        .poll(
          async () => {
            const { threads } = await backendClient.listThreads({ projectId: project.id });
            return threads.filter((r) => threadIds.includes(r.id)).length;
          },
          { timeout: 120_000, intervals: [1_000, 2_000, 5_000] },
        )
        .toBe(threadIds.length);

      const { threads } = await backendClient.listThreads({ projectId: project.id });
      return new Map(threads.map((r) => [r.id, r]));
    });

    await test.step('Seed shape is what the differential needs (sanity)', async () => {
      for (let t = 0; t < threadIds.length; t++) {
        const row = unfiltered.get(threadIds[t]);
        expect(row, `unfiltered read returned thread ${threadIds[t]}`).toBeDefined();
        // Each turn contributes an input and an output message.
        expect(row!.numberOfMessages, `message count for ${threadIds[t]}`).toBe(
          THREAD_TURN_COUNTS[t] * 2,
        );
        expect(row!.usage, `usage aggregate for ${threadIds[t]}`).not.toBeNull();
        expect(
          row!.totalEstimatedCost,
          `cost aggregate for ${threadIds[t]} — without it the cost half of this differential proves nothing`,
        ).not.toBeNull();
      }
    });

    await test.step('EQUAL (gate on) and CONTAINS (gate off) agree with the unfiltered read, field for field', async () => {
      for (const threadId of threadIds) {
        const equalRead = await backendClient.listThreads({
          projectId: project.id,
          filters: threadIdFilter(threadId, '='),
        });
        const containsRead = await backendClient.listThreads({
          projectId: project.id,
          filters: threadIdFilter(threadId, 'contains'),
        });

        expect(equalRead.total, `EQUAL read on ${threadId} matches exactly one thread`).toBe(1);
        expect(equalRead.threads, `EQUAL read on ${threadId} returned one row`).toHaveLength(1);

        const expected = comparable(unfiltered.get(threadId)!);
        expect(
          comparable(equalRead.threads[0]),
          `EQUAL read on ${threadId} must be byte-identical to the unfiltered row`,
        ).toEqual(expected);

        // Same exactness as the EQUAL path above. Without these two, a CONTAINS
        // response that also carried unrelated project threads would still find
        // the requested row and pass — which is the leak this differential is
        // here to catch.
        expect(containsRead.total, `CONTAINS read on ${threadId} matches exactly one thread`).toBe(
          1,
        );
        expect(containsRead.threads, `CONTAINS read on ${threadId} returned one row`).toHaveLength(
          1,
        );

        const containsRow = containsRead.threads.find((r) => r.id === threadId);
        expect(containsRow, `CONTAINS read returned ${threadId}`).toBeDefined();
        expect(
          comparable(containsRow!),
          `CONTAINS read on ${threadId} must agree with the EQUAL read`,
        ).toEqual(expected);
      }
    });

    await test.step('Thread stats under the EQUAL filter report one thread with matching cost', async () => {
      for (const threadId of threadIds) {
        const stats = await backendClient.getThreadsStats({
          projectId: project.id,
          filters: threadIdFilter(threadId, '='),
        });
        expect(
          numericStat(stats.thread_count, 'thread_count'),
          `thread_count under an EQUAL filter on ${threadId}`,
        ).toBe(1);

        const row = unfiltered.get(threadId)!;
        // Required, not conditional. The seed-shape step above already asserted
        // `totalEstimatedCost` is non-null on every seeded row, so an absent
        // cost aggregate here is a real regression in the stats endpoint — and
        // skipping the comparison when it goes missing would silently retire the
        // cost half of this differential, which is the half most likely to break
        // under a query rewrite.
        expect(
          stats.total_estimated_cost_sum,
          `stats must report a cost sum for ${threadId} — a missing aggregate is a regression, not a reason to skip the check`,
        ).not.toBeUndefined();
        expect(
          stats.total_estimated_cost_sum,
          `stats cost sum for ${threadId} must not be null`,
        ).not.toBeNull();
        expect(
          row.totalEstimatedCost,
          `the unfiltered row for ${threadId} must carry a cost to compare against`,
        ).not.toBeNull();
        expect(
          numericStat(stats.total_estimated_cost_sum, 'total_estimated_cost_sum'),
          `stats cost sum for ${threadId} agrees with the row's cost`,
        ).toBeCloseTo(row.totalEstimatedCost!, 6);
      }
    });

    await test.step('The EQUAL read still agrees once a time window is applied', async () => {
      // The uuid branch of the count template — the half of OPIK-7919 that only
      // runs when from_time/to_time are present. The window is wide enough to
      // hold everything this test seeded, so it must not change any answer.
      const toTime = new Date(Date.now() + 60 * 60 * 1000);
      const fromTime = new Date(Date.now() - 24 * 60 * 60 * 1000);

      for (const threadId of threadIds) {
        const windowed = await backendClient.listThreads({
          projectId: project.id,
          filters: threadIdFilter(threadId, '='),
          fromTime,
          toTime,
        });
        expect(windowed.total, `windowed EQUAL read on ${threadId} matches one thread`).toBe(1);
        expect(
          comparable(windowed.threads[0]),
          `windowed EQUAL read on ${threadId} must agree with the unwindowed one`,
        ).toEqual(comparable(unfiltered.get(threadId)!));
      }
    });
  });
});
