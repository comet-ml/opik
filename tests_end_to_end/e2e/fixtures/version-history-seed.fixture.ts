import { test as baseTest } from './outer-prompt-tokens-spans.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';
import type { DatasetVersionRef, ExecutionPolicyRef } from '../core/backend';

/**
 * Seeds for the Version history sidebar — a dataset and a test suite that each
 * hold an older version whose contents DIFFER from the latest, so a sheet
 * wired to the latest version instead of the opened one renders something a
 * spec can see.
 *
 * Everything here exists to make that discrimination possible. Two versions
 * holding the same rows would be served identically by a correct
 * implementation and by one that ignores the version entirely, which is the
 * precise failure the sidebar can have: a healthy-looking table with the wrong
 * rows in it.
 *
 * So the markers below are whole-string unique. A spec can assert that NO
 * latest-only content appears anywhere in the sheet, which is stronger than
 * counting rows and is what catches a partially-version-scoped read.
 */
export const V1_MARKER = 'V1ORIGINAL';
export const V2_EDITED_MARKER = 'V2EDITED';
export const V2_NEW_MARKER = 'V2NEW';
/** The decoy dataset's marker — must never be rendered by the subject's sheet. */
export const BYSTANDER_MARKER = 'BYSTANDERROW';

/** The data column the markers live in; `question` is the other, and stays constant. */
export const ANSWER_FIELD = 'answer';
export const QUESTION_FIELD = 'question';

export interface VersionedItemSeed {
  id: string;
  data: Record<string, string>;
}

/** One committed version of the seed, as the versions endpoint reports it back. */
export interface SeededVersionRef {
  /** `v1`, `v2`, … — also the Version column's cell text, and the sheet's title. */
  name: string;
  /** The version's row id, which is what `base_version` wants. */
  id: string;
  /** The content hash, which is what the sidebar and `?version=` want. */
  hash: string;
  itemsTotal: number;
}

export interface VersionedDatasetRef {
  id: string;
  name: string;
  projectId: string;
  projectName: string;
  /** The version a spec opens: 3 items, every one carrying `V1_MARKER`. */
  older: SeededVersionRef;
  /** The version the Records tab serves: 5 items, 2 edited and 2 added. */
  latest: SeededVersionRef;
  olderItems: VersionedItemSeed[];
  latestItems: VersionedItemSeed[];
  /**
   * A second dataset in the same project that no spec touches. A restore that
   * reached beyond the dataset it was invoked on, or a sheet that read items
   * without scoping to the dataset, both show up here and nowhere else.
   */
  bystander: {
    id: string;
    name: string;
    versionCount: number;
    items: VersionedItemSeed[];
  };
}

export interface VersionedSuiteRef {
  id: string;
  name: string;
  projectId: string;
  projectName: string;
  /** The version a spec opens: 2 test cases, under `olderAssertions`/`olderPolicy`. */
  older: SeededVersionRef;
  /** The current version: 4 test cases, under a DIFFERENT set of assertions and a stricter policy. */
  latest: SeededVersionRef;
  olderAssertions: string[];
  olderPolicy: ExecutionPolicyRef;
  latestAssertions: string[];
  latestPolicy: ExecutionPolicyRef;
  olderItems: VersionedItemSeed[];
  latestItems: VersionedItemSeed[];
}

export interface VersionHistorySeedFixtures {
  versionedDataset: VersionedDatasetRef;
  versionedSuite: VersionedSuiteRef;
}

const SUITE_OLDER_ASSERTIONS = [
  'The response names the refund window',
  'The response stays polite',
];
const SUITE_LATEST_ASSERTIONS = ['The response cites the policy document'];
const SUITE_OLDER_POLICY: ExecutionPolicyRef = { passThreshold: 1, runsPerItem: 2 };
const SUITE_LATEST_POLICY: ExecutionPolicyRef = { passThreshold: 2, runsPerItem: 3 };

/**
 * One version off the versions list, by name, with its hash asserted present.
 *
 * `versionHash` is nullable on the list shape, and a fixture that passed an
 * absent one through would hand the spec a sheet URL reading
 * `dvs_version=undefined` — which fails as a missing locator several steps
 * later rather than as a seed that did not produce what it claims.
 */
function seededVersion(
  versions: DatasetVersionRef[],
  name: string,
  label: string,
): SeededVersionRef {
  const found = versions.find((v) => v.versionName === name);
  if (!found) {
    throw new Error(
      `[${label}] seed has no version named ${name}; it cut ` +
        `${versions.map((v) => v.versionName).join(', ') || '(none)'}`,
    );
  }
  if (!found.versionHash) {
    throw new Error(`[${label}] version ${name} came back with no version_hash`);
  }
  // `id` is nullable on the list shape too, and it is what `base_version`
  // wants — an absent one would post `base_version: undefined` and read the
  // 409-free answer as agreement rather than as a seed that never pinned.
  if (!found.id) {
    throw new Error(`[${label}] version ${name} came back with no id`);
  }
  return { name, id: found.id, hash: found.versionHash, itemsTotal: found.itemsTotal };
}

export const test = baseTest.extend<VersionHistorySeedFixtures>({
  /**
   * A dataset whose v1 holds 3 items and whose v2 holds 5 — the two edited, the
   * one left alone, and the two added. Item ids are minted up front because
   * "edited" is expressed by re-sending an id with different content; without
   * explicit ids the second insert would add five fresh rows and the two
   * versions would differ only in size.
   */
  versionedDataset: async ({ sdkClient, backendClient, project, testNamespace }, use, testInfo) => {
    const name = `${testNamespace}-versioned-ds`;
    const ids = Array.from({ length: 5 }, () => uuid7());

    const item = (index: number, marker: string): VersionedItemSeed => ({
      id: ids[index],
      data: {
        [QUESTION_FIELD]: `question ${index}`,
        [ANSWER_FIELD]: `${marker} ${index}`,
      },
    });

    const olderItems = [item(0, V1_MARKER), item(1, V1_MARKER), item(2, V1_MARKER)];
    // The second insert rewrites two rows and adds two: items_total 5,
    // items_added 2, items_modified 2.
    const changes = [
      item(0, V2_EDITED_MARKER),
      item(1, V2_EDITED_MARKER),
      item(3, V2_NEW_MARKER),
      item(4, V2_NEW_MARKER),
    ];
    const latestItems = [...changes.slice(0, 2), olderItems[2], ...changes.slice(2)];

    const toSdkItems = (items: VersionedItemSeed[]) =>
      items.map((seed) => ({ id: seed.id, ...seed.data }));

    const created = await sdkClient.python.createDataset({
      project_name: project.name,
      name,
      description: 'two versions with different contents, for the version sidebar',
    });
    // One insert() call per version — the SDK mints a batch_group_id per call,
    // and that is what makes the backend commit a version rather than mutate
    // the latest one in place.
    await sdkClient.python.insertDatasetItems({
      project_name: project.name,
      dataset_name: name,
      items: toSdkItems(olderItems),
    });
    await sdkClient.python.insertDatasetItems({
      project_name: project.name,
      dataset_name: name,
      items: toSdkItems(changes),
    });

    const versions = await backendClient.getDatasetVersions(created.id);
    const older = seededVersion(versions, 'v1', 'versionedDataset');
    const latest = seededVersion(versions, 'v2', 'versionedDataset');

    const bystanderName = `${testNamespace}-bystander-ds`;
    const bystanderItems: VersionedItemSeed[] = [0, 1].map((index) => ({
      id: uuid7(),
      data: {
        [QUESTION_FIELD]: `bystander question ${index}`,
        [ANSWER_FIELD]: `${BYSTANDER_MARKER} ${index}`,
      },
    }));
    const bystanderCreated = await sdkClient.python.createDataset({
      project_name: project.name,
      name: bystanderName,
      description: 'decoy — never touched by any spec',
    });
    // Two versions, so a restore that reached the wrong dataset would move a
    // number a spec reads rather than one it does not.
    for (const seed of bystanderItems) {
      await sdkClient.python.insertDatasetItems({
        project_name: project.name,
        dataset_name: bystanderName,
        items: [{ id: seed.id, ...seed.data }],
      });
    }

    const ref: VersionedDatasetRef = {
      id: created.id,
      name: created.name,
      projectId: project.id,
      projectName: project.name,
      older,
      latest,
      olderItems,
      latestItems,
      bystander: {
        id: bystanderCreated.id,
        name: bystanderName,
        versionCount: 2,
        items: bystanderItems,
      },
    };
    await testInfo.attach('opik.versionedDataset', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    /** Datasets don't cascade with project deletion — explicit delete required. */
    if (!shouldLeaveArtifacts(testInfo)) {
      for (const [label, id] of [
        [name, created.id],
        [bystanderName, bystanderCreated.id],
      ] as const) {
        try {
          await backendClient.deleteDataset(id);
        } catch (err) {
          console.warn(`[versionedDataset fixture] delete warning for ${label}:`, err);
        }
      }
    }
  },

  /**
   * A test suite whose older version differs from the latest in its test cases
   * AND in its suite-level assertions and execution policy.
   *
   * The assertions-and-policy half is the load-bearing part. Opening the older
   * version and seeing "1 of 2 must pass" is only evidence of version-scoping
   * because the suite's CURRENT policy is "2 of 3" — against a suite with one
   * policy throughout, a sheet reading the latest version renders exactly the
   * same page as a correct one.
   *
   * Three versions get cut, not two: `create_test_suite` commits an empty v1
   * for the suite itself and a v2 for the items it was given. v2 is therefore
   * the version a spec opens, and v3 — from the Records tab's own delta-commit
   * endpoint, the only route that can CHANGE a suite's assertions or policy —
   * is the latest.
   */
  versionedSuite: async ({ sdkClient, backendClient, project, testNamespace }, use, testInfo) => {
    const name = `${testNamespace}-versioned-suite`;

    const olderData = [0, 1].map((index) => ({
      [QUESTION_FIELD]: `suite question ${index}`,
      [ANSWER_FIELD]: `${V1_MARKER} ${index}`,
    }));

    const suite = await sdkClient.python.createTestSuite({
      project_name: project.name,
      name,
      description: 'two versions with different policies, for the version sidebar',
      global_assertions: SUITE_OLDER_ASSERTIONS,
      runs_per_item: SUITE_OLDER_POLICY.runsPerItem,
      pass_threshold: SUITE_OLDER_POLICY.passThreshold,
      items: olderData.map((data) => ({ data })),
    });

    const older = seededVersion(
      await backendClient.getDatasetVersions(suite.id),
      'v2',
      'versionedSuite',
    );

    // The SDK mints the suite items' ids, so they are read back rather than
    // guessed — a spec addresses the sheet's rows by identity, and the Test
    // cases grid shows no data column it could match on instead.
    const seeded = await backendClient.listDatasetItemsAtVersion({
      datasetId: suite.id,
      versionHash: older.hash,
    });
    const olderItems: VersionedItemSeed[] = seeded.items.map((item) => ({
      id: item.id,
      data: item.data as Record<string, string>,
    }));

    const newItems: VersionedItemSeed[] = [2, 3].map((index) => ({
      id: uuid7(),
      data: {
        [QUESTION_FIELD]: `suite question ${index}`,
        [ANSWER_FIELD]: `${V2_NEW_MARKER} ${index}`,
      },
    }));
    await backendClient.applyDatasetItemChanges({
      datasetId: suite.id,
      baseVersionId: older.id,
      addedItems: newItems.map((seed) => ({ id: seed.id, data: seed.data })),
      changeDescription: 'two more cases, stricter policy',
      assertionNames: SUITE_LATEST_ASSERTIONS,
      executionPolicy: SUITE_LATEST_POLICY,
    });

    const latest = seededVersion(
      await backendClient.getDatasetVersions(suite.id),
      'v3',
      'versionedSuite',
    );

    const ref: VersionedSuiteRef = {
      id: suite.id,
      name,
      projectId: project.id,
      projectName: project.name,
      older,
      latest,
      olderAssertions: SUITE_OLDER_ASSERTIONS,
      olderPolicy: SUITE_OLDER_POLICY,
      latestAssertions: SUITE_LATEST_ASSERTIONS,
      latestPolicy: SUITE_LATEST_POLICY,
      olderItems,
      latestItems: [...olderItems, ...newItems],
    };
    await testInfo.attach('opik.versionedSuite', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    /** Suites share storage with datasets and don't cascade with the project. */
    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteDataset(suite.id);
      } catch (err) {
        console.warn(`[versionedSuite fixture] delete warning for ${name}:`, err);
      }
    }
  },
});

export { expect } from './outer-prompt-tokens-spans.fixture';
