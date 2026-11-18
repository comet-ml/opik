import {
  test,
  expect,
  DCM_TRACE_INPUT,
  DCM_TRACE_OUTPUT,
  DCM_EXISTING_COLUMN,
  DCM_SEED_ITEM_DATA,
} from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * The Add-to-dataset dialog's Advanced mapping against a dataset that ALREADY
 * HAS COLUMNS — the branch of opik#9457's mapping section that no spec reaches.
 *
 * `dataset-item-field-mappings.spec.ts` drives the same dialog, but every
 * dataset its fixture seeds is empty on purpose, so `useDatasetColumns` answers
 * with no columns and `useFieldMappings.existingRows` is never populated by any
 * test in the estate. Three behaviours live only in that branch:
 *
 *  - a column the dataset already has is listed under "Additional fields" as a
 *    row the user may map or leave empty, but may not remove or rename;
 *  - "Leave unmapped" takes such a row back to no mapping at all, which is a
 *    different request from one that never mapped it;
 *  - re-pointing an Opik-managed row (here `metadata`) at a trace path both
 *    sends a mapping for it AND switches Opik's own enrichment for it off.
 *
 * The last one is the silent-wrongness risk. A dialog that sent the mapping but
 * left `include_metadata: true` would have the backend write its enrichment
 * over the mapped value, or the other way about, and the created item would
 * look entirely plausible either way — right number of rows, right columns, no
 * error anywhere. So the submitted request is recorded and compared, and the
 * stored item is read back: the request says what the dialog asked for, the
 * item says what was done, and only together do they catch the two disagreeing.
 *
 * The dataset's pre-existing record is the bystander. Asserting the created
 * item alone would pass just as well against a write that rewrote or dropped
 * the rows that were already there.
 */

/** `POST .../items/from-traces`, as the dialog sent it. */
interface FromTracesRequest {
  trace_ids?: string[];
  enrichment_options?: Record<string, unknown>;
  field_mappings?: Record<string, string>;
}

test.describe(
  'Add to dataset — a dataset\'s own columns in Advanced mapping',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'An existing dataset column maps, unmaps and remaps, and a re-pointed managed field replaces its enrichment',
      { tag: ['@cap:traces.add-trace-to-dataset'] },
      async ({ datasetColumnMapping, project, backendClient, page }) => {
        const logs = new LogsPage(page);

        /**
         * Every from-traces POST the page issues, recorded from before the
         * first click. The read-back below says what the backend stored; it
         * cannot say what the dialog asked for, and `include_metadata: false`
         * is only observable here — a request that left it true and a backend
         * that let the mapping win produce the same item.
         */
        const writes: FromTracesRequest[] = [];
        page.on('request', (request) => {
          if (request.method() !== 'POST') return;
          if (!new URL(request.url()).pathname.endsWith('/items/from-traces')) return;
          writes.push(request.postDataJSON() as FromTracesRequest);
        });

        const dialog = await test.step('Select the seeded trace and open Add to → Dataset', async () => {
          await logs.goto(project.id);
          await logs.waitForReady();
          await logs.selectTrace(datasetColumnMapping.traceId);
          return logs.openAddToDataset();
        });

        await test.step('The dialog targets the seeded dataset', async () => {
          // The project holds exactly one DATASET-type dataset, so the dialog
          // selects it on its own. Asserted rather than assumed: every
          // assertion below is about what landed in THAT dataset.
          await dialog.expectDatasetSelected(datasetColumnMapping.datasetName);
        });

        await test.step('Advanced mapping lists the dataset\'s own column, and only that one', async () => {
          await dialog.enableAdvancedMapping();

          const existing = dialog.existingMappingRow(DCM_EXISTING_COLUMN);
          await expect(
            existing,
            `"${DCM_EXISTING_COLUMN}" is listed as one of the dataset's own columns`,
          ).toHaveCount(1);
          await expect(
            dialog.rowSource(existing),
            'and starts unmapped — the dialog cannot guess which trace field it holds',
          ).toHaveText('Not mapped');

          // `input` is a column of this dataset too, and must NOT be listed
          // here: it already has a fixed row, and a second row for it would
          // send two mappings for one field. This is the assertion that makes
          // the one above about a FILTERED list rather than about every column.
          await expect(
            dialog.existingMappingRow('input'),
            'a column that already has a fixed row is not listed again',
          ).toHaveCount(0);
          await expect(dialog.mappingRowSource('input')).toHaveText('input');
          await expect(dialog.mappingRowSource('expected_output')).toHaveText('output');
        });

        await test.step('The dataset\'s own column can be neither removed nor renamed', async () => {
          const existing = dialog.existingMappingRow(DCM_EXISTING_COLUMN);
          // Rendered, and rendered disabled. `FieldRowShell` draws the same
          // button either way, so "disabled" is a state to assert rather than
          // a button to find missing.
          await expect(
            dialog.rowRemoveButton(existing, DCM_EXISTING_COLUMN),
            'the remove control is present but refused',
          ).toBeDisabled();
          // A custom row renders its name as an `<input>`; this one renders it
          // as static text, so the absence of a textbox in the row is what
          // says the name cannot be edited.
          await expect(
            existing.getByRole('textbox'),
            'and the name is not an editable box',
          ).toHaveCount(0);
          await expect(existing).toContainText(DCM_EXISTING_COLUMN);
        });

        await test.step('Mapping, leaving unmapped, and mapping again all take', async () => {
          const existing = dialog.existingMappingRow(DCM_EXISTING_COLUMN);
          await dialog.setRowSource(existing, DCM_EXISTING_COLUMN, 'input.extra_field', ['input']);
          // The round trip through "Leave unmapped" is the point: a dialog that
          // treated clearing as a no-op would leave the row mapped, and the
          // re-map below would then pass without the clear having worked.
          await dialog.leaveRowUnmapped(existing, DCM_EXISTING_COLUMN);
          await dialog.setRowSource(existing, DCM_EXISTING_COLUMN, 'input.extra_field', ['input']);
        });

        await test.step('Re-point Opik\'s `metadata` row at the trace\'s output.answer', async () => {
          await dialog.remapManagedRow('includeMetadata', 'metadata', 'output.answer', ['output']);
        });

        await test.step('Submitting sends exactly the mappings the rows showed, with metadata enrichment off', async () => {
          await dialog.submit(1);

          expect(writes, 'the dialog issued exactly one from-traces write').toHaveLength(1);
          const [sent] = writes;
          expect(sent.trace_ids, 'for the selected trace alone').toEqual([
            datasetColumnMapping.traceId,
          ]);
          // The whole mapping object, not a lookup in it. A dialog that also
          // sent a stale mapping for a row the user cleared — or a second one
          // for `input` — would satisfy any per-key check.
          expect(sent.field_mappings, 'the mappings the rows were showing').toEqual({
            input: 'input',
            expected_output: 'output',
            [DCM_EXISTING_COLUMN]: 'input.extra_field',
            metadata: 'output.answer',
          });
          // And the other half of the re-point. Every other option is still on,
          // which is what makes `include_metadata` having flipped a consequence
          // of the re-point rather than of the dialog's defaults.
          expect(sent.enrichment_options, 'only the re-pointed field\'s enrichment was switched off').toEqual({
            include_spans: true,
            include_tags: true,
            include_feedback_scores: true,
            include_comments: true,
            include_usage: true,
            include_metadata: false,
          });
        });

        await test.step('The created item holds the mapped values, and the dataset\'s own record is untouched', async () => {
          await expect
            .poll(
              async () =>
                (await backendClient.listDatasetItemsWithData(datasetColumnMapping.datasetId))
                  .length,
              {
                message:
                  'the dataset to hold the seeded record plus the one this add created — a ' +
                  'timeout here is the item write still becoming queryable, not the mapping ' +
                  'having written the wrong thing',
                timeout: 60_000,
                intervals: [500, 1_000, 2_000],
              },
            )
            .toBe(2);

          const items = await backendClient.listDatasetItemsWithData(
            datasetColumnMapping.datasetId,
          );
          const seeded = items.filter((item) => item.id === datasetColumnMapping.seedItemId);
          const created = items.filter((item) => item.id !== datasetColumnMapping.seedItemId);
          expect(seeded, 'the record that was already there is still there').toHaveLength(1);
          expect(
            seeded[0].data,
            'with the values it was seeded with',
          ).toEqual(DCM_SEED_ITEM_DATA);
          expect(created, 'and the write added exactly one record').toHaveLength(1);

          // Compared as one object: this says the mapped column holds the
          // leaf it was pointed at, the re-pointed `metadata` holds the
          // trace's answer rather than the trace's metadata section, `input`
          // still holds the WHOLE trace input (so the leaf mapping added a
          // field rather than replacing the section it read from), and nothing
          // else was written — no `tags`, no `spans`, no enrichment section the
          // seed trace had no values for.
          expect(created[0].data, 'the item the dialog described').toEqual({
            input: { ...DCM_TRACE_INPUT },
            expected_output: { ...DCM_TRACE_OUTPUT },
            [DCM_EXISTING_COLUMN]: DCM_TRACE_INPUT.extra_field,
            metadata: DCM_TRACE_OUTPUT.answer,
          });
        });
      },
    );
  },
);
