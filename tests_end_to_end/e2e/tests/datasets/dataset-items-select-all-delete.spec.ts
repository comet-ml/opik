import { test, expect } from '@e2e/fixtures';
import { VERSIONED_DATASET_MATCHING } from '@e2e/fixtures';
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
 *
 * That failure is real and open as OPIK-8150, so the delete's outcome is
 * asserted in a separate `test.fail()` test below rather than here; see the
 * comment on it for why the two are split.
 */

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

      await test.step('The delete request is accepted', async () => {
        const status = await items.bulkDeleteAllSelected();
        expect(status, 'the delete request was accepted').toBe(204);
      });
    },
  );

  /**
   * Known failure — OPIK-8150. The select-all delete sends the filter and lets
   * the server decide the scope; the server binds `id` to the version snapshot
   * row's column, which carries a fresh UUID from the second version on. The
   * prefix therefore matches no snapshot row, and a 204 comes back over a table
   * nothing was removed from.
   *
   * Split from the test above rather than marking that one `test.fail()`: a
   * blanket `test.fail()` swallows every failure in its test, which would mask
   * the pagination, banner and selection assertions that do pass and would keep
   * reporting green while testing nothing. The test above is this one's guard —
   * it carries everything up to and including the accepted request, so a broken
   * filter, grid or banner fails loudly there instead of being absorbed here.
   *
   * Asserted against the CORRECT behaviour, so once OPIK-8150 lands this reports
   * "Expected to fail, but passed" and the `test.fail()` comes out.
   */
  test(
    'the select-all delete removes exactly the matching items, and only those',
    { tag: ['@cap:datasets.bulk-delete-items'] },
    async ({ versionedDataset, backendClient, page }) => {
      test.fail();

      const { id: datasetId, projectId, idPrefix, prefixBystanderItemIds } = versionedDataset;
      const items = new DatasetItemsPage(page, projectId, datasetId);
      const sorted = (ids: string[]): string[] => [...ids].sort();

      await items.goto({ filters: [{ field: 'id', type: 'string', operator: 'starts_with', value: idPrefix }] });
      await items.waitForReady();
      await items.selectAllOnPage();
      await items.selectAllMatching(VERSIONED_DATASET_MATCHING);
      await items.bulkDeleteAllSelected();

      const remaining = await backendClient.listDatasetItemsPage({ datasetId });
      expect(sorted(remaining.items.map((i) => i.id)), 'exactly the bystanders survive')
        .toEqual(sorted(prefixBystanderItemIds));
    },
  );
});
