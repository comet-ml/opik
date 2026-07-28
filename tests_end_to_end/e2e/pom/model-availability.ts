import type { Page } from '@playwright/test';
import { test } from '@e2e/fixtures';
import { ConfigurationPage } from '@e2e/pom/configuration.page';

/**
 * Provision an LLM provider for tests that drive the Playground / LLM-judge UI,
 * and return the model display name to select.
 *
 * Provider selection is driven by BOTH the runner's env keys AND what the target
 * deployment actually offers. A restricted environment may expose only a subset
 * of providers (e.g. no Anthropic), so picking a provider purely from env-var
 * presence — as the older per-spec copies did — would commit to a provider the
 * deployment can't add and hang on the dialog click. Each built-in candidate is
 * attempted only when its key is present, and skipped (falling through to the
 * next) when the deployment doesn't offer it. The OpenRouter Custom Provider is
 * the final fallback for environments that block the built-ins entirely.
 */
export async function ensureModelAvailable(page: Page): Promise<string> {
  const anthropic = process.env.ANTHROPIC_API_KEY;
  const openai = process.env.OPENAI_API_KEY;
  const openrouter = process.env.OPENROUTER_API_KEY;

  if (!anthropic && !openai && !openrouter) {
    test.skip(true, 'None of ANTHROPIC_API_KEY, OPENAI_API_KEY, OPENROUTER_API_KEY is set');
    return '';
  }

  const cfg = new ConfigurationPage(page);
  await cfg.gotoAiProviders();

  if (anthropic && (await cfg.ensureProviderConfigured('Anthropic', anthropic))) {
    return 'Claude Haiku 4.5';
  }
  if (openai && (await cfg.ensureProviderConfigured('OpenAI', openai))) {
    return 'GPT 4o Mini';
  }
  if (
    openrouter &&
    (await cfg.ensureCustomProviderConfigured({
      providerName: 'openrouter',
      baseUrl: 'https://openrouter.ai/api/v1',
      apiKey: openrouter,
      models: 'openai/gpt-4o-mini',
    }))
  ) {
    return 'openai/gpt-4o-mini';
  }

  test.skip(
    true,
    'No configured provider is offered by this deployment (none of Anthropic, OpenAI, or the OpenRouter Custom Provider is available for the keys present)',
  );
  return '';
}
