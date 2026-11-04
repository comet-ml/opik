import { test, expect } from '@e2e/fixtures';
import type { BackendFilter } from '@e2e/core/backend';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * The Threads tab's `comments` filter (opik#8724) keeps exactly the threads
 * carrying a THREAD comment — not the threads whose traces carry one.
 *
 * `FilterQueryBuilder` renders `TraceThreadField.COMMENTS` as
 * `thread_model_id IN (SELECT entity_id FROM comments WHERE workspace_id = …
 * AND entity_type = 'thread')`. Two things in that clause can rot
 * independently, and this is the only surface that can see either:
 *
 *  - **the `entity_type` scope.** Drop it and a thread whose TRACE was
 *    commented starts matching, because a trace id in the comments table is
 *    then as good as a thread id.
 *  - **the `thread_model_id` key.** A thread is addressed by the UUID the
 *    backend minted for the materialised row, not by the producer's
 *    `thread_id` string, and the comment is written against that same UUID.
 *
 * This is deliberately a separate spec from `comments-filter-traces.spec.ts`
 * rather than a second test in it: the two are different clauses over different
 * endpoints, they fail for different reasons, and they belong to different
 * taxonomy areas — `tag_lint` allows one `@area:` per file, and the capability
 * is `threads.comments-filter`.
 *
 * ## The discriminator
 *
 * `thread-trace-commented` in the fixture is a thread whose single trace
 * carries a TRACE comment and whose thread carries none. It must be EXCLUDED
 * here and INCLUDED by the traces spec — the same seed answering the two
 * questions differently is what says the scope is real on both sides.
 *
 * ## Not covered here
 *
 * The traces side (see above). The `contains` / `=` operators, which
 * `COMMENTS_OPERATORS` does not expose for this chip. And the thread panel's
 * own Comments tab, where a thread comment is written by hand — this spec
 * writes them through the API, because the subject is the filter.
 */

/**
 * The filter exactly as the Threads chip bar sends it.
 *
 * `THREAD_CHIP_DEFINITIONS`' comments chip declares
 * `columnType: COLUMN_TYPE.list` and `defaultOperator: 'is_not_empty'`, the
 * same as the Traces one, so the API half of this spec asks the byte-identical
 * question the UI half does.
 */
const commentsFilter = (operator: 'is_not_empty' | 'is_empty'): BackendFilter[] => [
  { field: 'comments', operator, value: '', type: 'list' },
];

/** The whole seed is seconds old, so a 24-hour window holds all of it. */
const TIME_RANGE = 'past24hours';

test.describe('Thread filters — comments', { tag: ['@t2-cuj', '@area:threads'] }, () => {
  test(
    'the comments filter returns exactly the thread carrying a THREAD comment, and its complement returns exactly the rest',
    { tag: ['@cap:threads.comments-filter'] },
    async ({ commentedEntities, project, backendClient }) => {
      // No page. The subject is which rows the clause selects; the UI half is
      // the second test.
      const seed = commentedEntities;
      const allThreadIds = seed.traces
        .filter((t) => t.threadId !== null)
        .map((t) => t.threadId!);

      await test.step('All three seeded threads have materialised before any filter is applied', async () => {
        // The baseline that makes both filtered answers subsets rather than
        // coincidences. A thread is aggregated from its traces rather than
        // written, so a count short of three is aggregation still catching up —
        // not an answer about comments. (The fixture already waited for each
        // thread's `thread_model_id`, so this is the LISTING catching up, which
        // is a separate read.)
        await expect
          .poll(
            async () => (await backendClient.listThreads({ projectId: project.id })).threads.length,
            {
              timeout: 90_000,
              intervals: [500, 1_000, 2_000, 5_000],
              message:
                `all ${allThreadIds.length} seeded threads of project ${project.id} to appear in ` +
                'the threads listing',
            },
          )
          .toBe(allThreadIds.length);

        const unfiltered = await backendClient.listThreads({ projectId: project.id });
        expect(
          unfiltered.threads.map((t) => t.id).sort(),
          'the project holds exactly the seeded threads and nothing else',
        ).toEqual(allThreadIds.slice().sort());
        expect(unfiltered.total, 'and the envelope counts them all').toBe(allThreadIds.length);
      });

      await test.step('`comments is_not_empty` keeps the one thread with a thread comment, and nothing else', async () => {
        // The exactness assertion, and where the trace-comment control earns
        // its place: that thread's trace has a comment, just not one of type
        // `thread`, so an unscoped subquery returns two ids here instead of one.
        const page = await backendClient.listThreads({
          projectId: project.id,
          filters: commentsFilter('is_not_empty'),
        });
        expect(
          page.threads.map((t) => t.id).sort(),
          'exactly the thread carrying a THREAD comment — the thread whose TRACE carries one ' +
            'must be excluded, which is what says the entity_type scope is still applied',
        ).toEqual(seed.threadsWithThreadComment.slice().sort());
        expect(
          page.total,
          'and the count query agrees with the listing — separate queries over the same clause',
        ).toBe(seed.threadsWithThreadComment.length);
      });

      await test.step('`comments is_empty` keeps exactly the complementary two', async () => {
        // A separate operator mapping (`NOT (…)`), so a build that broke one
        // would leave the other correct. Asserted as the full complement, which
        // is also what says the two answers PARTITION the project.
        const page = await backendClient.listThreads({
          projectId: project.id,
          filters: commentsFilter('is_empty'),
        });
        expect(
          page.threads.map((t) => t.id).sort(),
          'exactly the threads carrying no thread comment, the trace-commented one among them',
        ).toEqual(seed.threadsWithoutThreadComment.slice().sort());
        expect(page.total, 'and the count agrees').toBe(seed.threadsWithoutThreadComment.length);

        expect(
          [...seed.threadsWithThreadComment, ...seed.threadsWithoutThreadComment].sort(),
          'the two answers must partition the project — no thread in both, none in neither',
        ).toEqual(allThreadIds.slice().sort());
      });

      await test.step('The same seed answers the TRACES question differently', async () => {
        // The two-way half of the control, and the reason this seed is shared
        // with `comments-filter-traces.spec.ts`: the trace inside
        // `thread-trace-commented` is excluded from the threads answer above
        // and INCLUDED here. A single unscoped subquery cannot satisfy both, so
        // asserting the pair together is strictly stronger than either alone.
        const tracesPage = await backendClient.listTracePage({
          projectId: project.id,
          filters: commentsFilter('is_not_empty'),
        });
        const threadTraceCommented = seed.traces.find((t) => t.key === 'thread-trace-commented');
        expect(
          threadTraceCommented,
          'the fixture must seed the trace-commented-inside-a-thread control',
        ).toBeDefined();
        expect(
          tracesPage.ids,
          'the trace inside the trace-commented thread is a TRACE-commented trace, so the traces ' +
            'filter must return it even though the threads filter excluded its thread',
        ).toContain(threadTraceCommented!.id);
        expect(
          tracesPage.ids.slice().sort(),
          'and the traces answer is still exactly the trace-commented pair',
        ).toEqual(seed.tracesWithTraceComment.slice().sort());
      });
    },
  );

  test(
    'the Threads table narrows to exactly the thread-commented row when the Comments filter is applied',
    { tag: ['@cap:threads.comments-filter'] },
    async ({ commentedEntities, project, backendClient, page }) => {
      const seed = commentedEntities;
      const logs = new LogsPage(page);
      const allThreadIds = seed.traces
        .filter((t) => t.threadId !== null)
        .map((t) => t.threadId!);
      const expectedThreadId = seed.threadsWithThreadComment[0];

      await test.step('The server really answers the filtered question before the page is read for it', async () => {
        // A UI assertion over a seed whose thread comment never landed is a
        // test that cannot fail: the table would narrow to nothing and the
        // expected row would simply never appear, indistinguishable from a
        // rendering bug.
        await expect
          .poll(
            async () =>
              (
                await backendClient.listThreads({
                  projectId: project.id,
                  filters: commentsFilter('is_not_empty'),
                })
              ).threads.map((t) => t.id),
            {
              timeout: 90_000,
              intervals: [500, 1_000, 2_000, 5_000],
              message:
                'the server to return the one thread-commented thread — a timeout here is the ' +
                'seed still landing, not the table rendering the wrong rows',
            },
          )
          .toEqual([expectedThreadId]);
      });

      await test.step('Open the Threads tab and confirm every seeded thread is listed', async () => {
        // The time range is stated rather than inherited: it is persisted per
        // project and decides whether the read is windowed at all. The tab is
        // read back from the toggle for the same reason — `logsType` is
        // persisted too.
        await logs.gotoThreads(project.id, { timeRange: TIME_RANGE, size: 100 });
        await logs.waitForThreadsReady(expectedThreadId, { timeout: 60_000 });
        expect(await logs.activeLogsTab(), 'the Threads table is the one on screen').toBe(
          'threads',
        );
        await expect(logs.threadRows).toHaveCount(allThreadIds.length);
        expect(
          (await logs.readThreadIdsOnPage()).slice().sort(),
          'the unfiltered table shows exactly the seeded threads',
        ).toEqual(allThreadIds.slice().sort());
      });

      await test.step('Pick Comments from the All filters manager', async () => {
        // The chip is not pinned by default (`THREAD_DEFAULT_PINNED_CHIPS` is
        // tags / duration / start_time), so the manager is the only way a user
        // reaches it. Settled on the URL's `threads_filters` — the Threads tab's
        // own param, which is what the next listing request is built from.
        await logs.pinFilterChip('Comments');
        const filters = await logs.waitForUrlFilters(
          'threads',
          (applied) =>
            applied.length === 1 &&
            applied[0].field === 'comments' &&
            applied[0].operator === 'is_not_empty',
        );
        expect(
          filters,
          'exactly one filter, on `comments` with the no-value operator the chip defaults to',
        ).toHaveLength(1);
        expect(filters[0].type, 'the chip serialises its list column type').toBe('list');
        expect(filters[0].value, 'a no-value operator sends no value').toBe('');

        // The pick also opens the chip's popover, mounted `modal`, so it
        // swallows pointer events outside itself until dismissed.
        await expect(
          logs.filterChipPopover,
          'the manager pick leaves the chip it applied open',
        ).toBeVisible();
        await logs.closeFilterChip();
      });

      await test.step('The table shows exactly the thread-commented row', async () => {
        // Pinned as the rendered `data-row-id` set: on the Threads table that
        // attribute IS the thread id, and it is the only place the id appears —
        // the id is not one of the rendered columns. A filter returning the
        // trace-commented thread here is exactly what a dropped entity_type
        // scope looks like on the page.
        await expect(logs.threadRows).toHaveCount(seed.threadsWithThreadComment.length);
        expect(
          await logs.readThreadIdsOnPage(),
          "the rendered rows are exactly the API's answer to the same question",
        ).toEqual(seed.threadsWithThreadComment);
        for (const id of seed.threadsWithoutThreadComment) {
          await expect(
            logs.threadRow(id),
            `thread ${id} carries no thread comment and must not be listed`,
          ).toBeHidden();
        }
        await expect(logs.filterChip('comments')).toHaveText(/Comments:\s*not empty/);
        await expect(logs.clearAllFiltersButton).toHaveText('Clear all (1)');
      });

      await test.step('Clearing the filter restores the full set', async () => {
        await logs.clearAllFilters();
        await expect(logs.threadRows).toHaveCount(allThreadIds.length);
        expect(
          (await logs.readThreadIdsOnPage()).slice().sort(),
          'every seeded thread is back',
        ).toEqual(allThreadIds.slice().sort());
      });
    },
  );
});
