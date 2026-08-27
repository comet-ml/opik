import { test, expect } from '@e2e/fixtures';
import { VERSIONED_DATASET_BYSTANDERS, VERSIONED_DATASET_MATCHING } from '@e2e/fixtures';
import { DatasetItemsPage } from '@e2e/pom/dataset-items.page';

/**
 * The dataset items grid's select-all bulk delete (OPIK-7791, from the
 * 2.2.42 -> 2.2.43 release exploration).
 *
 * Every existing dataset-items spec ticks rows one at a time, which stages a
 * draft the user reviews and commits. This is a different code path with a
 * different blast radius: `DatasetItemsActionsPanel` sends an id list unless
 * `isAllItemsSelected` is set, and the ONLY control that sets it is the "Select
 * all N items?" banner — which only renders when the filtered set spans more
 * than one page. Every other spec stays under the default 10-row page, so
 * nothing in the estate has ever reached this path. Once it is set, the request
 * is the filter-scoped one: the browser sends the filter, the server decides
 * the scope, and the delete commits its own version immediately with no draft
 * to review.
 *
 * What that makes worth asserting is not "a delete happened" but that the rows
 * that went are exactly the rows the user was looking at. The grid is filtered
 * by an id prefix that three seeded items deliberately do not carry: they are
 * the bystanders, and they must still be there afterwards, so "the 12 rows
 * went" cannot be satisfied by a delete that emptied the dataset.
 *
 * The success toast is not evidence of anything and is deliberately not
 * asserted on — the failure this test exists to catch renders a success toast
 * over an unchanged table. The assertions are the rendered rows and the rows
 * the API still holds.
 */

const TOTAL_ITEMS = VERSIONED_DATASET_MATCHING + VERSIONED_DATASET_BYSTANDERS;
const PAGE_SIZE = 10;

test.describe('Dataset items — select-all delete', { tag: ['@t2-cuj', '@area:datasets'] }, () => {
  /** Items toolbar collapses to icon-only (no accessible name) below ~850px container width. */
  test.use({ viewport: { width: 1600, height: 900 } });

  test(
    'selecting every matching item across pages deletes exactly those items',
    { tag: ['@cap:datasets.view-items', '@cap:datasets.bulk-delete-items'] },
    async ({ versionedDataset, backendClient, page }) => {
      const { id: datasetId, projectId, idPrefix, matchingItemIds, prefixBystanderItemIds } =
        versionedDataset;
      const items = new DatasetItemsPage(page, projectId, datasetId);
      const sorted = (ids: string[]): string[] => [...ids].sort();

      await test.step('The filter really does select some rows and leave others', async () => {
        // Asserted server-side before the browser opens: a fixture that failed
        // to separate the two sets would leave the UI assertions below unable
        // to fail, and reading as coverage forever.
        const matched = await backendClient.listDatasetItemsPage({
          datasetId,
          filters: [{ field: 'id', operator: 'starts_with', value: idPrefix }],
        });
        expect(matched.total, `items matching the id prefix ${idPrefix}`).toBe(VERSIONED_DATASET_MATCHING);
        expect(sorted(matched.items.map((i) => i.id)), 'the prefix matches exactly the intended items')
          .toEqual(sorted(matchingItemIds));
        expect(VERSIONED_DATASET_MATCHING, 'the filtered set must exceed one page for the banner to render')
          .toBeGreaterThan(PAGE_SIZE);
      });

      await test.step('The filtered grid shows a full page and reports the whole filtered set', async () => {
        await items.goto({ filters: [{ field: 'id', type: 'string', operator: 'starts_with', value: idPrefix }] });
        await items.waitForReady();

        expect(await items.countItems(), 'rows rendered on the first page').toBe(PAGE_SIZE);
        await expect(items.paginationSummary, 'the pagination summary').toHaveText(
          `Showing 1-${PAGE_SIZE} of ${VERSIONED_DATASET_MATCHING}`,
        );
      });

      await test.step('The banner escalates the selection from this page to the whole filtered set', async () => {
        await items.selectAllOnPage();
        await items.selectAllMatching(VERSIONED_DATASET_MATCHING);
        await expect(
          items.allItemsSelectedBanner(VERSIONED_DATASET_MATCHING),
          'the banner confirms the whole filtered set is selected',
        ).toBeVisible();
      });

      await test.step('Deleting the selection empties the filtered grid', async () => {
        const status = await items.bulkDeleteAllSelected();
        expect(status, 'the delete request was accepted').toBe(204);

        await expect
          .poll(() => items.countItems(), { message: 'rows still rendered under the filter' })
          .toBe(0);
        await expect(items.emptyState, 'the filtered grid renders its empty state').toBeVisible();
      });

      await test.step('And removes exactly the matching items server-side', async () => {
        const remaining = await backendClient.listDatasetItemsPage({ datasetId });
        expect(sorted(remaining.items.map((i) => i.id)), 'exactly the bystanders survive')
          .toEqual(sorted(prefixBystanderItemIds));
        expect(remaining.total, 'the reported total agrees with the surviving rows')
          .toBe(VERSIONED_DATASET_BYSTANDERS);
        expect(TOTAL_ITEMS - remaining.total, 'items removed').toBe(VERSIONED_DATASET_MATCHING);
      });

      await test.step('The delete is recorded on the version it committed', async () => {
        const versions = await backendClient.getDatasetVersions(datasetId);
        const latest = versions.find((v) => v.isLatest);
        expect(latest, 'a version is marked latest').toBeDefined();
        expect(latest!.itemsDeleted, 'the new version records what the delete removed')
          .toBe(VERSIONED_DATASET_MATCHING);
        expect(latest!.itemsTotal, 'the new version holds the surviving items')
          .toBe(VERSIONED_DATASET_BYSTANDERS);
      });
    },
  );
});
