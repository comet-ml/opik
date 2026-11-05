import { test, expect } from '@e2e/fixtures';
import { numericStat, type BackendFilter } from '@e2e/core/backend';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * The Threads tab's environment filter narrows the thread list and the count
 * card above it together, carries its selection in the shared `environment`
 * URL param, and drops a name the workspace does not know (opik#9177,
 * OPIK-6842).
 *
 * `threads` reads 7/8 in the coverage map and none of those seven is this: the
 * estate had no page object for `EnvironmentFilterSelect` on any tab, so it
 * could not drive the control at all.
 *
 * ## The count card is the silent half
 *
 * The rows come from `GET /traces/threads` and the card above them from
 * `GET /traces/threads/stats` — two independently written queries, each handed
 * the filter separately by `ThreadsTab`. A card that ignored the filter looks
 * exactly like a correct one: the number is plausible, it is just the
 * unfiltered total, and a reviewer reading "4 threads" over a table of 2
 * concludes the table is paginated. So the card is asserted at every point the
 * rows are, and the two are compared against the same expected number rather
 * than against each other.
 *
 * ## What makes the narrowing a finding
 *
 * The fixture seeds 2 threads under environment A, 1 under B and 1 under none,
 * so the four possible answers (4 / 2 / 1 / 1) are three distinct numbers: a
 * filter that was ignored answers 4, one that matched nothing answers 0, and
 * one that matched the wrong name answers 1 — and none of those can pass as
 * the right answer for A. The untagged thread is the row that must disappear
 * under A *and* under B, which is the only way to tell "matches A" from "has
 * any environment at all".
 *
 * The fixture also proves, at the TRACE level and before any browser opens,
 * that each seeded trace really carries the environment it claims, and that
 * the name used for the invalid-param case is genuinely absent from the
 * workspace registry. A UI assertion over an environment that never landed
 * cannot fail.
 *
 * ## Not claimed
 *
 * **Export honouring the selection.** The Export control sits behind the
 * `EXPORT_ENABLED` feature toggle, so whether it renders is a property of the
 * deployment rather than of this change — a spec that drove it would pass or
 * skip for reasons unrelated to the filter. The exploration verified it by
 * hand on staging; it stays a manual item.
 *
 * **The Spans tab.** It shares the param with Traces through the same
 * `useLogsEnvironment` hook; the Traces half is asserted below as the proof
 * that the param is genuinely shared, and a third copy would add no question.
 */

/**
 * The Threads read is always windowed — `ThreadsTab` excludes the `alltime`
 * preset — so the range is stated and the fixture seeds inside it.
 */
const TIME_RANGE = 'past24hours';

/** Whole seed on one page, so a row's absence is never a row on page 2. */
const PAGE_SIZE = 100;

/** The label the trigger reads before anything is selected. */
const ALL_ENVIRONMENTS_LABEL = 'All environments';

/**
 * The filter `generateEnvironmentFilter` builds for a named environment.
 *
 * Mirrored here rather than imported (the frontend is not on the spec's import
 * path) and kept to the four fields the REST layer reads, so the API half of
 * this spec drives the same wire shape the page does. The `id` the frontend
 * also sets is a React key, not part of the query.
 */
const environmentFilter = (name: string): BackendFilter[] => [
  { field: 'environment', type: 'string', operator: '=', value: name },
];

test.describe(
  'Threads — the environment filter',
  { tag: ['@t2-cuj', '@area:threads'] },
  () => {
    test(
      'narrows the thread list and the count card to one environment, and drops the rest',
      { tag: ['@cap:threads.environment-filter'] },
      async ({ environmentThreads, backendClient, page }) => {
        const seed = environmentThreads;
        const logs = new LogsPage(page);

        await test.step('Unfiltered, the API answers every seeded thread and the card agrees', async () => {
          // The baseline every comparison below is against. Asserted as the
          // whole answer — the id SET and the envelope's total — rather than as
          // membership: a listing that also carried threads from another
          // project would make "the A threads came back" true while the filter
          // was leaking.
          const all = await backendClient.listThreads({
            projectId: seed.projectId,
            size: PAGE_SIZE,
          });
          expect(
            all.threads.map((t) => t.id).sort(),
            'the project holds exactly the four seeded threads',
          ).toEqual(seed.allThreadIds);
          expect(all.total, 'and the envelope reports four').toBe(seed.allThreadIds.length);

          const stats = await backendClient.getThreadsStats({ projectId: seed.projectId });
          // `numericStat` asserts the stat is PRESENT before narrowing it: the
          // endpoint answers an empty stats list rather than a zeroed count
          // when it matches nothing, so a reader that defaulted a missing
          // `thread_count` to 0 would turn "the server reported nothing" into a
          // passing number.
          expect(
            numericStat(stats.thread_count, 'thread_count'),
            'the unfiltered count card matches the unfiltered listing',
          ).toBe(seed.allThreadIds.length);
        });

        await test.step('Each environment narrows the listing and the stats to its own threads', async () => {
          for (const [name, expected] of [
            [seed.environmentA, seed.threadIdsInA],
            [seed.environmentB, seed.threadIdsInB],
          ] as const) {
            const filters = environmentFilter(name);
            const answer = await backendClient.listThreads({
              projectId: seed.projectId,
              filters,
              size: PAGE_SIZE,
            });
            expect(
              answer.threads.map((t) => t.id).sort(),
              `environment '${name}' answers exactly its own threads`,
            ).toEqual(expected);
            expect(
              answer.total,
              `and the envelope reports ${expected.length}, so nothing was lost at a page boundary`,
            ).toBe(expected.length);

            const stats = await backendClient.getThreadsStats({
              projectId: seed.projectId,
              filters,
            });
            expect(
              numericStat(stats.thread_count, 'thread_count'),
              `the count card under '${name}' agrees with its listing — an independently ` +
                'written query, so this is a comparison rather than a tautology',
            ).toBe(expected.length);
          }
        });

        await test.step('The untagged thread belongs to neither environment', async () => {
          // The row that tells "matches A" from "has any environment". Without
          // it, a filter that merely required the column to be non-empty would
          // satisfy every assertion above for B, whose answer is also 1.
          expect(seed.untaggedThreadIds, 'the fixture seeded one untagged thread').toHaveLength(1);
          for (const name of [seed.environmentA, seed.environmentB]) {
            const answer = await backendClient.listThreads({
              projectId: seed.projectId,
              filters: environmentFilter(name),
              size: PAGE_SIZE,
            });
            expect(
              answer.threads.map((t) => t.id),
              `the untagged thread must not appear under '${name}'`,
            ).not.toContain(seed.untaggedThreadIds[0]);
          }
          // And it is reachable: a filter that dropped it from both answers
          // because it had been lost altogether would pass the step above.
          const untagged = await backendClient.listThreads({
            projectId: seed.projectId,
            filters: [{ field: 'environment', type: 'string', operator: 'is_empty', value: '' }],
            size: PAGE_SIZE,
          });
          expect(
            untagged.threads.map((t) => t.id),
            'the Untagged branch answers exactly the thread with no environment',
          ).toEqual(seed.untaggedThreadIds);
        });

        await test.step('The page opens on every thread, with the card reading four', async () => {
          await logs.gotoThreads(seed.projectId, { timeRange: TIME_RANGE, size: PAGE_SIZE });
          await logs.waitForThreadsReady(undefined, { timeout: 60_000 });
          expect(
            await logs.activeLogsTab(),
            'the Threads tab is the one on screen — `logsType` is persisted per project',
          ).toBe('threads');
          await expect
            .poll(() => logs.readPaginationTotal(), {
              message: 'the footer reports the whole seeded project before any filter',
              timeout: 60_000,
            })
            .toBe(seed.allThreadIds.length);
          expect(
            (await logs.readThreadIdsOnPage()).sort(),
            'every seeded thread is on the one page',
          ).toEqual(seed.allThreadIds);
          await expect
            .poll(() => logs.countThreads(), { message: 'the count card reads four', timeout: 30_000 })
            .toBe(seed.allThreadIds.length);
          expect(
            logs.currentEnvironmentParam(),
            'nothing is selected yet, so the shared param is absent',
          ).toBeNull();
        });

        await test.step('Picking one environment narrows the rows AND the card', async () => {
          await logs.selectEnvironment(ALL_ENVIRONMENTS_LABEL, seed.environmentA);
          await expect
            .poll(() => logs.readPaginationTotal(), {
              message: `the footer narrows to the two '${seed.environmentA}' threads`,
              timeout: 60_000,
            })
            .toBe(seed.threadIdsInA.length);
          expect(
            (await logs.readThreadIdsOnPage()).sort(),
            'exactly the A threads are rendered',
          ).toEqual(seed.threadIdsInA);
          for (const dropped of [...seed.threadIdsInB, ...seed.untaggedThreadIds]) {
            await expect(
              logs.threadRow(dropped),
              `thread ${dropped} is not under '${seed.environmentA}', so its row is gone`,
            ).toHaveCount(0);
          }
          // The silent half. Asserted against the same expected number as the
          // rows, not against the rows themselves, so a card that had started
          // mirroring the table instead of querying the stats endpoint would
          // still have to be right.
          await expect
            .poll(() => logs.countThreads(), {
              message: 'the count card recomputes for the filtered set',
              timeout: 30_000,
            })
            .toBe(seed.threadIdsInA.length);
          await expect(
            logs.environmentFilterTrigger(seed.environmentA),
            'the control names the environment the table is scoped to',
          ).toBeVisible();
        });
      },
    );

    test(
      'the selection rides the shared URL param across a reload and a tab switch',
      { tag: ['@cap:threads.environment-filter'] },
      async ({ environmentThreads, page }) => {
        const seed = environmentThreads;
        const logs = new LogsPage(page);

        await test.step('Pick an environment on the Threads tab', async () => {
          await logs.gotoThreads(seed.projectId, { timeRange: TIME_RANGE, size: PAGE_SIZE });
          await logs.waitForThreadsReady(undefined, { timeout: 60_000 });
          await logs.selectEnvironment(ALL_ENVIRONMENTS_LABEL, seed.environmentA);
          await expect
            .poll(() => logs.readPaginationTotal(), { timeout: 60_000 })
            .toBe(seed.threadIdsInA.length);
        });

        await test.step('A reload keeps the selection and the narrowed rows', async () => {
          await page.reload();
          await logs.waitForThreadsReady(undefined, { timeout: 60_000 });
          expect(
            logs.currentEnvironmentParam(),
            'the param survives the reload',
          ).toBe(seed.environmentA);
          await expect
            .poll(() => logs.readPaginationTotal(), {
              message: 'and the table is still narrowed, not merely labelled as if it were',
              timeout: 60_000,
            })
            .toBe(seed.threadIdsInA.length);
          expect((await logs.readThreadIdsOnPage()).sort()).toEqual(seed.threadIdsInA);
        });

        await test.step('Switching to the Traces tab carries the same environment', async () => {
          // `environment` is one param for all three Logs tabs, which is what
          // makes "it survived the switch" worth asserting: the tabs read it
          // through one shared hook, and the Traces table applies it to a
          // different endpoint.
          await logs.tracesTab.click();
          await expect
            .poll(async () => logs.activeLogsTab(), {
              message: 'the Traces table is on screen',
              timeout: 30_000,
            })
            .toBe('traces');
          expect(
            logs.currentEnvironmentParam(),
            'the shared param came across unchanged',
          ).toBe(seed.environmentA);
          await expect(
            logs.environmentFilterTrigger(seed.environmentA),
            'and the Traces tab names the same environment',
          ).toBeVisible();

          // The rows have to agree too: a param that travelled but was not
          // applied is the same bug as one that was dropped, and only this
          // tells them apart.
          const tracesInA = seed.threads
            .filter((t) => t.environment === seed.environmentA)
            .map((t) => t.traceId)
            .sort();
          await expect
            .poll(() => logs.readPaginationTotal(), {
              message: 'the Traces table is narrowed to the same environment',
              timeout: 60_000,
            })
            .toBe(tracesInA.length);
          await expect
            .poll(
              async () =>
                (await logs.traceRows.evaluateAll((rows) =>
                  rows.map((row) => row.getAttribute('data-row-id') ?? ''),
                )).sort(),
              { message: 'exactly the A traces are listed', timeout: 60_000 },
            )
            .toEqual(tracesInA);
        });
      },
    );

    test(
      'an environment the workspace does not know is dropped, and the table resets to page 1',
      { tag: ['@cap:threads.environment-filter'] },
      async ({ environmentThreads, page }) => {
        const seed = environmentThreads;
        const logs = new LogsPage(page);

        await test.step('Open the tab on an unknown environment, past the first page', async () => {
          // Reachable only through the URL: the dropdown can offer nothing but
          // names the registry holds. `threads_page=2` is the state the
          // `envIsValid === false` effect exists to fix — without the reset a
          // user who paginates and then follows a stale link lands on an empty
          // page of an unfiltered table and reads it as "no threads".
          await logs.gotoThreads(seed.projectId, {
            timeRange: TIME_RANGE,
            size: PAGE_SIZE,
            environment: seed.unknownEnvironment,
            threadsPage: 2,
          });
        });

        await test.step('The param is dropped and the page is reset to 1', async () => {
          await expect
            .poll(() => logs.currentEnvironmentParam(), {
              message: 'an environment the registry does not hold is removed from the URL',
              timeout: 60_000,
            })
            .toBeNull();
          await expect
            .poll(() => logs.currentThreadsPage(), {
              message: 'and the table is sent back to its first page',
              timeout: 60_000,
            })
            .toBe(1);
        });

        await test.step('And the table falls back to every thread rather than to none', async () => {
          // The half that matters to a user. Dropping the param while leaving
          // the filter applied — or leaving the table on an out-of-range page —
          // both render an empty table, which is indistinguishable from a
          // project with no threads.
          await logs.waitForThreadsReady(undefined, { timeout: 60_000 });
          await expect
            .poll(() => logs.readPaginationTotal(), {
              message: 'the unfiltered population is back',
              timeout: 60_000,
            })
            .toBe(seed.allThreadIds.length);
          expect(
            (await logs.readThreadIdsOnPage()).sort(),
            'every seeded thread is listed again',
          ).toEqual(seed.allThreadIds);
          await expect(
            logs.environmentFilterTrigger(ALL_ENVIRONMENTS_LABEL),
            'and the control reads as unscoped',
          ).toBeVisible();
        });
      },
    );
  },
);
