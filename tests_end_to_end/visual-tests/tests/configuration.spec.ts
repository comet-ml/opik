import { test } from '@playwright/test';
import { getEnvironmentConfig } from '../config/env.config';
import { TestHelperClient } from '../helpers/test-helper-client';
import { ConfigurationPage } from '../page-objects/configuration.page';
import { screenshot, tableMasks } from './utils/screenshot';

const FEEDBACK_DEF_QUALITY = 'visual-config-quality';
const FEEDBACK_DEF_SENTIMENT = 'visual-config-sentiment';
const ENV_STAGING = 'visual-config-env-staging';
const ENV_PRODUCTION = 'visual-config-env-production';

test.setTimeout(300000);

test.describe('Visual Comparison - Configuration', () => {
  const { baseUrl, workspace } = getEnvironmentConfig().getConfig();

  test.beforeAll(async () => {
    const client = new TestHelperClient();

    await client.createFeedbackDefinition(FEEDBACK_DEF_QUALITY, 'numerical', { min: 0, max: 1 });
    await client.createFeedbackDefinition(FEEDBACK_DEF_SENTIMENT, 'categorical', {
      categories: { positive: 1, negative: 0 },
    });

    await client.createEnvironment(ENV_STAGING, { description: 'Staging deployment', color: '#5A6ACF' });
    await client.createEnvironment(ENV_PRODUCTION, { description: 'Production deployment', color: '#DB5A5A' });

    await client.createProviderApiKey('openai', 'sk-visual-test-fake-key-openai');
    await client.createProviderApiKey('anthropic', 'sk-visual-test-fake-key-anthropic');
  });

  test.afterAll(async () => {
    // Configuration entities are workspace-level, not project-scoped, so they'd
    // otherwise leak into empty-states.spec.ts's environments/AI-providers empty
    // assertions, which run in the same workspace right after this file.
    const client = new TestHelperClient();

    for (const name of [FEEDBACK_DEF_QUALITY, FEEDBACK_DEF_SENTIMENT]) {
      try {
        const definition = await client.getFeedbackDefinition(name);
        await client.deleteFeedbackDefinition(definition.id);
      } catch { /* ignore */ }
    }
    try {
      const environments = await client.findEnvironments();
      for (const env of environments) {
        if ([ENV_STAGING, ENV_PRODUCTION].includes(env.name)) {
          await client.deleteEnvironment(env.id);
        }
      }
    } catch { /* ignore */ }
    try {
      const providerKeys = await client.findProviderApiKeys();
      for (const key of providerKeys) {
        if (['openai', 'anthropic'].includes(key.provider)) {
          await client.deleteProviderApiKey(key.id);
        }
      }
    } catch { /* ignore */ }
  });

  test('C01: Configuration - Feedback definitions', { tag: ['@vcap:configuration.config-feedback-definitions'] }, async ({ page }) => {
    const configurationPage = new ConfigurationPage(page, baseUrl, workspace);
    await configurationPage.goto('feedback-definitions');
    await configurationPage.waitForFeedbackDefinitionsReady(FEEDBACK_DEF_QUALITY);
    await screenshot(page, 'C01-config-feedback-definitions', tableMasks(page));
  });

  test('C02: Configuration - Environments', { tag: ['@vcap:configuration.config-environments'] }, async ({ page }) => {
    const configurationPage = new ConfigurationPage(page, baseUrl, workspace);
    await configurationPage.goto('environments');
    await configurationPage.waitForEnvironmentsReady(ENV_STAGING);
    await screenshot(page, 'C02-config-environments', tableMasks(page));
  });

  test('C03: Configuration - AI providers', { tag: ['@vcap:configuration.config-ai-providers'] }, async ({ page }) => {
    const configurationPage = new ConfigurationPage(page, baseUrl, workspace);
    await configurationPage.goto('ai-provider');
    await configurationPage.waitForAiProvidersReady('OpenAI');
    await screenshot(page, 'C03-config-ai-providers', tableMasks(page));
  });

  test('C04: Configuration - Workspace preferences', { tag: ['@vcap:configuration.config-workspace-prefs'] }, async ({ page }) => {
    const configurationPage = new ConfigurationPage(page, baseUrl, workspace);
    await configurationPage.goto('workspace-preferences');
    await configurationPage.waitForWorkspacePreferencesReady();
    await screenshot(page, 'C04-config-workspace-prefs');
  });
});
