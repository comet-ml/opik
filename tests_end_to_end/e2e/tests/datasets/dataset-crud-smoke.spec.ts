import { test, expect } from '@e2e/fixtures';
import { DatasetsPage } from '@e2e/pom/datasets.page';

test.describe('Dataset CRUD — smoke', { tag: ['@t1-smoke', '@datasets'] }, () => {
  // The dataset items toolbar collapses to an icon-only "Add item" button
  // (no accessible name) at < ~850px container width — i.e. anything narrower
  // than ~1280px viewport with the sidebar expanded. Use a wider viewport so
  // the labelled button shape is exercised, and so legacy environments without
  // the dataset-items-add-button testid (deployed pre this PR) still pass.
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

    // Stage a new item via the side-panel.
    await items.clickAddItem();
    await items.fillAddItemPanel({
      input: 'ui-added input',
      expected_output: 'ui-added output',
    });
    await items.submitAddItemPanel();
    expect(await items.countItems()).toBe(4);

    // Commit the draft as a new version (v2).
    await items.commitDraft();
    await expect(items.versionLabel(2)).toBeVisible();
    expect(await items.countItems()).toBe(4);

    // Stage a delete of the row just added (most-recent-first ordering puts
    // the UI-added row at index 0).
    await items.deleteItemByIndex(0);
    expect(await items.countItems()).toBe(3);

    // Commit the deletion as v3.
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

    // "I created a dataset via the UI yesterday — now I look it up via the
    // SDK". Round-trip the metadata.
    const found = await backendClient.findDatasetByName(name);
    expect(found).not.toBeNull();
    expect(found?.name).toBe(name);
    expect(found?.description).toBe(description);

    // Drill into the items page and add the first item via the empty-state
    // JSON-editor modal (the populated-table sliding panel is a different
    // code path, exercised by Test A).
    const items = await datasets.openDatasetByName(name);
    await items.waitForReady();
    const newItem = { input: 'ui input', expected_output: 'ui output' };
    await items.addItemViaEmptyStateModal(newItem);

    // SDK-verify the new item round-trips.
    const fetchedItems = await backendClient.getDatasetItems(found!.id);
    expect(fetchedItems).toHaveLength(1);
    expect(fetchedItems[0].data).toMatchObject(newItem);

    // Test B owns dataset teardown (no dataset fixture used). global-teardown's
    // cuj-* sweep is the safety net; this inline delete keeps the worker's
    // workspace clean between consecutive cases.
    await backendClient.deleteDataset(found!.id);
  });
});
