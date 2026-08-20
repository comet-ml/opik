import {
  test,
  expect,
  PLAIN_OUTPUT_KEY,
  QUOTED_OUTPUT_KEY,
  BACKSLASHED_OUTPUT_KEY,
  JSON_SORTABLE_OUTPUT_KEYS,
  type JsonSortableComparisonRef,
} from '@e2e/fixtures';
import type { BackendClient, ComparedDatasetItemsPage } from '@e2e/core/backend';
import { CompareExperimentsPage } from '@e2e/pom/compare-experiments.page';

/**
 * Sorting the experiments-comparison grid by an "Evaluation task (last trial)"
 * column — that is, by a key inside the evaluation task's output JSON
 * (OPIK-8023, opik#7935).
 *
 * The backend used to interpolate that key straight into the ClickHouse
 * `JSONExtractRaw` call, so a key carrying a quote produced malformed SQL;
 * #7935 binds it as a query parameter instead. Both the old and the new
 * failure modes are silent in the way that makes a permanent test worth having:
 * a grid ordered by the wrong expression still renders a full, healthy-looking
 * table, and there is no error for a user to notice.
 *
 * `experiments.compare-sort-search` is already covered by
 * `experiments-compare.spec.ts`, but only for `feedback_scores_<metric>` —
 * nothing in the estate reads an `output.*` sort today. This spec covers that
 * branch and lives in its own file so the two failure modes stay separable, the
 * same split `experiment-logs-date-window.spec.ts` makes.
 *
 * Every order is asserted twice: on the rendered rows (what a user sees) and on
 * the server read behind them, so a disagreement between the two surfaces fails
 * rather than cancelling out.
 */

const descending = (ascending: string[]): string[] => [...ascending].reverse();

/**
 * The compare grid's server read, sorted by one output JSON key. Asserts the
 * page is the whole collection before returning it: a sort that dropped or
 * leaked rows must fail here, not be papered over by an order check on
 * whatever came back.
 */
const sortedItemIds = async (
  backendClient: BackendClient,
  seed: JsonSortableComparisonRef,
  field: string,
  direction: 'ASC' | 'DESC',
): Promise<string[]> => {
  const page: ComparedDatasetItemsPage = await backendClient.listComparedDatasetItems({
    datasetId: seed.datasetId,
    experimentIds: seed.experiments.map((experiment) => experiment.experimentId),
    sorting: [{ field, direction }],
  });
  expect(page.total, `total reported for ${field} ${direction}`).toBe(seed.itemIds.length);
  expect(page.items.length, `rows returned for ${field} ${direction}`).toBe(seed.itemIds.length);
  return page.items.map((item) => item.id);
};

test.describe('Experiments comparison — sort by evaluation-task JSON key', { tag: ['@t2-cuj', '@area:experiments'] }, () => {
  test('the compare grid sorts by an evaluation-task output key in both directions', { tag: ['@cap:experiments.compare-sort-search'] }, async ({
    jsonSortableComparison,
    backendClient,
    project,
    page,
  }) => {
    const seed = jsonSortableComparison;
    const ascending = seed.ascOrderByKey[PLAIN_OUTPUT_KEY];
    const compare = new CompareExperimentsPage(
      page,
      project.id,
      seed.datasetId,
      seed.experiments.map((experiment) => experiment.experimentId),
    );

    await test.step('The seed can tell one sort from another', async () => {
      // Each key has to produce its own order, and none of them the order the
      // items were created in — otherwise a sort that silently fell back to the
      // item id, or read the wrong JSON key, would still look right.
      const orders = JSON_SORTABLE_OUTPUT_KEYS.map((key) => seed.ascOrderByKey[key]);
      for (const order of orders) {
        expect(order, 'a sorted order must not repeat the seeded order').not.toEqual(seed.itemIds);
      }
      expect(orders[0], `"${PLAIN_OUTPUT_KEY}" and "${QUOTED_OUTPUT_KEY}" must order differently`)
        .not.toEqual(orders[1]);
      expect(orders[0], `"${PLAIN_OUTPUT_KEY}" and "${BACKSLASHED_OUTPUT_KEY}" must order differently`)
        .not.toEqual(orders[2]);
      expect(orders[1], `"${QUOTED_OUTPUT_KEY}" and "${BACKSLASHED_OUTPUT_KEY}" must order differently`)
        .not.toEqual(orders[2]);

      const unsorted = await backendClient.listComparedDatasetItems({
        datasetId: seed.datasetId,
        experimentIds: seed.experiments.map((experiment) => experiment.experimentId),
      });
      const unsortedOrder = unsorted.items.map((item) => item.id);
      expect([...unsortedOrder].sort(), 'both experiments cover every seeded item').toEqual(
        [...seed.itemIds].sort(),
      );
      // Neither direction may coincide with the grid's default order, or the
      // order assertions below would hold for a grid that never sorted at all.
      expect(unsortedOrder, `the default order must differ from "${PLAIN_OUTPUT_KEY}" ascending`)
        .not.toEqual(ascending);
      expect(unsortedOrder, `the default order must differ from "${PLAIN_OUTPUT_KEY}" descending`)
        .not.toEqual(descending(ascending));
    });

    await test.step('Open the compare Results tab with no sort applied', async () => {
      await compare.gotoResults();
      await compare.waitForResultsReady();
      await compare.clearSort();
      await expect(compare.outputColumnHeader(PLAIN_OUTPUT_KEY), `"${PLAIN_OUTPUT_KEY}" output column`)
        .toHaveCount(1);
    });

    await test.step(`Clicking the "${PLAIN_OUTPUT_KEY}" header sorts the rows descending by that key`, async () => {
      await compare.sortByOutputKey(PLAIN_OUTPUT_KEY);
      await compare.expectItemRowOrder(descending(ascending), `"${PLAIN_OUTPUT_KEY}" descending`);
    });

    await test.step('The rendered output cells carry the seeded values, for both experiments', async () => {
      // The order alone would also hold for a grid that sorted correctly and
      // then rendered the wrong row's output.
      for (const itemId of seed.itemIds) {
        const expected = seed.outputByItemId[itemId][PLAIN_OUTPUT_KEY];
        for (let experimentIndex = 0; experimentIndex < seed.experiments.length; experimentIndex++) {
          expect(
            await compare.readItemOutput(itemId, experimentIndex, PLAIN_OUTPUT_KEY),
            `rendered "${PLAIN_OUTPUT_KEY}" for item ${itemId}, experiment #${experimentIndex}`,
          ).toBe(expected);
        }
      }
    });

    await test.step('Clicking the same header again flips to ascending', async () => {
      await compare.sortByOutputKey(PLAIN_OUTPUT_KEY);
      await compare.expectItemRowOrder(ascending, `"${PLAIN_OUTPUT_KEY}" ascending`);
    });

    await test.step('The server read agrees with the order the grid rendered', async () => {
      expect(
        await sortedItemIds(backendClient, seed, `output.${PLAIN_OUTPUT_KEY}`, 'ASC'),
        `server order for output.${PLAIN_OUTPUT_KEY} ASC`,
      ).toEqual(ascending);
      expect(
        await sortedItemIds(backendClient, seed, `output.${PLAIN_OUTPUT_KEY}`, 'DESC'),
        `server order for output.${PLAIN_OUTPUT_KEY} DESC`,
      ).toEqual(descending(ascending));
    });
  });

  test('an output key containing a quote or a backslash sorts instead of failing', { tag: ['@cap:experiments.compare-sort-search'] }, async ({
    jsonSortableComparison,
    backendClient,
    project,
    page,
  }) => {
    const seed = jsonSortableComparison;
    const ascending = seed.ascOrderByKey[QUOTED_OUTPUT_KEY];
    const compare = new CompareExperimentsPage(
      page,
      project.id,
      seed.datasetId,
      seed.experiments.map((experiment) => experiment.experimentId),
    );

    await test.step('The seed can tell a sort on the quoted key from no sort at all', async () => {
      const unsorted = await backendClient.listComparedDatasetItems({
        datasetId: seed.datasetId,
        experimentIds: seed.experiments.map((experiment) => experiment.experimentId),
      });
      const unsortedOrder = unsorted.items.map((item) => item.id);
      expect(unsortedOrder, `the default order must differ from "${QUOTED_OUTPUT_KEY}" ascending`)
        .not.toEqual(ascending);
      expect(unsortedOrder, `the default order must differ from "${QUOTED_OUTPUT_KEY}" descending`)
        .not.toEqual(descending(ascending));
    });

    await test.step('Both awkward keys render as their own grid columns', async () => {
      await compare.gotoResults();
      await compare.waitForResultsReady();
      await compare.clearSort();
      for (const key of [QUOTED_OUTPUT_KEY, BACKSLASHED_OUTPUT_KEY]) {
        await expect(compare.outputColumnHeader(key), `output column for key "${key}"`).toHaveCount(1);
      }
    });

    await test.step(`Clicking the "${QUOTED_OUTPUT_KEY}" header sorts descending, then ascending`, async () => {
      await compare.sortByOutputKey(QUOTED_OUTPUT_KEY);
      await compare.expectItemRowOrder(descending(ascending), `"${QUOTED_OUTPUT_KEY}" descending`);
      await compare.sortByOutputKey(QUOTED_OUTPUT_KEY);
      await compare.expectItemRowOrder(ascending, `"${QUOTED_OUTPUT_KEY}" ascending`);
    });

    await test.step('The rendered cells under the quoted key carry its seeded values', async () => {
      for (const itemId of seed.itemIds) {
        expect(
          await compare.readItemOutput(itemId, 0, QUOTED_OUTPUT_KEY),
          `rendered "${QUOTED_OUTPUT_KEY}" for item ${itemId}`,
        ).toBe(seed.outputByItemId[itemId][QUOTED_OUTPUT_KEY]);
      }
    });

    await test.step('The server sorts both awkward keys, in both directions', async () => {
      for (const key of [QUOTED_OUTPUT_KEY, BACKSLASHED_OUTPUT_KEY]) {
        const expectedAscending = seed.ascOrderByKey[key];
        expect(
          await sortedItemIds(backendClient, seed, `output.${key}`, 'ASC'),
          `server order for output.${key} ASC`,
        ).toEqual(expectedAscending);
        expect(
          await sortedItemIds(backendClient, seed, `output.${key}`, 'DESC'),
          `server order for output.${key} DESC`,
        ).toEqual(descending(expectedAscending));
      }
    });
  });
});
