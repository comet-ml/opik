import {
  test,
  expect,
  EXPORT_PAGE_SIZE,
  EXPORT_SCORE_NAME,
  EXPORT_SEARCH_TERM,
  type ExportComparisonRef,
} from '@e2e/fixtures';
import type { Page } from '@playwright/test';
import { CompareExperimentsPage } from '@e2e/pom/compare-experiments.page';
import { parseCsv } from '@e2e/core/csv';

/**
 * Exporting a selection out of the experiment comparison grid.
 *
 * The grid narrows what it shows by a toolbar search and a column sort, and
 * paginates what is left. The export is a *second* fetch of the same endpoint,
 * intersected with the ids the user ticked — so if that fetch does not ask for
 * the same window the user is looking at, the intersection is empty and the
 * download arrives with a header row and nothing under it. No error, no toast:
 * the only way to notice is to open the file.
 *
 * Both tests therefore select rows that provably do not exist on the page the
 * export would fetch if it dropped the search or the sort — the fixture checks
 * that against the API before the browser opens.
 */
test.describe('Experiments comparison export — CUJ', { tag: ['@t2-cuj', '@area:experiments'] }, () => {
  test('a searched selection exports exactly the rows the search left on screen', { tag: ['@cap:experiments.export-comparison'] }, async ({
    exportComparison,
    project,
    page,
  }) => {
    const compare = compareGrid(page, project.id, exportComparison);
    const expectedLabels = exportComparison.searchMatchLabels;

    await test.step('Open the compare grid, searched for the seeded topic', async () => {
      await compare.gotoResults({ size: EXPORT_PAGE_SIZE, search: EXPORT_SEARCH_TERM });
      await compare.waitForResultsReady();
      expect(
        await labelsOnScreen(compare, exportComparison),
        `rows left on screen by the search "${EXPORT_SEARCH_TERM}"`,
      ).toEqual(expectedLabels);
    });

    await test.step('Select every matching row', async () => {
      await compare.selectRows(expectedLabels.map((label) => exportComparison.itemIdByLabel[label]));
    });

    await test.step('The CSV holds those rows and only those rows', async () => {
      const exported = await compare.exportSelection('CSV');
      expect(exported.filename, 'downloaded file name').toMatch(/\.csv$/);

      const csv = parseCsv(exported.body);
      expect(csv.headers, 'the dataset input column is in the export').toContain('dataset.input');
      expect(csv.rows.map((row) => row['dataset.input']), 'data rows in the CSV').toEqual(
        expectedLabels,
      );
      expect(
        exported.requestParams.get('search'),
        'the export refetch asked for the searched window',
      ).toBe(EXPORT_SEARCH_TERM);
    });

    await test.step('The JSON export reads the same query and holds the same rows', async () => {
      const exported = await compare.exportSelection('JSON');
      expect(exported.filename, 'downloaded file name').toMatch(/\.json$/);

      const rows = JSON.parse(exported.body) as Array<Record<string, unknown>>;
      expect(rows.map((row) => row['dataset.input']), 'records in the JSON file').toEqual(
        expectedLabels,
      );
      expect(
        exported.requestParams.get('search'),
        'the export refetch asked for the searched window',
      ).toBe(EXPORT_SEARCH_TERM);
    });
  });

  test('a selection made on page 2 of a score-sorted grid exports exactly those rows', { tag: ['@cap:experiments.export-comparison'] }, async ({
    exportComparison,
    project,
    page,
  }) => {
    const compare = compareGrid(page, project.id, exportComparison);
    const expectedFirstPageLabels = exportComparison.scoreSortedLabels.slice(0, EXPORT_PAGE_SIZE);
    const expectedSecondPageLabels = exportComparison.scoreSortedLabels.slice(
      EXPORT_PAGE_SIZE,
      EXPORT_PAGE_SIZE * 2,
    );
    const selectedLabels = exportComparison.sortedPageTwoSelectionLabels;

    await test.step(`Sort the grid by ${EXPORT_SCORE_NAME} descending`, async () => {
      await compare.gotoResults({
        size: EXPORT_PAGE_SIZE,
        sorting: { columnId: `feedback_scores_${EXPORT_SCORE_NAME}`, direction: 'desc' },
      });
      await compare.waitForResultsReady();
      expect(
        await labelsOnScreen(compare, exportComparison),
        'page 1 of the score-sorted grid',
      ).toEqual(expectedFirstPageLabels);
    });

    await test.step('Page forward to page 2', async () => {
      await compare.goToNextPage();
      expect(
        await labelsOnScreen(compare, exportComparison),
        'page 2 of the score-sorted grid',
      ).toEqual(expectedSecondPageLabels);
    });

    await test.step('Select two of the rows on that page', async () => {
      await compare.selectRows(selectedLabels.map((label) => exportComparison.itemIdByLabel[label]));
    });

    await test.step('The CSV holds those two rows, in the order they were on screen', async () => {
      const exported = await compare.exportSelection('CSV');

      const csv = parseCsv(exported.body);
      expect(csv.rows.map((row) => row['dataset.input']), 'data rows in the CSV').toEqual(
        selectedLabels,
      );
      expect(
        exported.requestParams.get('sorting'),
        'the export refetch asked for the sorted window',
      ).toBe(JSON.stringify([{ field: `feedback_scores.${EXPORT_SCORE_NAME}`, direction: 'DESC' }]));
      expect(exported.requestParams.get('page'), 'the page the export refetched').toBe('2');
    });
  });
});

/** The compare grid for the fixture's two experiments over its dataset. */
function compareGrid(page: Page, projectId: string, ref: ExportComparisonRef): CompareExperimentsPage {
  return new CompareExperimentsPage(
    page,
    projectId,
    ref.datasetId,
    ref.experiments.map((experiment) => experiment.experimentId),
  );
}

/** The rendered row order, read back as the seeded labels the spec reasons in. */
async function labelsOnScreen(
  compare: CompareExperimentsPage,
  ref: ExportComparisonRef,
): Promise<string[]> {
  const ids = await compare.itemRowOrder();
  return ids.map((id) => ref.labelByItemId[id] ?? id);
}
