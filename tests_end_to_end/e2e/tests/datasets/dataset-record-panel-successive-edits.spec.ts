import { test, expect, EDITABLE_RECORD_DATA, BYSTANDER_RECORD_DATA } from '@e2e/fixtures';
import { DatasetsPage } from '@e2e/pom/datasets.page';
import type { DatasetItemRef } from '@e2e/core/backend';

/**
 * Editing the SAME record twice without leaving the page — the half of
 * opik#9457's record editor that no spec in the estate reaches, because every
 * one of them edits a record once and stops.
 *
 * `DatasetItemFieldsAutosaveForm` seeds its rows once per record id, on purpose:
 * re-seeding from the store on every autosave "would reset ids and drop focus
 * mid-typing". The item read behind the panel is not refreshed after a commit,
 * so a second visit to the same record in the same page session rebuilds `data`
 * from the snapshot the FIRST visit loaded. The panel shows the pre-edit value
 * while the grid cell beside it shows the new one, and committing any other
 * field writes the whole stale object back — silently reverting the earlier
 * edit.
 *
 * ## Why two tests
 *
 * The second is the same sequence with a page RELOAD between the two commits,
 * and it is what makes the first one's failure mean something specific. A
 * reload re-mounts the form against a fresh read, so if the control also failed
 * the finding would be "committing two edits loses one" — a far broader claim,
 * and one that would point at the version-commit endpoint rather than at an
 * in-session snapshot. The pair localises it to the snapshot.
 *
 * Verified on staging for 2.2.149: the in-session test reproduces, the reload
 * control passes.
 *
 * Both tests edit ONE of the dataset's two records and read the other back.
 * Committing a draft posts a delta, so "the record I edited is right" would be
 * satisfied just as well by a commit that rewrote every record in the version.
 */

/** The dataset's records keyed by id, so an assertion can name the one it means. */
const byId = (items: DatasetItemRef[]): Map<string, Record<string, unknown>> =>
  new Map(items.map((item) => [item.id, item.data]));

/** The first edit: a new `input`. */
const FIRST_EDIT = 'successive-edit-1-input';
/** The second edit, to a DIFFERENT field, which is what commits the stale snapshot. */
const SECOND_EDIT = 'successive-edit-2-expected-output';

test.describe(
  'Dataset records — two edits to one record in one page session',
  { tag: ['@area:datasets'] },
  () => {
    /** Items toolbar collapses to icon-only (no accessible name) below ~850px container width. */
    test.use({ viewport: { width: 1600, height: 900 } });

    test(
      'A second edit keeps the first, without a reload between them',
      { tag: ['@t2-cuj', '@cap:datasets.edit-item-versions'] },
      async ({ editableRecordDataset, project, backendClient, page }) => {
        // QA: failing on staging for 2.2.149 (opik#9457). Reopening a record
        // already edited in this page session shows its PRE-EDIT value — while
        // the grid cell beside it shows the new one — and committing a change
        // to any other field writes that stale snapshot back, reverting the
        // first edit. Product: `DatasetItemFieldsAutosaveForm` seeds its rows
        // once per record id and the item read behind it is not refreshed after
        // a commit. The reload control below passes, which is what localises it.
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

        await test.step('Edit `input` and commit it', async () => {
          await items.openItemEditorById(editableRecordDataset.subjectId);
          await items.editItemField('input', FIRST_EDIT);
          await items.closeItemEditor();
          await items.commitDraft();
          await expect(items.versionLabel(2), 'the first commit cut v2').toBeVisible();
        });

        await test.step('The first edit landed — the premise the rest rests on', async () => {
          // Asserted before the second edit, so a failure below is about the
          // SECOND commit rather than about a first one that never took.
          const stored = byId(await backendClient.listDatasetItemsWithData(editableRecordDataset.id));
          expect(
            stored.get(editableRecordDataset.subjectId),
            'the record now reads the first edit and nothing else changed',
          ).toEqual({ ...EDITABLE_RECORD_DATA, input: FIRST_EDIT });
        });

        await test.step('Reopening the same record shows the value it now holds', async () => {
          // No reload. The grid cell is asserted first and separately: it reads
          // from the server, so the two together say the panel and the page
          // disagree — which is a sharper statement than the panel being wrong
          // on its own, and is what a user actually sees.
          await expect(
            items.itemCell(editableRecordDataset.subjectId, 'input'),
            'the grid cell shows the committed value',
          ).toHaveText(FIRST_EDIT);

          await items.openItemEditorById(editableRecordDataset.subjectId);
          const rows = await items.readItemEditorRows();
          expect(
            rows.find((row) => row.name === 'input')?.value,
            'and so does the panel that reopened over it',
          ).toBe(FIRST_EDIT);
        });

        await test.step('Edit a different field and commit', async () => {
          await items.editItemField('expected_output', SECOND_EDIT);
          await items.closeItemEditor();
          await items.commitDraft();
          await expect(items.versionLabel(3), 'the second commit cut v3').toBeVisible();
        });

        await test.step('The record carries BOTH edits, and nothing else moved', async () => {
          const stored = byId(await backendClient.listDatasetItemsWithData(editableRecordDataset.id));
          expect(stored.size, 'the dataset still holds exactly two records').toBe(2);
          // One object comparison carries the whole claim: the second edit took,
          // the first was not reverted, and the number, null, boolean and
          // nested object the panel never touched kept their own types.
          expect(
            stored.get(editableRecordDataset.subjectId),
            'the second commit kept the first edit',
          ).toEqual({
            ...EDITABLE_RECORD_DATA,
            input: FIRST_EDIT,
            expected_output: SECOND_EDIT,
          });
          expect(
            stored.get(editableRecordDataset.bystanderId),
            'the record the test never opened is untouched',
          ).toEqual(BYSTANDER_RECORD_DATA);
        });
      },
    );

    test(
      'A second edit keeps the first when the page is reloaded between them',
      { tag: ['@t2-cuj', '@cap:datasets.edit-item-versions'] },
      async ({ editableRecordDataset, project, backendClient, page }) => {
        const items = await test.step('Open the dataset\'s Records tab', async () => {
          const datasets = new DatasetsPage(page);
          await datasets.goto(project.id);
          await datasets.waitForReady();
          const itemsPage = await datasets.openDatasetByName(editableRecordDataset.name);
          await itemsPage.waitForReady();
          await expect(itemsPage.itemRows(), 'both seeded records render').toHaveCount(2);
          return itemsPage;
        });

        await test.step('Edit `input` and commit it', async () => {
          await items.openItemEditorById(editableRecordDataset.subjectId);
          await items.editItemField('input', FIRST_EDIT);
          await items.closeItemEditor();
          await items.commitDraft();
          await expect(items.versionLabel(2), 'the first commit cut v2').toBeVisible();
        });

        await test.step('Reload the page, so the form re-seeds from a fresh read', async () => {
          // The one difference from the test above, and the whole point of this
          // one. Re-navigated rather than `page.reload()`: the editor's own
          // `?row=` param would otherwise reopen the panel for us, and this
          // control has to take the same route to the panel the other test
          // does.
          await items.goto();
          await items.waitForReady();
          await expect(items.itemRows(), 'both records still render').toHaveCount(2);
        });

        await test.step('The reopened panel shows the committed value', async () => {
          await expect(
            items.itemCell(editableRecordDataset.subjectId, 'input'),
            'the grid cell shows the committed value',
          ).toHaveText(FIRST_EDIT);

          await items.openItemEditorById(editableRecordDataset.subjectId);
          const rows = await items.readItemEditorRows();
          expect(
            rows.find((row) => row.name === 'input')?.value,
            'and so does the panel',
          ).toBe(FIRST_EDIT);
        });

        await test.step('Edit a different field and commit', async () => {
          await items.editItemField('expected_output', SECOND_EDIT);
          await items.closeItemEditor();
          await items.commitDraft();
          await expect(items.versionLabel(3), 'the second commit cut v3').toBeVisible();
        });

        await test.step('The record carries BOTH edits, and nothing else moved', async () => {
          const stored = byId(await backendClient.listDatasetItemsWithData(editableRecordDataset.id));
          expect(stored.size, 'the dataset still holds exactly two records').toBe(2);
          expect(
            stored.get(editableRecordDataset.subjectId),
            'the second commit kept the first edit',
          ).toEqual({
            ...EDITABLE_RECORD_DATA,
            input: FIRST_EDIT,
            expected_output: SECOND_EDIT,
          });
          expect(
            stored.get(editableRecordDataset.bystanderId),
            'the record the test never opened is untouched',
          ).toEqual(BYSTANDER_RECORD_DATA);
        });
      },
    );
  },
);
