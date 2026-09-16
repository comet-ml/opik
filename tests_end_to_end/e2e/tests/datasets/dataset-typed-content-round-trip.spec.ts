import { test, expect } from '@e2e/fixtures';
import type { TypedValueSpec } from '@e2e/core/sdk';
import { DatasetsPage } from '@e2e/pom/datasets.page';

/**
 * `Dataset.insert()` decides whether it has already stored an item by hashing
 * the item's content, and a value the JSON encoder cannot represent natively
 * reaches that digest through `streaming_writer.encode_flexible`: a `uuid.UUID`
 * becomes a string, an `enum.Enum` member its value, a tz-aware `datetime` an
 * ISO string, a `set` a canonically-ordered list, a `tuple` a list.
 *
 * None of those survive the round trip as themselves. So the digest of the live
 * Python object has to equal the digest of the JSON it becomes, or an item
 * could never deduplicate against its own stored form — `_ordered_set_members`
 * says exactly this in its own docstring, and nothing in the e2e estate asserts
 * it. `dataset-insert-deduplication.spec.ts` drives the same `content_hash`
 * path, but only ever with plain strings, where there is no normalisation to
 * get wrong.
 *
 * The failure is silent in the direction that matters: a digest whose shape
 * moved does not raise, it stores a second copy of an item the caller sent once
 * and commits a spurious version behind it. A user reading the Version history
 * tab sees a "+ 1" they did not ask for and no error anywhere.
 *
 * Both inserts go through their own bridge request, and therefore their own
 * `Dataset` object whose hash cache starts unsynced. That is deliberate: it
 * forces the second insert to rebuild its cache from what the BACKEND stored —
 * plain JSON — and compare the live typed item against that, which is the
 * comparison worth making. Two inserts inside one session would compare the
 * live object against itself.
 *
 * NOT covered here, and it is the other half of #8330's risk: the same item
 * inserted from a process where `orjson` is unavailable. The two encoders do
 * not produce identical bytes (the standard library writes `9.79e-05` where
 * orjson writes `0.0000979`), and the SDK's own `json_helpers` docstring argues
 * that never matters because a digest never leaves the process that computed
 * it. Checking it would need a second interpreter with the module shadowed,
 * which the bridge is one process and cannot be. It belongs in the Python SDK's
 * own test suite. The bridge reports which encoder answered so a failure here
 * at least names it.
 */

/**
 * The item's content, as instructions the bridge materialises into real Python
 * objects. Sending the values themselves would defeat the test: JSON has
 * already done the normalisation under assertion by the time they arrive.
 *
 * `small_float` is `9.79e-05` on purpose — below 1e-4 is exactly where the two
 * encoders render a float differently, so it is the value most likely to expose
 * a digest that depends on the encoder rather than on the content.
 */
const TYPED_FIELDS: Record<string, TypedValueSpec> = {
  small_float: { kind: 'float', value: 9.79e-5 },
  uid: { kind: 'uuid', value: '11111111-2222-3333-4444-555555555555' },
  when: { kind: 'datetime', value: '2026-09-16T08:34:12+00:00' },
  color: { kind: 'enum', value: 'red' },
  a_set: { kind: 'set', value: [1, 2, 3] },
  a_tuple: { kind: 'tuple', value: [1, 'two', 3.0] },
};

/**
 * What each of those must be stored as, field for field.
 *
 * Asserted as a whole object rather than field by field: a comparison that
 * only checked the fields it names would pass over content that had also
 * gained or lost one.
 *
 * `when` ends in `Z` rather than `+00:00` because `serialize_datetime` special-
 * cases UTC; `a_set`'s order is `_ordered_set_members`' canonical one, not
 * Python's set iteration order, which varies between processes.
 */
const EXPECTED_STORED: Record<string, unknown> = {
  small_float: 9.79e-5,
  uid: '11111111-2222-3333-4444-555555555555',
  when: '2026-09-16T08:34:12Z',
  color: 'red',
  a_set: [1, 2, 3],
  a_tuple: [1, 'two', 3],
};

test.describe('Dataset insert — non-JSON-native item content', { tag: ['@area:datasets'] }, () => {
  test(
    'An item carrying a UUID, an Enum, a datetime, a set and a tuple round-trips to its JSON form and dedups against it',
    // Both caps, as `dataset-insert-deduplication.spec.ts` does for the same
    // reason: the version counters below are asserted, not merely read past, so
    // leaving them untagged would be coverage the map cannot see.
    { tag: ['@t2-cuj', '@cap:datasets.sdk-round-trip', '@cap:datasets.version-history-view'] },
    async ({ project, sdkClient, backendClient, registerDatasetCleanup, testNamespace, page }) => {
      const datasetName = `${testNamespace}-typed-content`;

      const { datasetId, accelerated } = await test.step(
        'Create a dataset and insert one item built from real Python types',
        async () => {
          const created = await sdkClient.python.createDataset({
            project_name: project.name,
            name: datasetName,
            description: 'non-JSON-native item content',
          });
          // Registered the moment the id exists: the dataset is created
          // mid-test, so no seed fixture can know it upfront, and datasets do
          // not cascade with their project.
          registerDatasetCleanup(created.id, datasetName);

          const inserted = await sdkClient.python.insertTypedDatasetItem({
            project_name: project.name,
            dataset_name: datasetName,
            typed_content: TYPED_FIELDS,
          });
          return { datasetId: created.id, accelerated: inserted.accelerated };
        },
      );

      const itemId = await test.step('Every field is stored as its JSON form, and nothing else is', async () => {
        const items = await backendClient.getDatasetItems(datasetId);
        expect(items, 'the insert stored exactly one item').toHaveLength(1);
        expect(
          items[0].data,
          `stored content (bridge encoder: ${accelerated ? 'orjson' : 'standard library'})`,
        ).toEqual(EXPECTED_STORED);
        return items[0].id;
      });

      await test.step('The insert cut one version counting one addition', async () => {
        const versions = await backendClient.getDatasetVersions(datasetId);
        expect(versions).toHaveLength(1);
        expect(versions[0].versionName).toBe('v1');
        expect(versions[0].itemsTotal).toBe(1);
        expect(versions[0].itemsAdded).toBe(1);
        expect(versions[0].itemsModified).toBe(0);
      });

      await test.step('Inserting the identical item again is deduplicated against the stored JSON', async () => {
        // The assertion this spec exists for. This request builds its own
        // client, so its `Dataset` starts with an unsynced hash cache and has
        // to rebuild it from the backend — hashing the JSON the first insert
        // produced. The item it is about to send is still the live Python
        // object. The two digests agree only if `encode_flexible` maps every
        // one of these types onto exactly the form it round-trips as.
        await sdkClient.python.insertTypedDatasetItem({
          project_name: project.name,
          dataset_name: datasetName,
          typed_content: TYPED_FIELDS,
        });
      });

      await test.step('No second copy was stored, and no second version was cut', async () => {
        const items = await backendClient.getDatasetItems(datasetId);
        expect(
          items,
          'a second row here means the live object and its stored JSON hashed differently',
        ).toHaveLength(1);
        expect(items[0].id, 'and it is the row the first insert wrote, not a replacement').toBe(
          itemId,
        );
        expect(items[0].data, 'whose content is unchanged').toEqual(EXPECTED_STORED);

        const versions = await backendClient.getDatasetVersions(datasetId);
        // Counted as well as listed: a dedup that dropped the item but still
        // committed an empty version would leave a "+ 0" on the Version
        // history tab that nobody asked for.
        expect(versions, 'the deduplicated insert sent nothing, so nothing was versioned').toHaveLength(
          1,
        );
        expect(versions[0].versionName).toBe('v1');
        expect(versions[0].itemsTotal).toBe(1);
      });

      await test.step('The Records tab renders the one row, with every field in its stored form', async () => {
        const datasets = new DatasetsPage(page);
        await datasets.goto(project.id);
        await datasets.waitForReady();
        const items = await datasets.openDatasetByName(datasetName);
        await items.waitForReady();

        await expect(items.itemRows(), 'one row renders').toHaveCount(1);
        expect(await items.itemRowIds()).toEqual([itemId]);

        // Cell by cell, addressed by the field's own column id. A row-level
        // text assertion would pass with two fields rendered into one cell and
        // a third missing entirely.
        await expect(items.itemCell(itemId, 'uid')).toHaveText(
          String(EXPECTED_STORED.uid),
        );
        await expect(items.itemCell(itemId, 'when')).toHaveText(
          String(EXPECTED_STORED.when),
        );
        await expect(items.itemCell(itemId, 'color')).toHaveText(
          String(EXPECTED_STORED.color),
        );
        // The grid's own rendering of the stored `9.79e-05`, not a different
        // number: 0.0000979 is the same value written without an exponent.
        await expect(items.itemCell(itemId, 'small_float')).toHaveText('0.0000979');
        // The two collections render as JSON arrays. Asserted in full rather
        // than by membership: order is the whole point of `_ordered_set_members`,
        // and a cell containing "1" would be satisfied by a great deal else.
        await expect(items.itemCell(itemId, 'a_set')).toHaveText('[ 1, 2, 3 ]');
        await expect(items.itemCell(itemId, 'a_tuple')).toHaveText('[ 1, "two", 3 ]');
      });
    },
  );
});
