import { test, expect } from '@e2e/fixtures';
import { CompareExperimentsPage } from '@e2e/pom/compare-experiments.page';

/**
 * The browser export on the comparison Results tab.
 *
 * Every assertion here is about the FILE, not the page. The export is assembled
 * in the tab and handed to the browser as a download, so the failure it exists
 * to catch — a file that quietly holds the 100 rows on screen instead of all
 * 250, or values cut short at the display truncation — looks perfectly healthy
 * from the DOM. Nothing else under tests/experiments reads a downloaded file.
 */

/** The dataset column the exported rows are keyed on. */
const INPUT_KEY = 'dataset.input';

/** The label the filter popover lists the `bucket` dataset column under. */
const BUCKET_FILTER_COLUMN = 'bucket (Dataset)';

/** Every exported row's value for one dataset column, in file order. */
const columnValues = (rows: Record<string, unknown>[], key: string): string[] =>
  rows.map((row, i) => {
    const value = row[key];
    if (typeof value !== 'string') {
      throw new Error(
        `exported row ${i} carries no string "${key}" (got ${JSON.stringify(value)}) — ` +
          'the export dropped a dataset column the assertions are keyed on',
      );
    }
    return value;
  });

const sorted = (values: string[]): string[] => [...values].sort();

test.describe(
  'Experiments comparison — browser export',
  { tag: ['@t2-cuj', '@area:experiments'] },
  () => {
    test(
      'exporting with no rows selected covers every page and carries untruncated values',
      { tag: ['@cap:experiments.export-comparison'] },
      async ({ exportComparison, project, page }) => {
        test.slow();

        const compare = new CompareExperimentsPage(
          page,
          project.id,
          exportComparison.datasetId,
          exportComparison.experiments.map((e) => e.id),
        );

        await test.step('Open the compare Results tab', async () => {
          await compare.gotoResults();
          await compare.waitForResultsReady();
        });

        await test.step('The export is offered with nothing selected', async () => {
          await compare.expectExportEnabled();
        });

        let exported: Record<string, unknown>[] = [];
        await test.step('Export the unscoped view as JSON', async () => {
          exported = await compare.exportAsJson();
        });

        await test.step('The file holds every seeded row, exactly once', async () => {
          expect(exported, 'exported rows').toHaveLength(exportComparison.rowCount);
          // The whole answer, not just "mine are in it": a file that also
          // carried rows from another comparison would satisfy a subset check,
          // and a file that held only the page on screen would not reach 250.
          expect(sorted(columnValues(exported, INPUT_KEY)), 'exported dataset inputs').toEqual(
            sorted(exportComparison.items.map((i) => i.input)),
          );
        });

        await test.step('The long value is whole in the file but cut short on screen', async () => {
          const longRow = exported.find(
            (row) => row[INPUT_KEY] === exportComparison.longItemInput,
          );
          expect(longRow, `exported row for ${exportComparison.longItemInput}`).toBeDefined();
          expect(
            longRow!['dataset.detail'],
            'the exported cell carries the full stored value',
          ).toBe(exportComparison.longDetail);

          // Narrow the grid to that one row first: the table is virtualised, so
          // a row 137 places out of view has no cell in the DOM to read.
          await compare.searchItems(exportComparison.longItemInput);
          await compare.expectRenderedRowCount(1);
          const onScreen = await compare.readDatasetCellText(
            exportComparison.longItemId,
            'detail',
          );
          expect(
            onScreen.length,
            'the grid truncates the long value for display',
          ).toBeLessThan(exportComparison.longDetail.length);
        });
      },
    );

    test(
      'the exported file follows the grid filter, search and sort',
      { tag: ['@cap:experiments.export-comparison'] },
      async ({ exportComparison, project, page }) => {
        test.slow();

        const compare = new CompareExperimentsPage(
          page,
          project.id,
          exportComparison.datasetId,
          exportComparison.experiments.map((e) => e.id),
        );

        await test.step('Open the compare Results tab', async () => {
          await compare.gotoResults();
          await compare.waitForResultsReady();
        });

        await test.step('A filter narrows the exported file to the matching rows', async () => {
          const filteredTotal = await compare.addGridFilter(BUCKET_FILTER_COLUMN, 'even');
          // The grid's own count first: an export that matched a view which was
          // never filtered would otherwise read as a passing scope assertion.
          expect(filteredTotal, 'rows the filtered grid reports').toBe(
            exportComparison.evenInputs.length,
          );

          const exported = await compare.exportAsJson();
          expect(exported, 'exported rows under the filter').toHaveLength(
            exportComparison.evenInputs.length,
          );
          expect(
            sorted(columnValues(exported, INPUT_KEY)),
            'exported dataset inputs under the filter',
          ).toEqual(sorted(exportComparison.evenInputs));
        });

        await test.step('A search on top narrows it to the intersection, not to either half', async () => {
          // The seed is built so each constraint admits a different count —
          // 125 filtered, 10 searched, 5 both — so an export that dropped one
          // of them lands on a number the other cannot produce.
          expect(
            exportComparison.evenMarkedInputs.length,
            'seed sanity: the intersection is a proper subset of both constraints',
          ).toBeLessThan(
            Math.min(exportComparison.evenInputs.length, exportComparison.markedInputs.length),
          );

          await compare.searchItems(exportComparison.searchMarker);

          const exported = await compare.exportAsJson();
          expect(exported, 'exported rows under filter + search').toHaveLength(
            exportComparison.evenMarkedInputs.length,
          );
          expect(
            sorted(columnValues(exported, INPUT_KEY)),
            'exported dataset inputs under filter + search',
          ).toEqual(sorted(exportComparison.evenMarkedInputs));
        });

        await test.step('The file is ordered the way the grid is ordered', async () => {
          await compare.sortByColumn('data.input', 'desc');

          const renderedOrder = await compare.itemRowOrder();
          const exported = await compare.exportAsJson();
          const inputById = new Map(exportComparison.items.map((i) => [i.id, i.input]));

          expect(renderedOrder, 'rendered rows under filter + search').toHaveLength(
            exportComparison.evenMarkedInputs.length,
          );
          // Against the RENDERED order rather than a hardcoded one: that pins
          // the file to whatever the table shows without this spec asserting a
          // server-side ordering of its own.
          expect(columnValues(exported, INPUT_KEY), 'exported row order').toEqual(
            renderedOrder.map((id) => {
              const input = inputById.get(id);
              if (input === undefined) {
                throw new Error(`rendered row ${id} is not one of the seeded items`);
              }
              return input;
            }),
          );
          // And that order is not the one the grid started in, so "the file
          // matches the table" is not satisfied by both being unsorted.
          expect(
            columnValues(exported, INPUT_KEY),
            'descending sort really reordered the file',
          ).toEqual(sorted(exportComparison.evenMarkedInputs).reverse());
        });
      },
    );

    test(
      'selecting rows exports only the selection, and clearing it restores the full export',
      { tag: ['@cap:experiments.export-comparison'] },
      async ({ exportComparison, project, page }) => {
        test.slow();

        const compare = new CompareExperimentsPage(
          page,
          project.id,
          exportComparison.datasetId,
          exportComparison.experiments.map((e) => e.id),
        );

        await test.step('Open the compare Results tab', async () => {
          await compare.gotoResults();
          await compare.waitForResultsReady();
        });

        // Three rows the grid has actually rendered — the checkbox only exists
        // for those, and the default row order is not the seed's.
        const picked = (await compare.itemRowOrder()).slice(0, 3);
        const inputById = new Map(exportComparison.items.map((i) => [i.id, i.input]));
        const pickedInputs = picked.map((id) => {
          const input = inputById.get(id);
          if (input === undefined) {
            throw new Error(`rendered row ${id} is not one of the seeded items`);
          }
          return input;
        });
        expect(picked, 'rows picked to select').toHaveLength(3);

        await test.step('Exporting with three rows ticked yields exactly those three', async () => {
          await compare.selectRows(picked);

          const exported = await compare.exportAsJson();
          expect(exported, 'exported rows under a selection').toHaveLength(picked.length);
          expect(
            sorted(columnValues(exported, INPUT_KEY)),
            'exported dataset inputs under a selection',
          ).toEqual(sorted(pickedInputs));
        });

        await test.step('Clearing the selection exports the whole result set again', async () => {
          await compare.deselectRows(picked);

          const exported = await compare.exportAsJson();
          expect(exported, 'exported rows after clearing the selection').toHaveLength(
            exportComparison.rowCount,
          );
        });
      },
    );
  },
);
