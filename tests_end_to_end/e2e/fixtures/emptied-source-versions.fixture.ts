import { test as baseTest, expect } from './studio-rerun-optimization.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import type { BackendClient, DatasetVersionRef } from '../core/backend';

/** Items seeded into the populated version. Two is enough to tell 2 from 0. */
const SEED_ITEM_COUNT = 2;

/** One version of a dataset or test suite, reduced to what the gate reads. */
export interface SourceVersionRef {
  /** The version id the Playground's picker keys its selection on. */
  id: string;
  /** `v1`, `v2`, … — the label the picker and the loaded-source pill render. */
  name: string;
  /** The content hash `/experiments/execute` takes as `version_hash`. */
  hash: string;
  itemsTotal: number;
}

/**
 * A dataset (or test suite) holding two versions with opposite item counts:
 * `populated` still has its rows, `emptied` is the latest and has none.
 */
export interface EmptiedSourceRef {
  id: string;
  name: string;
  projectId: string;
  projectName: string;
  /** The first version, still holding every seeded row. */
  populated: SourceVersionRef;
  /** The latest version, committed by the delete that removed them all. */
  emptied: SourceVersionRef;
}

export interface EmptiedSourceVersionsFixtures {
  emptiedDataset: EmptiedSourceRef;
  emptiedTestSuite: EmptiedSourceRef;
}

/**
 * Turn the two versions the API reports into the pair the specs address, and
 * refuse anything else.
 *
 * Every check here is load-bearing rather than defensive. What the specs assert
 * is that the Playground blocks a run on a version with no items and releases
 * it on one that has them — so a fixture that quietly produced one version, or
 * two non-empty ones, or left the populated version as the latest, would hand
 * the UI a state in which the assertions cannot fail. That reads as coverage
 * forever, which is worse than a fixture that throws here.
 *
 * The hash and the id are required rather than defaulted for the same reason:
 * the API marks both optional, and `/experiments/execute` without a
 * `version_hash` answers about the dataset's LATEST version. Sending an absent
 * hash would make the 400 below arrive for the right reason by accident, and
 * the 202 at the populated version impossible.
 */
const asVersionPair = (
  label: string,
  versions: DatasetVersionRef[],
): { populated: SourceVersionRef; emptied: SourceVersionRef } => {
  if (versions.length !== 2) {
    throw new Error(
      `[${label} fixture] expected exactly 2 versions after emptying, got ${versions.length}: ` +
        `${JSON.stringify(versions)}`,
    );
  }

  const required = (version: DatasetVersionRef): SourceVersionRef => {
    if (!version.id || !version.versionHash) {
      throw new Error(
        `[${label} fixture] version ${version.versionName} carried no id/hash: ` +
          `${JSON.stringify(version)}`,
      );
    }
    return {
      id: version.id,
      name: version.versionName,
      hash: version.versionHash,
      itemsTotal: version.itemsTotal,
    };
  };

  // Picked by what each version IS, never by its position in the response: the
  // ordering is the endpoint's business, and a fixture that read "newest first"
  // as a given would silently swap the two the day it changed.
  const emptiedRow = versions.find((v) => v.isLatest);
  const populatedRow = versions.find((v) => !v.isLatest);
  if (!emptiedRow || !populatedRow) {
    throw new Error(
      `[${label} fixture] expected one latest and one earlier version, got ` +
        `${JSON.stringify(versions)}`,
    );
  }
  if (emptiedRow.itemsTotal !== 0) {
    throw new Error(
      `[${label} fixture] the latest version (${emptiedRow.versionName}) still holds ` +
        `${emptiedRow.itemsTotal} item(s); the grouped delete did not commit an empty version`,
    );
  }
  if (populatedRow.itemsTotal !== SEED_ITEM_COUNT) {
    throw new Error(
      `[${label} fixture] the earlier version (${populatedRow.versionName}) holds ` +
        `${populatedRow.itemsTotal} item(s), expected ${SEED_ITEM_COUNT}`,
    );
  }

  return { populated: required(populatedRow), emptied: required(emptiedRow) };
};

/**
 * Empty everything the source currently holds, the way the UI's select-all
 * delete does — with a `batch_group_id`, so the delete COMMITS a new version
 * instead of mutating the latest one in place.
 *
 * That distinction is the whole fixture. An ungrouped delete leaves one version
 * whose `items_total` drops to 0, which is a state no user can reach through
 * the product; the grouped one leaves the realistic shape — a populated v1 and
 * an empty v2 — and is the only shape in which "the gate follows the SELECTED
 * version" is a question with two different answers.
 */
const emptyAndReadVersions = async (
  backendClient: BackendClient,
  label: string,
  sourceId: string,
): Promise<{ populated: SourceVersionRef; emptied: SourceVersionRef }> => {
  const itemIds = await backendClient.listDatasetItemIds(sourceId);
  if (itemIds.length !== SEED_ITEM_COUNT) {
    throw new Error(
      `[${label} fixture] seeded ${itemIds.length} item(s), expected ${SEED_ITEM_COUNT}`,
    );
  }
  await backendClient.deleteDatasetItemsByIds(itemIds);

  // The commit is not readable the instant the delete answers 204, so the read
  // is polled rather than taken once. Polled on the SHAPE the specs need, so a
  // delete that committed nothing times out here naming what it found.
  let versions: DatasetVersionRef[] = [];
  await expect
    .poll(
      async () => {
        versions = await backendClient.getDatasetVersions(sourceId);
        return versions.length;
      },
      { timeout: 30_000, intervals: [250, 500, 1000] },
    )
    .toBe(2);

  return asVersionPair(label, versions);
};

/**
 * A dataset and a test suite, each emptied into a committed latest version —
 * the state a user reaches by selecting every row and deleting it.
 *
 * Both are seeded through the public SDK and emptied over the typed backend
 * client, and both are project-scoped: a dataset created without a project is
 * invisible to the Playground's Run-experiment picker, so one seeded any other
 * way cannot drive these specs at all.
 *
 * Teardown deletes the source explicitly. Datasets and test suites share one
 * table and neither cascades with its project, and `global-teardown.ts`'s
 * run-prefix sweep does know about datasets — but only at the end of the whole
 * run, so a mid-run failure would leave a half-emptied dataset for every later
 * spec that lists them to trip over.
 */
export const test = baseTest.extend<EmptiedSourceVersionsFixtures>({
  emptiedDataset: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const name = `${testNamespace}-emptied-ds`;
    const created = await sdkClient.python.createDataset({
      project_name: project.name,
      name,
      description: `emptied by ${testInfo.title}`,
      items: Array.from({ length: SEED_ITEM_COUNT }, (_, i) => ({
        input: `emptied input ${i + 1}`,
        expected_output: `emptied output ${i + 1}`,
      })) as unknown as Array<Record<string, unknown>>,
    });

    const { populated, emptied } = await emptyAndReadVersions(
      backendClient,
      'emptiedDataset',
      created.id,
    );
    const ref: EmptiedSourceRef = {
      id: created.id,
      name: created.name,
      projectId: project.id,
      projectName: project.name,
      populated,
      emptied,
    };
    await testInfo.attach('opik.emptiedDataset', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteDataset(created.id);
      } catch (err) {
        console.warn(`[emptiedDataset fixture] delete warning for ${name}:`, err);
      }
    }
  },

  emptiedTestSuite: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const name = `${testNamespace}-emptied-suite`;
    const created = await sdkClient.python.createTestSuite({
      project_name: project.name,
      name,
      description: `emptied by ${testInfo.title}`,
      // No assertions and no run: what is under test is the Playground refusing
      // to START a run, which is decided before any judge would be reached.
      items: Array.from({ length: SEED_ITEM_COUNT }, (_, i) => ({
        data: { question: `emptied question ${i + 1}` },
      })),
    });

    const { populated, emptied } = await emptyAndReadVersions(
      backendClient,
      'emptiedTestSuite',
      created.id,
    );
    const ref: EmptiedSourceRef = {
      id: created.id,
      name: created.name,
      projectId: project.id,
      projectName: project.name,
      populated,
      emptied,
    };
    await testInfo.attach('opik.emptiedTestSuite', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteDataset(created.id);
      } catch (err) {
        console.warn(`[emptiedTestSuite fixture] delete warning for ${name}:`, err);
      }
    }
  },
});

export { expect };
