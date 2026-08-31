import { test, expect } from '@e2e/fixtures';
import { BASELINE_ITEM_COUNT } from '@e2e/fixtures';
import { uuid7 } from '@e2e/core/backend';
import type { DatasetItemWriteBody, DatasetVersionRef } from '@e2e/core/backend';
import { DatasetsPage } from '@e2e/pom/datasets.page';

/**
 * Many batches sharing one `batch_group_id`, sent at the same time, must commit
 * exactly ONE dataset version whose counters describe every item they carried
 * (OPIK-7708, which narrowed the dataset version lock to version creation).
 *
 * `dataset-version-counters.spec.ts` already asserts that a multi-batch
 * `insert()` collapses into one version, and it is the spec that owns
 * `datasets.version-history-view`. What it cannot guarantee is the axis this
 * change moved. It reaches concurrency only through
 * `insert(..., num_threads=8)`, and the SDK opens that gate only when the
 * backend's `/is-alive/ver` parses as semver >= 2.2.8 — which a locally built
 * docker-compose backend, whose OPIK_VERSION defaults to `latest`, never does.
 * On such an estate that spec passes without two requests ever overlapping.
 *
 * Driving `PUT /v1/private/datasets/items` directly makes the race a property
 * of the test rather than of the backend's version string, and raises it from
 * two batches to eight. The failure mode being hunted is silent wrongness under
 * contention, not a visible error: eight batches minting eight versions, or a
 * version reporting more items than the dataset holds because two racers each
 * counted the same row as new. Both would render as a plausible number on the
 * Version history tab that nobody can tell is wrong.
 *
 * Shape of the batches: two of the eight re-send the baseline version's ids
 * with changed content (modifications) and six carry fresh ids (additions), so
 * total / added / modified are three different numbers.
 */

const CONCURRENT_BATCHES = 8;
const BATCH_SIZE = 300;
/** The batches that re-send baseline ids; the rest carry ids nothing has seen. */
const MODIFYING_BATCHES = 2;

const EXPECTED_MODIFIED = MODIFYING_BATCHES * BATCH_SIZE;
const EXPECTED_ADDED = (CONCURRENT_BATCHES - MODIFYING_BATCHES) * BATCH_SIZE;
const EXPECTED_TOTAL = BASELINE_ITEM_COUNT + EXPECTED_ADDED;

// The modifying batches must cover the baseline exactly. If they ever stopped
// doing so, `items_modified` would still be a number the assertions could be
// bent to match, and the spec would quietly stop testing modification at all.
if (EXPECTED_MODIFIED !== BASELINE_ITEM_COUNT) {
  throw new Error(
    `dataset-version-concurrent-batches: ${MODIFYING_BATCHES} x ${BATCH_SIZE} modifying items ` +
      `must equal the fixture's baseline of ${BASELINE_ITEM_COUNT}`,
  );
}

const EXPECTED_VERSIONS = [
  {
    versionName: 'v1',
    itemsTotal: BASELINE_ITEM_COUNT,
    itemsAdded: BASELINE_ITEM_COUNT,
    itemsModified: 0,
    itemsDeleted: 0,
    isLatest: false,
  },
  {
    versionName: 'v2',
    itemsTotal: EXPECTED_TOTAL,
    itemsAdded: EXPECTED_ADDED,
    itemsModified: EXPECTED_MODIFIED,
    itemsDeleted: 0,
    isLatest: true,
  },
];

function byVersionName(versions: DatasetVersionRef[]) {
  return [...versions].sort((a, b) => a.versionName.localeCompare(b.versionName));
}

/**
 * The chips the "Changes" cell renders, in the order the cell emits them:
 * added, then modified, then deleted, each omitted when its counter is zero.
 * Anchored so an extra chip — a deletion this test never made — fails.
 */
function changeSummaryPattern(added: number, modified: number): RegExp {
  const chips = [`\\+\\s*${added}`];
  if (modified > 0) chips.push(`~\\s*${modified}`);
  return new RegExp(`^\\s*${chips.join('\\s*')}\\s*$`);
}

test.describe(
  'Dataset versions — concurrent grouped batches',
  { tag: ['@t3-nightly', '@area:datasets'] },
  () => {
    /** 600 seeded items plus 2400 uploaded ones outrun the default budget. */
    test.slow();

    test(
      'Eight batches sharing one group id, released together, commit one version whose counters match the items stored, and the Version history tab renders them',
      { tag: ['@cap:datasets.version-history-view'] },
      async ({ concurrentBatchDataset, project, backendClient, page }) => {
        const { id: datasetId, baselineItemIds } = concurrentBatchDataset;
        const batchGroupId = uuid7();

        const batches: DatasetItemWriteBody[][] = Array.from(
          { length: CONCURRENT_BATCHES },
          (_, batchIndex) => {
            const modifying = batchIndex < MODIFYING_BATCHES;
            const revision = modifying ? 'revised' : 'added';
            return Array.from({ length: BATCH_SIZE }, (_, offset) => {
              const index = batchIndex * BATCH_SIZE + offset;
              return {
                // A modifying batch re-sends a baseline id, so the backend
                // upserts it; the rest mint ids the dataset has never held.
                id: modifying ? baselineItemIds[index] : uuid7(),
                source: 'manual' as const,
                data: {
                  input: `${revision} question ${index}`,
                  expected_output: `${revision} answer ${index}`,
                },
              };
            });
          },
        );

        await test.step('All eight batches are accepted when released together', async () => {
          const responses = await backendClient.putDatasetItemBatchesConcurrently({
            datasetId,
            batchGroupId,
            batches,
          });
          // Asserted as the whole list rather than "none failed": a losing
          // racer answering 409 or 500 is the regression, and comparing the
          // full array surfaces the backend's message for whichever batch lost.
          expect(responses, 'every concurrent batch answers 204 with no body').toEqual(
            Array.from({ length: CONCURRENT_BATCHES }, () => ({ status: 204, message: '' })),
          );
        });

        await test.step('The group committed exactly one new version, with the counters it should', async () => {
          const versions = await backendClient.getDatasetVersions(datasetId);
          // Eight batches, one group, two versions — the baseline and the one
          // this group committed. Eight versions is what an unnarrowed lock or
          // a lost check-lock-recheck would produce, and asserting the whole
          // list is what catches it; a `find()` for v2 would not.
          expect(byVersionName(versions), 'the batch group folds into a single version').toEqual(
            EXPECTED_VERSIONS,
          );
        });

        await test.step('The version total agrees with the items actually stored', async () => {
          const itemIds = await backendClient.listDatasetItemIds(datasetId);
          // Both halves matter. A racer that double-counted a fresh id would
          // report more than the dataset holds; one whose write was lost would
          // report the right number over fewer rows.
          expect(itemIds, 'no item was dropped or stored twice').toHaveLength(EXPECTED_TOTAL);
          expect(new Set(itemIds).size, 'every stored id is distinct').toBe(EXPECTED_TOTAL);
        });

        await test.step('The Version history tab renders two rows with those counters', async () => {
          const datasets = new DatasetsPage(page);
          await datasets.goto(project.id);
          await datasets.waitForReady();
          const items = await datasets.openDatasetByName(concurrentBatchDataset.name);
          await items.waitForReady();
          await items.openVersionHistory();

          for (const expected of EXPECTED_VERSIONS) {
            // Resolve the row before reading its cells: an ambiguous match
            // should fail here rather than silently assert the wrong version.
            await expect(
              items.versionHistoryRow(expected.versionName),
              `exactly one ${expected.versionName} row`,
            ).toHaveCount(1);

            await expect(
              items.versionItemCount(expected.versionName),
              `${expected.versionName} item count`,
            ).toHaveText(expected.itemsTotal.toLocaleString('en-US'));

            await expect(
              items.versionChangeSummary(expected.versionName),
              `${expected.versionName} change summary`,
            ).toHaveText(changeSummaryPattern(expected.itemsAdded, expected.itemsModified));
          }
        });
      },
    );
  },
);
