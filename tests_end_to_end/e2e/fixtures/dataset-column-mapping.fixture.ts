import { test as baseTest, expect } from './editable-record-dataset.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/**
 * The trace the Add-to-dataset dialog maps from. Two keys under `input` on
 * purpose: it is what makes "the item carries the trace's whole input" and
 * "the item carries `input.extra_field`" two different answers, so a mapping
 * that silently read the section instead of the leaf is visible.
 */
export const DCM_TRACE_INPUT = {
  question: 'dcm-trace-question',
  extra_field: 'dcm-trace-extra-field',
} as const;
export const DCM_TRACE_OUTPUT = { answer: 'dcm-trace-output-answer' } as const;

/**
 * The non-reserved column the target dataset already has.
 *
 * `useFieldMappings` filters its "existing" rows against the fixed fields
 * (`input`, `expected_output`) and every quick-add chip key (`metadata`,
 * `tags`, `spans`, `usage`, `comments`, `feedback_scores`), so a column named
 * any of those would never be listed — which is the discrimination the seed
 * item's `input` key below is for.
 */
export const DCM_EXISTING_COLUMN = 'custom_col';

/**
 * The dataset's one pre-existing record.
 *
 * It carries BOTH a non-reserved column and a reserved one. The dialog must
 * list `custom_col` under Additional fields and must NOT list `input` there —
 * `input` already has a fixed row of its own, and a duplicate would send two
 * mappings for one field.
 */
export const DCM_SEED_ITEM_DATA: Record<string, unknown> = {
  input: 'dcm-seed-item-input',
  [DCM_EXISTING_COLUMN]: 'dcm-seed-item-custom-col',
};

export interface DatasetColumnMappingRef {
  traceId: string;
  traceName: string;
  /** The project's only `DATASET`-type dataset, so the dialog selects it itself. */
  datasetId: string;
  datasetName: string;
  /** The pre-existing record: the bystander the dialog's write must not touch. */
  seedItemId: string;
  seedItemData: Record<string, unknown>;
}

export interface DatasetColumnMappingFixtures {
  datasetColumnMapping: DatasetColumnMappingRef;
}

/**
 * One trace and one NON-empty dataset in the same project — the shape the
 * Add-to-dataset dialog's "existing dataset column" branch needs and that no
 * other fixture provides.
 *
 * `fieldMappingSeed` cannot stand in: its datasets are deliberately EMPTY, so
 * `useDatasetColumns` answers with no columns and `useFieldMappings.existingRows`
 * is never populated in any spec that uses it. The branch under test here is
 * the one that only exists when the target dataset already has fields.
 *
 * The dataset holds exactly one record, which is also the bystander for the
 * dialog's write: a create-from-traces that rewrote or dropped the rows already
 * in the dataset would otherwise satisfy every assertion about the row it
 * added.
 *
 * Seeded with no tags, no metadata and no spans. Enrichment drops the sections
 * a trace has no values for, so this is what makes the created item's key set
 * decided by the seed rather than by whatever the backend happened to roll up —
 * and the key set is compared exactly.
 */
export const test = baseTest.extend<DatasetColumnMappingFixtures>({
  datasetColumnMapping: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const traceName = `${testNamespace}-dcm-trace`;
    const datasetName = `${testNamespace}-dcm-ds`;
    const seedItemId = uuid7();

    let traceId: string | null = null;
    let datasetId: string | null = null;

    try {
      const trace = await sdkClient.python.createNestedTrace({
        project_name: project.name,
        name: traceName,
        input: { ...DCM_TRACE_INPUT },
        output: { ...DCM_TRACE_OUTPUT },
        spans: [],
      });
      traceId = trace.id;

      // What the trace actually HOLDS, compared by value. The spec's expected
      // item is built from DCM_TRACE_INPUT/OUTPUT, so a bridge that dropped
      // `extra_field` would leave the mapped assertion comparing `undefined`
      // with `undefined`.
      const stored = await backendClient.getTracePayload(trace.id);
      expect(
        stored?.input,
        `[datasetColumnMapping] trace ${traceName} stored the input the mappings read from`,
      ).toEqual(DCM_TRACE_INPUT);
      expect(
        stored?.output,
        `[datasetColumnMapping] trace ${traceName} stored the output the mappings read from`,
      ).toEqual(DCM_TRACE_OUTPUT);

      const dataset = await sdkClient.python.createDataset({
        project_name: project.name,
        name: datasetName,
        description: `dataset with its own column, seeded by ${testInfo.title}`,
      });
      datasetId = dataset.id;

      await backendClient.writeDatasetItemsBatch({
        datasetId: dataset.id,
        batchGroupId: crypto.randomUUID(),
        items: [{ id: seedItemId, data: DCM_SEED_ITEM_DATA }],
      });

      // The precondition the whole spec rests on: the dialog lists an existing
      // row only for a column this endpoint reports. Asserted as the WHOLE
      // column set rather than by membership — a dataset that had also derived
      // a third column would change which rows the dialog renders, and the
      // spec compares those exactly.
      const columns = await backendClient.listDatasetItemColumns(dataset.id);
      expect(
        columns.map((column) => column.name),
        `[datasetColumnMapping] ${datasetName} derives exactly the seeded columns`,
      ).toEqual(['custom_col', 'input']);

      const ref: DatasetColumnMappingRef = {
        traceId: trace.id,
        traceName: trace.name,
        datasetId: dataset.id,
        datasetName: dataset.name,
        seedItemId,
        seedItemData: DCM_SEED_ITEM_DATA,
      };
      await testInfo.attach('opik.datasetColumnMapping', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo)) {
        // Swallow-and-warn, never rethrow: a delete that failed here would
        // replace whichever seeding or assertion error actually broke the test.
        // Datasets do not cascade with project deletion, and neither do traces.
        if (datasetId) {
          try {
            await backendClient.deleteDataset(datasetId);
          } catch (err) {
            console.warn(`[datasetColumnMapping fixture] delete warning for ${datasetName}:`, err);
          }
        }
        if (traceId) {
          try {
            await backendClient.deleteTraces([traceId]);
          } catch (err) {
            console.warn(`[datasetColumnMapping fixture] delete warning for ${traceName}:`, err);
          }
        }
      }
    }
  },
});

export { expect };
