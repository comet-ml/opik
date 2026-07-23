// Copy-dataset-ID row action — the datasets list row menu gains a "Copy dataset ID"
// item that puts the dataset id on the clipboard and confirms with a toast.
import { test, expect } from '@e2e/fixtures';
import { DatasetsPage } from '@e2e/pom/datasets.page';

test.describe(
  'Copy dataset ID — datasets list row action',
  { tag: ['@release-gate', '@release-gate:2.1.33', '@datasets'] },
  () => {
    test('row menu copies the dataset ID to the clipboard and confirms with a toast', async ({
      dataset,
      project,
      page,
    }) => {
      await test.step('Grant clipboard access for the assertion', async () => {
        await page
          .context()
          .grantPermissions(['clipboard-read', 'clipboard-write']);
      });

      const datasets = new DatasetsPage(page);

      await test.step('Open the datasets list and find the seeded dataset row', async () => {
        await datasets.goto(project.id);
        await datasets.waitForReady();
        await expect(datasets.datasetRow(dataset.name)).toBeVisible();
      });

      await test.step('Copy the dataset ID from the row actions menu', async () => {
        await datasets
          .datasetRow(dataset.name)
          .getByRole('button', { name: 'Actions menu' })
          .click();
        await page.getByRole('menuitem', { name: 'Copy dataset ID' }).click();
      });

      await test.step('The toast confirms and the clipboard holds the dataset ID', async () => {
        // exact: the toast text also appears in Radix's aria-live announcement span
        // ("Notification Copied dataset ID to clipboard"), which trips strict mode.
        await expect(
          page.getByText('Copied dataset ID to clipboard', { exact: true }),
        ).toBeVisible();
        const clipboard = await page.evaluate(() =>
          navigator.clipboard.readText(),
        );
        expect(clipboard).toBe(dataset.id);
      });

      // Not covered here: the same action on the Test Suites surface
      // (rowActionsEntityName "test suite") — equivalent surface, see the plan.
    });
  },
);
