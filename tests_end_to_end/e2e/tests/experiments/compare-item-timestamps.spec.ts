import { test, expect } from '@e2e/fixtures';

/**
 * The timestamps the experiment-comparison read path reports for a dataset item
 * that has been versioned more than once (OPIK-7791, from the 2.2.42 -> 2.2.43
 * release exploration).
 *
 * `experiments-compare.spec.ts` covers the compare view thoroughly — scores,
 * outputs, sort, search, the Feedback scores tab, the row detail panel — and
 * asserts nothing about when a compared item was authored. That gap matters
 * because the compare read is one of the paths that has to alias the item's own
 * columns (`item_created_at AS created_at`) rather than the version snapshot
 * row's: from the second version on, the two hold different values, and reading
 * the snapshot's would present a user with the moment a version was cut in a
 * column labelled as when the item was created.
 *
 * This is a regression guard on behaviour that is correct today, over exactly
 * the column aliasing that was wrong elsewhere in this release — so it is
 * written to fail if the compare read ever starts binding the snapshot row.
 *
 * API-LEVEL ON PURPOSE, and this is a narrowing of the candidate rather than a
 * shortcut. The compare grid renders no created-at column at all: neither
 * `getFilterColumns()` nor the dataset/output column builders in
 * `ExperimentItemsTab` define one, and the row detail panel does not show it
 * either. There is therefore no UI proxy for this value to assert on, faithful
 * or otherwise, and asserting the endpoint the grid reads from is the honest
 * form of the check. If a created-at column is ever added to the compare view,
 * the UI half belongs here alongside it.
 */

test.describe('Experiments comparison — item timestamps', { tag: ['@t2-cuj', '@area:experiments'] }, () => {
  test(
    'the comparison reports each item authored when the item was, not when its version was cut',
    { tag: ['@cap:experiments.compare-side-by-side'] },
    async ({ comparison, versionedComparison, backendClient }) => {
      const { itemCreatedAtById, versionCreatedAt, lastItemCreatedAt } = versionedComparison;
      const experimentIds = comparison.experiments.map((e) => e.experimentId);

      await test.step('The dataset was versioned after its items were authored', async () => {
        // Without this the two column sets would still agree and every
        // assertion below would hold whichever one the endpoint bound to.
        expect(
          versionCreatedAt.getTime(),
          'the latest version must be stamped after the last item was authored',
        ).toBeGreaterThan(lastItemCreatedAt.getTime());
      });

      const rows = await test.step('The comparison still returns one row per shared item', async () => {
        const fetched = await backendClient.listCompareItems({
          datasetId: comparison.datasetId,
          experimentIds,
        });
        expect(fetched, 'rows returned by the comparison read').toHaveLength(comparison.itemIds.length);
        expect([...fetched.map((r) => r.id)].sort(), 'the comparison covers exactly the seeded items')
          .toEqual([...comparison.itemIds].sort());
        return fetched;
      });

      await test.step("Every row carries its own item timestamp, not the version snapshot's", async () => {
        for (const row of rows) {
          const itemCreatedAt = itemCreatedAtById[row.id];
          expect(itemCreatedAt, `a seeded authorship time for item ${row.id}`).toBeDefined();
          expect(
            row.createdAt.toISOString(),
            `comparison timestamp for item ${row.id}`,
          ).toBe(itemCreatedAt.toISOString());
          // Stated separately from the equality above so a failure says which
          // of the two values the endpoint returned, not merely that they
          // differed.
          expect(
            row.createdAt.getTime(),
            `comparison timestamp for item ${row.id} must predate the version snapshot`,
          ).toBeLessThan(versionCreatedAt.getTime());
        }
      });
    },
  );
});
