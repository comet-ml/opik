import { test as baseTest } from './versioned-dataset.fixture';

/** The tag the version-forcing batch update applies. Not asserted on — it exists to commit v2. */
const VERSION_FORCING_TAG = 'versioned-comparison-fixture';

export interface VersionedComparisonRef {
  /** The item's own authorship instant, keyed by dataset item id. */
  itemCreatedAtById: Record<string, Date>;
  /** When the second version was committed, and therefore when its snapshot rows were stamped. */
  versionCreatedAt: Date;
  /** The latest instant any ITEM was authored. Strictly before `versionCreatedAt`. */
  lastItemCreatedAt: Date;
}

export interface VersionedComparisonFixtures {
  versionedComparison: VersionedComparisonRef;
}

/**
 * The `comparison` seed — three shared dataset items, two experiments — pushed
 * into a second dataset version whose snapshot rows are stamped after the items
 * were authored.
 *
 * Chained onto `comparison` rather than reseeding, so the two experiments, their
 * traces and their teardown stay owned by the fixture that already does that
 * properly. Nothing new is created here (the second version is a mutation of
 * existing items), so there is nothing for this fixture to tear down.
 *
 * A GROUPED batch update is what commits the version — see
 * `commitDatasetItemVersionByTagging`. The alternative, an SDK re-insert, would
 * rewrite the items and move their authorship timestamps forward with the
 * snapshot, which is precisely the divergence a caller needs to survive.
 */
export const test = baseTest.extend<VersionedComparisonFixtures>({
  versionedComparison: async ({ comparison, backendClient }, use, testInfo) => {
    await backendClient.commitDatasetItemVersionByTagging({
      datasetId: comparison.datasetId,
      tag: VERSION_FORCING_TAG,
    });

    const stored = await backendClient.listDatasetItemsPage({ datasetId: comparison.datasetId });
    if (stored.items.length !== comparison.itemIds.length) {
      throw new Error(
        `[versionedComparison fixture] expected ${comparison.itemIds.length} items after the ` +
          `version commit, got ${stored.items.length}`,
      );
    }

    const itemCreatedAtById: Record<string, Date> = {};
    for (const itemId of comparison.itemIds) {
      const stored_ = stored.items.find((item) => item.id === itemId);
      if (!stored_) {
        throw new Error(`[versionedComparison fixture] seeded item ${itemId} is gone after the version commit`);
      }
      itemCreatedAtById[itemId] = stored_.createdAt;
    }

    const versions = await backendClient.getDatasetVersions(comparison.datasetId);
    if (versions.length !== 2) {
      throw new Error(
        `[versionedComparison fixture] expected exactly 2 versions, got ${versions.length}`,
      );
    }
    const latest = versions.find((v) => v.isLatest);
    if (!latest) {
      throw new Error('[versionedComparison fixture] no version is marked latest');
    }

    const lastItemCreatedAt = new Date(
      Math.max(...Object.values(itemCreatedAtById).map((d) => d.getTime())),
    );
    if (latest.createdAt.getTime() <= lastItemCreatedAt.getTime()) {
      throw new Error(
        `[versionedComparison fixture] version ${latest.versionName} was stamped ` +
          `${latest.createdAt.toISOString()}, not after the last item ` +
          `(${lastItemCreatedAt.toISOString()}) — the timestamps have not diverged, so a ` +
          'test over them could not tell the two column sets apart',
      );
    }

    const ref: VersionedComparisonRef = {
      itemCreatedAtById,
      versionCreatedAt: latest.createdAt,
      lastItemCreatedAt,
    };

    await testInfo.attach('opik.versionedComparison', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);
  },
});

export { expect } from './versioned-dataset.fixture';
