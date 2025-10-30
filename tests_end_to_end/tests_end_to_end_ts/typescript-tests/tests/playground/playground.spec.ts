import { test, expect } from '@playwright/test';
import { PlaygroundPage } from '../../page-objects/playground.page';
import { AIProviderSetupHelper } from '../../helpers/ai-provider-setup-helper';
import { modelConfigLoader } from '../../helpers/model-config-loader';

const enabledModels = modelConfigLoader.getEnabledModelsForPlayground();

test.describe('Playground Tests', () => {
  if (enabledModels.length === 0) {
    test.skip('No enabled models found for playground testing', () => {});
  }

  for (const { providerName, modelConfig, providerConfig } of enabledModels) {
    test(`${providerConfig.display_name} - ${modelConfig.name} should generate response @regression @playground`, async ({ page }) => {
      const providerSetupHelper = new AIProviderSetupHelper(page);
      const playgroundPage = new PlaygroundPage(page);

      console.log(`Testing playground with ${providerConfig.display_name} - ${modelConfig.name}`);

      await providerSetupHelper.setupProviderIfNeeded(providerName, providerConfig);
      await playgroundPage.goto();
      await playgroundPage.selectModel(providerConfig.display_name, modelConfig.ui_selector);

      const testPrompt = modelConfigLoader.getTestPrompt();
      await playgroundPage.enterPrompt(testPrompt, 'user');
      await playgroundPage.runPrompt();

      const responseReceived = await playgroundPage.waitForResponseOrError(30);
      expect(responseReceived).toBeTruthy();

      expect(await playgroundPage.hasError()).toBeFalsy();

      const response = await playgroundPage.getResponse();
      console.log(`Response received (excerpt): ${response.substring(0, 100)}...`);

      const validation = playgroundPage.validateResponseQuality(response);

      expect(validation.hasContent).toBeTruthy();
      expect(validation.minLength).toBeTruthy();
      expect(validation.containsLlmInfo).toBeTruthy();

      console.log(
        `Response validation passed: ${validation.responseLength} chars, ${validation.sentenceCount} sentences`
      );

      console.log(`Playground test completed successfully for ${providerConfig.display_name} - ${modelConfig.name}`);

      await providerSetupHelper.cleanupProvider(providerConfig);
    });
  }
});
