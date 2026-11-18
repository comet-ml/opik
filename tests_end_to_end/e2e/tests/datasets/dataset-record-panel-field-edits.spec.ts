import { test, expect, EDITABLE_RECORD_DATA, BYSTANDER_RECORD_DATA } from '@e2e/fixtures';
import { DatasetsPage } from '@e2e/pom/datasets.page';
import type { DatasetItemRef } from '@e2e/core/backend';

/**
 * The one add/edit record panel opik#9457 put behind the Records tab, and the
 * two things it can now do that nothing in the estate reached: edit a field
 * whose value is not a string, and change the record's FIELD NAMES.
 *
 * Before this release the editor rendered a fixed row per key and nothing else
 * — no rename, no add, no remove — so both tests below are about behaviour that
 * did not exist to break. `dataset-items.spec.ts` already commits an edit as a
 * new version, but it only ever retypes `input` and only ever reads
 * `data.input` back, so neither half is covered by it.
 *
 * Both failures are silent in the direction that matters, which is why each
 * test reads the record back through the API rather than stopping at the grid.
 *
 *  - The panel renders every field as TEXT. A number, a `null`, a boolean and a
 *    nested object survive an edit elsewhere in the record only because
 *    `toRawValue` returns the value the row was loaded with whenever its text
 *    is unchanged. A regression there stores `"42"` where `42` was, and the
 *    record still looks entirely ordinary in the grid, in the panel, and in the
 *    item payload.
 *  - A field introduced by an UNCOMMITTED draft has no column of its own in the
 *    items endpoint's answer — the API reports the saved version's columns — so
 *    `useColumnsWithDraftKeys` merging the draft's keys in is the only reason
 *    the new field is visible at all. That one is invisible in the API by
 *    construction and shows up on the page or nowhere.
 *
 * Both tests edit ONE of the dataset's two records and assert the other is
 * untouched, by id and by value. Committing a draft posts a delta; "the record
 * I edited is right" would be satisfied just as well by a commit that rewrote
 * every record in the version.
 */

/** The dataset's records keyed by id, so an assertion can name the one it means. */
const byId = (items: DatasetItemRef[]): Map<string, Record<string, unknown>> =>
  new Map(items.map((item) => [item.id, item.data]));

test.describe('Dataset records — editing fields in the record panel', { tag: ['@area:datasets'] }, () => {
  /** Items toolbar collapses to icon-only (no accessible name) below ~850px container width. */
  test.use({ viewport: { width: 1600, height: 900 } });

  test(
    'Editing one string field leaves the record\'s number, null, boolean and object fields as they were',
    { tag: ['@t2-cuj', '@cap:datasets.edit-item-versions'] },
    async ({ editableRecordDataset, project, backendClient, page }) => {
      const editedInput = 'edited-through-the-record-panel';

      const items = await test.step('Open the dataset\'s Records tab', async () => {
        const datasets = new DatasetsPage(page);
        await datasets.goto(project.id);
        await datasets.waitForReady();
        const itemsPage = await datasets.openDatasetByName(editableRecordDataset.name);
        await itemsPage.waitForReady();
        await expect(itemsPage.itemRows(), 'both seeded records render').toHaveCount(2);
        return itemsPage;
      });

      await test.step('Change only the `input` field of one record and commit the draft', async () => {
        await items.openItemEditorById(editableRecordDataset.subjectId);
        await items.editItemField('input', editedInput);
        await items.closeItemEditor();
        await items.commitDraft();
        await expect(items.versionLabel(2), 'the commit cut v2').toBeVisible();
        await expect(items.itemRows(), 'and neither record was added nor dropped').toHaveCount(2);
      });

      await test.step('The grid shows the new value, and the untouched fields unchanged', async () => {
        const { subjectId } = editableRecordDataset;
        await expect(items.itemCell(subjectId, 'input')).toHaveText(editedInput);
        // Cell by cell rather than row-level text: a row assertion would pass
        // with two fields rendered into one cell and a third missing. The
        // object renders as the grid's own JSON, which is why the API read
        // below is what actually pins its shape.
        await expect(items.itemCell(subjectId, 'score')).toHaveText('42');
        await expect(items.itemCell(subjectId, 'flag')).toHaveText('true');
        await expect(items.itemCell(subjectId, 'expected_output')).toHaveText(
          String(EDITABLE_RECORD_DATA.expected_output),
        );
      });

      await test.step('The stored record is the seed with exactly one field changed', async () => {
        const stored = byId(await backendClient.listDatasetItemsWithData(editableRecordDataset.id));
        expect(stored.size, 'the dataset still holds exactly two records').toBe(2);

        // The whole record, compared as one object. Asserting field by field
        // would pass over a record that had also GAINED or LOST a key — and
        // losing one is precisely what an editor that drops a row it could not
        // represent as text would do.
        expect(
          stored.get(editableRecordDataset.subjectId),
          'every field but `input` came back as the type and value it was seeded with',
        ).toEqual({ ...EDITABLE_RECORD_DATA, input: editedInput });

        expect(
          stored.get(editableRecordDataset.bystanderId),
          'the record the test never opened is untouched',
        ).toEqual(BYSTANDER_RECORD_DATA);
      });

      await test.step('The commit cut exactly one new version, counting one modified record', async () => {
        const versions = await backendClient.getDatasetVersions(editableRecordDataset.id);
        expect(versions.map((version) => version.versionName)).toEqual(['v2', 'v1']);
        const latest = versions[0];
        expect(latest.itemsTotal, 'v2 holds both records').toBe(2);
        // The discriminating pair: a commit that re-sent both records would
        // report two modified, and one that added a row instead of editing one
        // would report an addition.
        expect(latest.itemsModified, 'one record was modified').toBe(1);
        expect(latest.itemsAdded, 'and none was added').toBe(0);
        expect(latest.itemsDeleted, 'and none was deleted').toBe(0);
      });
    },
  );

  test(
    'Renaming a field and adding one shows both as columns while the draft is uncommitted, and commits them',
    { tag: ['@t2-cuj', '@cap:datasets.edit-item-versions'] },
    async ({ editableRecordDataset, project, backendClient, page }) => {
      const renamedField = 'renamed_output';
      const newField = 'brand_new';
      const newValue = 'brand-new-field-value';

      const items = await test.step('Open the dataset\'s Records tab', async () => {
        const datasets = new DatasetsPage(page);
        await datasets.goto(project.id);
        await datasets.waitForReady();
        const itemsPage = await datasets.openDatasetByName(editableRecordDataset.name);
        await itemsPage.waitForReady();
        await expect(itemsPage.itemRows(), 'both seeded records render').toHaveCount(2);
        return itemsPage;
      });

      await test.step('Neither field exists as a column before the edit', async () => {
        // The precondition the draft-column assertion rests on. Without it,
        // "the columns are there" would be satisfied by a grid that had always
        // rendered them.
        await expect(items.itemColumnHeader(renamedField)).toHaveCount(0);
        await expect(items.itemColumnHeader(newField)).toHaveCount(0);
        await expect(
          items.itemColumnHeader('expected_output'),
          'and the field about to be renamed does',
        ).toHaveCount(1);
      });

      await test.step('Rename `expected_output` and add a new field, without committing', async () => {
        await items.openItemEditorById(editableRecordDataset.subjectId);
        await items.renameItemField('expected_output', renamedField);
        await items.addItemField(newField, newValue);
        await items.closeItemEditor();
      });

      await test.step('Both new fields are columns while the draft is still uncommitted', async () => {
        // `useColumnsWithDraftKeys`, and the only place it is observable. The
        // draft badge is asserted first so this cannot pass against a page
        // that had quietly committed.
        await expect(items.draftBadge(), 'the edit is still a draft').toBeVisible();
        await expect(items.itemColumnHeader(renamedField)).toHaveCount(1);
        await expect(items.itemColumnHeader(newField)).toHaveCount(1);
        await expect(
          items.itemCell(editableRecordDataset.subjectId, newField),
          'with the draft value in the edited record\'s cell',
        ).toHaveText(newValue);
      });

      await test.step('Commit the draft as a new version', async () => {
        await items.commitDraft();
        await expect(items.versionLabel(2), 'the commit cut v2').toBeVisible();
        await expect(items.itemRows(), 'and neither record was added nor dropped').toHaveCount(2);
      });

      await test.step('The stored record carries the renamed and new fields, and lost the old key', async () => {
        const stored = byId(await backendClient.listDatasetItemsWithData(editableRecordDataset.id));
        expect(stored.size, 'the dataset still holds exactly two records').toBe(2);

        const { expected_output: oldValue, ...untouched } = EDITABLE_RECORD_DATA;
        // One object comparison carries all four claims the rename makes: the
        // renamed key holds the value the old one did, the added key
        // persisted, `expected_output` is gone rather than kept alongside its
        // new name, and the fields nobody touched — the number, the null, the
        // boolean, the nested object — still hold their own types.
        expect(
          stored.get(editableRecordDataset.subjectId),
          'the renamed field carries the old value and the old key is gone',
        ).toEqual({ ...untouched, [renamedField]: oldValue, [newField]: newValue });

        expect(
          stored.get(editableRecordDataset.bystanderId),
          'the record the test never opened keeps its own `expected_output`',
        ).toEqual(BYSTANDER_RECORD_DATA);
      });

      await test.step('The committed version derives both new columns, and keeps the bystander\'s', async () => {
        // The columns the items endpoint now reports — the saved-version answer
        // the draft merge was standing in for. `expected_output` is still among
        // them BECAUSE the bystander record still carries it, which is what
        // separates "the rename moved one record's key" from "the rename
        // dropped the column".
        const columns = await backendClient.listDatasetItemColumns(editableRecordDataset.id);
        expect(columns.map((column) => column.name)).toEqual([
          newField,
          'expected_output',
          'flag',
          'input',
          'meta',
          'note',
          renamedField,
          'score',
        ]);
        expect(
          columns.find((column) => column.name === 'score')?.types,
          '`score` is still typed as a number after the commit',
        ).toEqual(['number']);
      });
    },
  );
});
