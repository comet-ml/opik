import { test, expect } from '@e2e/fixtures';
import { DatasetsPage } from '@e2e/pom/datasets.page';
import type { DatasetVersionRef } from '@e2e/core/backend';

/**
 * `Dataset.insert()` drops items whose content hash it has already seen, and
 * `deduplication=False` turns that off: every item is sent as-is. Nothing in
 * the estate asserts either half of that flag. `dataset-crud-smoke.spec.ts`
 * round-trips a UI-added item, and `dataset-version-repeated-item-id.spec.ts`
 * deliberately seeds through the backend client *because* `insert()` dedups —
 * so the SDK dedup path itself has never been asserted, in either state.
 *
 * Both tests below pair the flag against its own default on a second entity
 * seeded identically, so the flag is the only variable and neither test can
 * pass by coincidence (e.g. a backend that stored everything, or one that
 * collapsed everything).
 *
 * The numbers matter more than the rows: `items_added` is rendered on the
 * Version history tab as a "+ N" tag and `items_total` as "Item count", and a
 * version reporting the wrong number is silent wrongness on a page a user
 * cannot sanity-check by eye.
 */

/** One item's worth of content, reused verbatim so the hashes collide. */
const ITEM = { input: 'dedup probe input', expected_output: 'dedup probe output' };
const OTHER = { input: 'second content', expected_output: 'second output' };
const THIRD = { input: 'third content', expected_output: 'third output' };

function counters(versions: DatasetVersionRef[]) {
  return versions
    .map((v) => ({
      versionName: v.versionName,
      itemsTotal: v.itemsTotal,
      itemsAdded: v.itemsAdded,
      itemsModified: v.itemsModified,
    }))
    .sort((a, b) => a.versionName.localeCompare(b.versionName));
}

/** How many stored items carry exactly this content, keys and all. */
function countMatching(
  items: Array<{ data: Record<string, unknown> }>,
  content: Record<string, string>,
): number {
  return items.filter((item) =>
    Object.entries(content).every(([key, value]) => item.data[key] === value),
  ).length;
}

test.describe('Dataset insert — deduplication flag', { tag: ['@area:datasets'] }, () => {
  test(
    'deduplication=false stores every duplicate and counts it; the default collapses duplicates into one item and one version',
    { tag: ['@t2-cuj', '@cap:datasets.sdk-round-trip'] },
    async ({
      project,
      sdkClient,
      backendClient,
      registerDatasetCleanup,
      testNamespace,
      page,
    }) => {
      const offName = `${testNamespace}-dedup-off`;
      const onName = `${testNamespace}-dedup-on`;

      const { offId, onId } = await test.step(
        'Insert the same item twice into two datasets — once with deduplication off, once with the default',
        async () => {
          const off = await sdkClient.python.createDataset({
            project_name: project.name,
            name: offName,
            description: 'deduplication=false',
          });
          // Registered the moment the id exists: these datasets are created
          // mid-test, so no seed fixture can know them upfront, and datasets do
          // not cascade with their project.
          registerDatasetCleanup(off.id, offName);
          const on = await sdkClient.python.createDataset({
            project_name: project.name,
            name: onName,
            description: 'deduplication default (true)',
          });
          registerDatasetCleanup(on.id, onName);

          // Two separate insert() calls per dataset, identical payloads, one
          // flag apart. No item ids are supplied, so the SDK mints a fresh one
          // per item — anything stored twice is stored under two distinct ids.
          for (let call = 0; call < 2; call++) {
            await sdkClient.python.insertDatasetItems({
              project_name: project.name,
              dataset_name: offName,
              items: [ITEM],
              deduplication: false,
            });
            await sdkClient.python.insertDatasetItems({
              project_name: project.name,
              dataset_name: onName,
              items: [ITEM],
            });
          }

          return { offId: off.id, onId: on.id };
        },
      );

      const offItemIds = await test.step(
        'With deduplication off the dataset holds both copies, under distinct ids',
        async () => {
          const items = await backendClient.getDatasetItems(offId);
          // Length first, then content: asserting only "two rows match ITEM"
          // would also pass if a third, unrelated row had been stored.
          expect(items, 'both inserts were stored').toHaveLength(2);
          expect(countMatching(items, ITEM), 'and both carry the content sent').toBe(2);
          const ids = items.map((item) => item.id);
          expect(new Set(ids).size, 'stored as two separate items, not one overwritten').toBe(2);
          return ids;
        },
      );

      const onItemIds = await test.step(
        'With the default the second insert is dropped entirely',
        async () => {
          const items = await backendClient.getDatasetItems(onId);
          expect(items, 'the duplicate never reached the backend').toHaveLength(1);
          expect(countMatching(items, ITEM)).toBe(1);
          return items.map((item) => item.id);
        },
      );

      await test.step('Each non-deduplicated insert cut its own version, counting its item', async () => {
        expect(counters(await backendClient.getDatasetVersions(offId))).toEqual([
          { versionName: 'v1', itemsTotal: 1, itemsAdded: 1, itemsModified: 0 },
          // The duplicate counts as an addition, not a modification: it is a
          // new item id, not an edit of the one already stored.
          { versionName: 'v2', itemsTotal: 2, itemsAdded: 1, itemsModified: 0 },
        ]);
      });

      await test.step('The deduplicated dataset has one version — the second insert sent nothing', async () => {
        // Pre-existing "nothing sent -> nothing versioned" behaviour, unchanged
        // by the flag: insert() was called twice, but the second call
        // deduplicated to zero items so no batch was uploaded and no version
        // was cut. Asserted so a future change that starts cutting empty
        // versions is caught here rather than surprising someone reading the
        // Version history tab.
        expect(counters(await backendClient.getDatasetVersions(onId))).toEqual([
          { versionName: 'v1', itemsTotal: 1, itemsAdded: 1, itemsModified: 0 },
        ]);
      });

      await test.step('Both copies render on the Records tab, and Version history counts 1 then 2', async () => {
        const datasets = new DatasetsPage(page);
        await datasets.goto(project.id);
        await datasets.waitForReady();
        const items = await datasets.openDatasetByName(offName);
        await items.waitForReady();

        await expect(items.itemRows(), 'two rows render').toHaveCount(2);
        // By id, not by cell text: the two rows are content-identical, so only
        // `data-row-id` can show the grid is rendering both stored items
        // rather than the same one twice.
        expect((await items.itemRowIds()).sort()).toEqual([...offItemIds].sort());

        await items.openVersionHistory();
        await expect(items.versionItemCount('v1')).toHaveText('1');
        await expect(items.versionItemCount('v2')).toHaveText('2');
        await expect(items.versionChangeSummary('v1')).toHaveText('+ 1');
        await expect(items.versionChangeSummary('v2')).toHaveText('+ 1');
      });

      await test.step('The deduplicated dataset renders exactly one row', async () => {
        const datasets = new DatasetsPage(page);
        await datasets.goto(project.id);
        await datasets.waitForReady();
        const items = await datasets.openDatasetByName(onName);
        await items.waitForReady();

        await expect(items.itemRows(), 'one row renders').toHaveCount(1);
        expect(await items.itemRowIds()).toEqual(onItemIds);
      });
    },
  );

  test(
    'A deduplication=false insert invalidates the local hash cache, so the next deduplicated insert still drops the duplicate',
    { tag: ['@t2-cuj', '@cap:datasets.version-history-view'] },
    async ({
      project,
      sdkClient,
      backendClient,
      registerDatasetCleanup,
      testNamespace,
      page,
    }) => {
      const datasetName = `${testNamespace}-dedup-resync`;

      /**
       * Three inserts against ONE `Dataset` object, which is why this goes
       * through `insertDatasetItemsSession` rather than three
       * `insertDatasetItems` calls: the bridge builds a fresh client per
       * request, and a backend-fetched `Dataset` always starts with its hash
       * cache marked unsynced — so across separate calls the third insert
       * would re-sync no matter what the second one did, and the test could
       * not fail.
       *
       * The order is chosen so the cache is genuinely stale by insert 3:
       *  1. a DEDUPLICATED insert, which forces the one-shot backend sync and
       *     leaves the cache marked in-sync holding only ITEM's hash;
       *  2. a NON-deduplicated insert of [ITEM, OTHER] — nothing is hashed, so
       *     the cache no longer describes the backend and must be invalidated;
       *  3. a DEDUPLICATED insert of [OTHER, THIRD]. Only a re-sync can know
       *     OTHER is already stored. Without the invalidation, the cache still
       *     holds ITEM alone, OTHER is sent again under a fresh id, and v3
       *     reads "+ 2" over 6 items instead of "+ 1" over 4.
       */
      const datasetId = await test.step('Seed three inserts in one SDK session', async () => {
        const created = await sdkClient.python.createDataset({
          project_name: project.name,
          name: datasetName,
          description: 'hash cache re-sync after a non-deduplicated insert',
        });
        registerDatasetCleanup(created.id, datasetName);

        await sdkClient.python.insertDatasetItemsSession({
          project_name: project.name,
          dataset_name: datasetName,
          inserts: [
            { items: [ITEM], deduplication: true },
            { items: [ITEM, OTHER], deduplication: false },
            { items: [OTHER, THIRD], deduplication: true },
          ],
        });

        return created.id;
      });

      await test.step('The dataset holds 4 items — the duplicate of OTHER was dropped, not stored again', async () => {
        const items = await backendClient.getDatasetItems(datasetId);
        // Four, not six: insert 2 stored ITEM a second time on purpose
        // (deduplication off), insert 3 added only THIRD.
        expect(items, 'two copies of ITEM plus one OTHER plus one THIRD').toHaveLength(4);
        expect(countMatching(items, ITEM), 'the non-deduplicated insert kept its duplicate').toBe(2);
        expect(countMatching(items, OTHER), 'the deduplicated insert dropped its duplicate').toBe(1);
        expect(countMatching(items, THIRD), 'and stored the item that was genuinely new').toBe(1);
        expect(new Set(items.map((item) => item.id)).size, 'every row has its own id').toBe(4);
      });

      await test.step('The three versions report 1, 2 and 1 additions and no modifications', async () => {
        // Three different added/total pairs, so the assertion cannot pass by
        // coincidence the way a single repeated number could. v3's `+ 1` is
        // the whole point: `+ 2` there is the regression this test exists for.
        expect(counters(await backendClient.getDatasetVersions(datasetId))).toEqual([
          { versionName: 'v1', itemsTotal: 1, itemsAdded: 1, itemsModified: 0 },
          { versionName: 'v2', itemsTotal: 3, itemsAdded: 2, itemsModified: 0 },
          { versionName: 'v3', itemsTotal: 4, itemsAdded: 1, itemsModified: 0 },
        ]);
      });

      await test.step('Version history renders those counters, and all four rows are on the Records tab', async () => {
        const datasets = new DatasetsPage(page);
        await datasets.goto(project.id);
        await datasets.waitForReady();
        const items = await datasets.openDatasetByName(datasetName);
        await items.waitForReady();
        await expect(items.itemRows()).toHaveCount(4);

        await items.openVersionHistory();
        await expect(items.versionItemCount('v1')).toHaveText('1');
        await expect(items.versionItemCount('v2')).toHaveText('3');
        await expect(items.versionItemCount('v3')).toHaveText('4');
        await expect(items.versionChangeSummary('v1')).toHaveText('+ 1');
        await expect(items.versionChangeSummary('v2')).toHaveText('+ 2');
        await expect(items.versionChangeSummary('v3')).toHaveText('+ 1');
      });
    },
  );
});
