import { test as baseTest } from './dashboard-cleanup.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/** Items in the committed baseline version the concurrent batches then modify. */
export const BASELINE_ITEM_COUNT = 600;

export interface ConcurrentBatchDatasetRef {
  id: string;
  name: string;
  projectId: string;
  /**
   * The ids stored by the baseline version, minted here rather than read back.
   * A batch that re-sends one of these with different content is what the
   * backend counts as a modification, so a test needs them exactly.
   */
  baselineItemIds: string[];
}

export interface ConcurrentBatchDatasetFixtures {
  concurrentBatchDataset: ConcurrentBatchDatasetRef;
}

function baselineItem(id: string, index: number): Record<string, unknown> {
  return {
    id,
    input: `baseline question ${index}`,
    expected_output: `baseline answer ${index}`,
  };
}

/**
 * A dataset holding exactly one committed version of `BASELINE_ITEM_COUNT`
 * items whose ids the test knows.
 *
 * This is the precondition for the version-contention path: with a baseline in
 * place, a later group of batches can be split into ones that re-send known ids
 * (modifications) and ones that carry fresh ids (additions), so the resulting
 * version's `items_total` / `items_added` / `items_modified` are three
 * different numbers and no counter can pass by coincidence.
 *
 * The seed goes through the Python SDK bridge, so `v1` is created the way a
 * user creates it. The fixture then asserts, via the API, that the baseline
 * really is one version of exactly that size before the test runs: a UI or
 * counter assertion sitting on top of a seed that silently half-landed is a
 * test that cannot fail.
 *
 * The dataset is deleted here rather than in the test — datasets do not cascade
 * with the project, and a mid-test failure would otherwise leak a few thousand
 * items.
 */
export const test = baseTest.extend<ConcurrentBatchDatasetFixtures>({
  concurrentBatchDataset: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const name = `${testNamespace}-concurrent-batches`;
    const baselineItemIds = Array.from({ length: BASELINE_ITEM_COUNT }, () => uuid7());

    const created = await sdkClient.python.createDataset({
      project_name: project.name,
      name,
      description: 'baseline version for concurrent grouped batch uploads',
    });

    await sdkClient.python.insertDatasetItems({
      project_name: project.name,
      dataset_name: name,
      items: baselineItemIds.map(baselineItem),
    });

    const versions = await backendClient.getDatasetVersions(created.id);
    if (versions.length !== 1) {
      throw new Error(
        `[concurrentBatchDataset fixture] expected 1 baseline version, got ${versions.length}`,
      );
    }
    const [baseline] = versions;
    if (
      baseline.itemsTotal !== BASELINE_ITEM_COUNT ||
      baseline.itemsAdded !== BASELINE_ITEM_COUNT ||
      baseline.itemsModified !== 0
    ) {
      throw new Error(
        `[concurrentBatchDataset fixture] baseline ${baseline.versionName} is ` +
          `${baseline.itemsTotal}/${baseline.itemsAdded}/${baseline.itemsModified} ` +
          `(total/added/modified), expected ${BASELINE_ITEM_COUNT}/${BASELINE_ITEM_COUNT}/0`,
      );
    }

    // The ids are the fixture's contract with the test: if the SDK had not
    // honoured them as upsert keys, every "modifying" batch would silently add
    // instead, and the counters the test asserts would be wrong for a reason
    // that has nothing to do with concurrency.
    const storedIds = new Set(await backendClient.listDatasetItemIds(created.id));
    const missing = baselineItemIds.filter((id) => !storedIds.has(id));
    if (storedIds.size !== BASELINE_ITEM_COUNT || missing.length > 0) {
      throw new Error(
        `[concurrentBatchDataset fixture] stored ${storedIds.size} ids, ` +
          `${missing.length} of the seeded ids missing`,
      );
    }

    const ref: ConcurrentBatchDatasetRef = {
      id: created.id,
      name: created.name,
      projectId: project.id,
      baselineItemIds,
    };

    await testInfo.attach('opik.concurrentBatchDataset', {
      body: JSON.stringify({ ...ref, baselineItemIds: `${BASELINE_ITEM_COUNT} ids` }, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteDataset(created.id);
      } catch (err) {
        console.warn(`[concurrentBatchDataset fixture] delete warning for ${name}:`, err);
      }
    }
  },
});

export { expect } from './dashboard-cleanup.fixture';
