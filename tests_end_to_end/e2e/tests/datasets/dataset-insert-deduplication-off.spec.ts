import { test, expect } from '@e2e/fixtures';
import { DatasetsPage } from '@e2e/pom/datasets.page';

/**
 * `Dataset.insert(..., deduplication=False)` sends every item as-is, with no
 * content-hash comparison. Re-sending items the dataset already holds
 * therefore persists a SECOND copy — new ids are minted, so the row count
 * doubles and a second version is cut with the additions to prove it.
 *
 * The three existing version specs all pin the opposite behaviour:
 * `dataset-version-counters.spec.ts` and `dataset-version-concurrent-writes.
 * spec.ts` insert with deduplication on (the SDK default), and
 * `dataset-version-repeated-item-id.spec.ts` bypasses the SDK entirely to
 * reach `PUT /v1/private/datasets/items`, precisely because `insert()` would
 * have dropped its repeated entry. Nothing asserts that turning the flag off
 * actually duplicates — and "Item count" on the Version history tab is the
 * number a user reads that off.
 *
 * The dedup-on arm is the control that makes the dedup-off arm mean something:
 * the two send byte-identical payloads through the same route and differ only
 * in the flag, so 20-vs-10 rows cannot be explained by the seed.
 *
 * Both arms go through `insertDatasetItemSequence` rather than two
 * `insertDatasetItems` calls: the content-hash cache dedup consults lives on
 * the Dataset object, and a per-call fresh object would make the control arm
 * assert the backend's behaviour rather than the SDK's.
 */
const SEED_SIZE = 10;

/**
 * Items with NO explicit id, which is what makes duplication observable: the
 * SDK mints a fresh id per item on every call, so a second dedup-off insert of
 * the same content lands as 10 new rows rather than upserting the first 10.
 * (Re-sending explicit ids would upsert instead — a different flow, covered by
 * `dataset-version-repeated-item-id.spec.ts`.)
 */
function seedItems(revision: string) {
  return Array.from({ length: SEED_SIZE }, (_, index) => ({
    input: `item ${index} ${revision}`,
    expected_output: `output ${index} ${revision}`,
  }));
}

test.describe('Dataset insert — deduplication off', { tag: ['@area:datasets'] }, () => {
  test(
    'Two deduplication=False inserts of the same items store both copies, and the Version history tab renders the doubled count',
    { tag: ['@t2-cuj', '@cap:datasets.version-history-view'] },
    async ({ project, sdkClient, backendClient, registerDatasetCleanup, testNamespace, page }) => {
      const datasetName = `${testNamespace}-dedup-off`;

      const datasetId = await test.step('Create an empty dataset', async () => {
        const created = await sdkClient.python.createDataset({
          project_name: project.name,
          name: datasetName,
          description: 'deduplication=False, duplicate rows persisted',
        });
        // Registered as soon as the id exists: the dataset is created mid-test,
        // so no seed fixture can know it upfront, and datasets do not cascade
        // with their project.
        registerDatasetCleanup(created.id, datasetName);
        return created.id;
      });

      await test.step('Insert the same 10 items twice with deduplication off', async () => {
        const result = await sdkClient.python.insertDatasetItemSequence({
          project_name: project.name,
          dataset_name: datasetName,
          steps: [
            { items: seedItems('v1'), deduplication: false },
            { items: seedItems('v1'), deduplication: false },
          ],
        });
        expect(result.steps_run, 'both inserts ran').toBe(2);
      });

      await test.step('The dataset holds 20 rows, not 10', async () => {
        const itemIds = await backendClient.listDatasetItemIds(datasetId);
        expect(itemIds, 'every item of both inserts was stored').toHaveLength(2 * SEED_SIZE);
        expect(new Set(itemIds).size, 'each copy got its own freshly minted id').toBe(
          2 * SEED_SIZE,
        );
      });

      await test.step('Both inserts cut a version, and the counters carry the duplicates forward', async () => {
        const versions = await backendClient.getDatasetVersions(datasetId);
        expect(versions, 'one version per insert() call').toHaveLength(2);

        const v1 = versions.filter((v) => v.versionName === 'v1');
        const v2 = versions.filter((v) => v.versionName === 'v2');
        expect(v1, 'exactly one v1').toHaveLength(1);
        expect(v2, 'exactly one v2').toHaveLength(1);

        expect(v1[0].itemsAdded).toBe(SEED_SIZE);
        expect(v1[0].itemsTotal).toBe(SEED_SIZE);
        expect(v1[0].itemsModified).toBe(0);
        expect(v1[0].isLatest).toBe(false);

        // The whole point of the flag: the second insert of identical content
        // is 10 more ADDITIONS, not 10 modifications and not a no-op.
        expect(v2[0].itemsAdded, 'the repeat is a second set of additions').toBe(SEED_SIZE);
        expect(v2[0].itemsModified, 'nothing was matched, so nothing was modified').toBe(0);
        expect(v2[0].itemsTotal).toBe(2 * SEED_SIZE);
        expect(v2[0].isLatest).toBe(true);
      });

      await test.step('The Version history tab renders 10 then 20 as "Item count"', async () => {
        const datasets = new DatasetsPage(page);
        await datasets.goto(project.id);
        await datasets.waitForReady();
        const items = await datasets.openDatasetByName(datasetName);
        await items.waitForReady();
        await items.openVersionHistory();

        await expect(items.versionItemCount('v1')).toHaveText(String(SEED_SIZE));
        await expect(items.versionItemCount('v2')).toHaveText(String(2 * SEED_SIZE));
      });
    },
  );

  test(
    'The same two inserts with deduplication left on store one copy and cut no second version',
    { tag: ['@t2-cuj', '@cap:datasets.version-history-view'] },
    async ({ project, sdkClient, backendClient, registerDatasetCleanup, testNamespace }) => {
      const datasetName = `${testNamespace}-dedup-on`;

      const datasetId = await test.step('Create an empty dataset', async () => {
        const created = await sdkClient.python.createDataset({
          project_name: project.name,
          name: datasetName,
          description: 'deduplication default, control arm',
        });
        registerDatasetCleanup(created.id, datasetName);
        return created.id;
      });

      await test.step('Insert the same 10 items twice, leaving deduplication at its default', async () => {
        const result = await sdkClient.python.insertDatasetItemSequence({
          project_name: project.name,
          dataset_name: datasetName,
          // `deduplication` omitted on purpose: the control has to be the
          // default a caller gets without asking for anything.
          steps: [{ items: seedItems('v1') }, { items: seedItems('v1') }],
        });
        expect(result.steps_run, 'both inserts ran').toBe(2);
      });

      await test.step('Only the first insert stored anything', async () => {
        const itemIds = await backendClient.listDatasetItemIds(datasetId);
        expect(itemIds, 'the second insert added no rows').toHaveLength(SEED_SIZE);
        expect(new Set(itemIds).size).toBe(SEED_SIZE);
      });

      await test.step('And only the first insert cut a version', async () => {
        // An insert whose items all dedup away sends no batch at all, so there
        // is no v2 to find — not an empty v2. This is the assertion that makes
        // the dedup-off arm above discriminating rather than decorative.
        const versions = await backendClient.getDatasetVersions(datasetId);
        expect(versions, 'a fully deduplicated insert cuts no version').toHaveLength(1);
        expect(versions[0].versionName).toBe('v1');
        expect(versions[0].itemsAdded).toBe(SEED_SIZE);
        expect(versions[0].itemsTotal).toBe(SEED_SIZE);
        expect(versions[0].itemsModified).toBe(0);
        expect(versions[0].isLatest).toBe(true);
      });
    },
  );
});
