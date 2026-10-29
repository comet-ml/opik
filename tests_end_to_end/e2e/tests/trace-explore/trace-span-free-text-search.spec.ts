import { test, expect } from '@e2e/fixtures';
import type { ProjectStatRef } from '@e2e/core/backend';
import { uuid7Moment } from '@e2e/core/backend';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * Free-text search over traces and spans (opik#8767 · OPIK-8206).
 *
 * The estate types into no search box at all. `traces.filter-traces` drives the
 * structured filter chips, which never render `search_text`, so the whole
 * `ilike('%term%')` branch of `TraceDAO`/`SpanDAO` — and the week pre-pass
 * opik#8767 added in front of it — has never been reached by any e2e test. The
 * backend's own `TracesSearchPartitionPruningTest` covers the pruning in
 * isolation; nothing covered the user-visible answer.
 *
 * What makes it worth a spec is the shape of the failure. Search is the control
 * a user reaches for when they already know their trace exists, and a scan
 * bounded to the wrong weeks drops rows from the listing, from the footer total
 * and from the stats row all at once, with no error anywhere. The page looks
 * entirely healthy; the trace simply "isn't there".
 *
 * So every assertion here is about EXACTNESS, never membership. Finding the
 * seeded rows in the answer would pass just as well against a search that also
 * returned the eleven decoys, and asserting a count alone would pass against
 * one that returned the right number of wrong rows. Both sides are pinned: the
 * id set as a set, and the envelope's `total` beside it.
 *
 * Deterministic by construction — the `searchPopulation` fixture mints every id
 * and writes every row over REST, so there is no LLM, no wall clock and no
 * dependence on what the workspace already holds. See its header for why the
 * one-character search is exact and why the group axis crosses the match axis.
 *
 * **What this does NOT cover, and does not claim.** The multi-week bound is
 * exercised only as far as the environment allows an id to be backdated:
 * `searchPopulation` asks for three weeks and falls back to one where
 * `UUIDv7TimestampValidator` runs in reject mode with a sub-week window
 * (staging's is `PT24H`). The per-week assertion below is written over whatever
 * spread was achieved and reports it, so a single-week run is visible in the
 * output rather than passing as a multi-week one. To actually drive the bound,
 * run this against a deployment with `uuidValidation.auditOnly=true`.
 */
test.describe(
  'Trace Explore — free-text search over traces and spans',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    /** Two batch writes plus a visibility poll, then several paged reads. */
    test.setTimeout(300_000);

    const sorted = (ids: string[]): string[] => [...ids].sort();

    /**
     * Read one named stat out of a stats answer, asserting it is there first.
     *
     * The assertion is the point. `/traces/stats` and `/spans/stats` answer
     * `{"stats": []}` — not a zeroed count — for a query that matches no row,
     * so a reader that defaulted a missing `trace_count` to 0 would turn "the
     * server reported nothing" into a passing number, which is exactly the
     * failure mode this spec exists to catch.
     */
    const countStat = (stats: ProjectStatRef[], name: string): number => {
      const stat = stats.find((candidate) => candidate.name === name);
      expect(
        stat,
        `the stats answer must carry '${name}' — an empty stats list is what this ` +
          'endpoint returns when it matched nothing, and must not read as zero',
      ).toBeDefined();
      expect(typeof stat!.value, `'${name}' must be a number`).toBe('number');
      return stat!.value as number;
    };

    /**
     * The Monday-00:00-UTC bucket an id's embedded instant falls in.
     *
     * The same grouping the backend's week pre-pass uses, re-derived here from
     * the id rather than from the row's `start_time`: the bound is computed off
     * the UUIDv7 timestamp, so that is the axis an assertion about weeks has to
     * be stated on.
     */
    const weekOf = (id: string): string => {
      const moment = uuid7Moment(id);
      const midnight = Date.UTC(
        moment.getUTCFullYear(),
        moment.getUTCMonth(),
        moment.getUTCDate(),
      );
      const mondayOffset = (new Date(midnight).getUTCDay() + 6) % 7;
      return new Date(midnight - mondayOffset * 86_400_000).toISOString().slice(0, 10);
    };

    /** How many of `ids` fall in each week bucket, as a plain map. */
    const countByWeek = (ids: string[]): Record<string, number> => {
      const counts: Record<string, number> = {};
      for (const id of ids) {
        const week = weekOf(id);
        counts[week] = (counts[week] ?? 0) + 1;
      }
      return counts;
    };

    /**
     * Walk a searched listing one small page at a time and return every id it
     * served, in order and WITH duplicates kept.
     *
     * Duplicates are preserved rather than collapsed because a re-served page
     * is a finding, not noise: a cursor that double-counted a row would
     * otherwise be indistinguishable from one that behaved, once the ids were
     * reduced to a set.
     *
     * `size` is deliberately small and does not divide the expected population
     * evenly, so the last page is short — a reader that stopped on a full page
     * would be caught.
     */
    const walkPages = async (
      read: (page: number) => Promise<{ ids: string[]; total: number; size: number }>,
      pageSize: number,
      expectedTotal: number,
    ): Promise<{ ids: string[]; totals: number[]; pageSizes: number[] }> => {
      const ids: string[] = [];
      const totals: number[] = [];
      const pageSizes: number[] = [];
      const pageCount = Math.ceil(expectedTotal / pageSize);
      // A fixed number of pages derived from the expected population, rather
      // than looping until a short page: a build that lost rows would end the
      // walk early and the assertions below would never see the pages it
      // skipped.
      for (let page = 1; page <= pageCount; page++) {
        const answer = await read(page);
        ids.push(...answer.ids);
        totals.push(answer.total);
        pageSizes.push(answer.ids.length);
      }
      return { ids, totals, pageSizes };
    };

    test(
      'a search over traces returns exactly the matching rows, in the listing, across pages and in the stats',
      { tag: ['@cap:traces.free-text-search'] },
      async ({ searchPopulation, project, backendClient }) => {
        const population = searchPopulation;
        const matchCount = population.matchingTraceIds.length;
        const allCount = population.allTraceIds.length;

        await test.step('With no search, the project serves every seeded trace', async () => {
          const answer = await backendClient.searchTraceIdsPage({
            projectId: project.id,
            size: 200,
          });
          // The baseline both sides of every comparison below rest on. If the
          // unsearched read were already short, a searched read agreeing with
          // the expectation would mean nothing.
          expect(answer.total, 'the unsearched listing reports the whole population').toBe(
            allCount,
          );
          expect(
            sorted(answer.ids),
            'the unsearched listing serves exactly the seeded traces and nothing else',
          ).toEqual(population.allTraceIds);
        });

        await test.step(`Searching "${population.needle}" returns exactly the ${matchCount} that carry it`, async () => {
          const answer = await backendClient.searchTraceIdsPage({
            projectId: project.id,
            search: population.needle,
            size: 200,
          });
          expect(
            sorted(answer.ids),
            'the search serves every matching trace and no decoy',
          ).toEqual(population.matchingTraceIds);
          // The envelope as well as the rows: the footer the user reads comes
          // from `total`, so a listing that served the right rows under a wrong
          // total is still a wrong answer on screen.
          expect(answer.total, 'and reports that many as the population').toBe(matchCount);
        });

        await test.step(`A one-character search for "${population.needleChar}" has the same answer`, async () => {
          const answer = await backendClient.searchTraceIdsPage({
            projectId: project.id,
            search: population.needleChar,
            size: 200,
          });
          expect(
            sorted(answer.ids),
            'the narrowest possible term still resolves to exactly the matching traces',
          ).toEqual(population.matchingTraceIds);
          expect(answer.total).toBe(matchCount);
        });

        await test.step('Walking that search three rows at a time loses nothing and repeats nothing', async () => {
          const walked = await walkPages(
            (page) =>
              backendClient.searchTraceIdsPage({
                projectId: project.id,
                search: population.needle,
                page,
                size: 3,
              }),
            3,
            matchCount,
          );

          expect(
            walked.ids.length,
            'the pages add up to the population, with no row served twice and none dropped',
          ).toBe(matchCount);
          expect(sorted(walked.ids), 'and they are exactly the matching traces').toEqual(
            population.matchingTraceIds,
          );
          expect(
            new Set(walked.ids).size,
            'no id appeared on two pages',
          ).toBe(walked.ids.length);
          // 14 at 3 per page is 3,3,3,3,2 — deliberately not an even division,
          // so a reader that stopped on the first full page is caught.
          expect(walked.pageSizes, 'the last page is short, and every earlier one is full').toEqual(
            [3, 3, 3, 3, 2],
          );
          expect(
            walked.totals,
            'every page reports the same whole population in its envelope',
          ).toEqual(Array(walked.totals.length).fill(matchCount));
        });

        await test.step('The stats agree with the listing, under the search and without it', async () => {
          const unsearched = await backendClient.entityStats({
            entity: 'traces',
            projectId: project.id,
          });
          const searched = await backendClient.entityStats({
            entity: 'traces',
            projectId: project.id,
            search: population.needle,
          });
          expect(
            countStat(unsearched, 'trace_count'),
            'the unsearched stats count the whole project',
          ).toBe(allCount);
          expect(
            countStat(searched, 'trace_count'),
            'and the searched stats count exactly the matching traces — the row of ' +
              'numbers above the table is read as often as the table itself',
          ).toBe(matchCount);
        });

        await test.step('Composing the search with a name filter intersects the two', async () => {
          const groupFilter = [
            { field: 'name', operator: 'contains', value: population.filterGroup },
          ];
          const filterOnly = await backendClient.searchTraceIdsPage({
            projectId: project.id,
            filters: groupFilter,
            size: 200,
          });
          const both = await backendClient.searchTraceIdsPage({
            projectId: project.id,
            search: population.needle,
            filters: groupFilter,
            size: 200,
          });

          // The filter alone is asserted first and is strictly larger than the
          // intersection: without it, "both returned six rows" is equally well
          // explained by a server that ignored the search and by one that
          // ignored the filter.
          expect(
            sorted(filterOnly.ids),
            `the filter alone serves every trace in group ${population.filterGroup}, matching or not`,
          ).toEqual(population.filterGroupTraceIds);
          expect(
            sorted(both.ids),
            'and composed with the search it serves exactly the rows satisfying both',
          ).toEqual(population.intersectionTraceIds);
          expect(both.total, 'the composed total is the intersection too').toBe(
            population.intersectionTraceIds.length,
          );
          expect(
            population.intersectionTraceIds.length,
            'the intersection is strictly smaller than either side, or this step proves nothing',
          ).toBeLessThan(
            Math.min(population.filterGroupTraceIds.length, population.matchingTraceIds.length),
          );
        });

        await test.step(
          `Every week the project has rows in is represented — ${population.weeksCovered} of ` +
            `${population.weeksRequested} requested on this environment`,
          async () => {
            const answer = await backendClient.searchTraceIdsPage({
              projectId: project.id,
              search: population.needle,
              size: 200,
            });
            // The assertion the week pre-pass can actually break: not "the
            // right number of rows came back" but "the rows came back from
            // every week bucket they were written into, in the right numbers".
            // A bound that stopped one week short loses a whole bucket here
            // while every count-only assertion above would also fail — but this
            // one names which week went missing.
            expect(
              countByWeek(answer.ids),
              'the searched answer holds the same per-week census as the seed',
            ).toEqual(
              countByWeek(population.rows.filter((row) => row.matches).map((row) => row.traceId)),
            );
            expect(
              population.weeksCovered,
              'the seed spread over at least one week and no more than it asked for',
            ).toBeGreaterThanOrEqual(1);
            expect(population.weeksCovered).toBeLessThanOrEqual(population.weeksRequested);
          },
        );
      },
    );

    test(
      'the same search is exact over spans, which match on more columns than traces do',
      { tag: ['@cap:traces.free-text-search'] },
      async ({ searchPopulation, project, backendClient }) => {
        const population = searchPopulation;
        const matchCount = population.matchingSpanIds.length;
        const allCount = population.allSpanIds.length;

        // Its own test rather than more steps in the one above, because
        // `SpanDAO`'s search clause is a different clause: it additionally
        // matches `trace_id`, `type`, `model` and `provider`. A term that is
        // exact over traces is therefore not automatically exact over spans,
        // and a spec that only drove traces would be silent about the half of
        // the change that touched `SpansReadPathPartitionPruningTest`.
        await test.step('With no search, the project serves every seeded span', async () => {
          const answer = await backendClient.searchSpanIdsPage({
            projectId: project.id,
            size: 200,
          });
          expect(answer.total, 'the unsearched span listing reports the whole population').toBe(
            allCount,
          );
          expect(sorted(answer.ids), 'and serves exactly the seeded spans').toEqual(
            population.allSpanIds,
          );
        });

        await test.step(`Searching "${population.needle}" returns exactly the ${matchCount} matching spans`, async () => {
          const answer = await backendClient.searchSpanIdsPage({
            projectId: project.id,
            search: population.needle,
            size: 200,
          });
          expect(sorted(answer.ids), 'every matching span and no decoy').toEqual(
            population.matchingSpanIds,
          );
          expect(answer.total).toBe(matchCount);
        });

        await test.step(`A one-character search for "${population.needleChar}" has the same answer`, async () => {
          const answer = await backendClient.searchSpanIdsPage({
            projectId: project.id,
            search: population.needleChar,
            size: 200,
          });
          // The span clause matches on `id` and `trace_id`, both hex — which is
          // why the fixture chose a character that cannot appear in either, and
          // asserts as much before writing anything.
          expect(
            sorted(answer.ids),
            'a one-character term resolves to exactly the matching spans, ids included',
          ).toEqual(population.matchingSpanIds);
          expect(answer.total).toBe(matchCount);
        });

        await test.step('Walking that search three rows at a time loses nothing and repeats nothing', async () => {
          const walked = await walkPages(
            (page) =>
              backendClient.searchSpanIdsPage({
                projectId: project.id,
                search: population.needle,
                page,
                size: 3,
              }),
            3,
            matchCount,
          );
          expect(walked.ids.length, 'the pages add up to the population').toBe(matchCount);
          expect(sorted(walked.ids), 'and they are exactly the matching spans').toEqual(
            population.matchingSpanIds,
          );
          expect(new Set(walked.ids).size, 'no id appeared on two pages').toBe(walked.ids.length);
          expect(walked.pageSizes).toEqual([3, 3, 3, 3, 2]);
          expect(walked.totals).toEqual(Array(walked.totals.length).fill(matchCount));
        });

        await test.step('The span stats agree with the span listing', async () => {
          const unsearched = await backendClient.entityStats({
            entity: 'spans',
            projectId: project.id,
          });
          const searched = await backendClient.entityStats({
            entity: 'spans',
            projectId: project.id,
            search: population.needle,
          });
          expect(countStat(unsearched, 'span_count')).toBe(allCount);
          expect(
            countStat(searched, 'span_count'),
            'the searched span stats count exactly the matching spans',
          ).toBe(matchCount);
        });

        await test.step('Composing the search with a name filter intersects the two', async () => {
          const groupFilter = [
            { field: 'name', operator: 'contains', value: population.filterGroup },
          ];
          const filterOnly = await backendClient.searchSpanIdsPage({
            projectId: project.id,
            filters: groupFilter,
            size: 200,
          });
          const both = await backendClient.searchSpanIdsPage({
            projectId: project.id,
            search: population.needle,
            filters: groupFilter,
            size: 200,
          });
          expect(sorted(filterOnly.ids)).toEqual(population.filterGroupSpanIds);
          expect(sorted(both.ids), 'exactly the spans satisfying both').toEqual(
            population.intersectionSpanIds,
          );
          expect(both.total).toBe(population.intersectionSpanIds.length);
        });

        await test.step(
          `Every week the project has spans in is represented — ${population.weeksCovered} of ` +
            `${population.weeksRequested} requested on this environment`,
          async () => {
            const answer = await backendClient.searchSpanIdsPage({
              projectId: project.id,
              search: population.needle,
              size: 200,
            });
            expect(
              countByWeek(answer.ids),
              'the searched span answer holds the same per-week census as the seed',
            ).toEqual(
              countByWeek(population.rows.filter((row) => row.matches).map((row) => row.spanId)),
            );
          },
        );
      },
    );

    test(
      'the Logs search box renders exactly the rows the API answers for the same term',
      { tag: ['@cap:traces.free-text-search'] },
      async ({ searchPopulation, project, backendClient, page }) => {
        const population = searchPopulation;
        const matchCount = population.matchingTraceIds.length;
        const allCount = population.allTraceIds.length;
        const logs = new LogsPage(page);

        // The two halves of the capability are wired independently — the box
        // commits `traces_search`, and `useTracesList` turns that into the
        // `search` param — so this test exists to pin that the control a user
        // actually touches reaches the branch the two API tests above proved
        // correct. It is not a second copy of those assertions: what is checked
        // here is the AGREEMENT between what the page shows and what the API
        // says for the identical term.
        //
        // `alltime` and an explicit page size, both stated rather than
        // inherited: the date range and the page size are each persisted per
        // project, so an unstated one is whatever the browser profile last
        // held — and the seed may carry rows older than the default window.
        await test.step('Open the project Logs on the Traces tab, whole history on one page', async () => {
          await logs.gotoTraces(project.id, { timeRange: 'alltime', size: 100 });
          // A longer readiness window than the 15s default. The first row cannot
          // paint until the listing and its count query have both answered over
          // a 25-row project this test seeded moments ago, and on a shared cloud
          // workspace that has been observed to exceed 15s — which surfaced as a
          // flake here rather than in anything the test asserts.
          await logs.waitForReady({ timeout: 60_000 });
          expect(
            await logs.activeLogsTab(),
            'the Traces tab is the one on screen — `logsType` is persisted per project',
          ).toBe('traces');
        });

        await test.step('Before searching, the table shows the whole seeded project', async () => {
          await expect
            .poll(() => logs.readPaginationTotal(), {
              message: 'the footer reports the whole seeded population',
              timeout: 60_000,
            })
            .toBe(allCount);
          // The footer comes from the listing's envelope and can be right while
          // the body is still painting, so the row COUNT is settled with an
          // auto-retrying assertion before the ids are read once.
          await expect(logs.traceRows, 'the table body paints every row').toHaveCount(allCount);
          expect(
            sorted(await logs.readRowIdsOnPage()),
            'and renders exactly the seeded traces',
          ).toEqual(population.allTraceIds);
        });

        const apiAnswer = await test.step('Ask the API the same question', async () => {
          const answer = await backendClient.searchTraceIdsPage({
            projectId: project.id,
            search: population.needle,
            size: 200,
          });
          // Asserted here as well as in the API test, because this test's
          // subject is the agreement between the two: if the API answer were
          // itself wrong, "the table agrees with the API" would be satisfied by
          // two wrong answers.
          expect(sorted(answer.ids), 'the API serves exactly the matching traces').toEqual(
            population.matchingTraceIds,
          );
          return answer;
        });

        await test.step(`Type "${population.needle}" into Search by anything`, async () => {
          await logs.searchFor(population.needle);
        });

        await test.step('The footer, the rendered rows and the API all say the same thing', async () => {
          await expect
            .poll(() => logs.readPaginationTotal(), {
              message: 'the footer narrows to the searched population',
              timeout: 60_000,
            })
            .toBe(apiAnswer.total);

          await expect(
            logs.traceRows,
            'the table body repaints down to the searched population',
          ).toHaveCount(apiAnswer.total);
          const rendered = sorted(await logs.readRowIdsOnPage());
          expect(
            rendered,
            'the table renders exactly the rows the API returned for this term',
          ).toEqual(sorted(apiAnswer.ids));
          // The same fact as an exhaustion over the seed, so a build that
          // rendered the right COUNT of wrong rows fails here: every matching
          // row present, every decoy absent.
          expect(rendered, 'which is every matching trace').toEqual(
            population.matchingTraceIds,
          );
          expect(
            rendered.length,
            'and strictly fewer rows than the unsearched table held',
          ).toBe(matchCount);
        });

        await test.step('No decoy row survived the search', async () => {
          // Stated as the negative too. The set comparison above already
          // implies it, but a decoy row left in the DOM outside the table's
          // row set — a stale render, a duplicated body — would pass that and
          // fail this.
          const decoys = population.rows.filter((row) => !row.matches);
          for (const decoy of decoys) {
            await expect(
              logs.traceRow(decoy.traceId),
              `the non-matching trace '${decoy.name}' is not on screen`,
            ).toHaveCount(0);
          }
        });

        await test.step('Clearing the search restores the whole project', async () => {
          await logs.clearSearch();
          await expect
            .poll(() => logs.readPaginationTotal(), {
              message: 'the footer returns to the whole population',
              timeout: 60_000,
            })
            .toBe(allCount);
          await expect(
            logs.traceRows,
            'the table body repaints back up to the whole population',
          ).toHaveCount(allCount);
          expect(
            sorted(await logs.readRowIdsOnPage()),
            'and every seeded trace is back, so the search narrowed rather than deleted',
          ).toEqual(population.allTraceIds);
        });
      },
    );
  },
);
