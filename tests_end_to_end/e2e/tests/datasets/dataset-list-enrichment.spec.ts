import { test, expect } from '@e2e/fixtures';
import { DatasetsPage, DATASET_COLUMN, EMPTY_CELL } from '@e2e/pom/datasets.page';
import type { EnrichedDatasetRef } from '@e2e/core/backend';

/**
 * Per-row enrichment of the Datasets list (OPIK-8176).
 *
 * `datasets.list-datasets` is already covered, but only for the row being
 * *there*: dataset-crud-smoke asserts the row is visible, and
 * dataset-version-counters reads `GET /datasets/{id}/versions` rather than the
 * enriched list. Nothing asserted the numbers on the page.
 *
 * Every number on `/projects/{id}/datasets` comes from
 * `enrichDatasetWithAdditionalInformation`, which fans four lookups out
 * concurrently and zips the answers back onto the rows. Its failure modes are
 * both silent: a lookup that returns nothing is read as a legitimate zero, and
 * a zip that slips lands one dataset's counters on another's row. Either leaves
 * a page that looks perfectly healthy, which is why this asserts values rather
 * than presence.
 *
 * Surface is deliberately both. The API half pins the exact values including
 * timestamps; the UI half proves those values are what the page renders, on the
 * right row. Comparing the multi-row list to the single-dataset read is the
 * part that catches cross-wiring specifically: `GET /datasets/{id}` takes the
 * same enrichment path with a one-element `ids` set, so the two agreeing is a
 * fact about the fan-out, not about either read alone.
 */

/**
 * The comparable shape of one row, with timestamps reduced to present/absent.
 * Their exact instants are seeded-at-test-time and so can't be asserted
 * literally; whether enrichment produced one at all is the fact that
 * distinguishes a used dataset from an unused one.
 */
function summarise(row: EnrichedDatasetRef) {
  return {
    name: row.name,
    datasetItemsCount: row.datasetItemsCount,
    experimentCount: row.experimentCount,
    optimizationCount: row.optimizationCount,
    hasMostRecentExperimentAt: row.mostRecentExperimentAt !== null,
    hasMostRecentOptimizationAt: row.mostRecentOptimizationAt !== null,
  };
}

test.describe('Datasets list — per-row enrichment', { tag: ['@t2-cuj', '@area:datasets'] }, () => {
  /** Seeding two datasets, an experiment and an optimization, then waiting for the ClickHouse-backed counters to settle. */
  test.slow();

  test(
    'each row carries its own counters, and the list agrees with the single-dataset read and with the page',
    { tag: ['@cap:datasets.list-datasets'] },
    async ({ enrichedDatasets, backendClient, page }) => {
      const { busy, quiet, projectId } = enrichedDatasets;

      const expectedSummaries = [
        {
          name: busy.name,
          datasetItemsCount: busy.itemCount,
          experimentCount: 1,
          optimizationCount: 1,
          hasMostRecentExperimentAt: true,
          hasMostRecentOptimizationAt: true,
        },
        {
          name: quiet.name,
          datasetItemsCount: quiet.itemCount,
          experimentCount: 0,
          optimizationCount: 0,
          hasMostRecentExperimentAt: false,
          hasMostRecentOptimizationAt: false,
        },
      ].sort((a, b) => a.name.localeCompare(b.name));

      const listed = await test.step('The enriched list settles on each dataset\'s own counters', async () => {
        // Both the total and the row set are asserted, not just "our rows are
        // in there": a project-scoped read that leaked a sibling project's
        // datasets, or dropped one of ours, has to fail here.
        await expect
          .poll(
            async () => {
              const { total, rows } = await backendClient.listEnrichedDatasets({ projectId });
              return {
                total,
                rows: rows.map(summarise).sort((a, b) => a.name.localeCompare(b.name)),
              };
            },
            {
              message: 'the project holds exactly the two seeded datasets, each with its own counters',
              timeout: 60_000,
            },
          )
          .toEqual({ total: 2, rows: expectedSummaries });

        const { rows } = await backendClient.listEnrichedDatasets({ projectId });
        return rows;
      });

      await test.step('Every enriched field matches the single-dataset read of the same dataset', async () => {
        for (const seeded of [busy, quiet]) {
          const fromList = listed.find((row) => row.id === seeded.id);
          expect(fromList, `${seeded.name} is in the list answer`).toBeDefined();

          const byId = await backendClient.getEnrichedDataset(seeded.id);
          // Full-object equality, timestamps included: batch enrichment and
          // single-element enrichment share a code path, so any field where
          // they disagree is a fan-out defect.
          expect(byId, `${seeded.name}: list row and get-by-id agree on every enriched field`)
            .toEqual(fromList);
        }
      });

      await test.step('The Datasets page renders those counters on the right rows', async () => {
        const datasets = new DatasetsPage(page);
        await datasets.goto(projectId);
        await datasets.waitForReady();
        // Not in the default column set, so the page cannot show it unasked.
        await datasets.showColumn('Most recent optimization');

        const cell = (datasetId: string, column: (typeof DATASET_COLUMN)[keyof typeof DATASET_COLUMN]) =>
          datasets.cell(datasetId, column);

        for (const [datasetId, expectedItems] of [
          [busy.id, busy.itemCount],
          [quiet.id, quiet.itemCount],
        ] as const) {
          // One cell per (row, column) — an ambiguous match would mean the
          // assertions below were reading some other row's number.
          await expect(cell(datasetId, DATASET_COLUMN.itemCount)).toHaveCount(1);
          await expect(
            cell(datasetId, DATASET_COLUMN.itemCount),
            `Item count for ${datasetId}`,
          ).toHaveText(String(expectedItems));
        }

        for (const column of [
          DATASET_COLUMN.mostRecentExperiment,
          DATASET_COLUMN.mostRecentOptimization,
        ]) {
          // The rendered instant is relative to now ("3 mins ago"), so the
          // assertion is on the distinction the enrichment actually makes:
          // the used dataset gets a timestamp, the unused one gets the dash.
          await expect(cell(busy.id, column)).toHaveCount(1);
          await expect(cell(busy.id, column), `${column} is populated for the used dataset`)
            .toHaveText(/\S/);
          await expect(cell(busy.id, column), `${column} is not the empty dash for the used dataset`)
            .not.toHaveText(EMPTY_CELL);

          await expect(cell(quiet.id, column)).toHaveCount(1);
          await expect(cell(quiet.id, column), `${column} is empty for the unused dataset`)
            .toHaveText(EMPTY_CELL);
        }
      });
    },
  );
});
