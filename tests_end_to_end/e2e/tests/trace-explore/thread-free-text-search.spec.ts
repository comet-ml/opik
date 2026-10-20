import { test, expect } from '@e2e/fixtures';
import { numericStat } from '@e2e/core/backend';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * Free-text search over THREADS (opik#8778 · OPIK-8206).
 *
 * `trace-span-free-text-search.spec.ts` covers the trace and span clauses, and
 * its taxonomy note says in as many words what it does not reach: thread search,
 * because `ThreadDAO` has a clause of its own — `ilike` over `thread_id`, `id`,
 * `input` and `output` of the traces it aggregates. opik#8778 put a week pre-pass
 * in front of exactly that clause (`traces_partitioned && search_text`), which is
 * reachable on no other combination of parameters, and the release report called
 * it unreachable from the estate because `logs.page.ts` modelled no search box on
 * this tab. The box is there — "Search by anything" — so this closes an estate
 * gap rather than reporting a product one.
 *
 * The failure mode is the quiet one that makes search worth testing at all. A
 * user reaches for it when they already know their conversation exists; a scan
 * bounded to the wrong weeks drops the thread from the listing, from the footer
 * total and from the count card at once, with no error anywhere. The page looks
 * perfectly healthy and the thread simply "isn't there".
 *
 * So every assertion is about EXACTNESS rather than membership. Finding the seven
 * matching threads in the answer would pass just as well against a search that
 * also returned the five decoys, and asserting a count alone would pass against
 * one that returned the right number of wrong rows — both sides are pinned: the
 * thread id set as a set, and the envelope's `total` beside it.
 *
 * **What this does NOT cover, and does not claim.** The week bound itself. Every
 * row here is seeded at `now`, because the Threads tab cannot be driven
 * unwindowed at all (`ThreadsTab` excludes the `alltime` preset) and because an
 * environment running `UUIDv7TimestampValidator` in reject mode with a sub-week
 * window — staging's is `PT24H` — refuses a backdated id outright. What is
 * covered is the clause the pre-pass fronts: that a search over threads returns
 * exactly the right conversations, on both payload columns, with the count card
 * and the rendered table agreeing. Driving the multi-week bound needs
 * `uuidValidation.auditOnly=true`; see `searchPopulation`'s header for the same
 * limitation on the trace side.
 */
test.describe(
  'Threads — free-text search over a conversation\'s input and output',
  { tag: ['@t2-cuj', '@area:threads'] },
  () => {
    /** Two batch writes, a thread-aggregation poll, then several reads. */
    test.setTimeout(300_000);

    /**
     * The date-range preset the UI half runs under.
     *
     * Stated rather than inherited, and bounded rather than `alltime`: the
     * Threads tab excludes the all-time preset, so its read is always windowed.
     * The whole seed is minutes old, so a 24-hour window holds all of it — which
     * the spec asserts as its unsearched baseline before trusting any searched
     * comparison.
     */
    const TIME_RANGE = 'past24hours';

    const sorted = (ids: string[]): string[] => [...ids].sort();

    test(
      'a search on either payload column returns exactly the matching threads, with the stats agreeing',
      { tag: ['@cap:threads.free-text-search'] },
      async ({ threadSearchPopulation, project, backendClient }) => {
        const population = threadSearchPopulation;
        const matchCount = population.matchingThreadIds.length;
        const allCount = population.allThreadIds.length;

        const readIds = async (search?: string) => {
          const answer = await backendClient.listThreads({
            projectId: project.id,
            size: 200,
            ...(search === undefined ? {} : { search }),
          });
          return { ids: sorted(answer.threads.map((t) => t.id)), total: answer.total };
        };

        await test.step('With no search, the project serves every seeded thread', async () => {
          const answer = await readIds();
          // The baseline every comparison below rests on. If the unsearched read
          // were already short, a searched read agreeing with the expectation
          // would mean nothing.
          expect(answer.total, 'the unsearched listing reports the whole population').toBe(
            allCount,
          );
          expect(
            answer.ids,
            'and serves exactly the seeded threads and nothing else',
          ).toEqual(population.allThreadIds);
        });

        await test.step(`A search for "${population.inputMarker}" matches on the trace INPUT`, async () => {
          const answer = await readIds(population.inputMarker);
          expect(
            answer.ids,
            'every thread whose trace input carries the marker, and no decoy',
          ).toEqual(population.matchingThreadIds);
          // The envelope as well as the rows: the footer a user reads comes from
          // `total`, so the right rows under a wrong total is still a wrong
          // answer on screen.
          expect(answer.total, 'and reports that many as the population').toBe(matchCount);
        });

        await test.step(`A search for "${population.outputMarker}" matches on the trace OUTPUT`, async () => {
          // Its own step and its own marker, not a second term for the same
          // rows: the fixture puts each marker on one column only, so this is
          // the half of the clause the input search cannot speak for. A build
          // that dropped `output` from the clause passes the step above and
          // fails this one.
          const answer = await readIds(population.outputMarker);
          expect(
            answer.ids,
            'every thread whose trace output carries the marker, and no decoy',
          ).toEqual(population.matchingThreadIds);
          expect(answer.total).toBe(matchCount);
        });

        await test.step('Each thread\'s own token resolves to exactly that one thread', async () => {
          // The narrowest real question a user asks of this box. Driven on both
          // columns per thread, because a clause that matched only the input
          // would still answer every broad search above correctly.
          for (const row of population.rows.filter((r) => r.matches)) {
            for (const [column, token] of [
              ['input', row.inputToken],
              ['output', row.outputToken],
            ] as const) {
              const answer = await readIds(token);
              expect(
                answer.ids,
                `searching the ${column} token '${token}' returns exactly its one thread`,
              ).toEqual([row.threadId]);
              expect(
                answer.total,
                `and the envelope agrees that one thread matched '${token}'`,
              ).toBe(1);
            }
          }
        });

        await test.step('The count card agrees with the listing, under each search and without one', async () => {
          // `/traces/threads/stats` is an independently written query from
          // `/traces/threads`, which is what makes comparing them worth doing
          // rather than circular — and the number it answers is the one rendered
          // above the table, read as often as the table itself.
          const cases: Array<{ label: string; search?: string; expected: number }> = [
            { label: 'no search', expected: allCount },
            { label: `"${population.inputMarker}"`, search: population.inputMarker, expected: matchCount },
            {
              label: `"${population.outputMarker}"`,
              search: population.outputMarker,
              expected: matchCount,
            },
          ];
          for (const scenario of cases) {
            const stats = await backendClient.getThreadsStats({
              projectId: project.id,
              ...(scenario.search === undefined ? {} : { search: scenario.search }),
            });
            // `numericStat` asserts the stat is PRESENT before narrowing it. The
            // endpoint answers an empty stats list rather than a zeroed count
            // when it matches nothing, so a reader that defaulted a missing
            // `thread_count` to 0 would turn "the server reported nothing" into
            // a passing number.
            expect(
              numericStat(stats.thread_count, 'thread_count'),
              `the count card under ${scenario.label} matches the listing`,
            ).toBe(scenario.expected);
          }
        });

        await test.step('Searching a trace NAME returns nothing, so the matches came from the payload', async () => {
          // The thread clause reads `thread_id`, `id`, `input` and `output` — not
          // the trace's name. Without this, a clause that had widened to match
          // anything the seed wrote would satisfy every assertion above.
          const answer = await readIds(population.traceNameOfFirstMatch);
          expect(
            answer.ids,
            `'${population.traceNameOfFirstMatch}' is a trace name, which thread search does ` +
              'not read — so no thread may come back for it',
          ).toEqual([]);
          expect(answer.total, 'and the envelope reports none').toBe(0);
        });

        await test.step('Every searched row still carries its aggregates', async () => {
          const answer = await backendClient.listThreads({
            projectId: project.id,
            search: population.inputMarker,
            size: 200,
          });
          expect(answer.threads, 'the searched read returned every matching row').toHaveLength(
            matchCount,
          );
          for (const row of answer.threads) {
            // Required, not conditional. These four are what the Threads table
            // renders in its cells, and a searched read that came back with the
            // right ids and empty aggregates is a blank table — which no count
            // assertion above would notice. The client types each as nullable
            // because an absent aggregate and a zero one are different answers
            // from this endpoint, so each must be asserted away rather than
            // coded around.
            expect(
              row.numberOfMessages,
              `thread ${row.id} reports a message count`,
            ).not.toBeNull();
            expect(
              row.numberOfMessages!,
              `thread ${row.id} aggregates its trace's two messages`,
            ).toBe(2);
            expect(row.duration, `thread ${row.id} reports a duration`).not.toBeNull();
            expect(row.duration!, `thread ${row.id} spans a positive duration`).toBeGreaterThan(0);
            expect(row.usage, `thread ${row.id} reports a usage map`).not.toBeNull();
            expect(
              row.totalEstimatedCost,
              `thread ${row.id} reports an estimated cost`,
            ).not.toBeNull();
            expect(
              row.totalEstimatedCost!,
              `thread ${row.id} was priced above zero from its span's usage`,
            ).toBeGreaterThan(0);
          }
        });
      },
    );

    test(
      'the Threads search box renders exactly the rows the API answers for the same term',
      { tag: ['@cap:threads.free-text-search'] },
      async ({ threadSearchPopulation, project, backendClient, page }) => {
        const population = threadSearchPopulation;
        const matchCount = population.matchingThreadIds.length;
        const allCount = population.allThreadIds.length;
        const logs = new LogsPage(page);

        // Its own test rather than more steps above, and not a second copy of
        // those assertions: the box and the read are wired independently — the
        // input commits `threads_search`, and `useThreadList` turns that into the
        // `search` param — so what is checked here is the AGREEMENT between what
        // the page shows and what the API says for the identical term.
        await test.step('Open the project Logs on the Threads tab, whole seed on one page', async () => {
          await logs.gotoThreads(project.id, { timeRange: TIME_RANGE, size: 100 });
          // A longer readiness window than the 15s default: the fixture has
          // already waited for every thread to be aggregated server-side, but the
          // first paint of a cold Logs page over a freshly seeded project on a
          // shared cloud workspace has been seen to exceed it on the Traces tab.
          await logs.waitForThreadsReady(undefined, { timeout: 60_000 });
          expect(
            await logs.activeLogsTab(),
            'the Threads tab is the one on screen — `logsType` is persisted per project',
          ).toBe('threads');
        });

        await test.step('Before searching, the table shows the whole seeded project', async () => {
          await expect
            .poll(() => logs.readPaginationTotal(), {
              message: 'the footer reports the whole seeded population',
              timeout: 60_000,
            })
            .toBe(allCount);
          // Also the proof that the 24-hour window is not cutting the seed:
          // every later comparison would be meaningless over a baseline that was
          // already short.
          await expect(logs.threadRows, 'the table body paints every row').toHaveCount(allCount);
          expect(
            sorted(await logs.readThreadIdsOnPage()),
            'and renders exactly the seeded threads',
          ).toEqual(population.allThreadIds);
        });

        const apiAnswer = await test.step('Ask the API the same question', async () => {
          const answer = await backendClient.listThreads({
            projectId: project.id,
            search: population.inputMarker,
            size: 200,
          });
          // Asserted here as well as in the API test, because this test's subject
          // is the agreement between the two: if the API answer were itself
          // wrong, "the table agrees with the API" would be satisfied by two
          // wrong answers.
          expect(
            sorted(answer.threads.map((t) => t.id)),
            'the API serves exactly the matching threads',
          ).toEqual(population.matchingThreadIds);
          return answer;
        });

        await test.step(`Type "${population.inputMarker}" into Search by anything`, async () => {
          // Already lower-case and trimmed, deliberately: `ThreadsTab` passes the
          // RAW term to the stats read and the trimmed, folded one to the
          // listing, so a term needing either treatment would have the table and
          // its count card asking different questions by construction.
          await logs.searchFor(population.inputMarker, 'threads');
        });

        await test.step('The footer, the rendered rows and the API all say the same thing', async () => {
          await expect
            .poll(() => logs.readPaginationTotal(), {
              message: 'the footer narrows to the searched population',
              timeout: 60_000,
            })
            .toBe(apiAnswer.total);
          await expect(
            logs.threadRows,
            'the table body repaints down to the searched population',
          ).toHaveCount(apiAnswer.total);

          const rendered = sorted(await logs.readThreadIdsOnPage());
          expect(
            rendered,
            'the table renders exactly the rows the API returned for this term',
          ).toEqual(sorted(apiAnswer.threads.map((t) => t.id)));
          // The same fact as an exhaustion over the seed, so a build that
          // rendered the right COUNT of wrong rows fails here.
          expect(rendered, 'which is every matching thread').toEqual(
            population.matchingThreadIds,
          );
          expect(
            rendered.length,
            'and strictly fewer rows than the unsearched table held',
          ).toBe(matchCount);
        });

        await test.step('The KPI card above the table keeps reporting the whole window', async () => {
          // Deliberately asserted as UNCHANGED, which is the opposite of what the
          // footer does one step above, and the one place the two numbers on this
          // page legitimately disagree. The card row comes from
          // `useProjectKpiCards`, whose params are the project, the entity type,
          // the chip `filters` and the time window — there is no `search` among
          // them — so it is a project-level overview of the window rather than a
          // count of the searched listing. The number a search narrows is the
          // table footer, and the number that must agree with the searched
          // listing is `/traces/threads/stats`, which the API test above pins.
          //
          // Stated rather than left silent so a reader is not left wondering
          // whether 12-above-7 is a defect, and so that wiring search into the
          // card later arrives here as a review conversation instead of as a
          // quietly changed page.
          expect(
            await logs.countThreads(),
            'the KPI count card still reports every thread in the window, search or not',
          ).toBe(allCount);
        });

        await test.step('No decoy row survived the search', async () => {
          // Stated as the negative too. The set comparison above implies it, but
          // a decoy left in the DOM outside the table's row set — a stale render,
          // a duplicated body — would pass that and fail this.
          for (const decoy of population.rows.filter((row) => !row.matches)) {
            await expect(
              logs.threadRow(decoy.threadId),
              `the non-matching thread '${decoy.threadId}' is not on screen`,
            ).toHaveCount(0);
          }
        });

        await test.step('Clearing the search restores the whole project', async () => {
          await logs.clearSearch('threads');
          await expect
            .poll(() => logs.readPaginationTotal(), {
              message: 'the footer returns to the whole population',
              timeout: 60_000,
            })
            .toBe(allCount);
          await expect(
            logs.threadRows,
            'the table body repaints back up to the whole population',
          ).toHaveCount(allCount);
          expect(
            sorted(await logs.readThreadIdsOnPage()),
            'and every seeded thread is back, so the search narrowed rather than deleted',
          ).toEqual(population.allThreadIds);
        });
      },
    );
  },
);
