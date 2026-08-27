import { test as baseTest } from './grouped-dataset.fixture';
import { uuid7 } from '../core/backend';
import { shouldLeaveArtifacts } from '../core/artifacts';

/**
 * Twelve items carrying the shared id prefix — more than the items grid's
 * default 10-row page, which is what makes the "Select all N items?" banner
 * render at all.
 */
export const VERSIONED_DATASET_MATCHING = 12;

/**
 * Three further items outside that prefix. They are the bystanders: a delete
 * scoped to the prefix must leave them, so "the target rows went" cannot also
 * be satisfied by a delete that took the whole dataset.
 */
export const VERSIONED_DATASET_BYSTANDERS = 3;

/** The `data` key half the items carry, for a control filter that resolves against an item-level column correctly. */
export const VERSION_GROUP_COLUMN = 'group';
export const VERSION_TARGET_GROUP = 'beta';

/** The tag the version-forcing batch update applies. Never asserted on — it exists to commit v2. */
const VERSION_FORCING_TAG = 'versioned-dataset-fixture';

/**
 * How far back each set of item ids has its embedded UUIDv7 timestamp pushed.
 *
 * A UUIDv7's first eight hex characters are the top 32 bits of its 48-bit
 * millisecond timestamp, which tick over roughly every 65 seconds. Minting the
 * two sets hours apart, and hours before the run, therefore guarantees three
 * things that a same-millisecond mint would leave to the clock: the matching
 * items share a prefix, the bystanders do not carry it, and neither collides
 * with an id generated during the run — a version's snapshot rows above all. A
 * seed and a re-version inside the same 65-second window would share a prefix
 * and the filter would silently stop discriminating.
 */
const MATCHING_ID_AGE_MS = 3 * 60 * 60 * 1000;
const BYSTANDER_ID_AGE_MS = 6 * 60 * 60 * 1000;

export interface VersionedDatasetItemRef {
  id: string;
  group: string;
  /** The item's own authorship instant, as the read path reports it. */
  createdAt: Date;
}

export interface VersionedDatasetRef {
  id: string;
  name: string;
  projectId: string;
  items: VersionedDatasetItemRef[];
  /** Every seeded item id. */
  itemIds: string[];
  /** The ids that carry `idPrefix`. */
  matchingItemIds: string[];
  /** The ids that do not, and which a prefix-scoped mutation must leave untouched. */
  prefixBystanderItemIds: string[];
  /** The eight-hex-character prefix the matching ids share, and no snapshot row does. */
  idPrefix: string;
  /** Ids whose `data.group` is `beta` — the control filter's scope. */
  targetItemIds: string[];
  /** Ids outside the control filter. */
  groupBystanderItemIds: string[];
  /** The latest (second) version's commit instant — when its snapshot rows were stamped. */
  versionCreatedAt: Date;
  /** The latest instant any ITEM was authored. Strictly before `versionCreatedAt`. */
  lastItemCreatedAt: Date;
}

export interface VersionedDatasetFixtures {
  versionedDataset: VersionedDatasetRef;
}

/**
 * A dataset that has been versioned twice WITHOUT its items being re-authored —
 * the state in which a dataset item's own columns and its version snapshot
 * row's columns hold different values.
 *
 * `groupedDataset` covers the single-version case, where the two agree and a
 * filter binds to the same thing whichever column set it resolves against. This
 * fixture exists to make them disagree, because that is the only state in which
 * a filter-scoped read and a filter-scoped mutation can be shown to be
 * resolving different columns.
 *
 * The second version is committed by a GROUPED batch update — the request the
 * UI's select-all "Add tag" sends. That is not interchangeable with an SDK
 * re-insert: re-inserting an item rewrites its content, so its
 * `item_created_at` moves forward with the new snapshot and the two column sets
 * agree again. Tagging leaves authorship alone, so only the snapshot moves.
 *
 * The fixture proves that state holds before any test reads it: two versions,
 * every seeded id present and unchanged, the matching prefix carried by exactly
 * the matching items, and the version stamped strictly after the last item was
 * authored. A test asserting on a divergence its fixture silently failed to
 * create would be a test that cannot fail.
 */
export const test = baseTest.extend<VersionedDatasetFixtures>({
  versionedDataset: async ({ sdkClient, backendClient, project, testNamespace }, use, testInfo) => {
    const name = `${testNamespace}-versioned-ds`;

    const mint = (count: number, ageMs: number): string[] => {
      const stamp = new Date(Date.now() - ageMs);
      return Array.from({ length: count }, () => uuid7(stamp));
    };
    const matchingIds = mint(VERSIONED_DATASET_MATCHING, MATCHING_ID_AGE_MS);
    const bystanderIds = mint(VERSIONED_DATASET_BYSTANDERS, BYSTANDER_ID_AGE_MS);
    const idPrefix = matchingIds[0].slice(0, 8);

    if (!matchingIds.every((id) => id.startsWith(idPrefix))) {
      throw new Error(`[versionedDataset fixture] the matching ids do not all share the prefix ${idPrefix}`);
    }
    if (bystanderIds.some((id) => id.startsWith(idPrefix))) {
      throw new Error(`[versionedDataset fixture] a bystander id also starts with ${idPrefix}`);
    }
    if (uuid7().startsWith(idPrefix)) {
      throw new Error(
        `[versionedDataset fixture] an id minted now also starts with ${idPrefix} — the age ` +
          "offset is not separating the items from this run's other ids",
      );
    }

    const seededIds = [...matchingIds, ...bystanderIds];
    const created = await sdkClient.python.createDataset({
      project_name: project.name,
      name,
      description: 'filter scope across dataset versions',
      items: seededIds.map((id, index) => ({
        id,
        [VERSION_GROUP_COLUMN]: index % 2 === 0 ? VERSION_TARGET_GROUP : 'alpha',
        input: `question ${index}`,
        expected_output: `answer ${index}`,
      })) as unknown as Array<Record<string, unknown>>,
    });

    // Commit v2 without touching the items themselves.
    await backendClient.commitDatasetItemVersionByTagging({
      datasetId: created.id,
      tag: VERSION_FORCING_TAG,
    });

    const stored = await backendClient.listDatasetItemsPage({ datasetId: created.id });
    if (stored.total !== seededIds.length || stored.items.length !== seededIds.length) {
      throw new Error(
        `[versionedDataset fixture] expected ${seededIds.length} items, ` +
          `got total=${stored.total} content=${stored.items.length}`,
      );
    }
    const sorted = (ids: string[]): string => JSON.stringify([...ids].sort());
    if (sorted(stored.items.map((i) => i.id)) !== sorted(seededIds)) {
      throw new Error('[versionedDataset fixture] the stored item ids are not the ids that were seeded');
    }

    const versions = await backendClient.getDatasetVersions(created.id);
    if (versions.length !== 2) {
      throw new Error(
        `[versionedDataset fixture] expected exactly 2 versions, got ${versions.length} ` +
          `(${versions.map((v) => v.versionName).join(', ')})`,
      );
    }
    const latest = versions.find((v) => v.isLatest);
    if (!latest) {
      throw new Error('[versionedDataset fixture] no version is marked latest');
    }

    const lastItemCreatedAt = new Date(
      Math.max(...stored.items.map((item) => item.createdAt.getTime())),
    );
    if (latest.createdAt.getTime() <= lastItemCreatedAt.getTime()) {
      throw new Error(
        `[versionedDataset fixture] version ${latest.versionName} was stamped ` +
          `${latest.createdAt.toISOString()}, not after the last item ` +
          `(${lastItemCreatedAt.toISOString()}) — the two column sets have not diverged, so a ` +
          'test over them could not tell which one a filter bound to',
      );
    }

    const groupOf = (item: (typeof stored.items)[number]): string => {
      const group = item.data[VERSION_GROUP_COLUMN];
      if (typeof group !== 'string') {
        throw new Error(`[versionedDataset fixture] item ${item.id} has no string "${VERSION_GROUP_COLUMN}"`);
      }
      return group;
    };

    const ref: VersionedDatasetRef = {
      id: created.id,
      name: created.name,
      projectId: project.id,
      items: stored.items.map((item) => ({ id: item.id, group: groupOf(item), createdAt: item.createdAt })),
      itemIds: stored.items.map((item) => item.id),
      matchingItemIds: matchingIds,
      prefixBystanderItemIds: bystanderIds,
      idPrefix,
      targetItemIds: stored.items.filter((i) => groupOf(i) === VERSION_TARGET_GROUP).map((i) => i.id),
      groupBystanderItemIds: stored.items.filter((i) => groupOf(i) !== VERSION_TARGET_GROUP).map((i) => i.id),
      versionCreatedAt: latest.createdAt,
      lastItemCreatedAt,
    };

    await testInfo.attach('opik.versionedDataset', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteDataset(created.id);
      } catch (err) {
        console.warn(`[versionedDataset fixture] delete warning for ${name}:`, err);
      }
    }
  },
});

export { expect } from './grouped-dataset.fixture';
