import * as fs from 'node:fs';
import * as path from 'node:path';
import * as yaml from 'js-yaml';

import { test, expect } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { ConfigurationPage, type ProviderName } from '@e2e/pom/configuration.page';

interface ModelEntry {
  name: string;
  ui_selector: string;
  enabled: boolean;
  options?: Record<string, string | number>;
}

interface BuiltInProviderEntry {
  display_name: string;
  provider_type?: 'builtin';
  provider_name: ProviderName;
  api_key_env_var: string;
  models: ModelEntry[];
}

interface CustomProviderEntry {
  display_name: string;
  provider_type: 'custom';
  provider_name: string;
  base_url: string;
  api_key_env_var: string;
  models: ModelEntry[];
}

type ProviderEntry = BuiltInProviderEntry | CustomProviderEntry;

interface ProvidersConfig {
  providers: Record<string, ProviderEntry>;
  test_config: { test_prompt: string; response_timeout_seconds: number };
}

const config = yaml.load(
  fs.readFileSync(path.resolve(__dirname, '../../data/playground-models.yaml'), 'utf8'),
) as ProvidersConfig;

const enabled = Object.values(config.providers).flatMap((p) =>
  p.models.filter((m) => m.enabled).map((m) => ({ provider: p, model: m })),
);

/**
 * Provider sanity tests — NOT in @t1-smoke. Tagged @provider-sanity so they run
 * on a separate cadence (no deploy-gating, no per-PR cost). Each test:
 *   1. Skips cleanly if the provider's API key env var is absent.
 *   2. Self-provisions the provider key via the AI Providers config UI (idempotent).
 *   3. Runs a simple prompt + model-options tweak via the Playground.
 *   4. Asserts a non-empty, non-error response came back.
 */
test.describe('Playground — provider sanity', { tag: ['@provider-sanity', '@playground'] }, () => {
  for (const { provider, model } of enabled) {
    test(`${provider.display_name} ${model.name} returns a completion`, async ({
      project,
      page,
    }) => {
      test.setTimeout(180_000);

      const apiKey = process.env[provider.api_key_env_var];
      test.skip(!apiKey, `${provider.api_key_env_var} not set in this env`);

      const offered = await test.step(
        `Ensure ${provider.provider_name} provider key is configured`,
        async () => {
          const cfg = new ConfigurationPage(page);
          await cfg.gotoAiProviders();
          if (provider.provider_type === 'custom') {
            return cfg.ensureCustomProviderConfigured({
              providerName: provider.provider_name,
              baseUrl: provider.base_url,
              apiKey: apiKey!,
              models: provider.models.map((m) => m.name).join(','),
            });
          }
          return cfg.ensureProviderConfigured(provider.provider_name, apiKey!);
        },
      );

      // This deployment doesn't offer the provider under test (its feature
      // toggle is off), so there is no model to run against — skip rather than
      // fail on a model selection that can never resolve.
      test.skip(!offered, `${provider.provider_name} is not offered by this deployment`);

      await test.step(`Run a simple prompt against ${model.ui_selector} with options ${JSON.stringify(
        model.options ?? {},
      )}`, async () => {
        const playground = new PlaygroundPage(page, project.id);
        await playground.goto();
        await playground.waitForReady();
        const result = await playground.runSimplePromptAndAwaitResponse({
          modelDisplayName: model.ui_selector,
          prompt: config.test_config.test_prompt,
          timeoutMs: config.test_config.response_timeout_seconds * 1000,
        });
        expect(result.isError, 'no error indicator').toBe(false);
        expect(result.outputText.length, 'non-empty response').toBeGreaterThan(0);
      });
    });
  }
});
