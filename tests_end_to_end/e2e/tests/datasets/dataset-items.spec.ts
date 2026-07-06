import { test, expect } from '@e2e/fixtures';
import { DatasetsPage } from '@e2e/pom/datasets.page';

test.describe('Dataset items — direct coverage', { tag: ['@datasets'] }, () => {
  /** Items toolbar collapses to icon-only (no accessible name) below ~850px container width. */
  test.use({ viewport: { width: 1600, height: 900 } });

  test('Editing an item field commits as a new version and round-trips to the SDK', {
    tag: ['@t2-cuj'],
  }, async ({
    dataset,
    project,
    backendClient,
    page,
  }) => {
    const editedInput = 'edited via items page';

    const items = await test.step('Open the dataset items page', async () => {
      const datasets = new DatasetsPage(page);
      await datasets.goto(project.id);
      await datasets.waitForReady();
      const itemsPage = await datasets.openDatasetByName(dataset.name);
      await itemsPage.waitForReady();
      expect(await itemsPage.countItems()).toBe(3);
      return itemsPage;
    });

    await test.step('Edit an item field (stages a draft) and commit as a new version', async () => {
      await items.openItemEditor(0);
      await items.editItemField('input', editedInput);
      await items.closeItemEditor();
      await items.commitDraft();
      await expect(items.versionLabel(2)).toBeVisible();
      expect(await items.countItems()).toBe(3);
    });

    await test.step('Verify the edit persisted via the SDK', async () => {
      const persisted = await backendClient.getDatasetItems(dataset.id);
      expect(persisted).toHaveLength(3);
      const inputs = persisted.map((i) => i.data.input);
      expect(inputs).toContain(editedInput);
    });
  });

  test(
    'Bulk-deleting selected items commits as a new version and round-trips to the SDK',
    { tag: ['@t3-nightly'] },
    async ({ dataset, project, backendClient, page }) => {
      const items = await test.step('Open the dataset items page', async () => {
        const datasets = new DatasetsPage(page);
        await datasets.goto(project.id);
        await datasets.waitForReady();
        const itemsPage = await datasets.openDatasetByName(dataset.name);
        await itemsPage.waitForReady();
        expect(await itemsPage.countItems()).toBe(3);
        return itemsPage;
      });

      await test.step('Select two rows, bulk-delete (stages a draft), and commit', async () => {
        await items.selectRow(0);
        await items.selectRow(1);
        await items.bulkDeleteSelected();
        expect(await items.countItems()).toBe(1);
        await items.commitDraft();
        await expect(items.versionLabel(2)).toBeVisible();
        expect(await items.countItems()).toBe(1);
      });

      await test.step('Verify only one item remains via the SDK', async () => {
        const remaining = await backendClient.getDatasetItems(dataset.id);
        expect(remaining).toHaveLength(1);
      });
    },
  );

  test(
    'Searching filters the items table to matching rows',
    { tag: ['@t3-nightly'] },
    async ({ dataset, project, page }) => {
      const items = await test.step('Open the dataset items page', async () => {
        const datasets = new DatasetsPage(page);
        await datasets.goto(project.id);
        await datasets.waitForReady();
        const itemsPage = await datasets.openDatasetByName(dataset.name);
        await itemsPage.waitForReady();
        expect(await itemsPage.countItems()).toBe(3);
        return itemsPage;
      });

      await test.step('Search narrows the table to the matching row', async () => {
        await items.search('seed input 2');
        await expect.poll(() => items.countItems()).toBe(1);
      });

      await test.step('Clearing the search restores all rows', async () => {
        await items.clearSearch();
        await expect.poll(() => items.countItems()).toBe(3);
      });
    },
  );
});
