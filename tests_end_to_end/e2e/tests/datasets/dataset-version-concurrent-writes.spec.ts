import { test, expect } from '@e2e/fixtures';
import { uuid7, buildDatasetItemBatches, sumDatasetVersionField } from '@e2e/core/backend';

/**
 * `PUT /v1/private/datasets/items` has two write paths, and they race each
 * other:
 *
 *  - **grouped** (`batch_group_id` set) — every batch sharing the id collapses
 *    into ONE new version, however many batches arrive and in whatever order;
 *  - **ungrouped** (`batch_group_id` omitted) — "mutate latest": the batch is
 *    folded into the version that is current when it lands, creating none.
 *
 * Both paths take the same per-dataset lock before touching `latest`: the
 * ungrouped path reads it from inside `withDatasetVersionLock`, and a grouped
 * insert creating a new version acquires the same lock before it commits. So
 * there is no pre-lock read window where either side could act on stale
 * `latest` state — DatasetItemService serializes them instead. What is not
 * serialized is *which* request the lock lets through first when both are
 * queued at once: an ungrouped batch folds into whichever version is latest
 * at the moment it gets the lock, so which version (old or newly-created) ends
 * up holding a given ungrouped batch is genuinely unpredictable. This spec
 * exercises that contention and checks the backend never loses or
 * double-counts a batch regardless of the order the lock resolves them in —
 * a real risk if `withDatasetVersionLock`'s scope (narrowed in OPIK-7708) ever
 * regresses to not covering one of these paths.
 * Nothing in the estate drives it: `dataset-version-counters.spec.ts` sends two
 * sequential `Dataset.insert()` calls, which are always grouped and never
 * concurrent with each other, and the SDK cannot express an ungrouped write at
 * all.
 *
 * API-level throughout, deliberately. The claim is about which rows and which
 * version rows the backend ended up with; a UI reading of the same numbers
 * would observe it second-hand and add nothing but flake. The rendering of
 * `items_total` is covered by the two UI specs alongside this one.
 *
 * Every assertion holds whatever order the race resolves in — see the counter
 * arithmetic on ACCOUNTED_* below.
 */
const BATCH_SIZE = 100;
const SEED_BATCHES = 8;
const SEED_ITEMS = BATCH_SIZE * SEED_BATCHES;

/** Phase 2: one grouped insert of 4 batches racing 4 ungrouped batches. */
const GROUPED_BATCHES = 4;
const UNGROUPED_BATCHES = 4;
const RESENT_ENTRIES = BATCH_SIZE * (GROUPED_BATCHES + UNGROUPED_BATCHES);

/**
 * Phase 2 re-sends ids that phase 1 already stored, and adds none. That is what
 * makes the arithmetic below independent of how the race resolves.
 *
 * An ungrouped batch is folded into whichever version is latest when it lands —
 * v1 if it beats the grouped insert, v2 if it loses — so no per-version counter
 * is predictable. But an entry's *kind* is: a fresh id is an addition and a
 * stored id is a modification, wherever it is counted. So the totals summed
 * across versions are fixed even though their distribution is not:
 *
 *   sum(items_added)    == SEED_ITEMS      (only phase 1 introduced ids)
 *   sum(items_modified) == RESENT_ENTRIES  (every phase-2 entry hit a stored id)
 *
 * A dropped batch shows up as a shortfall in one of those sums; a batch
 * double-counted shows up as an excess.
 */
const ACCOUNTED_ADDED = SEED_ITEMS;
const ACCOUNTED_MODIFIED = RESENT_ENTRIES;

/** This spec's batches are always BATCH_SIZE-sized. */
const batches = (ids: string[], count: number, revision: string) =>
  buildDatasetItemBatches(ids, count, BATCH_SIZE, revision);

test.describe('Dataset version counters — concurrent grouped and ungrouped writes', { tag: ['@area:datasets'] }, () => {
  /** 1600 item writes against a cloud backend outrun the default budget. */
  test.slow();

  test(
    'An ungrouped mutate-latest write racing a grouped insert leaves one latest version whose counters match the rows stored',
    { tag: ['@t2-cuj', '@cap:datasets.version-history-view'] },
    async ({ project, sdkClient, backendClient, registerDatasetCleanup, testNamespace }) => {
      const datasetName = `${testNamespace}-race`;
      const ids = Array.from({ length: SEED_ITEMS }, () => uuid7());

      const datasetId = await test.step('Create an empty dataset', async () => {
        const created = await sdkClient.python.createDataset({
          project_name: project.name,
          name: datasetName,
          description: 'version counters, grouped/ungrouped race',
        });
        registerDatasetCleanup(created.id, datasetName);
        return created.id;
      });

      await test.step(
        `Upload ${SEED_BATCHES} concurrent batches sharing one batch_group_id`,
        async () => {
          const groupId = crypto.randomUUID();
          await Promise.all(
            batches(ids, SEED_BATCHES, 'v1').map((items) =>
              backendClient.writeDatasetItemsBatch({ datasetId, batchGroupId: groupId, items }),
            ),
          );
        },
      );

      await test.step('One group is one version, whatever order its batches landed in', async () => {
        // This is the precondition the race in the next step is run against.
        // If the grouped path alone already cut two versions, everything below
        // would be asserting about a different scenario.
        const versions = await backendClient.getDatasetVersions(datasetId);
        expect(versions, `${SEED_BATCHES} batches, one group, one version`).toHaveLength(1);
        expect(versions[0].isLatest).toBe(true);
        expect(versions[0].itemsTotal).toBe(SEED_ITEMS);
        expect(versions[0].itemsAdded).toBe(SEED_ITEMS);
        expect(versions[0].itemsModified).toBe(0);

        const itemIds = await backendClient.listDatasetItemIds(datasetId);
        expect(itemIds, 'every batch was stored').toHaveLength(SEED_ITEMS);
        expect(new Set(itemIds)).toEqual(new Set(ids));
      });

      await test.step(
        `Race one grouped insert (${GROUPED_BATCHES} batches) against ${UNGROUPED_BATCHES} ungrouped batches`,
        async () => {
          // Fired from one Promise.all so both paths queue for the shared
          // per-dataset lock at once: which of them the lock lets through
          // first, and so which version an ungrouped batch lands in, is what
          // this step leaves to chance.
          const groupId = crypto.randomUUID();
          const groupedIds = ids.slice(0, BATCH_SIZE * GROUPED_BATCHES);
          const ungroupedIds = ids.slice(BATCH_SIZE * GROUPED_BATCHES);
          await Promise.all([
            ...batches(groupedIds, GROUPED_BATCHES, 'v2-grouped').map((items) =>
              backendClient.writeDatasetItemsBatch({ datasetId, batchGroupId: groupId, items }),
            ),
            ...batches(ungroupedIds, UNGROUPED_BATCHES, 'v2-ungrouped').map((items) =>
              backendClient.writeDatasetItemsBatch({ datasetId, items }),
            ),
          ]);
        },
      );

      const versions = await test.step(
        'The race produced exactly one new version, and exactly one latest',
        async () => {
          // Two failures live here. A duplicate version row (the shared lock
          // failing to serialize the two paths, so the grouped insert commits
          // twice) shows up as a third version; a lost `is_latest` flip — or
          // two rows both claiming it — shows up as anything other than one
          // latest.
          const fetched = await backendClient.getDatasetVersions(datasetId);
          expect(
            fetched,
            'the ungrouped batches mutate the latest version and must not create one',
          ).toHaveLength(2);
          expect(fetched.filter((v) => v.isLatest), 'exactly one version is latest').toHaveLength(1);
          return fetched;
        },
      );

      await test.step("The latest version's item total agrees with the rows stored", async () => {
        const latest = versions.find((v) => v.isLatest);
        expect(latest, 'a latest version must exist to assert about').toBeDefined();
        const itemIds = await backendClient.listDatasetItemIds(datasetId);
        // Phase 2 re-sent ids phase 1 had already stored, so the row set must be
        // unchanged: exactly the seeded ids, no more (a re-send stored as a new
        // row) and no fewer (a batch dropped in the race).
        expect(itemIds, 'no re-sent entry became a second row').toHaveLength(SEED_ITEMS);
        expect(new Set(itemIds), 'every seeded id survived the race').toEqual(new Set(ids));
        expect(latest!.itemsTotal, 'the counter matches the rows the dataset holds').toBe(
          SEED_ITEMS,
        );
      });

      await test.step('Every entry sent is accounted for across the two versions', async () => {
        // The distribution across versions depends on the race; the totals do
        // not (see ACCOUNTED_* above). A batch the backend silently dropped
        // fails here even though the row set above would still look right for a
        // re-send that never applied.
        expect(
          sumDatasetVersionField(versions, 'itemsAdded'),
          'only phase 1 introduced ids',
        ).toBe(ACCOUNTED_ADDED);
        expect(
          sumDatasetVersionField(versions, 'itemsModified'),
          `all ${RESENT_ENTRIES} phase-2 entries hit an already-stored id`,
        ).toBe(ACCOUNTED_MODIFIED);
      });
    },
  );
});
