import { test, expect } from '@e2e/fixtures';
import { DatasetsPage } from '@e2e/pom/datasets.page';
import type { BackendClient, DatasetVersionRef } from '@e2e/core/backend';

/**
 * A dataset version stores its own item counters — items_total / items_added /
 * items_modified / items_deleted. The Version history tab renders items_total
 * as "Item count" and the other three as the `+ / ~ / −` tags of its "Changes
 * summary" column, so a wrong counter is silent wrongness on a page users read.
 *
 * OPIK-7707 (#7705) put both write paths behind one signed-delta UPDATE:
 * `updateVersionCountsForInsert` and `updateVersionCountsForDelete` now funnel
 * through `updateVersionCounts(versionId, workspace, totalDelta, addedDelta,
 * modifiedDelta, deletedDelta, user)` — six positional ints, where a sign or an
 * argument slip would hide. Nothing covers the *delete* side of that:
 * `dataset-items.spec.ts` bulk-deletes but counts rendered rows and SDK items,
 * never a counter, so an items_total that kept climbing after a delete would
 * leave the whole suite green.
 *
 * Both specs drive the **ungrouped** write path — no `batch_group_id`, which
 * mutates the latest version in place rather than cutting a new one — and after
 * every operation assert three things together: the exact counter quadruple,
 * that the version was mutated and not replaced, and that items_total agrees
 * with the item ids the dataset actually holds. Then they read the same numbers
 * back off the Version history tab, which is the surface that has to agree with
 * the API.
 */

/** The four counters of a version, asserted as one value so a diff is legible. */
interface VersionCounters {
  total: number;
  added: number;
  modified: number;
  deleted: number;
}

/**
 * Dataset item ids must be UUIDv7; the backend rejects any other version. Ids
 * are minted here rather than read back after each write so that what a step
 * asserts about the stored set is independent of what the API reports.
 */
function uuidV7(): string {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  const millis = Date.now();
  bytes[0] = (millis / 2 ** 40) & 0xff;
  bytes[1] = (millis / 2 ** 32) & 0xff;
  bytes[2] = (millis / 2 ** 24) & 0xff;
  bytes[3] = (millis / 2 ** 16) & 0xff;
  bytes[4] = (millis / 2 ** 8) & 0xff;
  bytes[5] = millis & 0xff;
  bytes[6] = 0x70 | (bytes[6] & 0x0f);
  bytes[8] = 0x80 | (bytes[8] & 0x3f);
  const hex = [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function newItems(count: number, revision: string) {
  return Array.from({ length: count }, (_, index) => ({
    id: uuidV7(),
    data: { input: `${revision} input ${index}`, expected_output: `${revision} output ${index}` },
  }));
}

/**
 * Asserts the dataset's whole version state at once.
 *
 * `expectedItemIds` is the full set the dataset should hold, not a subset: a
 * delete that removed the wrong rows leaves the count right and the contents
 * wrong, and a check that only looked for the survivors it expected would pass
 * over a leak.
 */
async function expectVersionState(
  backendClient: BackendClient,
  datasetId: string,
  expected: { versionId: string; counters: VersionCounters; itemIds: string[] },
): Promise<void> {
  const versions = await backendClient.getDatasetVersions(datasetId);
  // An ungrouped write must never cut a version. If one appeared, every
  // assertion below would be reading the wrong row — fail here instead.
  expect(versions).toHaveLength(1);
  const version = versions[0];
  expect(version.isLatest).toBe(true);
  expect(version.id).toBe(expected.versionId);

  expect({
    total: version.itemsTotal,
    added: version.itemsAdded,
    modified: version.itemsModified,
    deleted: version.itemsDeleted,
  }).toEqual(expected.counters);

  const storedIds = await backendClient.listDatasetItemIds(datasetId);
  expect([...storedIds].sort()).toEqual([...expected.itemIds].sort());
  // The counter is only worth rendering if it agrees with the items stored.
  expect(version.itemsTotal).toBe(expected.itemIds.length);
}

/** The dataset's sole version, read before any of the writes under test. */
async function readSeededVersion(
  backendClient: BackendClient,
  datasetId: string,
): Promise<{ version: DatasetVersionRef; itemIds: string[] }> {
  const versions = await backendClient.getDatasetVersions(datasetId);
  expect(versions).toHaveLength(1);
  return { version: versions[0], itemIds: await backendClient.listDatasetItemIds(datasetId) };
}

test.describe('Dataset version counters — deletes and interleaved writes', { tag: ['@area:datasets'] }, () => {
  /** Items toolbar collapses to icon-only (no accessible name) below ~850px container width. */
  test.use({ viewport: { width: 1600, height: 900 } });

  test(
    'Deleting items moves only the total and deleted counters of the version it mutates, and the Version history tab renders them',
    { tag: ['@t2-cuj', '@cap:datasets.version-history-view'] },
    async ({ dataset, project, backendClient, page }) => {
      const seeded = await test.step('Read the counters the seeded version starts from', async () => {
        const { version, itemIds } = await readSeededVersion(backendClient, dataset.id);
        expect(itemIds).toHaveLength(dataset.items.length);
        await expectVersionState(backendClient, dataset.id, {
          versionId: version.id,
          counters: { total: 3, added: 3, modified: 0, deleted: 0 },
          itemIds,
        });
        return { versionId: version.id, itemIds };
      });

      const afterInsert = await test.step('Adding three items raises total and added, in the same version', async () => {
        const added = newItems(3, 'added');
        await backendClient.upsertDatasetItemsIntoLatestVersion({
          datasetId: dataset.id,
          items: added,
        });
        const itemIds = [...seeded.itemIds, ...added.map((item) => item.id)];
        await expectVersionState(backendClient, dataset.id, {
          versionId: seeded.versionId,
          counters: { total: 6, added: 6, modified: 0, deleted: 0 },
          itemIds,
        });
        return itemIds;
      });

      const survivors = afterInsert.slice(2);
      const removed = afterInsert.slice(0, 2);

      await test.step('Deleting two items drops total by two and raises deleted by two — added and modified untouched', async () => {
        expect(await backendClient.deleteDatasetItemsByIds(removed)).toBe(204);
        await expectVersionState(backendClient, dataset.id, {
          versionId: seeded.versionId,
          counters: { total: 4, added: 6, modified: 0, deleted: 2 },
          itemIds: survivors,
        });
      });

      await test.step('Re-sending the same delete is a no-op 204, not a 404, and moves no counter', async () => {
        // The `deletedCount == 0` short-circuit has to fire before the counter
        // update, or a repeated delete writes a phantom delta.
        expect(await backendClient.deleteDatasetItemsByIds(removed)).toBe(204);
        await expectVersionState(backendClient, dataset.id, {
          versionId: seeded.versionId,
          counters: { total: 4, added: 6, modified: 0, deleted: 2 },
          itemIds: survivors,
        });
      });

      await test.step('The Version history tab renders the reduced count and the deleted figure', async () => {
        const datasets = new DatasetsPage(page);
        await datasets.goto(project.id);
        await datasets.waitForReady();
        const items = await datasets.openDatasetByName(dataset.name);
        await items.waitForReady();
        await items.openVersionHistory();

        await expect(items.versionItemCount('v1')).toHaveText('4');
        // Exactly two tags: nothing was modified, so a `~` tag appearing here
        // is the disagreement worth failing on.
        await expect(items.versionChangeTags('v1')).toHaveCount(2);
        await expect(items.versionChangeTag('v1', 'added', 6)).toBeVisible();
        await expect(items.versionChangeTag('v1', 'deleted', 2)).toBeVisible();
      });
    },
  );

  test(
    'Interleaved inserts, upserts and deletes keep the version counters equal to the items the dataset holds',
    { tag: ['@t2-cuj', '@cap:datasets.version-history-view'] },
    async ({ dataset, project, backendClient, page }) => {
      // A single operation cannot catch a delta applied to the wrong column:
      // after one write the numbers still look plausible. A sequence that mixes
      // both signs and touches all three change counters can.
      const seeded = await test.step('Read the counters the seeded version starts from', async () => {
        const { version, itemIds } = await readSeededVersion(backendClient, dataset.id);
        expect(itemIds).toHaveLength(dataset.items.length);
        await expectVersionState(backendClient, dataset.id, {
          versionId: version.id,
          counters: { total: 3, added: 3, modified: 0, deleted: 0 },
          itemIds,
        });
        return { versionId: version.id, itemIds };
      });

      let held = seeded.itemIds;

      const firstBatch = newItems(4, 'first');
      await test.step('Insert four items', async () => {
        await backendClient.upsertDatasetItemsIntoLatestVersion({
          datasetId: dataset.id,
          items: firstBatch,
        });
        held = [...held, ...firstBatch.map((item) => item.id)];
        await expectVersionState(backendClient, dataset.id, {
          versionId: seeded.versionId,
          counters: { total: 7, added: 7, modified: 0, deleted: 0 },
          itemIds: held,
        });
      });

      await test.step('Delete two of them', async () => {
        const removed = firstBatch.slice(0, 2).map((item) => item.id);
        expect(await backendClient.deleteDatasetItemsByIds(removed)).toBe(204);
        held = held.filter((id) => !removed.includes(id));
        await expectVersionState(backendClient, dataset.id, {
          versionId: seeded.versionId,
          counters: { total: 5, added: 7, modified: 0, deleted: 2 },
          itemIds: held,
        });
      });

      await test.step('Insert three more', async () => {
        const secondBatch = newItems(3, 'second');
        await backendClient.upsertDatasetItemsIntoLatestVersion({
          datasetId: dataset.id,
          items: secondBatch,
        });
        held = [...held, ...secondBatch.map((item) => item.id)];
        await expectVersionState(backendClient, dataset.id, {
          versionId: seeded.versionId,
          counters: { total: 8, added: 10, modified: 0, deleted: 2 },
          itemIds: held,
        });
      });

      await test.step('Re-send two stored ids with new content — an upsert, which moves modified only', async () => {
        await backendClient.upsertDatasetItemsIntoLatestVersion({
          datasetId: dataset.id,
          items: held.slice(0, 2).map((id) => ({ id, data: { input: 'edited', expected_output: 'edited' } })),
        });
        await expectVersionState(backendClient, dataset.id, {
          versionId: seeded.versionId,
          counters: { total: 8, added: 10, modified: 2, deleted: 2 },
          itemIds: held,
        });
      });

      await test.step('Delete one more', async () => {
        const removed = held.slice(-1);
        expect(await backendClient.deleteDatasetItemsByIds(removed)).toBe(204);
        held = held.filter((id) => !removed.includes(id));
        await expectVersionState(backendClient, dataset.id, {
          versionId: seeded.versionId,
          counters: { total: 7, added: 10, modified: 2, deleted: 3 },
          itemIds: held,
        });
      });

      await test.step('The Version history tab renders all three change tags with the API figures', async () => {
        const datasets = new DatasetsPage(page);
        await datasets.goto(project.id);
        await datasets.waitForReady();
        const items = await datasets.openDatasetByName(dataset.name);
        await items.waitForReady();
        await items.openVersionHistory();

        await expect(items.versionItemCount('v1')).toHaveText('7');
        await expect(items.versionChangeTags('v1')).toHaveCount(3);
        await expect(items.versionChangeTag('v1', 'added', 10)).toBeVisible();
        await expect(items.versionChangeTag('v1', 'modified', 2)).toBeVisible();
        await expect(items.versionChangeTag('v1', 'deleted', 3)).toBeVisible();
      });
    },
  );
});
