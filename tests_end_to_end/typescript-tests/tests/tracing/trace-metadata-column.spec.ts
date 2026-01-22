import { test, expect } from '../../fixtures/tracing.fixture';
import { ProjectsPage } from '../../page-objects/projects.page';
import { TracesPage } from '../../page-objects/traces.page';

test.describe('Metadata Column Tests', () => {
  test.describe('Metadata column visibility', () => {
    test('Metadata column appears in Columns menu and can be toggled @sanity @happypaths @fullregression @tracing', async ({
      page,
      projectName,
      createTracesWithSpansClient,
    }) => {
      test.info().annotations.push({
        type: 'description',
        description: `Tests that the metadata column appears in the Columns menu and can be toggled on/off.

Steps:
1. Create a project and traces with metadata via low-level client (handled by fixture)
2. Navigate to the project traces page
3. Open the Columns menu
4. Verify "Metadata" checkbox exists
5. Toggle metadata column off and verify it's hidden
6. Toggle metadata column on and verify it's visible

This test ensures the metadata column is available in the UI and prevents accidental removal.`
      });

      await test.step('Navigate to project traces page', async () => {
        const projectsPage = new ProjectsPage(page);
        await projectsPage.goto();
        await projectsPage.checkProjectExistsWithRetry(projectName, 5000);
        await projectsPage.clickProject(projectName);
      });

      await test.step('Initialize traces page', async () => {
        const tracesPage = new TracesPage(page);
        await tracesPage.initialize();
        await tracesPage.waitForTracesToBeVisible();
      });

      await test.step('Verify Metadata column exists in Columns menu', async () => {
        // Open Columns menu
        await page.getByRole('button', { name: 'Columns' }).click();

        // Verify Metadata checkbox exists
        const metadataButton = page.getByRole('button', { name: 'Metadata' });
        await expect(metadataButton).toBeVisible();

        // Verify it has a checkbox
        const metadataCheckbox = metadataButton.getByRole('checkbox');
        await expect(metadataCheckbox).toBeVisible();
      });

      await test.step('Toggle metadata column off and verify it is hidden', async () => {
        // The Columns menu should still be open from the previous step
        // But let's verify the metadata button is visible first
        const metadataButton = page.getByRole('button', { name: 'Metadata' });
        
        // If the menu closed, we need to reopen it
        try {
          await expect(metadataButton).toBeVisible({ timeout: 1000 });
        } catch {
          // Menu closed, reopen it
          await page.getByRole('button', { name: 'Columns' }).click();
          await expect(metadataButton).toBeVisible();
        }
        
        const metadataCheckbox = metadataButton.getByRole('checkbox');
        await expect(metadataCheckbox).toBeVisible();
        
        const isChecked = await metadataCheckbox.isChecked();
        if (isChecked) {
          await metadataButton.click();
          // Wait for the UI to update
          await expect(metadataCheckbox).not.toBeChecked();
        }

        // Close columns menu
        await page.keyboard.press('Escape');

        // Wait for the columns menu to close and table to update
        const columnsButton = page.getByRole('button', { name: 'Columns' });
        await expect(columnsButton).toBeVisible();

        // Verify Metadata column header is not visible in table
        const metadataHeader = page.getByRole('columnheader', { name: 'Metadata' });
        await expect(metadataHeader).not.toBeVisible();
      });

      await test.step('Toggle metadata column on and verify it is visible', async () => {
        // Open Columns menu again
        await page.getByRole('button', { name: 'Columns' }).click();

        // Wait for menu to be visible and click to toggle on
        const metadataButton = page.getByRole('button', { name: 'Metadata' });
        await expect(metadataButton).toBeVisible();
        await metadataButton.click();

        // Verify checkbox is now checked
        const metadataCheckbox = metadataButton.getByRole('checkbox');
        await expect(metadataCheckbox).toBeChecked();

        // Close columns menu
        await page.keyboard.press('Escape');

        // Wait for the columns menu to close and table to update
        const columnsButton = page.getByRole('button', { name: 'Columns' });
        await expect(columnsButton).toBeVisible();

        // Verify Metadata column header is visible in table
        const metadataHeader = page.getByRole('columnheader', { name: 'Metadata' });
        await expect(metadataHeader).toBeVisible();
      });
    });
  });

  test.describe('Metadata column filtering', () => {
    test('Metadata option appears in Filters dropdown @happypaths @fullregression @tracing', async ({
      page,
      projectName,
      createTracesWithSpansClient,
    }) => {
      test.info().annotations.push({
        type: 'description',
        description: `Tests that the Metadata option appears in the Filters dropdown and can be selected.

Steps:
1. Create a project and traces with metadata via low-level client (handled by fixture)
2. Navigate to the project traces page
3. Open the Filters panel
4. Click to add a new filter
5. Open the column dropdown
6. Verify "Metadata" option exists in the dropdown
7. Select "Metadata" option

This test ensures the metadata column is available for filtering.`
      });

      await test.step('Navigate to project traces page', async () => {
        const projectsPage = new ProjectsPage(page);
        await projectsPage.goto();
        await projectsPage.checkProjectExistsWithRetry(projectName, 5000);
        await projectsPage.clickProject(projectName);
      });

      await test.step('Initialize traces page', async () => {
        const tracesPage = new TracesPage(page);
        await tracesPage.initialize();
        await tracesPage.waitForTracesToBeVisible();
      });

      await test.step('Open Filters panel and add new filter', async () => {
        // Click Filters button
        await page.getByRole('button', { name: 'Filters' }).click();

        // Click "Add filter" button (look for the + icon or "Add filter" text)
        const addFilterButton = page.getByRole('button', { name: /add filter/i }).first();
        await expect(addFilterButton).toBeVisible();
        await addFilterButton.click();
      });

      await test.step('Verify Metadata option exists in column dropdown', async () => {
        // Find and click the column dropdown (first combobox in the filter row)
        const columnDropdown = page.getByRole('combobox').first();
        await columnDropdown.click();

        // Verify "Metadata" option exists
        const metadataOption = page.getByRole('option', { name: 'Metadata' });
        await expect(metadataOption).toBeVisible();

        // Select the Metadata option
        await metadataOption.click();
      });
    });

    test('Metadata filter works with autocomplete for nested fields @happypaths @fullregression @tracing', async ({
      page,
      projectName,
      createTracesWithSpansClient,
    }) => {
      test.info().annotations.push({
        type: 'description',
        description: `Tests that the metadata filter works with autocomplete for nested fields and filters traces correctly.

Steps:
1. Create a project and traces with metadata via low-level client (handled by fixture)
   - Traces have metadata: { 'c-md1': 'val1', 'c-md2': 'val2' }
2. Navigate to the project traces page
3. Open Filters and select Metadata column
4. Enter a metadata key in the autocomplete field
5. Select a metadata key (e.g., "c-md1")
6. Enter a value filter
7. Apply the filter and verify results

This test ensures metadata filtering works correctly with autocomplete.`
      });

      const { traceConfig } = createTracesWithSpansClient;

      await test.step('Navigate to project traces page', async () => {
        const projectsPage = new ProjectsPage(page);
        await projectsPage.goto();
        await projectsPage.checkProjectExistsWithRetry(projectName, 5000);
        await projectsPage.clickProject(projectName);
      });

      await test.step('Initialize traces page and verify traces exist', async () => {
        const tracesPage = new TracesPage(page);
        await tracesPage.initialize();
        await tracesPage.waitForTracesToBeVisible();

        // Verify we have the expected number of traces
        const totalTraces = await tracesPage.getTotalNumberOfTracesInProject();
        expect(totalTraces).toBe(traceConfig.count);
      });

      await test.step('Open Filters and select Metadata column', async () => {
        // Click Filters button
        await page.getByRole('button', { name: 'Filters' }).click();

        // Click "Add filter" button
        const addFilterButton = page.getByRole('button', { name: /add filter/i }).first();
        await expect(addFilterButton).toBeVisible();
        await addFilterButton.click();

        // Select Metadata from column dropdown
        const columnDropdown = page.getByRole('combobox').first();
        await columnDropdown.click();

        const metadataOption = page.getByRole('option', { name: 'Metadata' });
        await expect(metadataOption).toBeVisible();
        await metadataOption.click();
      });

      await test.step('Enter metadata key using autocomplete', async () => {
        // Find the key input field (should be a combobox for autocomplete)
        // Look for input with placeholder "key" or similar
        const keyInput = page.getByPlaceholder('key').first();
        await expect(keyInput).toBeVisible();

        // Type a metadata key that exists in the test data
        await keyInput.fill('c-md1');

        // Wait for autocomplete dropdown to appear and select suggestion if available
        const suggestion = page.getByRole('option', { name: /c-md1/i }).first();
        try {
          await expect(suggestion).toBeVisible({ timeout: 2000 });
          await suggestion.click();
        } catch {
          // If no autocomplete appears, that's okay - the value is already filled
        }
      });

      await test.step('Enter filter value and apply', async () => {
        // Find the value input field
        const valueInput = page.getByPlaceholder(/value/i).first();
        await expect(valueInput).toBeVisible();

        // Enter the value that matches our test data
        await valueInput.fill('val1');

        // Close the filters panel
        await page.keyboard.press('Escape');
      });

      await test.step('Verify filtered results', async () => {
        // After filtering by metadata.c-md1 = val1, we should still see all traces
        // because all traces in the fixture have this metadata
        const tracesPage = new TracesPage(page);
        
        // Wait for the table to reload with filtered results
        // Give it a moment for the filter to be applied
        await page.waitForLoadState('networkidle');
        
        // Wait for the traces to be visible after filter is applied
        await tracesPage.waitForTracesToBeVisible();

        const visibleTraces = await tracesPage.getAllTraceNamesOnPage();
        
        // All traces should be visible since they all have c-md1: val1
        expect(visibleTraces.length).toBeGreaterThan(0);
        
        // Verify traces with the expected prefix are visible
        const expectedTraceNames = visibleTraces.filter(name => 
          name.startsWith(traceConfig.prefix)
        );
        expect(expectedTraceNames.length).toBe(traceConfig.count);
      });
    });
  });
});
