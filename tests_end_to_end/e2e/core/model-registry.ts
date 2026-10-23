/**
 * Reading the deployment's own LLM model registry — `GET /v1/private/llm/models`.
 *
 * The frontend seeds `modelRegistryStore` from the in-tree `PROVIDER_MODELS`
 * constant and then OVERWRITES it with this endpoint's answer on mount, so this
 * is what the panel-gating utilities actually read at runtime. A spec whose
 * expected outcome depends on which models a provider lists — rather than on
 * the frontend logic alone — has to assert that precondition against this, not
 * against the constant.
 *
 * Fetch rather than the suite's SDK client for the same reason as
 * `core/provider-keys.ts`: the generated SDK has no surface for the registry.
 */
import { loadEnvConfig } from '../config/env.config';

export interface RegistryModel {
  /** The id the frontend matches on, e.g. `gpt-5-nano` or `openai/gpt-5-nano`. */
  id: string;
  label: string;
  /** The registry's own reasoning flag. NOT what gates an OpenRouter model. */
  reasoning: boolean;
  structured_output: boolean;
}

/** Every provider the registry answers for, each with its model list. */
export type RegistryModelsMap = Record<string, RegistryModel[]>;

export async function fetchRegistryModels(): Promise<RegistryModelsMap> {
  const env = loadEnvConfig();
  const response = await fetch(`${env.apiBaseUrl}/v1/private/llm/models`, {
    headers: {
      ...(env.apiKey ? { authorization: env.apiKey } : {}),
      ...(env.workspace ? { 'Comet-Workspace': env.workspace } : {}),
    },
  });
  if (!response.ok) {
    throw new Error(`GET /v1/private/llm/models returned ${response.status}`);
  }
  return (await response.json()) as RegistryModelsMap;
}

/** The model ids one provider lists, as a set for membership questions. */
export function registryModelIds(
  models: RegistryModelsMap,
  provider: string,
): ReadonlySet<string> {
  return new Set((models[provider] ?? []).map((model) => model.id));
}

/**
 * One provider's `reasoning` flag for a model id, or `undefined` when the
 * provider does not list it.
 *
 * Returned rather than defaulted to `false`: "the registry says this model is
 * not a reasoning model" and "the registry has never heard of this model" are
 * different facts, and a spec asserting the first must not be satisfied by the
 * second.
 */
export function registryReasoningFlag(
  models: RegistryModelsMap,
  provider: string,
  id: string,
): boolean | undefined {
  return (models[provider] ?? []).find((model) => model.id === id)?.reasoning;
}
