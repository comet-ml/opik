import { test, expect } from '@e2e/fixtures';
import { DatasetsPage } from '@e2e/pom/datasets.page';

test.describe('Dataset CRUD — smoke', { tag: ['@t1-smoke', '@datasets'] }, () => {
  /** Items toolbar collapses to icon-only (no accessible name) below ~850px container width. */
  test.use({ viewport: { width: 1600, height: 900 } });

  test('SDK-seeded dataset renders in list and items page; UI add and delete commit as new versions', async ({
    dataset,
    project,
    page,
  }) => {
    const datasets = new DatasetsPage(page);
    await datasets.goto(project.id);
    await datasets.waitForReady();

    await expect(datasets.datasetRow(dataset.name)).toBeVisible();

    const items = await datasets.openDatasetByName(dataset.name);
    await items.waitForReady();
    expect(await items.countItems()).toBe(3);

    await items.clickAddItem();
    await items.fillAddItemPanel({
      input: 'ui-added input',
      expected_output: 'ui-added output',
    });
    await items.submitAddItemPanel();
    expect(await items.countItems()).toBe(4);

    await items.commitDraft();
    await expect(items.versionLabel(2)).toBeVisible();
    expect(await items.countItems()).toBe(4);

    await items.deleteItemByIndex(0);
    expect(await items.countItems()).toBe(3);

    await items.commitDraft();
    await expect(items.versionLabel(3)).toBeVisible();
    expect(await items.countItems()).toBe(3);
  });

  test('UI-created dataset and UI-added first item round-trip to the SDK', async ({
    project,
    backendClient,
    testNamespace,
    page,
  }) => {
    const name = `${testNamespace}-ui-ds`;
    const description = 'created via UI dialog';

    const datasets = new DatasetsPage(page);
    await datasets.goto(project.id);
    await datasets.waitForReady();

    await datasets.clickCreateDataset();
    await datasets.fillCreateDialog({ name, description });
    await datasets.submitCreateDialog();
    await expect(datasets.datasetRow(name)).toBeVisible();

    const found = await backendClient.findDatasetByName(name);
    expect(found).not.toBeNull();
    expect(found?.name).toBe(name);
    expect(found?.description).toBe(description);

    /** Empty datasets use a different Add Item path than the sliding panel — see POM. */
    const items = await datasets.openDatasetByName(name);
    await items.waitForReady();
    const newItem = { input: 'ui input', expected_output: 'ui output' };
    await items.addItemViaEmptyStateModal(newItem);

    const fetchedItems = await backendClient.getDatasetItems(found!.id);
    expect(fetchedItems).toHaveLength(1);
    expect(fetchedItems[0].data).toMatchObject(newItem);

    await backendClient.deleteDataset(found!.id);
  });
});
