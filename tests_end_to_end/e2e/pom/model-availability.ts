import type { Page } from '@playwright/test';
import { test } from '@e2e/fixtures';
import { ConfigurationPage } from '@e2e/pom/configuration.page';

/**
 * Providers that may already be configured on the target workspace, mapped to
 * the model display name to select for each — same providers and same
 * preference order as the env-key paths below, keyed by the `data-provider`
 * value the AI Providers table stamps on each row.
 *
 * `opik-free` is deliberately absent: it is a read-only, restricted free tier
 * with a single model, not a provider a spec should silently fall back onto.
 */
const PRE_CONFIGURED_PROVIDER_MODELS: ReadonlyArray<readonly [string, string]> = [
  ['anthropic', 'Claude Haiku 4.5'],
  ['openai', 'GPT 4o Mini'],
];

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
 *
 * An env key is how a runner *provisions* a provider — it is not what makes one
 * usable. A long-lived workspace normally already holds its keys server-side, in
 * which case no runner env var is involved and the self-provisioning paths have
 * nothing to do; that case is handled last, before giving up. Skipping on
 * "no env key" alone would silently drop every LLM-driving spec on exactly the
 * environments where they work.
 */
export async function ensureModelAvailable(page: Page): Promise<string> {
  const anthropic = process.env.ANTHROPIC_API_KEY;
  const openai = process.env.OPENAI_API_KEY;
  const openrouter = process.env.OPENROUTER_API_KEY;

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

  // Nothing was provisioned from env — fall back to a key the workspace
  // already carries.
  const configured = await cfg.listConfiguredProviders();
  for (const [providerType, modelDisplayName] of PRE_CONFIGURED_PROVIDER_MODELS) {
    if (configured.includes(providerType)) return modelDisplayName;
  }

  test.skip(
    true,
    'No LLM provider is usable on this deployment: none of ANTHROPIC_API_KEY, ' +
      'OPENAI_API_KEY or OPENROUTER_API_KEY could be provisioned, and the workspace ' +
      `carries no pre-configured provider this suite knows a model for (found: ${
        configured.join(', ') || 'none'
      })`,
  );
  return '';
}
