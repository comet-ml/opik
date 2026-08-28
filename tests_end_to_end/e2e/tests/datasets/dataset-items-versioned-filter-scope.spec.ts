import { test, expect } from '@e2e/fixtures';
import {
  VERSIONED_DATASET_BYSTANDERS,
  VERSIONED_DATASET_MATCHING,
  VERSION_GROUP_COLUMN,
  VERSION_TARGET_GROUP,
} from '@e2e/fixtures';
import type { BackendFilter } from '@e2e/core/backend';

/**
 * Filter-scoped dataset-item mutations on a dataset that has been versioned
 * more than once (OPIK-7791, from the 2.2.42 -> 2.2.43 release exploration).
 *
 * `dataset-items-filter-scope.spec.ts` already asserts the scope of these two
 * endpoints — but only over `data.<key>`, and only on a single-version dataset.
 * Both of those keep the item's own columns and its version snapshot row's
 * columns in agreement, so a filter binds to the same values whichever it
 * resolves against, and the spec passes without ever distinguishing them.
 *
 * `dataset_item_versions` carries two column sets: the snapshot row's (`id`,
 * `created_at`, `created_by`, ...) and the item's (`dataset_item_id`,
 * `item_created_at`, `item_created_by`, ...). Committing a version re-stamps
 * every snapshot row with a fresh id and a fresh `created_at` while the items
 * keep theirs, so from the second version on the two sets hold different
 * values — and which one a filter binds to becomes observable.
 *
 * The contract asserted here is a single sentence, and it is the one a user
 * relies on when they filter a grid and then act on what it shows: FOR ANY
 * FILTER, THE SET A MUTATION TOUCHES IS THE SET THE SAME FILTER LISTS. Nothing
 * here asserts an implementation detail about which column is correct — only
 * that the read and the write agree. An irreversible delete that disagrees with
 * the page the user read is data loss with no step at which it becomes visible.
 *
 * Three of the four tests here are known failures against an open bug,
 * OPIK-8150, and carry `test.fail()` with the specific binding each one hits.
 * They assert the CORRECT behaviour, so each reports "Expected to fail, but
 * passed" once the fix lands, which is the prompt to remove the marker.
 *
 * The control test runs first on purpose. It drives the same endpoint over the
 * same doubly-versioned dataset with a `data.<key>` filter and must pass, which
 * is what makes a failure below a statement about column binding rather than
 * about the endpoint, the fixture, or the environment.
 *
 * SCOPE — ungrouped, as `dataset-items-filter-scope.spec.ts` is: no
 * `batch_group_id` is sent, so each mutation updates the latest version rather
 * than committing a new one. The grouped form is what the UI's select-all
 * sends, and `dataset-items-select-all-delete.spec.ts` covers it there.
 */

const APPLIED_TAG = 'versioned-filter-scoped-update';
const TOTAL_ITEMS = VERSIONED_DATASET_MATCHING + VERSIONED_DATASET_BYSTANDERS;

const sorted = (ids: string[]): string[] => [...ids].sort();

test.describe('Dataset items — filter scope across versions', { tag: ['@t3-nightly', '@area:datasets'] }, () => {
  test(
    'CONTROL: after two versions a data.<key> filter still deletes exactly the items it lists',
    { tag: ['@cap:datasets.bulk-delete-items'] },
    async ({ versionedDataset, backendClient }) => {
      const { id: datasetId, targetItemIds, groupBystanderItemIds } = versionedDataset;
      const groupFilter: BackendFilter[] = [
        { field: 'data', key: VERSION_GROUP_COLUMN, operator: '=', value: VERSION_TARGET_GROUP },
      ];

      await test.step('The filter lists exactly the items in the target group', async () => {
        const listed = await backendClient.listDatasetItemsPage({ datasetId, filters: groupFilter });
        expect(listed.total, 'the reported total for the group filter').toBe(targetItemIds.length);
        expect(listed.items, 'the rows carried for the group filter').toHaveLength(targetItemIds.length);
        expect(sorted(listed.items.map((i) => i.id)), 'the filter lists exactly the target items')
          .toEqual(sorted(targetItemIds));
      });

      await test.step('A delete with the identical filter removes exactly those items', async () => {
        await backendClient.deleteDatasetItemsByFilter({ datasetId, filters: groupFilter });

        const remaining = await backendClient.listDatasetItemsPage({ datasetId });
        expect(sorted(remaining.items.map((i) => i.id)), 'exactly the non-matching items survive')
          .toEqual(sorted(groupBystanderItemIds));
        expect(remaining.total, 'the reported total agrees with the surviving rows')
          .toBe(groupBystanderItemIds.length);
      });
    },
  );

  test(
    'a delete filtered by item id removes exactly the item that filter lists',
    { tag: ['@cap:datasets.bulk-delete-items'] },
    async ({ versionedDataset, backendClient }) => {
      // Known failure — OPIK-8150. The delete binds `id` to the version
      // snapshot row's column, which holds a fresh UUID from the second version
      // on, so the filter matches nothing and the item survives a 204.
      test.fail();

      const { id: datasetId, itemIds } = versionedDataset;
      const target = itemIds[0];
      const survivors = itemIds.filter((id) => id !== target);
      const idFilter: BackendFilter[] = [{ field: 'id', operator: '=', value: target }];

      await test.step('The filter lists exactly one item, and it is the target', async () => {
        const listed = await backendClient.listDatasetItemsPage({ datasetId, filters: idFilter });
        expect(listed.total, `the reported total for id = ${target}`).toBe(1);
        expect(listed.items, `the rows carried for id = ${target}`).toHaveLength(1);
        expect(listed.items[0].id, 'the listed row is the target item').toBe(target);
      });

      await test.step('A delete with the identical filter removes exactly that item', async () => {
        await backendClient.deleteDatasetItemsByFilter({ datasetId, filters: idFilter });

        const remaining = await backendClient.listDatasetItemsPage({ datasetId });
        // Both halves matter. "The target is gone" alone would also be true of a
        // delete that emptied the dataset; "everything else survived" alone
        // would also be true of a delete that did nothing at all.
        expect(remaining.items.map((i) => i.id), 'the target item is gone').not.toContain(target);
        expect(sorted(remaining.items.map((i) => i.id)), 'every other item survives untouched')
          .toEqual(sorted(survivors));
        expect(remaining.total, 'the reported total agrees with the surviving rows')
          .toBe(survivors.length);
      });
    },
  );

  test(
    'a delete filtered by a created_at window that lists no rows removes no rows',
    { tag: ['@cap:datasets.bulk-delete-items'] },
    async ({ versionedDataset, backendClient }) => {
      // Known failure — OPIK-8150. `created_at` binds to the snapshot row's
      // column on the write path, so a window the items page reports as empty
      // still matches every snapshot row and the delete removes them.
      test.fail();

      const { id: datasetId, itemIds, versionCreatedAt, lastItemCreatedAt } = versionedDataset;

      // Strictly after every item was authored, strictly before the version was
      // committed. The items page resolves `created_at` against the item's own
      // column, so this window is empty from the user's point of view; the
      // snapshot rows all sit above it.
      const threshold = new Date(
        Math.floor((lastItemCreatedAt.getTime() + versionCreatedAt.getTime()) / 2),
      );
      const windowFilter: BackendFilter[] = [
        { field: 'created_at', operator: '>', value: threshold.toISOString() },
      ];

      await test.step('The fixture put the item and snapshot timestamps out of step', async () => {
        expect(
          versionCreatedAt.getTime(),
          'the version must be stamped after the last item for this window to exist',
        ).toBeGreaterThan(lastItemCreatedAt.getTime());
        expect(threshold.getTime(), 'the threshold sits after every item').toBeGreaterThanOrEqual(
          lastItemCreatedAt.getTime(),
        );
        expect(threshold.getTime(), 'the threshold sits before the version').toBeLessThan(
          versionCreatedAt.getTime(),
        );
      });

      await test.step('The window lists no rows', async () => {
        const listed = await backendClient.listDatasetItemsPage({ datasetId, filters: windowFilter });
        expect(listed.total, `the reported total for created_at > ${threshold.toISOString()}`).toBe(0);
        expect(listed.items, 'the rows carried for an empty window').toHaveLength(0);
      });

      await test.step('A delete with the identical filter removes nothing', async () => {
        await backendClient.deleteDatasetItemsByFilter({ datasetId, filters: windowFilter });

        const remaining = await backendClient.listDatasetItemsPage({ datasetId });
        expect(sorted(remaining.items.map((i) => i.id)), 'every item survives a delete that matched nothing')
          .toEqual(sorted(itemIds));
        expect(remaining.total, 'the reported total is unchanged').toBe(TOTAL_ITEMS);
      });
    },
  );

  test(
    'a batch update filtered by item id tags exactly the item that filter lists',
    { tag: ['@cap:datasets.filter-scoped-batch-update'] },
    async ({ versionedDataset, backendClient }) => {
      // Known failure — OPIK-8150, the same `id` binding as the delete above.
      test.fail();

      const { id: datasetId, itemIds } = versionedDataset;
      const target = itemIds[0];
      const idFilter: BackendFilter[] = [{ field: 'id', operator: '=', value: target }];

      await test.step('The filter lists exactly one item, and it is the target', async () => {
        const listed = await backendClient.listDatasetItemsPage({ datasetId, filters: idFilter });
        expect(listed.total, `the reported total for id = ${target}`).toBe(1);
        expect(listed.items[0].id, 'the listed row is the target item').toBe(target);
      });

      await test.step('The update tags exactly that item, and no other', async () => {
        await backendClient.batchUpdateDatasetItemsByFilter({
          datasetId,
          filters: idFilter,
          tags: [APPLIED_TAG],
        });

        const after = await backendClient.listDatasetItemsPage({ datasetId });
        expect(after.items, 'a scoped update must not add or remove rows').toHaveLength(TOTAL_ITEMS);

        const tagged = after.items.filter((item) => item.tags.includes(APPLIED_TAG)).map((i) => i.id);
        expect(tagged, 'exactly the target item carries the new tag').toEqual([target]);
      });
    },
  );
});
