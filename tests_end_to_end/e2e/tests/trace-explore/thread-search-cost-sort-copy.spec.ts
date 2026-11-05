import { test, expect } from '@e2e/fixtures';
import { numericStat, type BackendFilter } from '@e2e/core/backend';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * Thread free-text search through the NON-PUSHDOWN query copy (opik#9264).
 *
 * 9264 rewrote the search clause in four copies of the thread queries, and
 * which pair a read goes through is decided by `ThreadDAO.isPagePushdownEligible`:
 * a page is resolved from a narrow, payload-free id-only pre-pass
 * (`SELECT_PAGE_THREAD_IDS`) unless a sort or a filter needs a column that scan
 * cannot compute, in which case the full scan runs instead. The two copies
 * render the search clause separately.
 *
 * `thread-free-text-search.spec.ts` only ever reaches the pushdown copy. It
 * reads unsorted and unfiltered, and from outside there are exactly two ways
 * past the gate:
 *
 *  - a sort on a field outside `PAGE_PUSHDOWN_SORT_FIELDS` — cost, usage and
 *    feedback scores are the ones it lists as needing the spans and feedback
 *    joins — of which `total_estimated_cost` is the one the Threads table
 *    exposes as a clickable column header; and
 *  - a filter on `first_message` or `last_message`, the two entries in
 *    `PAGE_PUSHDOWN_EXCLUDED_THREAD_FILTERS`.
 *
 * It drives neither, and the taxonomy note for `threads.free-text-search`
 * already names search-composed-with-a-filter-chip as explicitly not covered.
 *
 * So this is a differential: the same searched question asked three ways —
 * unsorted (pushdown), cost-sorted (full scan), and search-plus-a-first-message
 * filter (full scan) — must give answers that agree where they should and
 * narrow where they should. A divergence between the copies is invisible on the
 * page: the rows come back, they are simply the wrong rows, with a footer total
 * that agrees with them.
 *
 * The population is shaped so neither copy can be right by luck. The decoys'
 * cost multipliers interleave with the matches', so the searched cost order is
 * not the project's order with rows removed; and the filter's target sits in the
 * middle of that order, so a filtered read that silently kept the unfiltered
 * page's first or last row cannot land on it.
 */
test.describe(
  'Threads — free-text search under a cost sort and a message filter',
  { tag: ['@t2-cuj', '@area:threads'] },
  () => {
    /** A batch write, a thread-aggregation poll, then several reads plus a UI pass. */
    test.setTimeout(300_000);

    /** Bounded, because `ThreadsTab` excludes the `alltime` preset. */
    const TIME_RANGE = 'past24hours';

    /** The `sorting` clause that takes the read off the page pushdown. */
    const COST_SORT = (direction: 'ASC' | 'DESC') => [
      { field: 'total_estimated_cost', direction } as const,
    ];

    const sorted = (ids: string[]): string[] => [...ids].sort();

    test(
      'a cost sort and a first_message filter reach the full-scan copy and agree with the pushdown one',
      { tag: ['@cap:threads.free-text-search'] },
      async ({ threadCostOrderSearch, project, backendClient }) => {
        const seed = threadCostOrderSearch;
        const matchCount = seed.matchingThreadIds.length;

        const threadCount = async (args: { search?: string; filters?: BackendFilter[] }) => {
          const stats = await backendClient.getThreadsStats({
            projectId: project.id,
            ...(args.search === undefined ? {} : { search: args.search }),
            ...(args.filters === undefined ? {} : { filters: args.filters }),
          });
          // `numericStat` asserts the stat is PRESENT before narrowing it: this
          // endpoint answers an empty stats list rather than a zeroed count when
          // it matches nothing, so a reader defaulting a missing `thread_count`
          // to 0 would turn "the server reported nothing" into a passing number.
          return numericStat(stats.thread_count, 'thread_count');
        };

        await test.step('With no search, the project serves every seeded thread', async () => {
          const answer = await backendClient.listThreads({ projectId: project.id, size: 200 });
          // The baseline every comparison below rests on. A searched read
          // agreeing with an expectation means nothing over an unsearched read
          // that was already short.
          expect(answer.total, 'the unsearched listing reports the whole population').toBe(
            seed.allThreadIds.length,
          );
          expect(
            sorted(answer.threads.map((t) => t.id)),
            'and serves exactly the seeded threads',
          ).toEqual(seed.allThreadIds);
        });

        const baseline = await test.step(
          `The pushdown copy: "${seed.marker}" unsorted returns exactly the matching threads`,
          async () => {
            // Unsorted and unfiltered, so `isPagePushdownEligible` is satisfied
            // and this is the copy the existing spec already covers. Recorded as
            // the baseline the other two reads are compared against — and
            // asserted in its own right, because "the three copies agree" is
            // satisfied by three identical wrong answers.
            const answer = await backendClient.listThreads({
              projectId: project.id,
              search: seed.marker,
              size: 200,
            });
            expect(
              sorted(answer.threads.map((t) => t.id)),
              'every thread carrying the marker, and no decoy',
            ).toEqual(seed.matchingThreadIds);
            expect(answer.total, 'and reports that many as the population').toBe(matchCount);
            expect(
              await threadCount({ search: seed.marker }),
              'the count card agrees with the pushdown listing',
            ).toBe(matchCount);
            return answer;
          },
        );

        await test.step('Every searched row carries the cost the order will be asserted on', async () => {
          // Required, not conditional. The client types `totalEstimatedCost` as
          // nullable because an absent aggregate and a zero one are different
          // answers from this endpoint — and a cost-ordered read over rows whose
          // costs all came back null would be asserting about an order nothing
          // computed. Asserted before the sort rather than after, so a null cost
          // fails as a null cost instead of as a wrong sequence.
          for (const row of baseline.threads) {
            expect(
              row.totalEstimatedCost,
              `thread ${row.id} reports an estimated cost`,
            ).not.toBeNull();
            expect(
              row.totalEstimatedCost!,
              `thread ${row.id} was priced above zero from its span's usage`,
            ).toBeGreaterThan(0);
          }
          // And that the seed's cost spread really is strict on the server's own
          // numbers, not just in the multipliers the fixture chose. Without
          // this, a pricing table that flattened every row to one value would
          // make the DESC assertion below a test of tie-breaking.
          const costs = baseline.threads.map((row) => row.totalEstimatedCost!);
          expect(
            new Set(costs).size,
            'the searched threads have pairwise distinct costs, so their order is strict',
          ).toBe(costs.length);
        });

        await test.step('The full-scan copy: a DESC cost sort returns the same set, in cost order', async () => {
          // `total_estimated_cost` is not in `PAGE_PUSHDOWN_SORT_FIELDS`, so
          // this read is the other copy of the clause. Both halves matter: the
          // SET must be the pushdown copy's (a divergent search clause returns
          // different rows) and the ORDER must be the cost order (a sort that
          // silently did nothing returns the right set).
          const answer = await backendClient.listThreads({
            projectId: project.id,
            search: seed.marker,
            sorting: COST_SORT('DESC'),
            size: 200,
          });
          expect(
            sorted(answer.threads.map((t) => t.id)),
            'the full-scan copy answers the same thread set as the pushdown copy',
          ).toEqual(seed.matchingThreadIds);
          expect(answer.total, 'and the same population').toBe(matchCount);
          expect(
            answer.threads.map((t) => t.id),
            'in strictly descending cost order, which is the order the seed built',
          ).toEqual(seed.matchingThreadIdsByCostDesc);
          // The costs themselves, not only the ids: an id order that happened to
          // match while the costs did not would mean the sort was keyed on
          // something else.
          const costs = answer.threads.map((t) => t.totalEstimatedCost!);
          expect(
            costs,
            'and the rendered costs descend, so the sort was keyed on the cost',
          ).toEqual([...costs].sort((a, b) => b - a));
          expect(
            await threadCount({ search: seed.marker }),
            'the count card is unchanged by a sort — it is the same searched population',
          ).toBe(matchCount);
        });

        await test.step('An ASC cost sort is the exact reverse, over the same set', async () => {
          // The mirror. A "sort" that ordered by anything stable — insertion
          // order, the thread id — would answer the same sequence both ways and
          // pass the DESC step alone.
          const answer = await backendClient.listThreads({
            projectId: project.id,
            search: seed.marker,
            sorting: COST_SORT('ASC'),
            size: 200,
          });
          expect(
            answer.threads.map((t) => t.id),
            'ascending cost is the descending order reversed',
          ).toEqual([...seed.matchingThreadIdsByCostDesc].reverse());
          expect(answer.total, 'and still the whole searched population').toBe(matchCount);
        });

        await test.step('The full-scan copy again: a first_message filter composes with the search', async () => {
          // `first_message` is one of the two entries in
          // `PAGE_PUSHDOWN_EXCLUDED_THREAD_FILTERS`, so this is the other route
          // to the full-scan copy — and the one the taxonomy note calls out as
          // uncovered. The target's token is in exactly one thread's first
          // message, so the intersection with the search is strictly smaller
          // than either side: neither "the search alone" nor "the filter alone"
          // can satisfy this.
          const filters: BackendFilter[] = [
            {
              field: 'first_message',
              type: 'string',
              operator: 'contains',
              value: seed.filterTarget.firstMessageToken,
            },
          ];
          const answer = await backendClient.listThreads({
            projectId: project.id,
            search: seed.marker,
            filters,
            size: 200,
          });
          expect(
            answer.threads.map((t) => t.id),
            `search + a first_message filter narrows to '${seed.filterTarget.threadId}' alone`,
          ).toEqual([seed.filterTarget.threadId]);
          expect(answer.total, 'and the envelope reports exactly one').toBe(1);
          expect(
            await threadCount({ search: seed.marker, filters }),
            'and the count card agrees with the narrowed listing',
          ).toBe(1);
          // The intersection is strictly smaller than the search alone, which is
          // what makes the previous assertion about composition rather than
          // about one side winning.
          expect(
            matchCount,
            'the search alone matched more than one thread, so the filter did narrow',
          ).toBeGreaterThan(1);
        });

        await test.step('The filter without the search reaches a decoy too, so the two really compose', async () => {
          // The negative control for the step above. Filtering on a term that
          // one MATCH and one DECOY share proves the filter is not doing the
          // search's job: without the search it answers two threads, with it
          // one. A filter that had silently swallowed the search would answer
          // two in both cases.
          const filters: BackendFilter[] = [
            {
              field: 'first_message',
              type: 'string',
              operator: 'contains',
              value: seed.sharedFirstMessageTerm,
            },
          ];
          const unsearched = await backendClient.listThreads({
            projectId: project.id,
            filters,
            size: 200,
          });
          expect(
            sorted(unsearched.threads.map((t) => t.id)),
            'the shared opener is in every thread\'s first message, matches and decoys alike',
          ).toEqual(seed.allThreadIds);

          const searched = await backendClient.listThreads({
            projectId: project.id,
            search: seed.marker,
            filters,
            size: 200,
          });
          expect(
            sorted(searched.threads.map((t) => t.id)),
            'adding the search to the same filter narrows it to the matching threads',
          ).toEqual(seed.matchingThreadIds);
          expect(
            searched.total,
            'so the filter and the search each removed rows the other kept',
          ).toBe(matchCount);
        });
      },
    );

    test(
      'clicking the Estimated cost header while a search is active keeps the same rows',
      { tag: ['@cap:threads.free-text-search'] },
      async ({ threadCostOrderSearch, project, backendClient, page }) => {
        const seed = threadCostOrderSearch;
        const matchCount = seed.matchingThreadIds.length;
        const logs = new LogsPage(page);

        // Its own test, and the only one that proves the full-scan copy is
        // reachable the way a USER reaches it. The Threads columns take their
        // sortability from the listing's own `sortable_by` rather than from the
        // column definition, so the header being clickable at all is part of
        // what this asserts — a `total_estimated_cost` dropped from
        // `TraceThreadSortingFactory` would leave the API half of this spec
        // green and this half unable to click anything.
        const apiAnswer = await test.step('Ask the API for the cost-sorted searched answer', async () => {
          const answer = await backendClient.listThreads({
            projectId: project.id,
            search: seed.marker,
            sorting: COST_SORT('DESC'),
            size: 200,
          });
          // Asserted here as well as in the API test, because this test's
          // subject is the agreement between the page and the API: if the API
          // answer were itself wrong, "the table agrees with the API" would be
          // satisfied by two wrong answers.
          expect(
            answer.threads.map((t) => t.id),
            'the API serves the matching threads in descending cost order',
          ).toEqual(seed.matchingThreadIdsByCostDesc);
          return answer;
        });

        await test.step('Open the project Logs on the Threads tab, whole seed on one page', async () => {
          await logs.gotoThreads(project.id, { timeRange: TIME_RANGE, size: 100 });
          // A longer readiness window than the 15s default: the fixture has
          // waited for every thread to be aggregated server-side, but the first
          // paint of a cold Logs page over a freshly seeded project on a shared
          // cloud workspace has been seen to exceed it.
          await logs.waitForThreadsReady(undefined, { timeout: 60_000 });
          expect(
            await logs.activeLogsTab(),
            'the Threads tab is the one on screen — `logsType` is persisted per project',
          ).toBe('threads');
          expect(
            await logs.readUrlSorting('threads'),
            'the table starts unsorted, so the click below is a first sort rather than a flip',
          ).toBeNull();
        });

        await test.step('Before searching, the table shows the whole seeded project', async () => {
          await expect
            .poll(() => logs.readPaginationTotal(), {
              message: 'the footer reports the whole seeded population',
              timeout: 60_000,
            })
            .toBe(seed.allThreadIds.length);
          // Also the proof that the 24-hour window is not cutting the seed.
          expect(
            sorted(await logs.readThreadIdsOnPage()),
            'and renders exactly the seeded threads',
          ).toEqual(seed.allThreadIds);
        });

        await test.step(`Type "${seed.marker}" into Search by anything`, async () => {
          // Already lower-case and trimmed: `ThreadsTab` passes the RAW term to
          // the stats read and the trimmed, folded one to the listing, so a term
          // needing either treatment would have the table and its count card
          // asking different questions by construction.
          await logs.searchFor(seed.marker, 'threads');
        });

        const beforeSort = await test.step('The searched table narrows to the matching threads', async () => {
          await expect
            .poll(() => logs.readPaginationTotal(), {
              message: 'the footer narrows to the searched population',
              timeout: 60_000,
            })
            .toBe(matchCount);
          await expect(
            logs.threadRows,
            'the table body repaints down to the searched population',
          ).toHaveCount(matchCount);
          const rendered = await logs.readThreadIdsOnPage();
          expect(
            sorted(rendered),
            'the table renders exactly the matching threads before any sort',
          ).toEqual(seed.matchingThreadIds);
          return rendered;
        });

        await test.step('Click the Estimated cost header, which takes the read off the pushdown', async () => {
          const direction = await logs.sortByColumn('threads', 'Estimated cost', 'total_estimated_cost');
          // Asserted rather than assumed: the first click's direction is the
          // table's choice, and the expected ORDER below depends on it.
          expect(
            direction,
            'the first click on a cost column sorts descending, as the API read did',
          ).toBe('desc');
        });

        await test.step('The same rows are on screen, now in descending cost order', async () => {
          await expect
            .poll(() => logs.readPaginationTotal(), {
              message: 'the footer still reports the searched population after the sort',
              timeout: 60_000,
            })
            .toBe(apiAnswer.total);
          await expect(
            logs.threadRows,
            'and the table body still holds that many rows',
          ).toHaveCount(matchCount);

          const rendered = await logs.readThreadIdsOnPage();
          // The set first: this is the question the whole spec exists for. A
          // divergence between the two renderings of the search clause shows up
          // here as a row appearing or vanishing when a column header was
          // clicked, which nothing on the page would flag as an error.
          expect(
            sorted(rendered),
            'sorting by cost did not change WHICH threads the search matched',
          ).toEqual(sorted(beforeSort));
          expect(
            sorted(rendered),
            'which is still exactly the matching threads',
          ).toEqual(seed.matchingThreadIds);
          // Then the order, against the API's own answer for the same sort.
          expect(
            rendered,
            'and the rows are in the order the API answered for the same sort',
          ).toEqual(apiAnswer.threads.map((t) => t.id));
        });

        await test.step('No decoy row arrived with the sort', async () => {
          // Stated as the negative too. The set comparison above implies it, but
          // a decoy left in the DOM outside the table's row set — a stale
          // render, a duplicated body — would pass that and fail this.
          for (const decoy of seed.rows.filter((row) => !row.matches)) {
            await expect(
              logs.threadRow(decoy.threadId),
              `the non-matching thread '${decoy.threadId}' is not on screen`,
            ).toHaveCount(0);
          }
        });
      },
    );
  },
);
