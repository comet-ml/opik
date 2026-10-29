import { test, expect } from '@e2e/fixtures';
import { uuid7 } from '@e2e/core/backend';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * The Logs table's column sorting, on both the Traces and the Spans tab
 * (opik#9149 / OPIK-8917).
 *
 * 9149 rewrote `WorkspaceMetadataDAO.CALCULATE_METADATA` — the project size
 * estimate — and dropped its `table_size` CTE, so `ScopeMetadata` lost
 * `totalTableSizeGb` and `percentageOfTable`. UNCONDITIONALLY: no flag, every
 * deployment. That estimate is what `ScopeMetadata.canUseDynamicSorting()` gates
 * on, and the gate's only external signal is `sortable_by` on the traces and
 * spans list responses.
 *
 * A wrong size estimate does not error. It silently takes column sorting away
 * from the two busiest tables in the product — or hands it to a project that
 * cannot afford it. `traces.configure-columns` has no test at all, and no spec
 * in the estate sorts either list, so that regression is caught by nothing.
 *
 * Both surfaces, because they fail independently: `sortable_by` being present
 * says nothing about the table honouring a header click, and a table that sorts
 * client-side over one page would satisfy a UI-only assertion while the API had
 * stopped ordering anything.
 *
 * Scope, stated plainly: this covers the gate's ALLOW branch on a small project.
 * The withdraw branch — a project over the 100 GB
 * `maxProjectSizeToAllowSorting` limit — is not reachable on a shared
 * deployment: the 2.2.116 exploration found the workspace's two largest projects
 * (2476 and 1910 traces) and the next ten all keep every sortable field, and
 * manufacturing one needs ~4000 × 25 MB spans. So a gate that stopped
 * withdrawing sorting would still pass here. That is a real limit of this spec,
 * not an oversight.
 *
 * Determinism: five fixed, widely-spaced durations give one unambiguous expected
 * order, and they are assigned so that the duration order matches NEITHER the
 * seed order nor the table's default `start_time` ordering — a sort that quietly
 * did nothing would otherwise pass.
 */

/**
 * Durations in ms, by seed index. Start times increase with the index, so the
 * table's default order (start_time DESC) is [4, 3, 2, 1, 0] — which is neither
 * the ascending nor the descending duration order below.
 */
const DURATIONS_MS = [1_500, 100, 9_000, 250, 4_000];

/** Seed indexes in ascending duration order: 100, 250, 1500, 4000, 9000. */
const BY_DURATION_ASC = [1, 3, 0, 4, 2];

const SEED_COUNT = DURATIONS_MS.length;

test.describe('Logs column sorting — CUJ', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  test(
    'the Logs table sorts by Duration through the API and through a header click, on both tabs',
    { tag: ['@cap:traces.configure-columns'] },
    async ({ project, backendClient, testNamespace, page }) => {
      test.setTimeout(600_000);

      const traceIds = DURATIONS_MS.map(() => uuid7());
      const spanIds = DURATIONS_MS.map(() => uuid7());

      await test.step(`Seed ${SEED_COUNT} traces with fixed distinct durations, one span each`, async () => {
        // A fixed base so start times are deterministic relative to each other.
        // Inside the ingestion window: staging refuses a backdated id outside
        // PT24H, and these ids are minted now.
        const base = Date.now() - 60 * 60 * 1000;
        const startOf = (index: number) => new Date(base + index * 60_000);
        const endOf = (index: number) => new Date(base + index * 60_000 + DURATIONS_MS[index]);

        await backendClient.createTracesBatch({
          projectName: project.name,
          traces: traceIds.map((id, index) => ({
            id,
            name: `${testNamespace}-trace-${index}`,
            input: { index },
            output: { index },
            startTime: startOf(index),
            endTime: endOf(index),
          })),
        });
        await backendClient.createSpansBatch({
          projectName: project.name,
          spans: spanIds.map((id, index) => ({
            id,
            traceId: traceIds[index],
            name: `${testNamespace}-span-${index}`,
            startTime: startOf(index),
            endTime: endOf(index),
          })),
        });
      });

      await test.step('Both lists report the seeded population before anything is sorted', async () => {
        for (const entity of ['traces', 'spans'] as const) {
          await expect
            .poll(
              async () =>
                (await backendClient.listLogsPage({ entity, projectId: project.id })).total,
              {
                message: `all ${SEED_COUNT} ${entity} are ingested`,
                // Generous because `listLogsPage` may stand off for ~2 minutes
                // of its own on a rate limit before it answers once, and
                // `expect.poll` does not swallow the throw if it gives up.
                timeout: 300_000,
                intervals: [1_000, 2_000, 5_000],
              },
            )
            .toBe(SEED_COUNT);
        }
      });

      await test.step('Both lists offer a non-empty sortable_by that includes duration', async () => {
        for (const entity of ['traces', 'spans'] as const) {
          const page1 = await backendClient.listLogsPage({ entity, projectId: project.id });
          // Non-empty FIRST: an empty list is the gate having withdrawn sorting
          // wholesale, which is a different finding from duration alone going
          // missing, and it is the one 9149 could cause.
          expect(
            page1.sortableBy.length,
            `the ${entity} list offers sortable columns at all — an empty ` +
              'sortable_by is the dynamic-sorting gate having withdrawn them',
          ).toBeGreaterThan(0);
          expect(
            page1.sortableBy,
            `the ${entity} list offers duration as a sortable column`,
          ).toContain('duration');
        }
      });

      await test.step('The API returns the exact expected order in both directions, for both entities', async () => {
        const expectedIds = {
          traces: BY_DURATION_ASC.map((index) => traceIds[index]),
          spans: BY_DURATION_ASC.map((index) => spanIds[index]),
        };

        for (const entity of ['traces', 'spans'] as const) {
          for (const direction of ['ASC', 'DESC'] as const) {
            const read = await backendClient.listLogsPage({
              entity,
              projectId: project.id,
              sorting: [{ field: 'duration', direction }],
            });

            // The whole answer, not just the order of the rows we recognise: a
            // sorted read that also leaked rows from elsewhere, or dropped one,
            // is the regression a `find()`-based check would pass straight over.
            expect(read.total, `the sorted ${entity} read counts only the seeded rows`).toBe(
              SEED_COUNT,
            );
            expect(
              read.rows.length,
              `the sorted ${entity} read returns as many rows as it counted`,
            ).toBe(SEED_COUNT);

            const ascending = expectedIds[entity];
            const expectedOrder =
              direction === 'ASC' ? ascending : [...ascending].slice().reverse();
            expect(
              read.rows.map((row) => row.id),
              `${entity} sorted by duration ${direction}`,
            ).toEqual(expectedOrder);

            // The durations themselves, so a response that happened to return
            // the right ids with wrong numbers fails too — and asserted away
            // rather than defaulted, because a column of nulls sorts into any
            // order at all.
            const durations = read.rows.map((row) => row.duration);
            expect(
              durations.every((value) => value !== null),
              `every sorted ${entity} row reports a duration`,
            ).toBe(true);
            const expectedDurations = expectedOrder.map((id) => {
              const index =
                entity === 'traces' ? traceIds.indexOf(id) : spanIds.indexOf(id);
              return DURATIONS_MS[index];
            });
            // Rounded: the backend computes duration from the two timestamps in
            // floating-point milliseconds, so the stored value is the seeded one
            // to within a rounding error rather than exactly it.
            expect(
              durations.map((value) => Math.round(value!)),
              `${entity} durations in ${direction} order`,
            ).toEqual(expectedDurations);
          }
        }
      });

      const logs = new LogsPage(page);

      for (const tab of ['traces', 'spans'] as const) {
        await test.step(`A Duration header click sorts the ${tab} table, then reverses it`, async () => {
          const seededIds = tab === 'traces' ? traceIds : spanIds;
          const ascending = BY_DURATION_ASC.map((index) => seededIds[index]);

          if (tab === 'traces') {
            await logs.gotoTraces(project.id);
          } else {
            await logs.gotoSpans(project.id);
          }
          // Settled on the population the footer reports, which is the estate's
          // pattern for the Spans view (`spans-view-pagination.spec.ts`) and
          // works for both tabs — `waitForReady()` matches the Traces header's
          // own counter and so can never settle on the Spans tab.
          await expect
            .poll(async () => await logs.readPaginationTotal(), {
              message: `the ${tab} table reports all ${SEED_COUNT} rows`,
              timeout: 120_000,
              intervals: [1_000, 2_000, 5_000],
            })
            .toBe(SEED_COUNT);

          // The precondition that makes the first click mean something: an
          // already-sorted table would satisfy the order assertion below
          // without the click having done anything.
          expect(
            await logs.readUrlSorting(tab),
            `the ${tab} table starts with no sort in the URL`,
          ).toBeNull();

          // The other table's sort state as it stands BEFORE these clicks, not
          // asserted to be absent: the page keeps each table's sort in
          // localStorage as well as in the URL and syncs the two on init
          // (`useQueryParamAndLocalStorageState`), so by the time the second tab
          // runs the first tab's sort is legitimately still stored. Unchanged is
          // the real claim; absent would just be asserting the iteration order.
          const otherTab = tab === 'traces' ? 'spans' : 'traces';
          const otherSortingBefore = await logs.readUrlSorting(otherTab);

          await expect
            .poll(async () => (await logs.readRowIdsOnPage()).length, {
              message: `all ${SEED_COUNT} ${tab} rows are rendered`,
              timeout: 60_000,
            })
            .toBe(SEED_COUNT);

          // The direction the first click produces is read off the URL rather
          // than assumed, so this does not encode which way TanStack happens to
          // sort a numeric column first.
          const first = await logs.sortByColumn(tab, 'Duration', 'duration');
          const expectedFirst =
            first === 'asc' ? ascending : [...ascending].slice().reverse();
          await expect
            .poll(async () => await logs.readRowIdsOnPage(), {
              message: `${tab} rows in ${first}ending duration order`,
              timeout: 60_000,
            })
            .toEqual(expectedFirst);

          const second = await logs.sortByColumn(tab, 'Duration', 'duration');
          expect(second, 'the second click reverses the direction').not.toBe(first);
          await expect
            .poll(async () => await logs.readRowIdsOnPage(), {
              message: `${tab} rows in ${second}ending duration order`,
              timeout: 60_000,
            })
            .toEqual([...expectedFirst].slice().reverse());

          // The two tabs keep separate sort state, so a click that wrote both
          // is a regression no single-tab assertion could see.
          expect(
            await logs.readUrlSorting(otherTab),
            `sorting the ${tab} table left the ${otherTab} table's sort alone`,
          ).toEqual(otherSortingBefore);
        });
      }
    },
  );
});
