import { test, expect } from '@e2e/fixtures';
import { numericStat, type ThreadRowRef } from '@e2e/core/backend';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * A searched thread row reports the WHOLE conversation's aggregate, not the
 * matching trace's (opik#9304, OPIK-9051).
 *
 * Before the fix, `ThreadDAO`'s search clause selected TRACE ids and the thread
 * aggregate was then computed over that subset, so a search that matched one
 * turn of a six-message conversation answered a row claiming two messages, one
 * turn's cost and tokens, one turn's duration, and that turn's input and output
 * as the conversation's first and last message. 9304 made the clause select
 * THREAD ids instead, so a search decides *which threads* are listed and
 * nothing about what each row reports.
 *
 * ## Why no existing spec catches this
 *
 * `thread-free-text-search.spec.ts` is the right spec and even has a step named
 * "Every searched row still carries its aggregates" — but its fixture seeds ONE
 * trace per thread, deliberately and in as many words ("One trace per thread
 * rather than several: the subject is which threads come back"). With one trace
 * per thread the matching trace IS the whole thread, so the pre-fix and
 * post-fix queries return identical aggregates and that step asserts
 * `numberOfMessages === 2` either way. The near misses are near for the mirror
 * reason: `thread-logging-smoke` asserts a multi-turn count but never searches,
 * `thread-moved-trace-aggregate` asserts multi-trace aggregates through
 * `/threads/retrieve` with no `search`, and `thread-id-prefilter` asserts
 * filtered aggregates with no `search`. The gap is exactly the intersection —
 * a search, and a thread with more traces than match.
 *
 * ## What makes the answer a finding
 *
 * The regression is silent. A row showing one turn's cost and message count
 * renders as a perfectly ordinary row: nothing is missing, no error is shown,
 * and the footer total agrees with it. So the subject thread's turns are seeded
 * with token counts whose every SUBSET sums to a different number, the marker
 * sits on the MIDDLE turn (so a narrowed row differs in `first_message` and
 * `last_message` too), and the pre-fix answer is named on the fixture's ref and
 * asserted against explicitly rather than merely being different from the one
 * expected.
 *
 * Both surfaces, because the candidate is `both`: the API is where the
 * aggregate is computed and the Threads table is where a user reads these five
 * values, and a disagreement between them is its own bug.
 */
test.describe(
  'Threads — a searched row keeps the whole conversation',
  { tag: ['@t2-cuj', '@area:threads'] },
  () => {
    /** Two batch writes, two aggregation polls, several reads, and a UI pass. */
    test.setTimeout(300_000);

    /** Bounded, because `ThreadsTab` excludes the `alltime` preset. */
    const TIME_RANGE = 'past24hours';

    /**
     * The fields a narrowed aggregate would corrupt, compared as one object so
     * a mismatch reports every differing field at once rather than the first.
     *
     * `status` is deliberately absent: a thread flips from active to inactive on
     * its own timeout, so including it would make two reads taken minutes apart
     * disagree for a reason that has nothing to do with the search.
     */
    const comparable = (row: ThreadRowRef) => ({
      id: row.id,
      numberOfMessages: row.numberOfMessages,
      totalEstimatedCost: row.totalEstimatedCost,
      usage: row.usage,
      duration: row.duration,
      startTime: row.startTime,
      endTime: row.endTime,
      firstMessage: row.firstMessage,
      lastMessage: row.lastMessage,
    });

    const onlyRow = (
      answer: { total: number; threads: ThreadRowRef[] },
      threadId: string,
      what: string,
    ): ThreadRowRef => {
      // The whole answer, not just that the subject is in it: a read that also
      // carried the decoy would satisfy a `find()` and is exactly the leak this
      // spec would otherwise miss.
      expect(
        answer.threads.map((t) => t.id),
        `${what} lists the subject thread and nothing else`,
      ).toEqual([threadId]);
      expect(answer.total, `${what} reports exactly one thread as the population`).toBe(1);
      return answer.threads[0];
    };

    test(
      'the search narrows which threads are listed, not what the matching row reports',
      { tag: ['@cap:threads.free-text-search'] },
      async ({ searchedThreadAggregate, project, backendClient }) => {
        const seed = searchedThreadAggregate;
        const subject = seed.subject;

        const baseline = await test.step(
          'Unsearched, the project serves both conversations and the subject reports all three turns',
          async () => {
            const answer = await backendClient.listThreads({
              projectId: project.id,
              size: 100,
            });
            expect(
              answer.threads.map((t) => t.id).sort(),
              'both seeded threads are listed',
            ).toEqual(seed.allThreadIds);
            expect(answer.total, 'and the envelope reports both').toBe(2);

            const row = answer.threads.find((t) => t.id === subject.threadId)!;
            // The baseline is asserted in its own right, not merely recorded:
            // "the searched row equals the unsearched row" is satisfied by two
            // identical WRONG rows, so the unsearched one has to be pinned to
            // the numbers the seed wrote.
            expect(
              row.numberOfMessages,
              'two messages per turn, over all three turns',
            ).toBe(subject.expectedMessageCount);
            expect(row.usage, 'and the usage is every turn folded together').toMatchObject({
              total_tokens: subject.expectedTotalTokens,
              prompt_tokens: subject.expectedPromptTokens,
              completion_tokens: subject.expectedCompletionTokens,
            });
            expect(
              row.totalEstimatedCost,
              'the row was priced from its spans, so cost is present',
            ).not.toBeNull();
            expect(
              row.totalEstimatedCost!,
              'and priced above zero',
            ).toBeGreaterThan(0);
            expect(row.duration, 'and the conversation has a duration').not.toBeNull();
            expect(row.startTime, 'and a start').not.toBeNull();
            expect(row.endTime, 'and an end').not.toBeNull();
            // The endpoint columns, by the turn each must come from. A narrowed
            // aggregate reports the MATCHING turn on both ends, which is what
            // these two reject.
            expect(
              JSON.stringify(row.firstMessage),
              'first_message is the FIRST turn\'s input',
            ).toContain('turn 1:');
            expect(
              JSON.stringify(row.lastMessage),
              'last_message is the LAST turn\'s output',
            ).toContain('turn 3:');
            return row;
          },
        );

        await test.step('The decoy reports its own, different aggregate', async () => {
          // Stated so the comparison below is between two distinguishable rows.
          // If both threads reported the same numbers, a searched read that had
          // answered the wrong thread entirely would pass every assertion here.
          const answer = await backendClient.listThreads({ projectId: project.id, size: 100 });
          const decoyRow = answer.threads.find((t) => t.id === seed.decoy.threadId)!;
          expect(
            decoyRow.numberOfMessages,
            'the decoy reports its own turn count',
          ).toBe(seed.decoy.expectedMessageCount);
          expect(
            decoyRow.numberOfMessages,
            'which differs from the subject\'s, so the two rows are distinguishable',
          ).not.toBe(subject.expectedMessageCount);
        });

        await test.step(
          `Searching "${seed.marker}" — on the MIDDLE turn only — returns the subject row unchanged`,
          async () => {
            const answer = await backendClient.listThreads({
              projectId: project.id,
              search: seed.marker,
              size: 100,
            });
            const row = onlyRow(answer, subject.threadId, 'the searched listing');
            // The claim of the whole spec, field for field against the read
            // taken before any search existed.
            expect(
              comparable(row),
              'the searched row is the unsearched row, field for field',
            ).toEqual(comparable(baseline));
            // And the rejected answer, named. Without this the step above is
            // satisfied by any pair of equal rows; this is the number the
            // pre-fix query actually produced.
            expect(
              row.numberOfMessages,
              'and NOT the matching trace\'s two messages, which is the pre-fix answer',
            ).not.toBe(seed.matchingTraceOnly.messageCount);
            expect(
              row.usage?.total_tokens,
              'nor the matching trace\'s token count',
            ).not.toBe(seed.matchingTraceOnly.totalTokens);
          },
        );

        await test.step('The count card agrees with the searched listing', async () => {
          const stats = await backendClient.getThreadsStats({
            projectId: project.id,
            search: seed.marker,
          });
          // `numericStat` asserts the stat is PRESENT before narrowing it: this
          // endpoint answers an empty stats list rather than a zeroed count when
          // it matches nothing, so a reader defaulting a missing `thread_count`
          // to 0 would turn "the server reported nothing" into a passing number.
          expect(
            numericStat(stats.thread_count, 'thread_count'),
            'one thread carries the marker',
          ).toBe(1);
          // The cost sum is the half that would have been wrong before the fix
          // while the count was right: `/threads/stats` is an independently
          // written query over the same clause, so a narrowed aggregate there
          // reports the matching turn's cost under a correct thread count.
          expect(
            numericStat(stats.total_estimated_cost_sum, 'total_estimated_cost_sum'),
            'and the summed cost is the whole conversation\'s, not one turn\'s',
          ).toBeCloseTo(baseline.totalEstimatedCost!, 6);
        });

        await test.step('The same answer through the non-pushdown query copy', async () => {
          // `total_estimated_cost` is not in `PAGE_PUSHDOWN_SORT_FIELDS`, so a
          // cost sort takes the read off the id-only pre-pass and down the full
          // scan — a separate rendering of the same clause, which 9304 edited
          // separately. The SET is covered by `thread-search-cost-sort-copy`;
          // what is new here is the AGGREGATE that copy answers.
          const answer = await backendClient.listThreads({
            projectId: project.id,
            search: seed.marker,
            sorting: [{ field: 'total_estimated_cost', direction: 'DESC' }],
            size: 100,
          });
          const row = onlyRow(answer, subject.threadId, 'the cost-sorted searched listing');
          expect(
            comparable(row),
            'the full-scan copy answers the same whole-conversation row as the pushdown copy',
          ).toEqual(comparable(baseline));
        });

        await test.step('A term on no turn of either thread returns nothing', async () => {
          // The negative control. Every assertion above is about a search that
          // MATCHED; without this, a clause that ignored `search` entirely and
          // always answered the whole project would fail only the "and nothing
          // else" half — and that half would also fail for a genuine leak, so
          // the two causes need separating.
          const answer = await backendClient.listThreads({
            projectId: project.id,
            search: `${seed.marker}-no-such-term`,
            size: 100,
          });
          expect(answer.threads, 'no thread carries the unseeded term').toEqual([]);
          expect(answer.total, 'and the envelope reports none').toBe(0);
        });
      },
    );

    test(
      'the Threads table renders the same five values under the search as without it',
      { tag: ['@cap:threads.free-text-search'] },
      async ({ searchedThreadAggregate, project, backendClient, page }) => {
        const seed = searchedThreadAggregate;
        const subject = seed.subject;
        const logs = new LogsPage(page);

        /**
         * The five cells the manual check reads, as the table renders them.
         *
         * Read as text rather than parsed: `first_message` and `last_message`
         * go through `PrettyCell` and the cost through `formatCost`, so the
         * rendered string is the thing a user compares — and comparing the
         * strings before and after the search is exactly the claim. The two
         * numeric cells are also read as numbers below, so a pair of identical
         * `-` placeholders cannot pass as agreement.
         */
        const readRenderedRow = async () => ({
          messageCount: await logs.readThreadMessageCount(subject.threadId),
          totalTokens: await logs.readThreadTotalTokens(subject.threadId),
          cost: ((await logs.estimatedCostCell(subject.threadId).textContent()) ?? '').trim(),
          firstMessage: (
            (await logs.threadFirstMessageCell(subject.threadId).textContent()) ?? ''
          ).trim(),
          lastMessage: (
            (await logs.threadLastMessageCell(subject.threadId).textContent()) ?? ''
          ).trim(),
        });

        const apiRow = await test.step('Ask the API what the searched row should be', async () => {
          const answer = await backendClient.listThreads({
            projectId: project.id,
            search: seed.marker,
            size: 100,
          });
          // Asserted here as well as in the API test, because this test's
          // subject is the agreement between the page and the API: if the API
          // answer were itself narrowed, "the table matches the API" would be
          // satisfied by two wrong answers.
          expect(
            answer.threads.map((t) => t.id),
            'the API serves the subject thread alone for this term',
          ).toEqual([subject.threadId]);
          expect(
            answer.threads[0].numberOfMessages,
            'reporting the whole conversation',
          ).toBe(subject.expectedMessageCount);
          return answer.threads[0];
        });

        await test.step('Open the project Logs on the Threads tab', async () => {
          await logs.gotoThreads(project.id, { timeRange: TIME_RANGE, size: 100 });
          // A longer readiness window than the 15s default: the fixture has
          // waited for both threads to be aggregated server-side, but the first
          // paint of a cold Logs page over a freshly seeded project on a shared
          // cloud workspace has been seen to exceed it.
          await logs.waitForThreadsReady(undefined, { timeout: 60_000 });
          expect(
            await logs.activeLogsTab(),
            'the Threads tab is the one on screen — `logsType` is persisted per project',
          ).toBe('threads');
        });

        const kpiBefore = await test.step(
          'Unsearched, the table shows both conversations and the subject\'s own numbers',
          async () => {
            await expect
              .poll(() => logs.readPaginationTotal(), {
                message: 'the footer reports both seeded threads',
                timeout: 60_000,
              })
              .toBe(seed.allThreadIds.length);
            expect(
              (await logs.readThreadIdsOnPage()).sort(),
              'and renders exactly the seeded threads — the 24-hour window is not cutting the seed',
            ).toEqual(seed.allThreadIds);
            return logs.countThreads();
          },
        );

        const before = await test.step(
          'The rendered row reports every turn, opening on turn 1 and closing on turn 3',
          async () => {
            const rendered = await readRenderedRow();
            expect(
              rendered.messageCount,
              'the Message count cell is two per turn, over all three turns',
            ).toBe(subject.expectedMessageCount);
            expect(
              rendered.totalTokens,
              'and the Total tokens cell is every turn folded together',
            ).toBe(subject.expectedTotalTokens);
            // The two endpoint columns, by the turn each must come from. These
            // are what a narrowed aggregate changes most visibly, and they are
            // pinned BEFORE the search so the comparison afterwards is against
            // a known-correct render rather than against whatever was there.
            expect(
              rendered.firstMessage,
              'First message is the FIRST turn\'s input',
            ).toContain('turn 1:');
            expect(
              rendered.firstMessage,
              'and not the matching middle turn\'s',
            ).not.toContain('turn 2:');
            expect(
              rendered.lastMessage,
              'Last message is the LAST turn\'s output',
            ).toContain('turn 3:');
            expect(
              rendered.lastMessage,
              'and not the matching middle turn\'s',
            ).not.toContain('turn 2:');
            expect(
              rendered.cost,
              'and the Estimated cost cell renders a priced value rather than a placeholder',
            ).toMatch(/\d/);
            return rendered;
          },
        );

        await test.step(`Type "${seed.marker}" into Search by anything`, async () => {
          // Already lower-case and trimmed: `ThreadsTab` passes the RAW term to
          // the stats read and the trimmed, folded one to the listing, so a term
          // needing either treatment would have the table and its count card
          // asking different questions by construction.
          await logs.searchFor(seed.marker, 'threads');
          await expect
            .poll(() => logs.readPaginationTotal(), {
              message: 'the footer narrows to the one thread carrying the marker',
              timeout: 60_000,
            })
            .toBe(1);
          await expect(
            logs.threadRows,
            'and the table body repaints down to that one row',
          ).toHaveCount(1);
          await expect(
            logs.threadRow(seed.decoy.threadId),
            'the decoy, which carries the marker nowhere, is gone',
          ).toHaveCount(0);
        });

        await test.step('The surviving row renders exactly the five values it did before', async () => {
          const after = await readRenderedRow();
          expect(
            after,
            'the search changed WHICH rows are listed and nothing about this one',
          ).toEqual(before);
          // And against the API's own answer for the same term, so the page and
          // the endpoint are pinned to each other rather than only to history.
          expect(
            after.messageCount,
            'the rendered message count is the one the API answered',
          ).toBe(apiRow.numberOfMessages);
          expect(
            after.totalTokens,
            'as is the rendered token total',
          ).toBe(apiRow.usage?.total_tokens);
        });

        await test.step('The KPI count card above the table does NOT narrow', async () => {
          // The one place two numbers on this page legitimately disagree:
          // `useProjectKpiCards` takes the chip filters and the window and no
          // `search`, so the card stays a project-level overview while the
          // footer is the searched total. Asserted so a future change that
          // wired the search into the card is noticed deliberately rather than
          // read as this spec breaking.
          expect(
            await logs.countThreads(),
            'the card still reports the whole window, as it did before the search',
          ).toBe(kpiBefore);
          expect(
            kpiBefore,
            'and that is more than the searched footer total of 1',
          ).toBeGreaterThan(1);
        });
      },
    );
  },
);
