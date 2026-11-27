import { test as baseTest } from './widget-edit-traces.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/** One seeded experiment and the dataset version it was pinned to. */
export interface VersionScopedExperimentRef {
  id: string;
  name: string;
  /** The version's own row id, as sent in `dataset_version_id`. */
  versionId: string;
  /** The version's content hash — what `dataset_versions` addresses it by. */
  versionHash: string;
  /** `v1` / `v2`, read back off the experiment rather than assumed. */
  versionName: string;
  /** Dataset items this experiment reported a result against. */
  itemIds: string[];
}

export interface VersionScopedExperimentsRef {
  datasetId: string;
  datasetName: string;
  /** Every item the dataset holds, in the order the items endpoint pages them. */
  itemIds: string[];
  /** The experiment pinned to v1, which covers V1_ITEM_COUNT of the items. */
  v1: VersionScopedExperimentRef;
  /** The experiment pinned to v2 (latest), which covers every item. */
  v2: VersionScopedExperimentRef;
}

export interface VersionScopedExperimentsFixtures {
  versionScopedExperiments: VersionScopedExperimentsRef;
}

/**
 * Items in the first version, and the one the second version adds.
 *
 * Two and three rather than any larger pair because the numbers themselves are
 * the assertion: `dataset_versions=[v1]` must answer 2 and `[v2]` must answer 3,
 * so the two versions have to hold DIFFERENT item counts or a scoped read and an
 * unscoped one would agree. They are also deliberately both under the
 * all-experiments page-size cap (100), so every row fits on one page and `total`
 * can be compared against the collection length.
 */
export const VSE_V1_ITEM_COUNT = 2;
export const VSE_V2_ITEM_COUNT = 3;

/**
 * A dataset with TWO committed versions and one experiment pinned to each, for
 * the `dataset_versions` parameter opik#9448 (OPIK-9113) put on
 * `GET /v1/private/datasets/{id}/items/experiments/items`.
 *
 * **Why a second version is the whole point.** `allDatasetExperiments` already
 * seeds this endpoint's all-experiments arm, and
 * `compare-all-experiments-read.spec.ts` already sends `dataset_versions` over
 * it — but that fixture's insert cuts exactly ONE version, and the spec says so
 * in its own header: on a one-version dataset the row set a correctly-scoped
 * read answers is indistinguishable from the row set an IGNORED parameter
 * answers, because `latest` and the only hash name the same thing and both
 * resolve to every experiment. Everything there except the unknown-hash 404 is
 * therefore satisfied by a backend that discards the parameter. This fixture is
 * the shape that can tell them apart: two versions, two different item counts,
 * one experiment pinned to each, so each hash has a row count only it can
 * produce.
 *
 * **Why a distinct `batch_group_id` per write.** A grouped PUT commits a new
 * version and every batch sharing an id collapses into one; an UNGROUPED write
 * mutates the latest version in place. Two writes under one group id would
 * leave a single version holding all three items, which is this fixture's
 * failure mode rather than a smaller version of its success — so the group ids
 * are minted separately and the version count is asserted below.
 *
 * **Why the pin is read back.** `dataset_version_id` is optional at creation and
 * the backend's documented default is to link the experiment to whatever is
 * latest (`Experiment.java`). A pin the backend ignored or a fixture bug that
 * sent the version HASH where the row id belongs therefore does not fail here —
 * it silently puts BOTH experiments on v2, where every scoped read answers the
 * same three rows and the spec's scoping assertions cannot fail. So each
 * experiment's `dataset_version_summary.version_name` is read back off the
 * listing and asserted to be the version it was pinned to, and the two names are
 * asserted to differ. That is the fixture proving it can discriminate, before a
 * spec asserts anything.
 *
 * Seeded over REST rather than through `evaluate()`: the SDK's evaluate path
 * mints its own experiment and cannot pin a version, and the items have to carry
 * caller-minted UUIDv7 ids so the seed knows which of them belong to v1.
 *
 * Teardown deletes the experiments before the dataset they reference. Datasets
 * do not cascade with their project and experiments do not cascade with the
 * dataset, so both are explicit; the traces go with the `project` fixture.
 */
export const test = baseTest.extend<VersionScopedExperimentsFixtures>({
  versionScopedExperiments: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const datasetName = `${testNamespace}-vse-ds`;
    const created = await sdkClient.python.createDataset({
      project_name: project.name,
      name: datasetName,
      description: 'version-scoped compare read (opik#9448)',
    });

    // Caller-minted so the seed knows which ids v1 holds. The endpoint answers
    // 204 with no body, so there is no other way to find out.
    const itemIds = Array.from({ length: VSE_V2_ITEM_COUNT }, () => uuid7());
    const v1ItemIds = itemIds.slice(0, VSE_V1_ITEM_COUNT);

    await backendClient.writeDatasetItemsBatch({
      datasetId: created.id,
      batchGroupId: crypto.randomUUID(),
      items: v1ItemIds.map((id, i) => ({
        id,
        data: { input: `vse question ${i + 1}`, expected_output: `vse answer ${i + 1}` },
      })),
    });
    await backendClient.writeDatasetItemsBatch({
      // A SECOND group id, so this commits v2 rather than folding into v1.
      datasetId: created.id,
      batchGroupId: crypto.randomUUID(),
      items: itemIds.slice(VSE_V1_ITEM_COUNT).map((id, i) => ({
        id,
        data: {
          input: `vse question ${VSE_V1_ITEM_COUNT + i + 1}`,
          expected_output: `vse answer ${VSE_V1_ITEM_COUNT + i + 1}`,
        },
      })),
    });

    const versions = await backendClient.getDatasetVersions(created.id);
    if (versions.length !== 2) {
      throw new Error(
        `[versionScopedExperiments fixture] dataset '${datasetName}' has ${versions.length} ` +
          'version(s), expected exactly 2 — two grouped writes under distinct batch_group_ids ' +
          'must commit two versions, and one version cannot discriminate a scoped read from an ' +
          'ignored parameter',
      );
    }
    // By item count, not by list order: the order the versions endpoint pages
    // them in is its own contract and not this fixture's to depend on.
    const v1Row = versions.find((v) => v.itemsTotal === VSE_V1_ITEM_COUNT);
    const v2Row = versions.find((v) => v.itemsTotal === VSE_V2_ITEM_COUNT);
    if (!v1Row || !v2Row) {
      throw new Error(
        `[versionScopedExperiments fixture] expected one version holding ${VSE_V1_ITEM_COUNT} ` +
          `items and one holding ${VSE_V2_ITEM_COUNT}, got ` +
          `${JSON.stringify(versions.map((v) => ({ name: v.versionName, items: v.itemsTotal })))}`,
      );
    }
    if (!v2Row.isLatest || v1Row.isLatest) {
      throw new Error(
        `[versionScopedExperiments fixture] the ${VSE_V2_ITEM_COUNT}-item version must be the ` +
          `latest and the ${VSE_V1_ITEM_COUNT}-item one must not: latest flags are ` +
          `v1=${v1Row.isLatest}, v2=${v2Row.isLatest}. The spec resolves ["latest"] to v2.`,
      );
    }
    // Both halves of each version's identity are asserted present rather than
    // defaulted: a read scoped to `undefined` silently becomes an unscoped read,
    // and an absent id compared to another absent id reads as agreement.
    for (const [label, row] of [
      ['v1', v1Row],
      ['v2', v2Row],
    ] as const) {
      if (!row.id || !row.versionHash) {
        throw new Error(
          `[versionScopedExperiments fixture] version ${label} ('${row.versionName}') carries ` +
            `id '${row.id ?? 'none'}' and hash '${row.versionHash ?? 'none'}' — both are needed, ` +
            'the id to pin an experiment and the hash to address the version in a query',
        );
      }
    }
    if (v1Row.versionHash === v2Row.versionHash) {
      throw new Error(
        `[versionScopedExperiments fixture] both versions hashed to ` +
          `'${v1Row.versionHash}' — two versions addressed by one hash cannot be told apart`,
      );
    }

    const stored = await backendClient.listDatasetItemIds(created.id);
    if (stored.length !== VSE_V2_ITEM_COUNT) {
      throw new Error(
        `[versionScopedExperiments fixture] dataset '${datasetName}' stored ${stored.length} ` +
          `items, expected ${VSE_V2_ITEM_COUNT}`,
      );
    }

    const shapes = [
      { key: 'v1' as const, row: v1Row, items: v1ItemIds },
      { key: 'v2' as const, row: v2Row, items: itemIds },
    ];
    const seeded: Array<Omit<VersionScopedExperimentRef, 'versionName'>> = [];
    for (const shape of shapes) {
      const experimentId = uuid7();
      const experimentName = `${testNamespace}-vse-exp-${shape.key}`;
      await backendClient.createExperiment({
        id: experimentId,
        name: experimentName,
        datasetName,
        projectName: project.name,
        // The row id, never the hash — see createExperiment's own note.
        datasetVersionId: shape.row.id!,
      });

      // One trace per item this experiment covers: the compare read sources a
      // row's input/output from the trace its experiment item names, so an
      // experiment item without one comes back as a well-formed row with empty
      // cells.
      const traceIds = shape.items.map(() => uuid7());
      await backendClient.createTracesBatch({
        projectName: project.name,
        traces: traceIds.map((id, i) => ({
          id,
          name: `${experimentName}-trace-${i + 1}`,
          input: { question: `vse question ${i + 1}` },
          output: { answer: `vse answer ${i + 1}` },
        })),
      });
      await backendClient.createExperimentItems(
        shape.items.map((datasetItemId, i) => ({
          experimentId,
          datasetItemId,
          traceId: traceIds[i],
        })),
      );

      seeded.push({
        id: experimentId,
        name: experimentName,
        versionId: shape.row.id!,
        versionHash: shape.row.versionHash!,
        itemIds: shape.items,
      });
    }

    // The discrimination proof. An ignored `dataset_version_id` links both
    // experiments to the latest version, and then every scoped read in the spec
    // answers the same three rows whether the parameter works or not.
    const listed = await backendClient.listExperimentsForProject({ projectId: project.id });
    const linkedVersionNames = seeded.map((experiment) => {
      const row = listed.experiments.find((e) => e.id === experiment.id);
      if (!row) {
        throw new Error(
          `[versionScopedExperiments fixture] experiment '${experiment.name}' is not in the ` +
            `project's experiments listing (${listed.total} row(s)) — it cannot be read back, ` +
            'so the version it was pinned to cannot be confirmed',
        );
      }
      if (!row.datasetVersionName) {
        throw new Error(
          `[versionScopedExperiments fixture] experiment '${experiment.name}' reports no ` +
            'dataset_version_summary.version_name — an experiment linked to no version matches ' +
            'a dataset_versions query only through the include-unversioned fallback, which is ' +
            'not what this fixture seeds',
        );
      }
      return row.datasetVersionName;
    });
    if (linkedVersionNames[0] === linkedVersionNames[1]) {
      throw new Error(
        `[versionScopedExperiments fixture] both experiments are linked to version ` +
          `'${linkedVersionNames[0]}' — dataset_version_id did not pin them to different ` +
          'versions, so a version-scoped read cannot be told from an unscoped one',
      );
    }
    if (linkedVersionNames[0] !== v1Row.versionName || linkedVersionNames[1] !== v2Row.versionName) {
      throw new Error(
        `[versionScopedExperiments fixture] experiments are linked to ` +
          `['${linkedVersionNames[0]}', '${linkedVersionNames[1]}'] but were pinned to ` +
          `['${v1Row.versionName}', '${v2Row.versionName}']`,
      );
    }

    const ref: VersionScopedExperimentsRef = {
      datasetId: created.id,
      datasetName,
      itemIds: stored,
      v1: { ...seeded[0], versionName: linkedVersionNames[0] },
      v2: { ...seeded[1], versionName: linkedVersionNames[1] },
    };

    try {
      await testInfo.attach('opik.versionScopedExperiments', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo)) {
        const safe = async (what: string, fn: () => Promise<unknown>): Promise<void> => {
          try {
            await fn();
          } catch (err) {
            console.warn(`[versionScopedExperiments fixture] delete warning for ${what}:`, err);
          }
        };
        for (const experiment of seeded) {
          await safe(`experiment ${experiment.id}`, () =>
            backendClient.deleteExperiment(experiment.id),
          );
        }
        await safe(`dataset ${datasetName}`, () => backendClient.deleteDataset(created.id));
      }
    }
  },
});

export { expect } from './widget-edit-traces.fixture';
