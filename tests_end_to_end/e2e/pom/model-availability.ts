import type { Page } from '@playwright/test';
import { test } from '@e2e/fixtures';
import { ConfigurationPage } from '@e2e/pom/configuration.page';
import { anthropicKeyUsable } from '@e2e/core/llm-key-preflight';
import { mockAuthSkipReason } from '@e2e/core/mock-auth';
import { ensureBuiltInProviderKey } from '@e2e/core/provider-keys';
import type { ProviderKeysFixture } from '@e2e/fixtures/provider-key.fixture';

/**
 * Provision a working provider on the suite's mock gateway and return the model to select.
 *
 * For specs whose subject is what Opik does with a completion (traces, experiments,
 * prompt links, scores), not what a real model says: the mock answers every chat and
 * LLM-judge call for free. It runs on the test runner, so a backend that cannot reach
 * it (any remote deployment) skips the spec. Real-provider coverage is the @llm-daily
 * canary in playground-providers.spec.ts.
 */
export async function ensureMockModelAvailable(
  providerKeys: ProviderKeysFixture,
  testNamespace: string,
): Promise<string> {
  const reason = await mockAuthSkipReason();
  test.skip(reason !== null, reason ?? '');
  const model = `${testNamespace}-mock-model`;
  await providerKeys.createOauth({ providerName: `${testNamespace}-mock`, modelNames: [model] });
  return model;
}

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
  // anthropicKeyUsable(), not the raw env var: the preflight blanks a key it
  // proved dead, and this keeps the UI-configured provider consistent with
  // the credential the SDK driver actually holds.
  const anthropic = anthropicKeyUsable() ? process.env.ANTHROPIC_API_KEY : undefined;
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

/**
 * The one Vertex AI model the suite drives, and the three names it goes by.
 *
 * Kept together because a Vertex spec needs all three and they differ: the
 * picker renders `displayName` — which the GEMINI group also renders, for
 * `gemini-2.5-flash`, so a selection must always name its provider group — the
 * backend and the span logged for a run call it `modelId`, and the provider-key
 * row and the span's `provider` field call it `provider`.
 *
 * `displayName` is on `ALLOWED_MODEL_DISPLAY_NAMES` already, put there for the
 * provider matrix: it is the cheapest model that answers on Vertex.
 */
export const VERTEX_AI_MODEL = {
  displayName: 'Gemini 2.5 Flash',
  modelId: 'vertex_ai/gemini-2.5-flash',
  provider: 'vertex-ai',
} as const;

/**
 * The runner's Vertex AI service-account key, or skip.
 *
 * There is no cheaper gate and no stand-in, which is why every Vertex spec
 * carries `@llm-daily` rather than a tier. A Vertex credential is a
 * service-account JSON that the backend exchanges for an OAuth token at a
 * GOOGLE host — `VERTEX_AI_TOKEN_URI` refuses any other — so the suite's mock
 * gateway cannot answer as Vertex the way it answers as a Custom provider, and
 * the placeholder `modelRegistryProviders` writes into an empty slot is not a
 * credential at all. A spec whose subject IS the Vertex client therefore needs
 * the real key or nothing.
 */
export function vertexAiKeyOrSkip(): string {
  const apiKey = process.env.VERTEX_AI_API_KEY;
  test.skip(
    !apiKey,
    'VERTEX_AI_API_KEY not set in this env — the Vertex AI client can only be exercised ' +
      'against a real service-account key',
  );
  return apiKey!;
}

/**
 * Make sure the workspace has a Vertex AI provider key, through the AI Providers
 * UI, and skip when this deployment does not offer Vertex AI at all.
 *
 * The UI route rather than `ensureVertexAiProviderKey` because the add-provider
 * dialog is the only place that reports the SECOND reason a Vertex spec cannot
 * run: a deployment whose provider toggle is off renders no Vertex tile, and a
 * spec that had provisioned the key over REST regardless would discover that
 * much later, as a model picker that never offers the option.
 *
 * Additive and idempotent, like every provider-key helper here — a built-in
 * provider is one row per workspace, so a workspace that already holds a Vertex
 * key keeps the key it has (see `ensureBuiltInProviderKey` for why nothing here
 * ever overwrites or deletes one).
 */
export async function ensureVertexAiProviderConfigured(page: Page): Promise<void> {
  const apiKey = vertexAiKeyOrSkip();
  const cfg = new ConfigurationPage(page);
  await cfg.gotoAiProviders();
  const offered = await cfg.ensureProviderConfigured('Vertex AI', apiKey);
  test.skip(!offered, 'Vertex AI is not offered by this deployment');
}

/**
 * The same provisioning for a spec that drives no browser.
 *
 * An API-level spec about a backend path should not pay for a page just to fill
 * a form, so this takes the REST route and gives up the "is the provider
 * offered" signal its UI sibling carries: without a dialog to read, a
 * deployment that does not serve Vertex surfaces as the call itself failing,
 * which is a legible failure rather than a silent pass.
 */
export async function ensureVertexAiProviderKey(): Promise<void> {
  await ensureBuiltInProviderKey(VERTEX_AI_MODEL.provider, vertexAiKeyOrSkip());
}
