import { test, expect } from '../../fixtures/projects.fixture';
import { RulesPage } from '../../page-objects/rules.page';
import { TracesPage } from '../../page-objects/traces.page';
import { ProjectsPage } from '../../page-objects/projects.page';
import { AIProviderSetupHelper } from '../../helpers/ai-provider-setup-helper';
import { modelConfigLoader } from '../../helpers/model-config-loader';

const RULE_ACTIVATION_TIMEOUT = 10000;
const TABLE_READY_TIMEOUT = 4000;
const POST_RELOAD_TIMEOUT = 1000;
const EXPECTED_ROW_COUNT = 10;

const enabledModels = modelConfigLoader.getEnabledModelsForOnlineScoring();

test.describe('Online Scoring Tests - Full Moderation Flow', () => {
  if (enabledModels.length === 0) {
    test.skip('No enabled models found for online scoring testing', () => {});
  }

  for (const { providerName, modelConfig, providerConfig } of enabledModels) {
    test(`Online scoring full moderation flow with ${providerConfig.display_name} ${modelConfig.name} @sanity @happypaths @fullregression @onlinescoring`, async ({
      page,
      projectName,
      helperClient,
      createProjectApi,
    }) => {
      test.setTimeout(120_000);
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

        const tracesToggle = page.getByRole('radio', { name: 'Traces' });
        if ((await tracesToggle.getAttribute('aria-checked')) !== 'true') {
          await tracesToggle.click();
          await page.waitForTimeout(500);
        }

        const maxAttempts = 25;
        const tableRows = page.locator('table tbody tr');
        const headerCells = page.locator('table thead th, table thead td');
        let moderationColumnIndex = -1;
        let moderationValue = '';

        for (let attempt = 1; attempt <= maxAttempts; attempt++) {
          try {
            await expect(tableRows).toHaveCount(EXPECTED_ROW_COUNT, { timeout: TABLE_READY_TIMEOUT });
          } catch {
            console.log(`Traces table not yet showing ${EXPECTED_ROW_COUNT} rows (attempt ${attempt}/${maxAttempts}), refreshing...`);
            await page.reload();
            await page.waitForTimeout(POST_RELOAD_TIMEOUT);
            continue;
          }

          try {
            await expect(headerCells.filter({ hasText: 'Moderation' }).first()).toBeVisible({
              timeout: TABLE_READY_TIMEOUT,
            });
          } catch {
            console.log(`Moderation column header not found yet (attempt ${attempt}/${maxAttempts}), refreshing...`);
            await page.reload();
            await page.waitForTimeout(POST_RELOAD_TIMEOUT);
            continue;
          }

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
            console.log(`Moderation column header disappeared between visibility check and index scan (attempt ${attempt}/${maxAttempts}), refreshing...`);
            await page.reload();
            await page.waitForTimeout(POST_RELOAD_TIMEOUT);
            continue;
          }

          console.log(`Found Moderation column at index ${moderationColumnIndex} (attempt ${attempt})`);

          const firstRow = tableRows.first();
          const moderationCell = firstRow.locator('td').nth(moderationColumnIndex);
          moderationValue = (await moderationCell.textContent())?.trim() || '';
          console.log(`Moderation value: ${moderationValue}`);

          if (moderationValue !== '-' && moderationValue !== '') {
            break;
          }

          if (attempt < maxAttempts) {
            console.log(`Score not populated yet (attempt ${attempt}/${maxAttempts}), refreshing...`);
            await page.reload();
            await page.waitForTimeout(POST_RELOAD_TIMEOUT);
          }
        }

        if (moderationColumnIndex === -1) {
          throw new Error(`Moderation column did not appear after ${maxAttempts} attempts`);
        }

        const rowCount = await tableRows.count();
        console.log(`Found ${rowCount} rows in traces table`);
        expect(rowCount).toBeGreaterThanOrEqual(EXPECTED_ROW_COUNT);

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
