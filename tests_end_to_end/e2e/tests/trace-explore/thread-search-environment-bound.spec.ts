import { test, expect } from '@e2e/fixtures';
import { numericStat, type BackendFilter, type ThreadRowRef } from '@e2e/core/backend';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * An environment filter bounds WHICH of a thread's traces a search may match,
 * and what the row then reports (opik#9304, OPIK-9051).
 *
 * This is the second, independent behaviour change in the same diff, and it is
 * covered by no test at any layer. 9304 added `<if(filters)> AND <filters>`
 * INSIDE the search subquery in all four thread query templates, so the rule
 * became: *a thread matches when one of its traces that passes the filters
 * matches the search*. A term carried only by a trace the filter excludes must
 * therefore return nothing — and the same term with the filter removed must
 * return the thread, which is what separates "the filter excluded it" from "the
 * search stopped working".
 *
 * ## Only one control on this tab reaches the clause
 *
 * The `filters` the changed clause binds are `FilterStrategy.TRACE` ones. Every
 * filter CHIP the Threads tab offers — tags, duration, message count,
 * first/last message, thread id, annotation queue, feedback scores — is
 * `TRACE_THREAD`-strategy or an aggregation strategy, and is applied by the
 * OUTER query instead. On the Threads tab exactly one control produces a
 * TRACE-strategy filter: the **Environment select**. So a chip-based spec would
 * not exercise this at all, however faithfully it composed a chip with a search.
 * That is why the fixture splits one conversation across two environments
 * rather than reaching for a chip.
 *
 * `threads-environment-filter.spec.ts` covers the control itself — that picking
 * an environment narrows the rows and the count card together — over four
 * single-trace threads. It never searches, and with one trace per thread the
 * filter cannot bound a search WITHIN a conversation, which is the whole
 * subject here.
 *
 * ## The four reads, and why all four are needed
 *
 * | filter | search | expected |
 * |---|---|---|
 * | none | none | the conversation, all three turns |
 * | included env | none | the conversation, its two included turns |
 * | included env | a term on the EXCLUDED turn | nothing |
 * | none | the same term | the conversation, all three turns |
 * | included env | a term on an INCLUDED turn | the conversation, two turns |
 *
 * Dropping the fourth row leaves "nothing" explainable by a broken search;
 * dropping the fifth leaves it explainable by a filter that rejects every
 * searched read. Together they pin the composition.
 */
test.describe(
  'Threads — an environment filter bounds what a search may match',
  { tag: ['@t2-cuj', '@area:threads'] },
  () => {
    /** Two batch writes, an aggregation poll, an environment-registry poll, reads, and a UI pass. */
    test.setTimeout(300_000);

    /** Bounded, because `ThreadsTab` excludes the `alltime` preset. */
    const TIME_RANGE = 'past24hours';

    /** The filter `generateEnvironmentFilter` builds for a named environment. */
    const environmentFilter = (name: string): BackendFilter[] => [
      { field: 'environment', type: 'string', operator: '=', value: name },
    ];

    test(
      'a term on an excluded trace returns nothing, and the same term without the filter returns the thread',
      { tag: ['@cap:threads.free-text-search', '@cap:threads.environment-filter'] },
      async ({ environmentSplitThread, project, backendClient }) => {
        const seed = environmentSplitThread;

        const read = async (args: { environment?: string; search?: string }) =>
          backendClient.listThreads({
            projectId: project.id,
            size: 100,
            ...(args.environment === undefined
              ? {}
              : { filters: environmentFilter(args.environment) }),
            ...(args.search === undefined ? {} : { search: args.search }),
          });

        const threadCount = async (args: { environment?: string; search?: string }) => {
          const stats = await backendClient.getThreadsStats({
            projectId: project.id,
            ...(args.environment === undefined
              ? {}
              : { filters: environmentFilter(args.environment) }),
            ...(args.search === undefined ? {} : { search: args.search }),
          });
          // `numericStat` asserts the stat is PRESENT before narrowing it: this
          // endpoint answers an empty stats list rather than a zeroed count when
          // it matches nothing, so a reader defaulting a missing `thread_count`
          // to 0 would turn "the server reported nothing" into a passing number.
          return numericStat(stats.thread_count, 'thread_count');
        };

        const subjectRow = (
          answer: { total: number; threads: ThreadRowRef[] },
          what: string,
        ): ThreadRowRef => {
          // The whole answer, not just that the subject is in it — a read that
          // also carried the decoy is the leak this spec exists to catch.
          expect(
            answer.threads.map((t) => t.id),
            `${what} lists the split conversation and nothing else`,
          ).toEqual([seed.threadId]);
          expect(answer.total, `${what} reports exactly one thread`).toBe(1);
          return answer.threads[0];
        };

        await test.step('Unfiltered and unsearched, the conversation reports all three turns', async () => {
          const answer = await read({});
          expect(
            answer.threads.map((t) => t.id).sort(),
            'both seeded threads are listed',
          ).toEqual(seed.allThreadIds);
          expect(answer.total, 'and the envelope reports both').toBe(2);

          const row = answer.threads.find((t) => t.id === seed.threadId)!;
          expect(row.numberOfMessages, 'two messages per turn, over all three').toBe(
            seed.expectedMessageCountAll,
          );
          expect(
            row.usage?.total_tokens,
            'and the tokens of every turn, both environments',
          ).toBe(seed.expectedTotalTokensAll);
        });

        const filteredOnly = await test.step(
          `Filtered to "${seed.includedEnvironment}" with no search, the row reports only its included turns`,
          async () => {
            const answer = await read({ environment: seed.includedEnvironment });
            expect(
              answer.threads.map((t) => t.id).sort(),
              'the filter keeps the split conversation and the wholly-included decoy',
            ).toEqual(seed.allThreadIds);
            expect(answer.total, 'and reports both').toBe(2);

            const row = answer.threads.find((t) => t.id === seed.threadId)!;
            // The narrowed aggregate, pinned on its own. Every composed read
            // below is compared against THIS row, so it has to be the right one
            // first — and it is a different number from the unfiltered row, so
            // a filter that was ignored fails here rather than three steps later.
            expect(
              row.numberOfMessages,
              'two messages per INCLUDED turn, and none for the excluded one',
            ).toBe(seed.expectedMessageCountIncluded);
            expect(
              row.numberOfMessages,
              'which is fewer than the unfiltered row, so the filter did narrow',
            ).toBeLessThan(seed.expectedMessageCountAll);
            expect(
              row.usage?.total_tokens,
              'and only the included turns\' tokens',
            ).toBe(seed.expectedTotalTokensIncluded);
            expect(
              row.totalEstimatedCost,
              'the narrowed row is still priced',
            ).not.toBeNull();
            expect(row.totalEstimatedCost!, 'and priced above zero').toBeGreaterThan(0);
            return row;
          },
        );

        await test.step(
          `A term on the EXCLUDED turn, under the filter, matches no thread`,
          async () => {
            // The behaviour 9304 added. The matching trace exists and carries
            // the term; it simply does not pass the filter, so its thread must
            // not be listed. Before the fix the filters sat only in the outer
            // query, so the search subquery matched the excluded trace and the
            // thread came back.
            const answer = await read({
              environment: seed.includedEnvironment,
              search: seed.excludedTerm,
            });
            expect(
              answer.threads,
              'the only trace carrying this term is outside the filter, so no thread matches',
            ).toEqual([]);
            expect(answer.total, 'and the envelope reports none').toBe(0);

            // `/threads/stats` is an independently written query over the same
            // clause, and the count card above the table is what a user reads.
            // It answers an EMPTY stats list rather than a zeroed count when it
            // matches nothing, so the shape is what is asserted — a `thread_count`
            // of any value here would mean the two queries disagree.
            const stats = await backendClient.getThreadsStats({
              projectId: project.id,
              filters: environmentFilter(seed.includedEnvironment),
              search: seed.excludedTerm,
            });
            expect(
              Object.keys(stats).sort(),
              'the stats endpoint reports nothing at all for a composed read that matched nothing',
            ).toEqual([]);
          },
        );

        await test.step(
          'The same term WITHOUT the filter returns the whole conversation',
          async () => {
            // The control that rules out "the search stopped working". Without
            // it, the empty answer above is equally explained by a clause that
            // never matches this term at all.
            const answer = await read({ search: seed.excludedTerm });
            const row = subjectRow(answer, 'the unfiltered searched listing');
            expect(
              row.numberOfMessages,
              'and the row still reports every turn, as a searched row must',
            ).toBe(seed.expectedMessageCountAll);
            expect(
              await threadCount({ search: seed.excludedTerm }),
              'the count card agrees that one thread carries the term',
            ).toBe(1);
          },
        );

        await test.step(
          `A term on an INCLUDED turn, under the filter, returns the narrowed row`,
          async () => {
            // The other half of the composition, and the control that rules out
            // "the filter rejects every searched read". The answer must be the
            // filter-only row, field for field: the search decided WHICH threads
            // are listed and the filter decided which of the thread's traces the
            // aggregate covers.
            const answer = await read({
              environment: seed.includedEnvironment,
              search: seed.includedTerm,
            });
            const row = subjectRow(answer, 'the composed listing');
            expect(
              {
                numberOfMessages: row.numberOfMessages,
                usage: row.usage,
                duration: row.duration,
                startTime: row.startTime,
                endTime: row.endTime,
                firstMessage: row.firstMessage,
                lastMessage: row.lastMessage,
              },
              'the composed row is the filter-only row, field for field',
            ).toEqual({
              numberOfMessages: filteredOnly.numberOfMessages,
              usage: filteredOnly.usage,
              duration: filteredOnly.duration,
              startTime: filteredOnly.startTime,
              endTime: filteredOnly.endTime,
              firstMessage: filteredOnly.firstMessage,
              lastMessage: filteredOnly.lastMessage,
            });
            expect(
              row.totalEstimatedCost,
              'including its cost',
            ).toBeCloseTo(filteredOnly.totalEstimatedCost!, 6);
            // And the search really narrowed inside the filter: the filter alone
            // answered two threads, the composition answers one. Without the
            // decoy this would be the same row either way.
            expect(
              (await read({ environment: seed.includedEnvironment })).total,
              'the filter alone kept more than one thread, so the search did narrow',
            ).toBeGreaterThan(1);

            const stats = await backendClient.getThreadsStats({
              projectId: project.id,
              filters: environmentFilter(seed.includedEnvironment),
              search: seed.includedTerm,
            });
            expect(
              numericStat(stats.thread_count, 'thread_count'),
              'the count card agrees with the composed listing',
            ).toBe(1);
            expect(
              numericStat(stats.total_estimated_cost_sum, 'total_estimated_cost_sum'),
              'and its cost sum is the narrowed row\'s cost, not the whole conversation\'s',
            ).toBeCloseTo(filteredOnly.totalEstimatedCost!, 6);
          },
        );

        await test.step(
          'The decoy, which lives wholly inside the filter, carries neither term',
          async () => {
            // Stated as the negative so "exactly the split conversation" above
            // is about the search rather than about the decoy having been
            // filtered out: it passes the filter, it simply carries no term.
            for (const term of [seed.includedTerm, seed.excludedTerm]) {
              const answer = await read({
                environment: seed.includedEnvironment,
                search: term,
              });
              expect(
                answer.threads.map((t) => t.id),
                `the decoy does not carry '${term}'`,
              ).not.toContain(seed.decoyThreadId);
            }
          },
        );
      },
    );

    test(
      'the Threads tab shows the same bound through its environment select and search box',
      { tag: ['@cap:threads.free-text-search', '@cap:threads.environment-filter'] },
      async ({ environmentSplitThread, project, backendClient, page }) => {
        const seed = environmentSplitThread;
        const logs = new LogsPage(page);

        await test.step('Ask the API for both composed answers first', async () => {
          // So that "the table is empty" and "the table shows one row" below are
          // compared against the endpoint's own answers rather than taken on
          // faith — the empty state the table renders for a no-match read is the
          // same one it renders for a FAILED request.
          const excluded = await backendClient.listThreads({
            projectId: project.id,
            filters: environmentFilter(seed.includedEnvironment),
            search: seed.excludedTerm,
            size: 100,
          });
          expect(excluded.total, 'the excluded term composes to nothing').toBe(0);
          const included = await backendClient.listThreads({
            projectId: project.id,
            filters: environmentFilter(seed.includedEnvironment),
            search: seed.includedTerm,
            size: 100,
          });
          expect(
            included.threads.map((t) => t.id),
            'and the included term composes to the split conversation alone',
          ).toEqual([seed.threadId]);
          expect(
            included.threads[0].numberOfMessages,
            'reporting only its included turns',
          ).toBe(seed.expectedMessageCountIncluded);
        });

        await test.step(
          `Open Logs → Threads scoped to "${seed.includedEnvironment}"`,
          async () => {
            // Through the URL param rather than the dropdown: the param outranks
            // the per-project localStorage memory `useLogsEnvironment` keeps, so
            // this is the only way to state a known starting point. The fixture
            // has already waited for the name to reach the workspace registry,
            // without which the tab would drop the param as unknown.
            await logs.gotoThreads(project.id, {
              timeRange: TIME_RANGE,
              size: 100,
              environment: seed.includedEnvironment,
            });
            await logs.waitForThreadsReady(undefined, { timeout: 60_000 });
            expect(
              await logs.activeLogsTab(),
              'the Threads tab is the one on screen — `logsType` is persisted per project',
            ).toBe('threads');
            expect(
              logs.currentEnvironmentParam(),
              'the environment survived as a param, so the registry knows the name',
            ).toBe(seed.includedEnvironment);
            await expect(
              logs.environmentFilterTrigger(seed.includedEnvironment),
              'and the select names the environment the table is scoped to',
            ).toHaveCount(1);
          },
        );

        await test.step('Scoped but unsearched, the row shows only its included turns', async () => {
          await expect
            .poll(() => logs.readPaginationTotal(), {
              message: 'the footer reports the split conversation and the decoy',
              timeout: 60_000,
            })
            .toBe(seed.allThreadIds.length);
          expect(
            await logs.readThreadMessageCount(seed.threadId),
            'the Message count cell counts the included turns only',
          ).toBe(seed.expectedMessageCountIncluded);
          expect(
            await logs.readThreadTotalTokens(seed.threadId),
            'as does the Total tokens cell',
          ).toBe(seed.expectedTotalTokensIncluded);
        });

        await test.step(
          `Searching the EXCLUDED turn's term empties the table`,
          async () => {
            await logs.searchFor(seed.excludedTerm, 'threads');
            await expect(
              logs.threadRows,
              'no row survives: the only trace carrying the term is outside the environment',
            ).toHaveCount(0);
            await expect(
              logs.noMatchingResults,
              'and the table says so rather than showing a stale page',
            ).toBeVisible();
          },
        );

        await test.step(
          `Searching an INCLUDED turn's term brings back the narrowed row`,
          async () => {
            await logs.searchFor(seed.includedTerm, 'threads');
            await expect
              .poll(() => logs.readPaginationTotal(), {
                message: 'the footer narrows to the one thread the composition keeps',
                timeout: 60_000,
              })
              .toBe(1);
            await expect(
              logs.threadRows,
              'and the table body holds exactly that row',
            ).toHaveCount(1);
            await expect(
              logs.threadRow(seed.decoyThreadId),
              'the decoy passes the filter but carries no term, so it is gone',
            ).toHaveCount(0);
            expect(
              await logs.readThreadMessageCount(seed.threadId),
              'and the surviving row still reports its included turns, not the matching one alone',
            ).toBe(seed.expectedMessageCountIncluded);
            expect(
              await logs.readThreadTotalTokens(seed.threadId),
              'as do its tokens',
            ).toBe(seed.expectedTotalTokensIncluded);
          },
        );

        await test.step(
          'Dropping the environment while the excluded term is searched brings the thread back whole',
          async () => {
            // The UI form of the control: the same term that emptied the table
            // under the filter returns the conversation — with ALL of its turns,
            // since nothing now bounds the aggregate.
            await logs.searchFor(seed.excludedTerm, 'threads');
            await expect(
              logs.threadRows,
              'still nothing while the environment is applied',
            ).toHaveCount(0);

            await logs.gotoThreads(project.id, { timeRange: TIME_RANGE, size: 100 });
            await logs.waitForThreadsReady(undefined, { timeout: 60_000 });
            expect(
              logs.currentEnvironmentParam(),
              'the environment is no longer scoping the table',
            ).toBeNull();
            await logs.searchFor(seed.excludedTerm, 'threads');
            await expect
              .poll(() => logs.readPaginationTotal(), {
                message: 'the unscoped search finds the conversation',
                timeout: 60_000,
              })
              .toBe(1);
            expect(
              (await logs.readThreadIdsOnPage()),
              'which is the split conversation',
            ).toEqual([seed.threadId]);
            expect(
              await logs.readThreadMessageCount(seed.threadId),
              'and with no environment bounding it, the row reports every turn',
            ).toBe(seed.expectedMessageCountAll);
          },
        );
      },
    );
  },
);
