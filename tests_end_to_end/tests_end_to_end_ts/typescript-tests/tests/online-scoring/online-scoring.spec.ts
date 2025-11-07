import { test, expect } from '../../fixtures/projects.fixture';
import { RulesPage } from '../../page-objects/rules.page';
import { TracesPage } from '../../page-objects/traces.page';
import { ProjectsPage } from '../../page-objects/projects.page';
import { AIProviderSetupHelper } from '../../helpers/ai-provider-setup-helper';
import { modelConfigLoader } from '../../helpers/model-config-loader';

// Timeout constants
const RULE_ACTIVATION_TIMEOUT = 10000; // 10 seconds for rule to fully activate in backend
const PAGE_REFRESH_TIMEOUT = 2000; // 2 seconds wait after page refresh
const POST_RELOAD_TIMEOUT = 1000; // 1 second wait after page reload

const enabledModels = modelConfigLoader.getEnabledModelsForOnlineScoring();

test.describe('Online Scoring Tests', () => {
  if (enabledModels.length === 0) {
    test.skip('No enabled models found for online scoring testing', () => {});
  }

  for (const { providerName, modelConfig, providerConfig } of enabledModels) {
    test(`Create moderation rule with ${providerConfig.display_name} ${modelConfig.name} @fullregression @onlinescoring`, async ({
      page,
      projectName,
      helperClient,
      createProjectApi,
    }) => {
      const providerSetupHelper = new AIProviderSetupHelper(page);
      const rulesPage = new RulesPage(page);

      console.log(`Testing moderation rule creation with ${providerConfig.display_name} - ${modelConfig.name}`);

      await test.step('Setup AI provider if needed', async () => {
        await providerSetupHelper.setupProviderIfNeeded(providerName, providerConfig);
      });

      await test.step('Navigate to project and rules tab', async () => {
        const projectsPage = new ProjectsPage(page);
        await projectsPage.goto();
        await projectsPage.clickProject(createProjectApi);
        await rulesPage.navigateToRulesTab();
      });

      await test.step('Create moderation rule', async () => {
        const ruleName = `Test Moderation Rule - ${modelConfig.name}`;
        await rulesPage.createModerationRule(ruleName, providerConfig.display_name, modelConfig.ui_selector);

        // Verify rule was created
        await expect(page.getByText(ruleName)).toBeVisible();
        console.log(`Successfully created and verified moderation rule: ${ruleName}`);
      });

      await test.step('Cleanup provider', async () => {
        await providerSetupHelper.cleanupProvider(providerConfig);
      });

      console.log(`Moderation rule creation test completed successfully for ${providerConfig.display_name} - ${modelConfig.name}`);
    });

    test(`Online scoring full moderation flow with ${providerConfig.display_name} ${modelConfig.name} @sanity @happypaths @fullregression @onlinescoring`, async ({
      page,
      projectName,
      helperClient,
      createProjectApi,
    }) => {
      const providerSetupHelper = new AIProviderSetupHelper(page);
      const rulesPage = new RulesPage(page);
      const tracesPage = new TracesPage(page);
      const projectsPage = new ProjectsPage(page);

      console.log(`Testing online scoring moderation full flow for ${providerConfig.display_name} - ${modelConfig.name}`);

      await test.step('Setup AI provider if needed', async () => {
        await providerSetupHelper.setupProviderIfNeeded(providerName, providerConfig);
      });

      await test.step('Navigate to project and create moderation rule FIRST (before any traces)', async () => {
        await projectsPage.goto();
        await projectsPage.clickProject(createProjectApi);
        await rulesPage.navigateToRulesTab();

        const ruleName = `Test Moderation Rule - ${modelConfig.name}`;
        await rulesPage.createModerationRule(ruleName, providerConfig.display_name, modelConfig.ui_selector);

        console.log('Rule created successfully - waiting for rule to fully activate before creating traces');
        // CRITICAL: Wait for the rule to fully activate in the backend before creating traces
        await page.waitForTimeout(RULE_ACTIVATION_TIMEOUT);
      });

      await test.step('Create traces AFTER rule exists (critical: rule must exist first!)', async () => {
        await helperClient.createTracesClient(createProjectApi, 10, 'scored-trace-');
        await helperClient.waitForTracesVisible(createProjectApi, 10, 30);
        console.log('Traces created - they should be automatically scored by the rule');
      });

      await test.step('Navigate to traces and verify moderation column appears', async () => {
        await projectsPage.goto();
        await projectsPage.clickProject(createProjectApi);
        console.log('Successfully navigated to project traces');

        // The Moderation column might not appear immediately, try refreshing
        let columnFound = false;
        const maxAttempts = 5;

        for (let attempt = 1; attempt <= maxAttempts; attempt++) {
          try {
            // Wait for the Moderation column to exist in the DOM
            // The column appears as a cell in the table header, not as a proper columnheader role
            const moderationColumn = page.getByText('Moderation', { exact: false });
            await expect(moderationColumn).toBeAttached({ timeout: 5000 });
            console.log(`Moderation column is attached (attempt ${attempt})!`);
            columnFound = true;
            break;
          } catch (error) {
            if (attempt < maxAttempts) {
              console.log(`Moderation column not visible yet (attempt ${attempt}), refreshing page...`);
              await page.reload();
              await page.waitForTimeout(PAGE_REFRESH_TIMEOUT);
            }
          }
        }

        if (!columnFound) {
          throw new Error(`Moderation column did not appear after ${maxAttempts} attempts and refreshes`);
        }
      });

      await test.step('Verify moderation scores are present', async () => {
        // All traces should have moderation scores of 0 (safe content)
        // Scoring is asynchronous, so we need to wait for the scores to populate

        // Count rows in the table - each row should have a moderation score
        const tableRows = page.locator('table tbody tr');
        const rowCount = await tableRows.count();

        console.log(`Found ${rowCount} rows in traces table`);

        // We expect all 10 traces to be present
        expect(rowCount).toBeGreaterThanOrEqual(10);

        // Find the column index of the Moderation column
        const headerCells = page.locator('table thead th, table thead td');
        const headerCount = await headerCells.count();
        let moderationColumnIndex = -1;

        for (let i = 0; i < headerCount; i++) {
          const cellText = await headerCells.nth(i).textContent();
          if (cellText?.includes('Moderation')) {
            moderationColumnIndex = i;
            console.log(`Found Moderation column at index ${i}`);
            break;
          }
        }

        if (moderationColumnIndex === -1) {
          throw new Error('Could not find Moderation column in table header');
        }

        // Wait for scoring to complete - retry until we see actual scores (not "-")
        const firstRow = tableRows.first();
        const cells = firstRow.locator('td');
        const moderationCell = cells.nth(moderationColumnIndex);

        let moderationValue = '';
        const maxRetries = 10;
        for (let attempt = 1; attempt <= maxRetries; attempt++) {
          moderationValue = (await moderationCell.textContent())?.trim() || '';
          console.log(`Attempt ${attempt}: First row moderation value: ${moderationValue}`);

          if (moderationValue !== '-' && moderationValue !== '') {
            break;
          }

          if (attempt < maxRetries) {
            console.log(`Scores not populated yet, waiting before retry...`);
            await page.waitForTimeout(PAGE_REFRESH_TIMEOUT);
            await page.reload();
            await page.waitForTimeout(POST_RELOAD_TIMEOUT);
          }
        }

        // The value should be "0" (safe content)
        expect(moderationValue).toBe('0');
        console.log(`Successfully verified moderation scoring is working - traces have been scored with value: ${moderationValue}`);
      });

      await test.step('Cleanup provider', async () => {
        await providerSetupHelper.cleanupProvider(providerConfig);
      });

      console.log(`Online scoring moderation full flow completed successfully for ${providerConfig.display_name} - ${modelConfig.name}`);
    });
  }
});
