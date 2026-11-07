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
  /**
   * The row's display label — and the flag that decides whether any model
   * picker LISTS it.
   *
   * Optional and nullable, never defaulted: `scripts/sync_provider_models.py`'s
   * convention is that a dropdown-curated row carries a label and every other
   * row OMITS THE KEY ENTIRELY, which is how `/v1/private/llm/models` serves
   * the 420 unlabelled OpenRouter rows on staging for 2.2.132. So "no label" is
   * a fact about the picker rather than missing data, and a caller asking which
   * rows are offered has to read it through {@link listsInPicker} — the same
   * `Boolean(m.label)` the frontend's own `isDropdownVisible` computes — not by
   * comparing against `null` or `''`, either of which answers only one of the
   * two shapes the endpoint uses.
   */
  label?: string | null;
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

/**
 * Whether a registry row is offered by the model pickers.
 *
 * This IS the frontend's rule, not a paraphrase of it:
 * `useLLMProviderModelsData` filters every dropdown with
 * `isDropdownVisible = Boolean(m.label)`. Mirrored here rather than asserted as
 * `label === null` because the endpoint expresses "no label" as an absent key,
 * so a spec comparing against one shape would silently stop discriminating if
 * the serializer started emitting the other.
 */
export function listsInPicker(model: RegistryModel): boolean {
  return Boolean(model.label);
}

/**
 * Two ids one provider serves, one the pickers LIST and one they omit.
 *
 * The pair, not either half alone, is what makes an assertion about a stored
 * unlisted id discriminating: the listed id proves the picker's option list and
 * its search really do offer this provider's models on this deployment, so
 * "the unlisted id is in no option list" is a verdict rather than a dead check
 * over a list that was empty all along.
 */
export interface RegistryListingPair {
  /** An id the provider serves and the pickers omit — it carries no label. */
  unlisted: string;
  /** An id the provider serves and the pickers offer. */
  listed: string;
  /**
   * The label the listed row carries, which is what a picker renders for it.
   * Returned rather than assumed equal to `listed`: it is only on OpenRouter
   * that the two coincide.
   */
  listedLabel: string;
}

/**
 * Ids excluded from the `unlisted` pick because they are unlisted for a reason
 * other than the label convention, and would make the pair prove the wrong
 * thing.
 *
 * `DECISION_MODELS` (`constants/decisionModels.ts`) are not in the registry at
 * all; `useLLMProviderModelsData` splices them into the RESOLUTION map only, so
 * a picker resolves them while never offering them. A `:batch` id is served
 * only through OpenRouter's batch API, and `NewRunSidebar` refuses to treat one
 * as runnable whatever its label says — so a rerun seeded on one would be
 * replaced for that reason and the spec would read as a regression.
 */
const NOT_UNLISTED_BY_LABEL = (id: string): boolean =>
  id.startsWith('~') || id.startsWith('typesafe/') || id.includes(':');

/**
 * Pick a listed/unlisted pair out of one provider's registry rows.
 *
 * Picked at RUNTIME and never pinned, because which ids carry a label is
 * regenerated by `scripts/sync_provider_models.py` on every provider-model sync
 * — opik#9277 alone moved eight of them — so a spec naming one would be
 * asserting the artifact and would go quietly vacuous the moment the sync
 * labelled it. Chosen as the lexicographically first of each kind so two runs
 * against one deployment agree, which is what makes a failure reproducible.
 *
 * Both halves are REQUIRED: this throws rather than returning a partial pair,
 * because a caller that fell back to a single id would be asserting without its
 * control.
 */
export function pickRegistryListingPair(
  models: RegistryModelsMap,
  provider: string,
): RegistryListingPair {
  const rows = models[provider];
  if (!Array.isArray(rows)) {
    throw new Error(
      `pickRegistryListingPair: the registry carries no '${provider}' provider ` +
        `(it has: ${Object.keys(models).join(', ') || 'nothing'})`,
    );
  }
  const byId = (a: RegistryModel, b: RegistryModel) => (a.id < b.id ? -1 : 1);
  const unlisted = rows
    .filter((row) => !listsInPicker(row) && !NOT_UNLISTED_BY_LABEL(row.id))
    .sort(byId)[0];
  const listed = rows.filter(listsInPicker).sort(byId)[0];
  if (!unlisted || !listed) {
    throw new Error(
      `pickRegistryListingPair: '${provider}' serves ${rows.length} models but ` +
        `${unlisted ? 'none without' : 'none with'} a label — no listed/unlisted ` +
        'pair to pick, so a spec about an unlisted id has no control',
    );
  }
  return { unlisted: unlisted.id, listed: listed.id, listedLabel: listed.label! };
}
