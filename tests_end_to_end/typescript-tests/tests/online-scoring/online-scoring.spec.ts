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

      await test.step('Navigate to traces and verify moderation column with scores appears', async () => {
        await projectsPage.goto();
        await projectsPage.clickProject(createProjectApi);
        console.log('Successfully navigated to project traces');

        // Ensure we're on the Traces view
        const tracesToggle = page.getByRole('radio', { name: 'Traces' });
        if ((await tracesToggle.getAttribute('aria-checked')) !== 'true') {
          await tracesToggle.click();
          await page.waitForTimeout(500);
        }

        // Retry loop: wait for Moderation column header in the table, then check score value.
        // Scoring is async and Anthropic models can take longer, so we allow generous retries.
        const maxAttempts = 15;
        let moderationColumnIndex = -1;
        let moderationValue = '';

        for (let attempt = 1; attempt <= maxAttempts; attempt++) {
          // Find Moderation column in thead
          const headerCells = page.locator('table thead th, table thead td');
          const headerCount = await headerCells.count();
          moderationColumnIndex = -1;

          for (let i = 0; i < headerCount; i++) {
            const cellText = await headerCells.nth(i).textContent();
            if (cellText?.includes('Moderation')) {
              moderationColumnIndex = i;
              break;
            }
          }

          if (moderationColumnIndex === -1) {
            console.log(`Moderation column header not found yet (attempt ${attempt}/${maxAttempts}), refreshing...`);
            await page.reload();
            await page.waitForTimeout(PAGE_REFRESH_TIMEOUT);
            continue;
          }

          console.log(`Found Moderation column at index ${moderationColumnIndex} (attempt ${attempt})`);

          // Check if there's a score value (not "-")
          const firstRow = page.locator('table tbody tr').first();
          const moderationCell = firstRow.locator('td').nth(moderationColumnIndex);
          moderationValue = (await moderationCell.textContent())?.trim() || '';
          console.log(`Moderation value: ${moderationValue}`);

          if (moderationValue !== '-' && moderationValue !== '') {
            break;
          }

          if (attempt < maxAttempts) {
            console.log(`Score not populated yet (attempt ${attempt}/${maxAttempts}), refreshing...`);
            await page.reload();
            await page.waitForTimeout(PAGE_REFRESH_TIMEOUT);
          }
        }

        if (moderationColumnIndex === -1) {
          throw new Error(`Moderation column did not appear after ${maxAttempts} attempts`);
        }

        const tableRows = page.locator('table tbody tr');
        const rowCount = await tableRows.count();
        console.log(`Found ${rowCount} rows in traces table`);
        expect(rowCount).toBeGreaterThanOrEqual(10);

        // The value should be "0" (safe content)
        expect(moderationValue).toBe('0');
        console.log(`Successfully verified moderation scoring with value: ${moderationValue}`);
      });

      await test.step('Cleanup provider', async () => {
        await providerSetupHelper.cleanupProvider(providerConfig);
      });

      console.log(`Online scoring moderation full flow completed successfully for ${providerConfig.display_name} - ${modelConfig.name}`);
    });
  }
});
