import { test, expect } from '@e2e/fixtures';
import { EXPORT_LABEL_COLUMN } from '@e2e/fixtures';
import type { BackendSort } from '@e2e/core/backend';
import { CompareExperimentsPage } from '@e2e/pom/compare-experiments.page';

/**
 * Exporting the experiment-comparison Results grid (OPIK-8030, OPIK-8031).
 *
 * The export used to re-fetch the rows without the grid's `sorting` and
 * `search`, and with the same truncation the table renders with. It now sends
 * both, and `truncate=false`.
 *
 * What makes this worth a permanent test is where the failure lands. A
 * misordered, over-inclusive or silently cut-off export does not error and does
 * not look wrong on screen — the file leaves the product and is read somewhere
 * else, so nobody is in a position to notice that the 4 KB completion they are
 * scoring stops dead at a thousand characters.
 *
 * The fixture's matching subset in label-DESC order is neither the insertion
 * order nor the label-ASC order, and one seeded row must not match the search
 * at all, so an export that dropped either parameter cannot produce the
 * expected answer by accident. The first step asserts those properties hold
 * before anything else is checked.
 */

const LABEL_FIELD = `data.${EXPORT_LABEL_COLUMN}`;
const SORT_DESC: BackendSort[] = [{ field: LABEL_FIELD, direction: 'DESC' }];

/** The exported key for a dataset column, per CompareExperimentsActionsPanel. */
const EXPORTED_LABEL_KEY = `dataset.${EXPORT_LABEL_COLUMN}`;

/** The exported key for one experiment's evaluation-task output in compare mode. */
const exportedOutputKey = (experimentName: string): string => `${experimentName}.output.answer`;

test.describe('Experiment comparison — export', { tag: ['@t2-cuj', '@area:experiments'] }, () => {
  /**
   * Seeding four multi-kilobyte dataset items plus eight traces across two
   * experiments, then downloading and parsing the export, outruns the default
   * budget against a cloud backend. Declared on the describe so it also covers
   * fixture setup.
   */
  test.slow();

  test(
    'the compare items read carries the grid sort and search, and only the export gets untruncated payloads',
    { tag: ['@cap:experiments.export-comparison'] },
    async ({ exportComparison, backendClient }) => {
      const {
        datasetId,
        experiments,
        itemIdByLabel,
        searchTerm,
        decoyLabel,
        insertionOrderLabels,
        matchingLabelsByLabelDesc,
      } = exportComparison;
      const experimentIds = experiments.map((experiment) => experiment.experimentId);

      const read = (args: { search?: string; sorting?: BackendSort[]; truncate?: boolean }) =>
        backendClient.listCompareItems({ datasetId, experimentIds, ...args });

      await test.step('The fixture discriminates: the searched subset has its own order', () => {
        const matchingByInsertion = insertionOrderLabels.filter((label) => label !== decoyLabel);
        expect(insertionOrderLabels, 'a row that must not match the search was seeded')
          .toContain(decoyLabel);
        expect(matchingLabelsByLabelDesc, 'label-DESC order differs from insertion order')
          .not.toEqual(matchingByInsertion);
        expect([...matchingLabelsByLabelDesc].reverse(), 'label-ASC order differs from insertion order')
          .not.toEqual(matchingByInsertion);
      });

      await test.step('Every seeded item is linked to both experiments', async () => {
        // The dataset write, the trace writes and the experiment-item linkage
        // are all eventually consistent, and this read joins all three — so
        // poll the read itself rather than any one of them.
        await expect
          .poll(async () => (await read({ sorting: SORT_DESC })).items.length, {
            timeout: 60_000,
            intervals: [500, 1_000, 2_000],
          })
          .toBe(Object.keys(itemIdByLabel).length);
      });

      const expectedMatchingIds = matchingLabelsByLabelDesc.map((label) => itemIdByLabel[label]);

      await test.step('Search plus sort returns exactly the matching rows, in sorted order', async () => {
        const answer = await read({ search: searchTerm, sorting: SORT_DESC });
        expect(answer.items.map((item) => item.id), 'rows for the searched, sorted read')
          .toEqual(expectedMatchingIds);
        // The ids being right is not enough on its own: a search that matched
        // everything would still contain them, just with the decoy alongside on
        // this page or a later one. The total is what rules that out.
        expect(answer.total, 'server total for the searched read')
          .toBe(expectedMatchingIds.length);
        expect(
          answer.items.map((item) => item.data[EXPORT_LABEL_COLUMN]),
          'the decoy row is excluded',
        ).not.toContain(decoyLabel);
      });

      await test.step('The export read (truncate=false) returns the complete payloads', async () => {
        const answer = await read({ search: searchTerm, sorting: SORT_DESC, truncate: false });
        for (const item of answer.items) {
          expect(item.experimentItems, `both experiments present on row ${item.id}`)
            .toHaveLength(experiments.length);
          for (const experiment of experiments) {
            const band = item.experimentItems.find(
              (experimentItem) => experimentItem.experimentId === experiment.experimentId,
            );
            expect(band, `${experiment.experimentName} band on row ${item.id}`).toBeDefined();
            expect(
              (band!.output as { answer?: unknown } | null)?.answer,
              `${experiment.experimentName} output on row ${item.id}`,
            ).toBe(experiment.outputsByItemId[item.id]);
          }
        }
      });

      await test.step('The grid read (truncate=true) cuts the same payloads short', async () => {
        // This is what makes the assertion above mean something: without it, an
        // export that had quietly kept `truncate=true` would still pass if the
        // seeded payloads happened to sit under the server's truncation size.
        const answer = await read({ search: searchTerm, sorting: SORT_DESC, truncate: true });
        expect(answer.items.map((item) => item.id), 'the truncated read returns the same rows')
          .toEqual(expectedMatchingIds);
        for (const item of answer.items) {
          for (const experiment of experiments) {
            const band = item.experimentItems.find(
              (experimentItem) => experimentItem.experimentId === experiment.experimentId,
            );
            expect(band, `${experiment.experimentName} band on row ${item.id}`).toBeDefined();
            const truncated = String((band!.output as { answer?: unknown } | null)?.answer ?? '');
            expect(
              truncated.length,
              `${experiment.experimentName} output on row ${item.id} is truncated`,
            ).toBeLessThan(experiment.outputsByItemId[item.id].length);
          }
        }
      });
    },
  );

  test(
    'exporting the grid downloads the rows on screen, in their order, with untruncated payloads',
    { tag: ['@cap:experiments.export-comparison'] },
    async ({ exportComparison, project, backendClient, page }) => {
      const { datasetId, experiments, itemIdByLabel, searchTerm, decoyLabel, matchingLabelsByLabelDesc } =
        exportComparison;
      const experimentIds = experiments.map((experiment) => experiment.experimentId);
      const expectedMatchingIds = matchingLabelsByLabelDesc.map((label) => itemIdByLabel[label]);

      await test.step('Every seeded item is linked to both experiments', async () => {
        await expect
          .poll(
            async () =>
              (await backendClient.listCompareItems({ datasetId, experimentIds, sorting: SORT_DESC }))
                .items.length,
            { timeout: 60_000, intervals: [500, 1_000, 2_000] },
          )
          .toBe(Object.keys(itemIdByLabel).length);
      });

      const compare = new CompareExperimentsPage(page, project.id, datasetId, experimentIds);

      await test.step('Open the compare Results tab, sorted by label descending and searched', async () => {
        await compare.gotoResults();
        await compare.waitForResultsReady();
        await compare.sortByColumn(LABEL_FIELD, 'desc');
        await compare.searchItems(searchTerm);
      });

      await test.step('The grid shows exactly the matching rows, in the sorted order', async () => {
        expect(await compare.itemRowOrder(), 'grid rows under search + label DESC')
          .toEqual(expectedMatchingIds);
      });

      const exported = await test.step('Select every row and export as JSON', async () => {
        await compare.selectAllRows();
        return compare.exportSelectedAsJson();
      });

      await test.step('The file carries the rows on screen, in the same order', async () => {
        expect(exported, 'one exported row per row on screen').toHaveLength(expectedMatchingIds.length);
        expect(
          exported.map((row) => row[EXPORTED_LABEL_KEY]),
          'exported row order matches the grid',
        ).toEqual(matchingLabelsByLabelDesc);
        expect(
          exported.map((row) => row[EXPORTED_LABEL_KEY]),
          'the searched-out row is not in the file',
        ).not.toContain(decoyLabel);
      });

      await test.step("Each row's payloads are complete, not the grid's truncated copy", async () => {
        for (const [index, label] of matchingLabelsByLabelDesc.entries()) {
          const itemId = itemIdByLabel[label];
          for (const experiment of experiments) {
            expect(
              exported[index][exportedOutputKey(experiment.experimentName)],
              `${experiment.experimentName} output exported for ${label}`,
            ).toBe(experiment.outputsByItemId[itemId]);
          }
          expect(
            exported[index][`dataset.input`],
            `dataset input exported for ${label}`,
          ).toBe(exportComparison.datasetInputsByItemId[itemId]);
        }
      });
    },
  );
});
