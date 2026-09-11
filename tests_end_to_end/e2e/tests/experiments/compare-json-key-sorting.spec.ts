import { test, expect } from '@e2e/fixtures';
import { JSON_SORT_KEYS, JSON_SORT_PREFIXES, LABEL_COLUMN } from '@e2e/fixtures';
import type { BackendSort } from '@e2e/core/backend';
import { CompareExperimentsPage } from '@e2e/pom/compare-experiments.page';

/**
 * Sorting the experiment-comparison grid by a JSON key — `output.<key>`,
 * `input.<key>`, `metadata.<key>` — including keys carrying a dot, a space or a
 * double quote (OPIK-8023).
 *
 * Those three paths used to build their ClickHouse `JSONExtractRaw` argument by
 * interpolating the key text into the SQL; they now bind it as a query
 * parameter, the way `data.<key>` always did. What makes this worth a permanent
 * test is the failure mode: a mis-bound key does not error. It extracts nothing,
 * every row compares equal, and the grid returns a plausible-looking order that
 * is simply not the one asked for — on a view people read to decide which
 * experiment is better.
 *
 * `experiments.compare-sort-search` is already covered by experiments-compare,
 * but only for `feedback_scores.<name>`, which was bound before this change and
 * is unaffected by it. This spec covers the JSON-key paths and keeps
 * `data.<key>` as the non-regression control.
 *
 * The fixture's label order, JSON-value order and insertion order are three
 * different permutations, so an implementation that sorted by the wrong field —
 * or ignored the field — cannot produce the expected sequence by accident. The
 * first step asserts that property holds before anything else is checked.
 */

const sortBy = (field: string, direction: 'ASC' | 'DESC'): BackendSort[] => [{ field, direction }];

/**
 * Keys that are not JSON keys at all, but SQL fragments and an empty string.
 * Bound as a parameter they select nothing and the answer is the full page in a
 * stable fallback order; interpolated, they are an injection site.
 */
const HOSTILE_KEYS = ['a\') OR 1=1 --', '")', "a\\', 'x", ''];

/**
 * The key the readiness polls sort by before either test asserts anything.
 *
 * Deliberately the plain one: readiness is about the seeded JSON being
 * queryable at all, and an awkward key here would conflate "not ingested yet"
 * with "this key does not bind" — the very thing under test.
 */
const READINESS_KEY = 'plain';

test.describe('Experiment comparison — JSON-key sorting', { tag: ['@t2-cuj', '@area:experiments'] }, () => {
  /**
   * Seeding five traces plus an experiment, then reading every key in both
   * directions, outruns the default budget against a cloud backend — and the
   * overrun surfaces as a timeout on whichever step happened to be last, which
   * reads like a product failure and isn't one. Declared on the describe so it
   * also covers fixture setup.
   */
  test.slow();

  test(
    'the compare items API sorts by every JSON container key, including keys with special characters',
    { tag: ['@cap:experiments.compare-sort-search'] },
    async ({ jsonOutputExperiment, backendClient }) => {
      const {
        datasetId,
        experimentId,
        itemIds,
        itemIdsByJsonValueAsc,
        itemIdsByLabelAsc,
      } = jsonOutputExperiment;

      const read = (sorting?: BackendSort[]): Promise<string[]> =>
        backendClient.listCompareItemIds({ datasetId, experimentIds: [experimentId], sorting });

      await test.step('The fixture discriminates: three different orders over the same five items', () => {
        expect(itemIdsByJsonValueAsc, 'JSON-value order must differ from label order')
          .not.toEqual(itemIdsByLabelAsc);
        expect(itemIdsByJsonValueAsc, 'JSON-value order must differ from insertion order')
          .not.toEqual(itemIds);
        expect(itemIdsByLabelAsc, 'label order must differ from insertion order').not.toEqual(itemIds);
      });

      await test.step('All five items are linked, and their JSON is queryable', async () => {
        // Two writes must land before any sort assertion means anything: the
        // experiment-item linkage, and the traces whose JSON the sort extracts.
        // Both are eventually consistent, and the sort reads the *joined* trace
        // columns — so waiting on the row count alone lets a sort run against
        // rows whose `output` is not queryable yet, which returns the fallback
        // order and fails intermittently.
        //
        // Polling the sorted read covers both: it can only produce this order
        // once the linkage exists AND the JSON is extractable.
        await expect
          .poll(async () => read(sortBy(`output.${READINESS_KEY}`, 'ASC')), {
            timeout: 60_000,
            intervals: [500, 1_000, 2_000],
          })
          .toEqual(itemIdsByJsonValueAsc);

        expect([...(await read())].sort(), 'the unsorted read returns exactly the seeded items')
          .toEqual([...itemIds].sort());
      });

      for (const prefix of JSON_SORT_PREFIXES) {
        for (const key of JSON_SORT_KEYS) {
          const field = `${prefix}.${key}`;

          await test.step(`Sorting by ${JSON.stringify(field)} orders the items by their JSON value`, async () => {
            expect(await read(sortBy(field, 'ASC')), `${field} ascending`)
              .toEqual(itemIdsByJsonValueAsc);
            expect(await read(sortBy(field, 'DESC')), `${field} descending`)
              .toEqual([...itemIdsByJsonValueAsc].reverse());
          });
        }
      }

      await test.step(`Non-regression: sorting by "${LABEL_COLUMN}" still orders by the dataset column`, async () => {
        const field = `data.${LABEL_COLUMN}`;
        expect(await read(sortBy(field, 'ASC')), `${field} ascending`).toEqual(itemIdsByLabelAsc);
        expect(await read(sortBy(field, 'DESC')), `${field} descending`)
          .toEqual([...itemIdsByLabelAsc].reverse());
      });

      for (const key of HOSTILE_KEYS) {
        await test.step(`A key of ${JSON.stringify(key)} is treated as a key, not as SQL`, async () => {
          const returned = await read(sortBy(`output.${key}`, 'ASC'));
          // The order is not specified — every row extracts the same nothing —
          // but the whole page must still come back, and nothing may 500.
          expect([...returned].sort(), `sorting by output.${key} returns every item`)
            .toEqual([...itemIds].sort());
        });
      }
    },
  );

  test(
    'the compare grid renders the row order the API sorted by',
    { tag: ['@cap:experiments.compare-sort-search'] },
    async ({ jsonOutputExperiment, project, backendClient, page }) => {
      const { datasetId, experimentId, itemIds, itemIdsByJsonValueAsc, itemIdsByLabelAsc } =
        jsonOutputExperiment;

      await test.step('All five items are linked, and their JSON is queryable', async () => {
        // Same two-write readiness condition as the API test above: poll the
        // sorted read, not the row count, so the grid is only opened once the
        // JSON the sort extracts is actually queryable.
        await expect
          .poll(
            async () =>
              backendClient.listCompareItemIds({
                datasetId,
                experimentIds: [experimentId],
                sorting: sortBy(`output.${READINESS_KEY}`, 'ASC'),
              }),
            { timeout: 60_000, intervals: [500, 1_000, 2_000] },
          )
          .toEqual(itemIdsByJsonValueAsc);
      });

      const compare = new CompareExperimentsPage(page, project.id, datasetId, [experimentId]);

      await test.step('Open the compare Results tab', async () => {
        await compare.gotoResults();
        await compare.waitForResultsReady();
        expect(await compare.countItemRows(), 'every seeded item has a row').toBe(itemIds.length);
      });

      // `key with space` travels as `output.key+with+space`, so this is also the
      // assertion that the server decodes the key back before extracting it.
      for (const key of ['plain', 'key with space']) {
        await test.step(`Sorting the grid by ${JSON.stringify(`output.${key}`)} renders the JSON-value order`, async () => {
          await compare.sortByColumn(`output.${key}`, 'asc');
          expect(await compare.itemRowOrder(), `grid ascending by output.${key}`)
            .toEqual(itemIdsByJsonValueAsc);

          await compare.sortByColumn(`output.${key}`, 'desc');
          expect(await compare.itemRowOrder(), `grid descending by output.${key}`)
            .toEqual([...itemIdsByJsonValueAsc].reverse());
        });
      }

      await test.step(`Non-regression: the grid still sorts by "data.${LABEL_COLUMN}"`, async () => {
        await compare.sortByColumn(`data.${LABEL_COLUMN}`, 'asc');
        expect(await compare.itemRowOrder(), `grid ascending by data.${LABEL_COLUMN}`)
          .toEqual(itemIdsByLabelAsc);
      });
    },
  );
});
