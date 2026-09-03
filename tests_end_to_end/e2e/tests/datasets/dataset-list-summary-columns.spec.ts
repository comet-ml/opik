import { test, expect } from '@e2e/fixtures';
import { DatasetsPage } from '@e2e/pom/datasets.page';
import type { SummarisedDatasetRef } from '@e2e/fixtures';

/**
 * Every row of the Datasets list carries four numbers the dataset does not
 * store: how many items it holds, how many experiments and optimizations use
 * it, and which version is latest. The backend derives each from a separate
 * lookup and zips them onto the row.
 *
 * That makes the interesting failure a mis-attribution rather than an error —
 * a real number belonging to a different dataset. It renders as a completely
 * plausible row on a page people read daily, and nothing about it looks wrong.
 * Nothing in this estate could catch it: `dataset-crud-smoke.spec.ts` asserts
 * only that a row is visible, and the datasets POM had no column readers at
 * all.
 *
 * The `summarisedDatasets` fixture is what makes the assertion able to fail:
 * four datasets whose (items, experiments, optimizations, versions) tuples are
 * pairwise distinct, so a summary attached to the wrong row disagrees with
 * something. One of them is empty, covering the "no rows to summarise" branch
 * of all four lookups at once — the case where a lookup that answers with a
 * default instead of a zero, or throws, shows up.
 *
 * Split by surface, because they fail for different reasons and are worth
 * failing separately:
 *   - the API test pins the contract, including the fields the list renders no
 *     column for (`experiment_count`, `optimization_count`, `latest_version`),
 *     and re-reads under pagination, where a zip in the wrong order is visible
 *     only once a page holds a subset of the rows;
 *   - the UI test pins what a user actually reads off the page, which is the
 *     item count and the two recency columns.
 */

/** Rendered by `TimeCell` when the underlying timestamp is null. */
const NO_TIME = '-';

function byName(datasets: SummarisedDatasetRef[]): string[] {
  return datasets.map((d) => d.name).sort();
}

test.describe('Datasets list — computed summary columns', { tag: ['@area:datasets'] }, () => {
  test(
    'Each row of GET /datasets carries its own dataset totals, unchanged under pagination',
    { tag: ['@t2-cuj', '@cap:datasets.list-datasets'] },
    async ({ summarisedDatasets, backendClient }) => {
      const seeded = summarisedDatasets.datasets;

      const listed = await test.step('Read the datasets list for the seeded project', async () => {
        const { total, rows } = await backendClient.listDatasetSummaries({
          projectId: summarisedDatasets.projectId,
        });
        // The whole answer, not just "mine are in it": a scope leak that also
        // returned another project's datasets is itself a defect, and a
        // per-row `find()` would pass straight through it.
        expect(total, 'the project holds exactly the seeded datasets').toBe(seeded.length);
        expect(rows.map((r) => r.name).sort()).toEqual(byName(seeded));
        return new Map(rows.map((r) => [r.name, r]));
      });

      for (const dataset of seeded) {
        await test.step(`${dataset.name}: the summary is this dataset's own`, async () => {
          const row = listed.get(dataset.name);
          expect(row, 'the seeded dataset is listed').toBeDefined();
          expect(row!.id, 'the listed row is the dataset that was seeded').toBe(dataset.id);

          expect(row!.datasetItemsCount, 'dataset_items_count').toBe(dataset.itemCount);
          expect(row!.experimentCount, 'experiment_count').toBe(dataset.shape.experiments);
          expect(row!.optimizationCount, 'optimization_count').toBe(
            dataset.shape.optimizations,
          );

          // The recency timestamps are what the list actually renders, and they
          // must be present on exactly the datasets that have the thing they
          // name — a timestamp on a dataset with no experiment is the same
          // mis-attribution seen from the other side.
          if (dataset.shape.experiments > 0) {
            expect(row!.mostRecentExperimentAt, 'most_recent_experiment_at').not.toBeNull();
          } else {
            expect(row!.mostRecentExperimentAt, 'most_recent_experiment_at').toBeNull();
          }
          if (dataset.shape.optimizations > 0) {
            expect(
              row!.mostRecentOptimizationAt,
              'most_recent_optimization_at',
            ).not.toBeNull();
          } else {
            expect(row!.mostRecentOptimizationAt, 'most_recent_optimization_at').toBeNull();
          }
        });

        await test.step(`${dataset.name}: the list agrees with the dataset's own endpoints`, async () => {
          // The counts above are checked against what the fixture seeded; these
          // check them against what the backend itself reports elsewhere. Both
          // matter: the first catches a wrong number, the second catches the
          // list and the detail disagreeing, which leaves a user reading two
          // different truths for the same dataset.
          const detail = await backendClient.getDatasetSummary(dataset.id);
          expect(detail, 'the detail read reports the same summary as the list').toEqual(
            listed.get(dataset.name),
          );

          const items = await backendClient.getDatasetItems(dataset.id);
          expect(items, 'the items the dataset actually holds').toHaveLength(
            dataset.itemCount,
          );

          const versions = await backendClient.getDatasetVersions(dataset.id);
          expect(versions, 'one version per insert() call').toHaveLength(
            dataset.versionCount,
          );
        });
      }

      await test.step('The empty dataset reports zeros and a null latest_version', async () => {
        const empty = seeded.find((d) => d.itemCount === 0);
        expect(empty, 'the fixture seeds an empty dataset').toBeDefined();
        const row = listed.get(empty!.name)!;
        expect(row.datasetItemsCount).toBe(0);
        expect(row.experimentCount).toBe(0);
        expect(row.optimizationCount).toBe(0);
        expect(
          row.latestVersionHash,
          'a dataset with no version has no latest version, rather than erroring',
        ).toBeNull();
      });

      await test.step('Every non-empty dataset names its own latest version', async () => {
        for (const dataset of seeded.filter((d) => d.itemCount > 0)) {
          const versions = await backendClient.getDatasetVersions(dataset.id);
          const latest = versions.filter((v) => v.isLatest);
          expect(latest, `${dataset.name} has exactly one latest version`).toHaveLength(1);
          // Asserted present rather than compared as two possibly-absent
          // values: `null === null` would otherwise read as agreement.
          expect(latest[0].versionHash, `${dataset.name} version hash`).not.toBeNull();
          expect(
            listed.get(dataset.name)!.latestVersionHash,
            `${dataset.name}: the list names the version the dataset actually has`,
          ).toBe(latest[0].versionHash);
        }
      });

      await test.step('Paging the same read returns the same summaries', async () => {
        // A summary zipped onto the rows in the wrong order still looks correct
        // while every dataset is on one page. Halving the page size is the
        // cheapest way to make that ordering observable.
        const first = await backendClient.listDatasetSummaries({
          projectId: summarisedDatasets.projectId,
          page: 1,
          size: 2,
        });
        const second = await backendClient.listDatasetSummaries({
          projectId: summarisedDatasets.projectId,
          page: 2,
          size: 2,
        });
        expect(first.rows, 'first page holds half the datasets').toHaveLength(2);
        expect(second.rows, 'second page holds the rest').toHaveLength(2);

        const paged = [...first.rows, ...second.rows].sort((a, b) =>
          a.name.localeCompare(b.name),
        );
        const unpaged = [...listed.values()].sort((a, b) => a.name.localeCompare(b.name));
        expect(paged, 'the paged read agrees with the unpaged one, row for row').toEqual(
          unpaged,
        );
      });
    },
  );

  test(
    'The Datasets list renders each row its own item count and recency columns',
    { tag: ['@t2-cuj', '@cap:datasets.list-datasets'] },
    async ({ summarisedDatasets, page }) => {
      const datasets = new DatasetsPage(page);

      await test.step('Open the Datasets list and show the optimization column', async () => {
        await datasets.goto(summarisedDatasets.projectId);
        await datasets.waitForReady();
        // "Most recent optimization" is off by default, so the column a user
        // would have to turn on to see optimization recency is turned on here.
        await datasets.setColumnEnabled('Most recent optimization', true);
      });

      for (const dataset of summarisedDatasets.datasets) {
        await test.step(`${dataset.name}: the rendered row matches its own shape`, async () => {
          await expect(
            datasets.datasetRow(dataset.name),
            'the dataset is listed exactly once',
          ).toHaveCount(1);

          expect(
            await datasets.datasetCellText(dataset.id, 'dataset_items_count'),
            'Item count',
          ).toBe(String(dataset.itemCount));

          const experimentAt = await datasets.datasetCellText(
            dataset.id,
            'most_recent_experiment_at',
          );
          const optimizationAt = await datasets.datasetCellText(
            dataset.id,
            'most_recent_optimization_at',
          );

          // Asserted in both directions: a recency stamp is as wrong on a
          // dataset that has no experiment as its absence is on one that does.
          if (dataset.shape.experiments > 0) {
            expect(experimentAt, 'Most recent experiment is populated').not.toBe(NO_TIME);
            expect(experimentAt).not.toBe('');
          } else {
            expect(experimentAt, 'Most recent experiment is empty').toBe(NO_TIME);
          }
          if (dataset.shape.optimizations > 0) {
            expect(optimizationAt, 'Most recent optimization is populated').not.toBe(NO_TIME);
            expect(optimizationAt).not.toBe('');
          } else {
            expect(optimizationAt, 'Most recent optimization is empty').toBe(NO_TIME);
          }
        });
      }
    },
  );
});
