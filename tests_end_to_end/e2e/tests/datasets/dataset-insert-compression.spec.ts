import { test, expect } from '@e2e/fixtures';
import { DatasetsPage } from '@e2e/pom/datasets.page';
import type { DatasetVersionRef } from '@e2e/core/backend';

/**
 * OPIK-8274 moved dataset upload compression off the producer thread, which
 * made `gzip_level=None` a genuinely different code path rather than a cheaper
 * setting on the same one: with compression off the send pool joins and ships
 * the raw chunks, instead of the writer emitting a compressed stream
 * (`streaming_writer`, `Dataset._open_send_pool`). Which arm runs is decided by
 * the client's `enable_json_request_compression`.
 *
 * Nothing in the estate toggles it. Every insert here — the `dataset` fixture,
 * `dataset-item-count.spec.ts`, `dataset-insert-deduplication.spec.ts`,
 * `dataset-parallel-item-read.spec.ts` — goes through a client built from the
 * deployment's default, so e2e only ever sees the gzip arm.
 *
 * The failure mode is silent, which is why this is worth a permanent test: a
 * slice dropped, duplicated or reordered on the uncompressed path changes the
 * bytes the backend parses, and surfaces as a wrong stored-item count rather
 * than as an error anyone would see. So the assertion is that both arms store
 * the SAME items and report the SAME counters, not that either one merely
 * succeeded.
 *
 * Driven on both surfaces: written and read back through the SDK, then read off
 * the Datasets list's Item count, so a backend that stored the right items and
 * a page that renders a wrong number stay distinguishable failures.
 *
 * SEED_SIZE is above the SDK's 1000-item batch size, so each arm reaches the
 * backend as several batches and the reassembly of chunks is actually
 * exercised — at one batch the two paths have nothing to disagree about.
 */
const SEED_SIZE = 2500;

/**
 * The one version an insert cuts, reduced to the four counters the estate
 * compares — the same shape `dataset-insert-deduplication.spec.ts` and
 * `dataset-version-counters.spec.ts` use. `itemsDeleted` is not among them,
 * here or anywhere else in the suite; see the note in
 * `dataset-insert-thread-clamp.spec.ts`.
 */
function counters(versions: DatasetVersionRef[]) {
  return versions.map((v) => ({
    versionName: v.versionName,
    itemsTotal: v.itemsTotal,
    itemsAdded: v.itemsAdded,
    itemsModified: v.itemsModified,
  }));
}

/**
 * The identical payload both arms upload. Every item differs by index:
 * `Dataset.insert()` drops duplicates by content hash, so items that repeated
 * would be silently collapsed and both arms would agree on a number neither of
 * them stored.
 */
function seedItems() {
  return Array.from({ length: SEED_SIZE }, (_, seq) => ({
    input: `compression probe input ${seq}`,
    expected_output: `compression probe output ${seq}`,
    seq,
  }));
}

test.describe('Dataset insert — request compression arms', { tag: ['@area:datasets'] }, () => {
  /** The Item count column is off-screen at the default 1280px viewport. */
  test.use({ viewport: { width: 1600, height: 900 } });

  test(
    `Compressed and uncompressed uploads of the same ${SEED_SIZE} items store identical items, report identical version counters, and render the same Item count`,
    // `list-datasets` as well as the round trip: the last step asserts the
    // Datasets list's Item count cell, which is that capability's surface.
    // Untagged, that assertion would be invisible coverage. `version-history-
    // view` for the same reason one step earlier — both arms' version counters
    // are compared in full, which is that capability's contract and what
    // `dataset-insert-deduplication.spec.ts` tags for the same assertion.
    {
      tag: [
        '@t2-cuj',
        '@cap:datasets.sdk-round-trip',
        '@cap:datasets.list-datasets',
        '@cap:datasets.version-history-view',
      ],
    },
    async ({ project, sdkClient, backendClient, registerDatasetCleanup, testNamespace, page }) => {
      /**
       * Two multi-batch inserts against a cloud backend outrun the default
       * budget when the workspace is being rate-limited. Measured at ~25s
       * against staging for both arms, so this is headroom rather than need.
       */
      test.slow();

      const gzipName = `${testNamespace}-gzip-on`;
      const rawName = `${testNamespace}-gzip-off`;

      const { gzipId, rawId } = await test.step(
        `Insert the same ${SEED_SIZE} items twice — once compressed, once not`,
        async () => {
          const gzip = await sdkClient.python.createDataset({
            project_name: project.name,
            name: gzipName,
            description: 'enable_json_request_compression default (true)',
          });
          registerDatasetCleanup(gzip.id, gzipName);
          const raw = await sdkClient.python.createDataset({
            project_name: project.name,
            name: rawName,
            description: 'enable_json_request_compression=False',
          });
          registerDatasetCleanup(raw.id, rawName);

          const compressed = await sdkClient.python.insertDatasetItems({
            project_name: project.name,
            dataset_name: gzipName,
            items: seedItems(),
            enable_json_request_compression: true,
          });
          const uncompressed = await sdkClient.python.insertDatasetItems({
            project_name: project.name,
            dataset_name: rawName,
            items: seedItems(),
            enable_json_request_compression: false,
          });

          expect(compressed.value_error, 'the compressed insert was accepted').toBeNull();
          expect(uncompressed.value_error, 'the uncompressed insert was accepted').toBeNull();
          // The discriminator. `compression_enabled` is read off the client the
          // bridge built, so this is what shows the toggle reached the
          // transport — without it the whole spec would pass just as well
          // against two runs of the very same gzip arm, and read as coverage of
          // a path nothing exercised.
          expect(compressed.compression_enabled, 'the first arm gzipped its bodies').toBe(true);
          expect(uncompressed.compression_enabled, 'the second arm did not').toBe(false);

          return { gzipId: gzip.id, rawId: raw.id };
        },
      );

      await test.step('Each arm cut exactly one version, and both report the same counters', async () => {
        const expected = [
          {
            versionName: 'v1',
            itemsTotal: SEED_SIZE,
            itemsAdded: SEED_SIZE,
            itemsModified: 0,
          },
        ];
        // The whole version list, not a lookup of v1 within it: an upload that
        // cut a second version for a batch it re-sent would still hold a
        // correct-looking v1.
        //
        // Deliberately not compared: `versionHash`. Item ids are freshly minted
        // per insert and feed the hash, so the two arms differ there by
        // construction — that is not a defect, and asserting it would make this
        // spec fail on correct behaviour.
        expect(counters(await backendClient.getDatasetVersions(gzipId))).toEqual(expected);
        expect(counters(await backendClient.getDatasetVersions(rawId))).toEqual(expected);
      });

      await test.step(`Both datasets read back exactly ${SEED_SIZE} distinct items through the SDK`, async () => {
        for (const [label, name] of [
          ['compressed', gzipName],
          ['uncompressed', rawName],
        ] as const) {
          const { item_ids, value_error } = await sdkClient.python.readDatasetItems({
            project_name: project.name,
            dataset_name: name,
          });
          expect(value_error, `${label}: the read was accepted`).toBeNull();
          expect(item_ids, `${label}: every item sent came back`).toHaveLength(SEED_SIZE);
          // A reordered or duplicated slice can land the right count with the
          // wrong contents, so the ids have to be distinct as well as counted.
          expect(new Set(item_ids).size, `${label}: and none of them twice`).toBe(SEED_SIZE);
        }
      });

      await test.step(`The Datasets list renders ${SEED_SIZE} for both rows`, async () => {
        const datasets = new DatasetsPage(page);
        await datasets.goto(project.id);
        await datasets.waitForReady();
        // Addressed by dataset id through `data-cell-id`, and rendered raw —
        // this column has no accessorFn, so "2500", not "2,500".
        await expect(datasets.datasetCell(gzipId, 'dataset_items_count')).toHaveText(
          String(SEED_SIZE),
        );
        await expect(datasets.datasetCell(rawId, 'dataset_items_count')).toHaveText(
          String(SEED_SIZE),
        );
      });
    },
  );
});
