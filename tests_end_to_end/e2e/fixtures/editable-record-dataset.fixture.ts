import { test as baseTest, expect } from './environment-split-thread.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/**
 * A dataset whose one record carries a value of every JSON type the record
 * panel has to put back the way it found it, plus a second record no spec
 * touches.
 *
 * The types are the whole point. The add/edit record panel renders each field
 * as TEXT and saves what the textarea holds, so a number, a `null`, a boolean
 * and a nested object only survive an edit elsewhere in the record because
 * `datasetFieldRows.toRawValue` hands back the value the row was LOADED with
 * whenever its text is unchanged. A regression there does not raise: `42`
 * becomes `"42"`, `null` becomes `""`, and the record still reads as a
 * perfectly ordinary one in the grid, in the panel and in the API. An
 * experiment reading `item.score` then gets a string, and nothing says so.
 *
 * `meta` is deliberately a nested object rather than a flat one: it is
 * stringified into the textarea with `JSON.stringify(value, null, 2)`, so a
 * round trip that went through the text would come back as a string whose
 * content still *looks* like the object — only a deep comparison against the
 * seed separates the two.
 */
export const EDITABLE_RECORD_DATA: Record<string, unknown> = {
  input: 'editable-record-input',
  expected_output: 'editable-record-expected-output',
  score: 42,
  note: null,
  flag: true,
  meta: { nested: { depth: 2 }, list: [1, 'two'] },
};

/**
 * The bystander record, in the same shape with pairwise-different values.
 *
 * Committing a draft sends a delta for the edited record and nothing else, so
 * a commit that rewrote the whole version — or an editor whose draft store
 * leaked across rows — shows up here and nowhere else. Asserting only that
 * the edited record is right would pass equally well against a commit that
 * flattened every other record in the dataset.
 */
export const BYSTANDER_RECORD_DATA: Record<string, unknown> = {
  input: 'bystander-record-input',
  expected_output: 'bystander-record-expected-output',
  score: 7,
  note: null,
  flag: false,
  meta: { nested: { depth: 9 }, list: ['three', 4] },
};

export interface EditableRecordDatasetRef {
  id: string;
  name: string;
  projectId: string;
  projectName: string;
  /** The record the spec opens and edits. */
  subjectId: string;
  subjectData: Record<string, unknown>;
  /** The record no spec touches — see `BYSTANDER_RECORD_DATA`. */
  bystanderId: string;
  bystanderData: Record<string, unknown>;
}

export interface EditableRecordDatasetFixtures {
  editableRecordDataset: EditableRecordDatasetRef;
}

export const test = baseTest.extend<EditableRecordDatasetFixtures>({
  /**
   * Seeded through `backendClient.writeDatasetItemsBatch` rather than the SDK
   * bridge, for the ids and for the types. The specs address both records by
   * id — row order on the Records grid is not something a seed can promise —
   * and `Dataset.insert` mints its own, while the batch endpoint takes
   * caller-supplied UUIDv7s. One `batch_group_id` shared by the single batch
   * is what makes the backend COMMIT the write as v1 rather than mutate a
   * latest version that does not exist yet.
   */
  editableRecordDataset: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const name = `${testNamespace}-editable-record-ds`;
    const subjectId = uuid7();
    const bystanderId = uuid7();

    const created = await sdkClient.python.createDataset({
      project_name: project.name,
      name,
      description: `typed record for the edit panel, seeded by ${testInfo.title}`,
    });

    try {
      await backendClient.writeDatasetItemsBatch({
        datasetId: created.id,
        batchGroupId: crypto.randomUUID(),
        items: [
          { id: subjectId, data: EDITABLE_RECORD_DATA },
          { id: bystanderId, data: BYSTANDER_RECORD_DATA },
        ],
      });

      // The seed is asserted to have STORED the types, not merely to have sent
      // them. Every assertion in both specs is a comparison against these
      // objects, so a backend that coerced `42` to `"42"` on the way in would
      // leave them comparing a string with a string — a test that cannot fail,
      // reading as coverage of exactly the regression it was written for.
      const stored = await backendClient.listDatasetItemsWithData(created.id);
      expect(stored, `[editableRecordDataset] ${name} holds exactly two records`).toHaveLength(2);
      const byId = new Map(stored.map((item) => [item.id, item.data]));
      expect(
        byId.get(subjectId),
        '[editableRecordDataset] the subject stored its number, null, boolean and nested object as themselves',
      ).toEqual(EDITABLE_RECORD_DATA);
      expect(
        byId.get(bystanderId),
        '[editableRecordDataset] the bystander stored its own values',
      ).toEqual(BYSTANDER_RECORD_DATA);

      // And that the write committed ONE version. The specs assert a v2 label
      // after their commit, which says nothing unless v1 is where they started.
      const versions = await backendClient.getDatasetVersions(created.id);
      expect(
        versions.map((version) => version.versionName),
        `[editableRecordDataset] ${name} starts at exactly one committed version`,
      ).toEqual(['v1']);

      const ref: EditableRecordDatasetRef = {
        id: created.id,
        name: created.name,
        projectId: project.id,
        projectName: project.name,
        subjectId,
        subjectData: EDITABLE_RECORD_DATA,
        bystanderId,
        bystanderData: BYSTANDER_RECORD_DATA,
      };
      await testInfo.attach('opik.editableRecordDataset', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      /** Datasets don't cascade with project deletion — explicit delete required. */
      if (!shouldLeaveArtifacts(testInfo)) {
        try {
          await backendClient.deleteDataset(created.id);
        } catch (err) {
          console.warn(`[editableRecordDataset fixture] delete warning for ${name}:`, err);
        }
      }
    }
  },
});

export { expect };
