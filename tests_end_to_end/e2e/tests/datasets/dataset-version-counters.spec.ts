import { test, expect } from '@e2e/fixtures';
import { DatasetsPage } from '@e2e/pom/datasets.page';
import type { DatasetVersionRef } from '@e2e/core/backend';

/**
 * A dataset version stores its own item counters — items_total / items_added /
 * items_modified — and the Version history tab renders items_total as "Item
 * count". The existing dataset specs insert three items and count rendered
 * rows, so nothing covers the counters themselves: a version can hold the
 * right rows and still report the wrong number, and that number is what a user
 * reads off the page.
 *
 * Shape of the seed, chosen to exercise the two things that make the counters
 * non-trivial:
 *  - SEED_SIZE is above the SDK's 1000-item batch size, so each insert() call
 *    is split into several backend calls that must still collapse into ONE
 *    version;
 *  - the second call re-sends half the ids with changed content (updates) and
 *    half fresh ones (adds), so added/modified/total are three different
 *    numbers and a spec can't pass by coincidence.
 */
const SEED_SIZE = 1200;
const CHANGE_SIZE = SEED_SIZE / 2;

/**
 * What both the sequential and the parallel upload path must store. They share
 * one expectation on purpose: `insert(..., num_threads=8)` splits the same
 * items across parallel batch uploads, and the version it produces has to be
 * indistinguishable from the sequential one.
 */
const EXPECTED_VERSIONS = [
  { versionName: 'v1', itemsTotal: SEED_SIZE, itemsAdded: SEED_SIZE, itemsModified: 0 },
  {
    versionName: 'v2',
    itemsTotal: SEED_SIZE + CHANGE_SIZE,
    itemsAdded: CHANGE_SIZE,
    itemsModified: CHANGE_SIZE,
  },
];

/** Dataset item ids must be UUIDv7; the backend rejects any other version. */
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

function seedItems(ids: string[], from: number, to: number, revision: string) {
  return ids.slice(from, to).map((id, index) => ({
    id,
    input: `item ${from + index} ${revision}`,
    expected_output: `output ${from + index} ${revision}`,
  }));
}

function byVersionName(versions: DatasetVersionRef[]) {
  return versions
    .map((v) => ({
      versionName: v.versionName,
      itemsTotal: v.itemsTotal,
      itemsAdded: v.itemsAdded,
      itemsModified: v.itemsModified,
    }))
    .sort((a, b) => a.versionName.localeCompare(b.versionName));
}

test.describe('Dataset version counters', { tag: ['@area:datasets'] }, () => {
  /** Two 1200-item inserts per dataset against a cloud backend outrun the default budget. */
  test.slow();

  test(
    'A multi-batch insert stores one version whose counters match the items actually stored, and the Version history tab renders them',
    { tag: ['@t2-cuj', '@cap:datasets.version-history-view'] },
    async ({ project, sdkClient, backendClient, testNamespace, page }) => {
      const datasetName = `${testNamespace}-seq`;
      const ids = Array.from({ length: SEED_SIZE + CHANGE_SIZE }, uuidV7);

      const datasetId = await test.step('Seed a dataset with two multi-batch inserts', async () => {
        const created = await sdkClient.python.createDataset({
          project_name: project.name,
          name: datasetName,
          description: 'version counters, sequential upload',
        });
        await sdkClient.python.insertDatasetItems({
          project_name: project.name,
          dataset_name: datasetName,
          items: seedItems(ids, 0, SEED_SIZE, 'v1'),
        });
        // Half the ids come back with different content (modifications), half
        // are new (additions) — one call, so one version covering both.
        await sdkClient.python.insertDatasetItems({
          project_name: project.name,
          dataset_name: datasetName,
          items: [
            ...seedItems(ids, 0, CHANGE_SIZE, 'edited'),
            ...seedItems(ids, SEED_SIZE, SEED_SIZE + CHANGE_SIZE, 'v1'),
          ],
        });
        return created.id;
      });

      try {
        const versions = await test.step('Each insert() cut exactly one version, with the counters it should', async () => {
          const fetched = await backendClient.getDatasetVersions(datasetId);
          // Two insert() calls => two versions. Four backend batches were sent
          // (1200 and 600 items, split at 1000), and a batch must never cut a
          // version of its own.
          expect(fetched).toHaveLength(2);
          expect(byVersionName(fetched)).toEqual(EXPECTED_VERSIONS);
          return fetched;
        });

        await test.step('The stored item total agrees with the items actually in the dataset', async () => {
          const itemIds = await backendClient.listDatasetItemIds(datasetId);
          const latest = versions.find((v) => v.isLatest);
          // The counter is only worth rendering if it matches reality: a
          // version that reports more items than the dataset holds is exactly
          // the failure this catches.
          expect(new Set(itemIds).size).toBe(latest?.itemsTotal);
        });

        await test.step('The Version history tab renders those totals as "Item count"', async () => {
          const datasets = new DatasetsPage(page);
          await datasets.goto(project.id);
          await datasets.waitForReady();
          const items = await datasets.openDatasetByName(datasetName);
          await items.waitForReady();
          await items.openVersionHistory();

          for (const expected of EXPECTED_VERSIONS) {
            await expect(items.versionItemCount(expected.versionName)).toHaveText(
              expected.itemsTotal.toLocaleString('en-US'),
            );
          }
        });
      } finally {
        await backendClient.deleteDataset(datasetId);
      }
    },
  );

  test(
    'Uploading the same items on several threads stores the same version counters as the sequential path',
    { tag: ['@t2-cuj', '@cap:datasets.version-history-view'] },
    async ({ project, sdkClient, backendClient, testNamespace }) => {
      const datasetName = `${testNamespace}-parallel`;
      const ids = Array.from({ length: SEED_SIZE + CHANGE_SIZE }, uuidV7);

      const datasetId = await test.step('Seed the same shape with a parallel upload', async () => {
        const created = await sdkClient.python.createDataset({
          project_name: project.name,
          name: datasetName,
          description: 'version counters, parallel upload',
        });
        // num_threads only changes HOW the batches of one insert() are
        // uploaded, never what they add up to. Against a backend older than
        // 2.2.8 the SDK falls back to sequential, which passes too — the
        // assertion is on the result, not the transport.
        await sdkClient.python.insertDatasetItems({
          project_name: project.name,
          dataset_name: datasetName,
          items: seedItems(ids, 0, SEED_SIZE, 'v1'),
          num_threads: 8,
        });
        await sdkClient.python.insertDatasetItems({
          project_name: project.name,
          dataset_name: datasetName,
          items: [
            ...seedItems(ids, 0, CHANGE_SIZE, 'edited'),
            ...seedItems(ids, SEED_SIZE, SEED_SIZE + CHANGE_SIZE, 'v1'),
          ],
          num_threads: 8,
        });
        return created.id;
      });

      try {
        await test.step('The parallel path cut the same two versions with the same counters', async () => {
          const versions = await backendClient.getDatasetVersions(datasetId);
          expect(versions).toHaveLength(2);
          expect(byVersionName(versions)).toEqual(EXPECTED_VERSIONS);
        });

        await test.step('And its stored total still matches the items in the dataset', async () => {
          const itemIds = await backendClient.listDatasetItemIds(datasetId);
          expect(new Set(itemIds).size).toBe(EXPECTED_VERSIONS[1].itemsTotal);
        });
      } finally {
        await backendClient.deleteDataset(datasetId);
      }
    },
  );
});
