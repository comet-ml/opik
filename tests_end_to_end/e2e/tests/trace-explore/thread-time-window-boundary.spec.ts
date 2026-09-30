import { test, expect } from '@e2e/fixtures';

/**
 * A threads read whose window cuts INSIDE the data (DND-1735 / opik#8452).
 *
 * 8452 adds a minmax skip index on `trace_threads.created_at`. A skip index
 * only does anything when the query's range lets ClickHouse discard granules —
 * so the failure it can introduce, dropping rows that belonged in the answer,
 * is reachable only when the window boundary falls between rows.
 *
 * `thread-id-prefilter.spec.ts` already runs a windowed `listThreads` and
 * compares it field-for-field against the unwindowed read, but its window is
 * −24h/+1h over rows seeded seconds earlier: every seeded row is inside it, no
 * granule is ever pruned, and the index is never asked a question it could get
 * wrong. This spec is the complement — same endpoint, boundary moved into the
 * middle of the data.
 *
 * API-level by design, and not because the UI is hard to drive. The subject is
 * a ClickHouse read path; the Threads tab chooses its window client-side and
 * would only observe the same answer second-hand, more slowly and more
 * flakily. The Logs Threads tab's own date-range control is additionally
 * calendar-day based, so any UI assertion over it would depend on what time of
 * day the suite happens to run — which is exactly the non-determinism this
 * estate's conventions rule out.
 */
test.describe('Threads — time-window boundary', { tag: ['@t2-cuj', '@area:threads'] }, () => {
  test(
    'a window cutting between threads returns exactly the rows on that side',
    { tag: ['@cap:threads.list-threads'] },
    async ({ boundaryThreads, backendClient }) => {
      test.setTimeout(300_000);

      const { projectId, threads, boundaryBefore } = boundaryThreads;
      const seededIds = threads.map((t) => t.threadId);

      const unwindowed = await test.step('Read the whole project unwindowed', async () => {
        const { total, threads: rows } = await backendClient.listThreads({
          projectId,
          size: seededIds.length * 2,
        });
        const ids = rows.map((r) => r.id).sort();

        // The project is this test's own, so the unwindowed read must be
        // exactly the seed and nothing else. Asserting the count as well as
        // the membership is what makes every partition assertion below a
        // statement about the whole answer rather than about whether the rows
        // this test cares about happen to be present.
        expect(total, 'the unwindowed read reports every seeded thread and no others').toBe(
          seededIds.length,
        );
        expect(ids, 'the unwindowed read IS the seed').toEqual([...seededIds].sort());
        return new Set(ids);
      });

      // Three interior cuts rather than one: a quarter, a half and three
      // quarters of the way through. An index that prunes wrongly can easily
      // be right at one boundary and wrong at another — the granule layout is
      // not something the test can see or choose.
      for (const k of [12, 24, 36]) {
        const boundary = boundaryBefore(k);
        const olderIds = seededIds.slice(0, k);
        const newerIds = seededIds.slice(k);

        await test.step(`A boundary before thread ${k} splits the set ${k}/${newerIds.length}`, async () => {
          const after = await backendClient.listThreads({
            projectId,
            size: seededIds.length * 2,
            fromTime: boundary,
          });
          const before = await backendClient.listThreads({
            projectId,
            size: seededIds.length * 2,
            toTime: boundary,
          });

          // Both halves non-empty is not decoration: it is the assertion that
          // the cut really landed inside the data. If the window compared
          // against something that is the same for every seeded row, one side
          // would come back whole and the other empty, and every equality
          // below would still hold against a partition that tested nothing.
          expect(after.threads.length, `rows at or after the boundary before ${k}`).toBeGreaterThan(
            0,
          );
          expect(before.threads.length, `rows at or before the boundary before ${k}`).toBeGreaterThan(
            0,
          );

          // `total` as well as the rows: the envelope is what the Threads tab
          // renders as "N threads", and a read that returned the right rows
          // under a wrong total is a bug this would otherwise miss.
          expect(after.total, `total at or after the boundary before ${k}`).toBe(newerIds.length);
          expect(before.total, `total at or before the boundary before ${k}`).toBe(olderIds.length);

          expect(
            after.threads.map((r) => r.id).sort(),
            `from_time must return exactly the threads newer than the cut before ${k}`,
          ).toEqual([...newerIds].sort());
          expect(
            before.threads.map((r) => r.id).sort(),
            `to_time must return exactly the threads older than the cut before ${k}`,
          ).toEqual([...olderIds].sort());

          // The halves have to reconstitute the whole. A pruning bug that drops
          // the same row from BOTH sides would satisfy neither equality above
          // only by accident; stating the partition directly makes the failure
          // say "a row fell out of the window entirely".
          const union = new Set([
            ...after.threads.map((r) => r.id),
            ...before.threads.map((r) => r.id),
          ]);
          expect(union.size, `the two halves either side of the cut before ${k} cover the seed`).toBe(
            unwindowed.size,
          );
        });
      }

      // A window with BOTH bounds inside the data — the shape the Threads tab
      // actually issues for a custom range, and the one where a skip index has
      // two chances to prune wrongly rather than one.
      await test.step('A window closed on both sides returns only its interior', async () => {
        const from = boundaryBefore(12);
        const to = boundaryBefore(36);
        const expected = seededIds.slice(12, 36);

        const windowed = await backendClient.listThreads({
          projectId,
          size: seededIds.length * 2,
          fromTime: from,
          toTime: to,
        });

        expect(windowed.total, 'total inside a doubly-bounded window').toBe(expected.length);
        expect(
          windowed.threads.map((r) => r.id).sort(),
          'a window bounded on both sides returns its interior and nothing else',
        ).toEqual([...expected].sort());
      });
    },
  );
});
