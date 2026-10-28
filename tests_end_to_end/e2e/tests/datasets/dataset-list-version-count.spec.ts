import { test, expect } from '@e2e/fixtures';
import { DatasetsPage } from '@e2e/pom/datasets.page';

/**
 * The Datasets list's "Item count" follows the latest committed version across
 * an insert that both rewrites an existing item and adds new ones (OPIK-8176).
 *
 * With versioning on, the number the list renders is the latest version's
 * `items_total`, and `latest_version` arrives from a MySQL transaction opened
 * separately from the dataset row itself. A version lookup that comes back
 * empty falls back to the legacy `dataset_items` count with no error anywhere —
 * the page keeps rendering a number, just the wrong one, and only a dataset
 * whose two counts disagree can tell the difference.
 *
 * Scoped deliberately narrowly, because the estate already covers the
 * neighbouring cases and this spec is only the gap between them:
 *
 *   - `dataset-item-count.spec.ts` drives the same v1 -> v2 move on the list,
 *     but via a *delete*. Its two branches (items_total vs the fallback scan)
 *     and its bystander rows are not re-asserted here.
 *   - `dataset-list-summary-columns.spec.ts` covers the rest of the per-row
 *     summary — experiment/optimization counts, recency timestamps,
 *     mis-attribution across four pairwise-distinct datasets.
 *   - `dataset-version-repeated-item-id.spec.ts` and
 *     `dataset-version-counters.spec.ts` cover what a version *stores*, on the
 *     Version history tab.
 *
 * What none of them do is move the list's number with an insert that is
 * simultaneously a modification and an addition. That is the one shape where
 * the three plausible wrong answers are all distinguishable: a fallback to the
 * pre-insert count reads 3, counting only what the last batch carried reads 2,
 * and counting batch entries rather than distinct ids reads 6. The correct
 * answer is 5, and it is the only one of the four that means the list read
 * `items_total` off the version it claims is latest.
 */

const SEEDED_ITEMS = 3;
const ADDED_ITEMS = 2;
const MODIFIED_ITEMS = 1;
const TOTAL_AFTER_COMMIT = SEEDED_ITEMS + ADDED_ITEMS;

test.describe('Datasets list — Item count follows the committed version', { tag: ['@area:datasets'] }, () => {
  /** Two version commits against a remote backend, plus the settle between them. */
  test.slow();

  test(
    'committing a second version moves the list\'s Item count to that version\'s items_total',
    { tag: ['@t2-cuj', '@cap:datasets.list-datasets'] },
    async ({ dataset, project, sdkClient, backendClient, page }) => {
      const onlyRow = async () => {
        const { total, rows } = await backendClient.listDatasetSummaries({
          projectId: project.id,
        });
        // The project is seeded with exactly this one dataset, so the whole
        // answer is assertable — a find() over a longer list would pass even
        // if the read had leaked rows.
        expect(total, 'the project holds exactly the seeded dataset').toBe(1);
        expect(rows, 'one row for one dataset').toHaveLength(1);
        expect(rows[0].id, 'the row is the seeded dataset').toBe(dataset.id);
        return rows[0];
      };

      await test.step('The freshly seeded dataset reports its first version', async () => {
        await expect
          .poll(
            async () => {
              const row = await onlyRow();
              return {
                datasetItemsCount: row.datasetItemsCount,
                latestVersionName: row.latestVersionName,
              };
            },
            { message: 'the list settles on v1 with the seeded item count', timeout: 60_000 },
          )
          .toEqual({ datasetItemsCount: SEEDED_ITEMS, latestVersionName: 'v1' });

        const versions = await backendClient.getDatasetVersions(dataset.id);
        expect(versions, 'one insert cut exactly one version').toHaveLength(1);
        expect(versions[0].versionName).toBe('v1');
        expect(versions[0].itemsTotal, 'v1 stores the seeded total').toBe(SEEDED_ITEMS);
      });

      const rewrittenItemId = await test.step('Commit a second version: one rewrite and two new items', async () => {
        const existing = await backendClient.getDatasetItems(dataset.id);
        expect(existing, 'the seed is intact before the second insert').toHaveLength(SEEDED_ITEMS);
        const rewritten = existing[0].id;

        // One insert() call, so one new version covering both the
        // modification and the additions. Re-sending an existing id is what
        // makes it a modification rather than a fourth row.
        await sdkClient.python.insertDatasetItems({
          project_name: project.name,
          dataset_name: dataset.name,
          items: [
            { id: rewritten, input: 'seed input 1 rewritten', expected_output: 'seed output 1 rewritten' },
            { input: 'added input 1', expected_output: 'added output 1' },
            { input: 'added input 2', expected_output: 'added output 2' },
          ] as unknown as Array<Record<string, unknown>>,
        });
        return rewritten;
      });

      await test.step('The list moves to v2 and to that version\'s items_total', async () => {
        await expect
          .poll(
            async () => {
              const row = await onlyRow();
              return {
                datasetItemsCount: row.datasetItemsCount,
                latestVersionName: row.latestVersionName,
              };
            },
            { message: 'the list settles on v2 with the new total', timeout: 60_000 },
          )
          .toEqual({ datasetItemsCount: TOTAL_AFTER_COMMIT, latestVersionName: 'v2' });
      });

      await test.step('v2 stores that total, and the dataset really holds that many items', async () => {
        // Corroboration for the number asserted above rather than fresh
        // coverage of the version counters themselves — that is
        // `dataset-version-counters.spec.ts` and
        // `dataset-version-repeated-item-id.spec.ts`. It is here because a list
        // reading `items_total` off the right version is only the correct
        // answer if `items_total` is itself right for this insert shape.
        const versions = await backendClient.getDatasetVersions(dataset.id);
        expect(versions, 'two inserts cut two versions').toHaveLength(2);

        const latest = versions.find((v) => v.isLatest);
        expect(latest, 'one version is marked latest').toBeDefined();
        expect(latest!.versionName).toBe('v2');
        expect(latest!.itemsTotal, 'v2 totals the rewrite and the additions').toBe(
          TOTAL_AFTER_COMMIT,
        );
        expect(latest!.itemsAdded, 'only the two new ids count as added').toBe(ADDED_ITEMS);
        expect(latest!.itemsModified, 'the re-sent id counts as modified').toBe(MODIFIED_ITEMS);

        // Row count and distinct-id count are asserted separately and both
        // against the same total: a de-duplicated count alone would still
        // read 5 if the rewrite had landed as a sixth row carrying a
        // duplicate id, which is the specific failure "updated in place, not
        // duplicated" is claiming did not happen.
        const itemIds = await backendClient.listDatasetItemIds(dataset.id);
        expect(itemIds, 'the dataset holds the total v2 reports, one row per item').toHaveLength(
          TOTAL_AFTER_COMMIT,
        );
        expect(new Set(itemIds).size, 'no id appears on two rows').toBe(TOTAL_AFTER_COMMIT);
        expect(itemIds, 'the rewritten item was updated in place, not duplicated').toContain(
          rewrittenItemId,
        );
      });

      await test.step('The Datasets page renders the new total', async () => {
        const datasets = new DatasetsPage(page);
        await datasets.goto(project.id);
        await datasets.waitForReady();

        // `dataset_items_count` is in the default column set, so no Columns
        // picker step is needed to read it.
        const itemCount = datasets.datasetCell(dataset.id, 'dataset_items_count');
        await expect(itemCount).toHaveCount(1);
        await expect(itemCount, 'Item count on the list page').toHaveText(
          String(TOTAL_AFTER_COMMIT),
        );
      });
    },
  );
});
