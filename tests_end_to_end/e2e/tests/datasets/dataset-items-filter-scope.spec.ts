import { test, expect } from '@e2e/fixtures';
import { GROUP_COLUMN, TARGET_GROUP } from '@e2e/fixtures';
import type { BackendFilter } from '@e2e/core/backend';
import { DatasetsPage } from '@e2e/pom/datasets.page';

/**
 * Deleting and batch-updating dataset items by *filter* rather than by id
 * (OPIK-7923).
 *
 * `datasets.bulk-delete-items` is covered by dataset-items.spec.ts, but only
 * for the UI path, where the user ticks the rows and can see what they picked.
 * The filter-scoped variant is different in kind: the caller never names the
 * ids, the server decides the scope, and the delete is not reversible. A filter
 * that over-matches destroys data that nobody asked it to touch, and there is
 * no UI step at which that becomes visible first.
 *
 * So every assertion here is about *exact* scope — which rows changed and which
 * demonstrably did not — rather than about a count. A count would pass for a
 * filter that deleted the wrong three items.
 *
 * The second half covers the validation `FiltersFactory` now applies to these
 * two endpoints: a filter the backend cannot serve has to be refused at
 * validation (400/422) rather than failing inside the query builder (500).
 *
 * SCOPE — these are the *ungrouped* forms of both endpoints: no
 * `batch_group_id` is sent, so each mutation updates the latest dataset version
 * rather than committing a new one. The UI's select-all variants do send one.
 * Filter *scoping* is what needed covering here — an over-matching filter
 * deletes data irreversibly — while version-commit semantics are asserted for
 * the id-scoped paths by dataset-items and dataset-version-counters. A grouped
 * variant is worth its own test; it is not this one.
 *
 * Tags: `bulk-delete-items` for the filter-scoped delete, plus
 * `filter-scoped-batch-update` and `filter-scoped-mutation-validation` — both
 * added to the taxonomy with this spec, because the batch-update path and the
 * validation contract are behaviours the map had no key for. The UI sends
 * `filters` on this mutation (`useDatasetItemBatchUpdateMutation`), so these are
 * user-facing behaviours, not API-only affordances.
 */

const groupFilter: BackendFilter[] = [
  { field: 'data', key: GROUP_COLUMN, operator: '=', value: TARGET_GROUP },
];

/** An operator no LIST field can serve — the 400 path. */
const unsupportedOperatorFilter = [{ field: 'tags', operator: '>', value: 'x' }];

const APPLIED_TAG = 'filter-scoped-update';

test.describe('Dataset items — filter-scoped mutations', { tag: ['@t3-nightly', '@area:datasets'] }, () => {
  test(
    'a filter-scoped batch update and delete change exactly the items the filter matches',
    {
      tag: [
        '@cap:datasets.bulk-delete-items',
        '@cap:datasets.filter-scoped-batch-update',
        '@cap:datasets.filter-scoped-mutation-validation',
      ],
    },
    async ({ groupedDataset, project, backendClient, page }) => {
      const { id: datasetId, targetItemIds, bystanderItemIds, allItemIds } = groupedDataset;
      const sorted = (ids: string[]): string[] => [...ids].sort();

      await test.step('The filter matches exactly the three target items, and nothing else', async () => {
        const unfiltered = await backendClient.listDatasetItemsFiltered({ datasetId });
        expect(sorted(unfiltered.map((i) => i.id)), 'the dataset holds all six seeded items')
          .toEqual(sorted(allItemIds));

        const matched = await backendClient.listDatasetItemsFiltered({
          datasetId,
          filters: groupFilter,
        });
        // Both halves matter: the right ids, and no extra ones. A `find()` over
        // the response would pass even if it also carried the bystanders.
        expect(matched, `items matching data.${GROUP_COLUMN} = ${TARGET_GROUP}`)
          .toHaveLength(targetItemIds.length);
        expect(sorted(matched.map((i) => i.id)), 'the filter matches exactly the target items')
          .toEqual(sorted(targetItemIds));
      });

      await test.step('An operator the backend cannot serve is refused, not run', async () => {
        for (const operation of ['delete', 'batch-update'] as const) {
          const result = await backendClient.datasetItemMutationStatus({
            operation,
            datasetId,
            filters: unsupportedOperatorFilter,
          });
          expect(result.status, `${operation} with an unsupported operator`).toBe(400);
          expect(result.message, `${operation} rejection names the field`).toContain('tags');
          expect(result.message, `${operation} rejection names the operator`).toContain('>');
        }
      });

      await test.step('A malformed filter list is refused as unprocessable, not as a server error', async () => {
        for (const operation of ['delete', 'batch-update'] as const) {
          const result = await backendClient.datasetItemMutationStatus({
            operation,
            datasetId,
            filters: [null],
          });
          expect(result.status, `${operation} with a null filter element`).toBe(422);
        }
      });

      await test.step('The batch update tags exactly the matching items', async () => {
        await backendClient.batchUpdateDatasetItemsByFilter({
          datasetId,
          filters: groupFilter,
          tags: [APPLIED_TAG],
        });

        const after = await backendClient.listDatasetItemsFiltered({ datasetId });
        expect(after, 'a scoped update must not add or remove rows').toHaveLength(allItemIds.length);

        const tagged = after.filter((item) => item.tags.includes(APPLIED_TAG)).map((i) => i.id);
        expect(sorted(tagged), 'exactly the target items carry the new tag')
          .toEqual(sorted(targetItemIds));

        for (const id of bystanderItemIds) {
          const bystander = after.find((item) => item.id === id);
          expect(bystander, `bystander ${id} still exists`).toBeDefined();
          expect(bystander!.tags, `bystander ${id} was not touched by the scoped update`)
            .not.toContain(APPLIED_TAG);
        }
      });

      await test.step('The filter-scoped delete removes exactly the matching items', async () => {
        await backendClient.deleteDatasetItemsByFilter({ datasetId, filters: groupFilter });

        const remaining = await backendClient.listDatasetItemsFiltered({ datasetId });
        expect(sorted(remaining.map((i) => i.id)), 'exactly the non-matching items survive')
          .toEqual(sorted(bystanderItemIds));
      });

      await test.step('The dataset items page renders the surviving rows and only those', async () => {
        const datasets = new DatasetsPage(page);
        await datasets.goto(project.id);
        await datasets.waitForReady();
        const items = await datasets.openDatasetByName(groupedDataset.name);
        await items.waitForReady();

        await expect
          .poll(async () => sorted(await items.itemRowIds()), {
            message: 'the grid lists exactly the surviving items',
          })
          .toEqual(sorted(bystanderItemIds));
      });
    },
  );
});
