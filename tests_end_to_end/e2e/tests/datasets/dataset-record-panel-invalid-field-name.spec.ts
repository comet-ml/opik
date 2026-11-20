import { test, expect, EDITABLE_RECORD_DATA, BYSTANDER_RECORD_DATA } from '@e2e/fixtures';
import { DatasetsPage } from '@e2e/pom/datasets.page';
import type { DatasetItemRef } from '@e2e/core/backend';
import type { RenderedFieldRow } from '@e2e/pom/dataset-items.page';

/**
 * What the record editor opik#9457 shipped does to a field whose NAME it cannot
 * accept — blank, already used, or over the 150-character limit.
 *
 * `fieldRowsToData` is documented to SKIP a row with an invalid name, so that
 * "an autosaving editor keeps persisting the valid fields while one is
 * mid-rename". The add panel gates its submit on the same errors
 * (`canSubmit = fieldCount > 0 && !hasErrors`); the EDIT panel has no gate at
 * all, so whatever the skip produced is what `Save changes` commits. The field
 * is therefore not left alone — it is dropped from the stored record, behind a
 * success toast.
 *
 * The duplicate case is the worst of the three and is deterministic rather than
 * unlucky. `getFieldRowErrors` walks the rows in order and the FIRST claimant of
 * a name keeps it; `dataToFieldRows` renders them in the dataset's column order,
 * which the items endpoint returns alphabetically. So renaming a field to a name
 * an EARLIER row already holds flags the earlier row — the one the user never
 * touched — and that row is the one skipped. A rename of one field destroys the
 * value of another.
 *
 * ## What these tests assert, and why it is implementation-neutral
 *
 * Each one asserts the stored record is EXACTLY what it was seeded with. That
 * holds under either shape a fix could take: leaving an un-representable row as
 * it was loaded, or refusing the commit while an error stands. A fix of the
 * second kind would fail these tests at the commit step rather than at the
 * comparison — a failure that means "fixed, re-express me", and the reason the
 * commit is driven through the real control instead of being asserted as
 * refused.
 *
 * Verified on staging for 2.2.149: all three reproduce. Each is marked
 * `test.fail()` individually, never on the describe block, so the three stay
 * separable and so a fourth failure beside them could not hide.
 *
 * Nothing here overlaps `dataset-record-panel-field-edits.spec.ts`, which
 * covers the VALID rename and the type-preservation round trip, or
 * `dataset-items.spec.ts`, which retypes `input` and reads only `data.input`
 * back.
 */

/** The dataset's records keyed by id, so an assertion can name the one it means. */
const byId = (items: DatasetItemRef[]): Map<string, Record<string, unknown>> =>
  new Map(items.map((item) => [item.id, item.data]));

/**
 * What the panel renders in a row's value box for a stored value.
 *
 * Mirrors `toFieldRow` in `datasetFieldRows.ts`: `null` and `undefined` become
 * the empty string, an object is pretty-printed JSON, everything else is
 * `String(value)`. Mirrored rather than approximated because the whole panel is
 * compared as one array — `42` must read "42" and `null` must read "", and a
 * row that came back holding the JSON text of a number would be exactly the
 * regression the sibling spec covers.
 */
const panelText = (value: unknown): string => {
  if (value === null || value === undefined) return '';
  if (typeof value === 'object') return JSON.stringify(value, null, 2);
  return String(value);
};

/**
 * The order the panel lists the seeded record's rows in.
 *
 * `dataToFieldRows` takes the dataset's schema columns first and the items
 * endpoint returns those alphabetically, so for a record whose keys ARE the
 * schema that is the sorted key list. Derived rather than written out, so the
 * expectation cannot drift from the fixture's own seed.
 */
const PANEL_ROW_ORDER: string[] = Object.keys(EDITABLE_RECORD_DATA).sort();

/** The panel as it should look for an untouched seeded record. */
const seededPanelRows = (): RenderedFieldRow[] =>
  PANEL_ROW_ORDER.map((name) => ({
    name,
    value: panelText(EDITABLE_RECORD_DATA[name]),
    message: null,
  }));

/** The same, with one row's name replaced and one row carrying a message. */
const panelRowsWith = (
  changes: Record<string, Partial<RenderedFieldRow>>,
): RenderedFieldRow[] =>
  seededPanelRows().map((row) => ({ ...row, ...(changes[row.name] ?? {}) }));

/** `MAX_FIELD_NAME_LENGTH` in `constants/datasets.ts`, and the limit the message names. */
const MAX_FIELD_NAME_LENGTH = 150;

test.describe(
  'Dataset records — an invalid field name in the record panel',
  { tag: ['@area:datasets'] },
  () => {
    /** Items toolbar collapses to icon-only (no accessible name) below ~850px container width. */
    test.use({ viewport: { width: 1600, height: 900 } });

    test(
      'Clearing a field\'s name does not delete the field',
      { tag: ['@t2-cuj', '@cap:datasets.edit-item-versions'] },
      async ({ editableRecordDataset, project, backendClient, page }) => {
        // QA: failing on staging for 2.2.149 (opik#9457). Clearing the `score`
        // row's NAME box and committing stores the record WITHOUT `score` — the
        // field is gone, behind a "New version created" toast. Product:
        // `fieldRowsToData` skips the row it cannot name and the edit panel has
        // no error gate on its commit, so the skip reads as a deletion.
        // Remove test.fail() once this passes; Playwright reports it when it does.
        test.fail();

        const items = await test.step('Open the dataset\'s Records tab', async () => {
          const datasets = new DatasetsPage(page);
          await datasets.goto(project.id);
          await datasets.waitForReady();
          const itemsPage = await datasets.openDatasetByName(editableRecordDataset.name);
          await itemsPage.waitForReady();
          await expect(itemsPage.itemRows(), 'both seeded records render').toHaveCount(2);
          return itemsPage;
        });

        await test.step('Clear the `score` row\'s name', async () => {
          await items.openItemEditorById(editableRecordDataset.subjectId);
          await items.renameItemField('score', '');
        });

        await test.step('The panel says the name is required, and says it on that row alone', async () => {
          // The whole panel, compared as one array. A per-row check would pass
          // over an error that had also landed somewhere else, and WHICH row
          // carries the error is the mechanism by which a field gets dropped.
          expect(
            await items.readItemEditorRows(),
            'only the row whose name was cleared is flagged',
          ).toEqual(
            panelRowsWith({ score: { name: '', message: 'Field name is required' } }),
          );
          await expect(items.draftBadge(), 'and the edit is staged as a draft').toBeVisible();
        });

        await test.step('Commit the draft as a new version', async () => {
          await items.closeItemEditor();
          await items.commitDraft();
          await expect(items.itemRows(), 'neither record was added nor dropped').toHaveCount(2);
        });

        await test.step('The stored record still carries `score`, with the number it held', async () => {
          const stored = byId(await backendClient.listDatasetItemsWithData(editableRecordDataset.id));
          expect(stored.size, 'the dataset still holds exactly two records').toBe(2);
          // One object comparison rather than a `score` lookup: a commit that
          // dropped a DIFFERENT field, or renamed one, is the same class of
          // defect and must not pass here.
          expect(
            stored.get(editableRecordDataset.subjectId),
            'a name the editor could not accept left the record alone',
          ).toEqual(EDITABLE_RECORD_DATA);
          expect(
            stored.get(editableRecordDataset.bystanderId),
            'the record the test never opened is untouched',
          ).toEqual(BYSTANDER_RECORD_DATA);
        });
      },
    );

    test(
      'Renaming a field to a name another row already holds does not overwrite that row',
      { tag: ['@t2-cuj', '@cap:datasets.edit-item-versions'] },
      async ({ editableRecordDataset, project, backendClient, page }) => {
        // QA: failing on staging for 2.2.149 (opik#9457). Renaming
        // `expected_output` to `input` stores
        // `{input: <expected_output's value>, …}` with `expected_output` gone
        // and the real `input` value destroyed — one rename losing two fields'
        // worth of data. Product: `getFieldRowErrors` gives the name to the
        // first claimant in render order, which is the RENAMED row, so the
        // error lands on the untouched `input` row and `fieldRowsToData` skips
        // that one. Remove test.fail() once this passes.
        test.fail();

        const items = await test.step('Open the dataset\'s Records tab', async () => {
          const datasets = new DatasetsPage(page);
          await datasets.goto(project.id);
          await datasets.waitForReady();
          const itemsPage = await datasets.openDatasetByName(editableRecordDataset.name);
          await itemsPage.waitForReady();
          await expect(itemsPage.itemRows(), 'both seeded records render').toHaveCount(2);
          return itemsPage;
        });

        await test.step('Rename `expected_output` to `input`, which `input` already holds', async () => {
          await items.openItemEditorById(editableRecordDataset.subjectId);
          await items.renameItemField('expected_output', 'input');
        });

        await test.step('The duplicate is flagged on the row the user never touched', async () => {
          // The precise finding, and the reason the stored record loses the
          // wrong field: both rows now read `input`, and it is the SECOND —
          // still holding the record's real input value — that is flagged and
          // therefore skipped. Comparing the whole panel is what says which,
          // since the two rows are otherwise indistinguishable by name.
          expect(
            await items.readItemEditorRows(),
            'the renamed row keeps the name and the original `input` row is flagged',
          ).toEqual(
            panelRowsWith({
              expected_output: { name: 'input' },
              input: { message: 'Field name is already used' },
            }),
          );
          await expect(items.draftBadge(), 'and the edit is staged as a draft').toBeVisible();
        });

        await test.step('Commit the draft as a new version', async () => {
          await items.closeItemEditor();
          await items.commitDraft();
          await expect(items.itemRows(), 'neither record was added nor dropped').toHaveCount(2);
        });

        await test.step('The stored record is unchanged — `input` keeps its own value', async () => {
          const stored = byId(await backendClient.listDatasetItemsWithData(editableRecordDataset.id));
          expect(stored.size, 'the dataset still holds exactly two records').toBe(2);
          expect(
            stored.get(editableRecordDataset.subjectId),
            'a rename the editor refused changed nothing, and destroyed nothing',
          ).toEqual(EDITABLE_RECORD_DATA);
          expect(
            stored.get(editableRecordDataset.bystanderId),
            'the record the test never opened is untouched',
          ).toEqual(BYSTANDER_RECORD_DATA);
        });
      },
    );

    test(
      'Renaming a field to an over-long name does not delete the field',
      { tag: ['@t2-cuj', '@cap:datasets.edit-item-versions'] },
      async ({ editableRecordDataset, project, backendClient, page }) => {
        // QA: failing on staging for 2.2.149 (opik#9457). The same defect as
        // the blank name in its third dress: the panel names the limit and the
        // commit stores the record without `score`. Kept as its own test rather
        // than folded in because it is a DIFFERENT rung of `getFieldRowErrors`
        // — the length check, added by this PR with `MAX_FIELD_NAME_LENGTH` —
        // and a fix that gated only the blank case would leave this one
        // shipping. Remove test.fail() once this passes.
        test.fail();

        const overLongName = 'x'.repeat(MAX_FIELD_NAME_LENGTH + 1);

        const items = await test.step('Open the dataset\'s Records tab', async () => {
          const datasets = new DatasetsPage(page);
          await datasets.goto(project.id);
          await datasets.waitForReady();
          const itemsPage = await datasets.openDatasetByName(editableRecordDataset.name);
          await itemsPage.waitForReady();
          await expect(itemsPage.itemRows(), 'both seeded records render').toHaveCount(2);
          return itemsPage;
        });

        await test.step(`Rename \`score\` to a ${overLongName.length}-character name`, async () => {
          await items.openItemEditorById(editableRecordDataset.subjectId);
          await items.renameItemField('score', overLongName);
        });

        await test.step('The panel names the limit, on that row alone', async () => {
          expect(
            await items.readItemEditorRows(),
            'only the over-long row is flagged',
          ).toEqual(
            panelRowsWith({
              score: {
                name: overLongName,
                message: `Field name must be ${MAX_FIELD_NAME_LENGTH} characters or fewer`,
              },
            }),
          );
          await expect(items.draftBadge(), 'and the edit is staged as a draft').toBeVisible();
        });

        await test.step('Commit the draft as a new version', async () => {
          await items.closeItemEditor();
          await items.commitDraft();
          await expect(items.itemRows(), 'neither record was added nor dropped').toHaveCount(2);
        });

        await test.step('The stored record still carries `score`, with the number it held', async () => {
          const stored = byId(await backendClient.listDatasetItemsWithData(editableRecordDataset.id));
          expect(stored.size, 'the dataset still holds exactly two records').toBe(2);
          expect(
            stored.get(editableRecordDataset.subjectId),
            'a name the editor could not accept left the record alone',
          ).toEqual(EDITABLE_RECORD_DATA);
          expect(
            stored.get(editableRecordDataset.bystanderId),
            'the record the test never opened is untouched',
          ).toEqual(BYSTANDER_RECORD_DATA);
        });
      },
    );
  },
);
