import { test, expect } from '../../fixtures/projects.fixture';
import { RulesPage } from '../../page-objects/rules.page';
import { ProjectsPage } from '../../page-objects/projects.page';
import { AIProviderSetupHelper } from '../../helpers/ai-provider-setup-helper';
import { modelConfigLoader } from '../../helpers/model-config-loader';

const enabledModels = modelConfigLoader.getEnabledModelsForOnlineScoring();

test.describe('Online Scoring Tests - Rule Creation', () => {
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

        await expect(page.getByText(ruleName)).toBeVisible();
        console.log(`Successfully created and verified moderation rule: ${ruleName}`);
      });

      await test.step('Cleanup provider', async () => {
        await providerSetupHelper.cleanupProvider(providerConfig);
      });

      console.log(`Moderation rule creation test completed successfully for ${providerConfig.display_name} - ${modelConfig.name}`);
    });
  }
});
