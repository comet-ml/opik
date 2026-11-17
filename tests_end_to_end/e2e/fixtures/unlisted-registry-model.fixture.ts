import { test as baseTest, expect } from './image-model-token-cost-spans.fixture';
import { registerUnbilledModel } from '../core/llm-model-policy';
import {
  fetchRegistryModels,
  listsInPicker,
  pickRegistryListingPair,
  type RegistryModelsMap,
} from '../core/model-registry';

/**
 * The provider whose catalog the pair is picked from.
 *
 * OpenRouter rather than one of the native lists because it is the only
 * provider whose registry rows are numerous enough that both kinds exist on
 * every deployment (789 rows on staging for 2.2.132, 369 of them labelled) —
 * and because opik#9277, the change these specs were written for, hid eight
 * `openai/*` ids inside this very list.
 */
export const UNLISTED_MODEL_PROVIDER = 'openrouter';

export interface UnlistedRegistryModelRef {
  /**
   * An id this deployment's registry serves and no model picker offers: its
   * row carries no `label`, which is the only thing `isDropdownVisible` reads.
   */
  unlisted: string;
  /** An id the same provider serves and the pickers DO offer — the control. */
  listed: string;
  /** The label a picker renders for `listed`. */
  listedLabel: string;
  /** The whole registry answer, for a spec that wants to assert more of it. */
  registry: RegistryModelsMap;
}

export interface UnlistedRegistryModelFixtures {
  unlistedRegistryModel: UnlistedRegistryModelRef;
}

/**
 * A pair of model ids this deployment serves — one the pickers list, one they
 * do not — and the proof that the pair really is a pair.
 *
 * ## What the pair is for
 *
 * Every model picker in the product renders from the DROPDOWN map, which
 * `useLLMProviderModelsData` filters with `isDropdownVisible = Boolean(m.label)`,
 * and falls back to the raw stored value for anything it cannot find there
 * (`groupOptions.find(m => m.value === selectedValue)?.label ?? value`). So an
 * id the registry still serves without a label is a config a user can be
 * holding and no picker will offer — the state opik#9277 created for eight
 * `openai/*` ids in one commit. The `listed` half is the control: it is what
 * says the option list and its search are populated at all on this run, so a
 * "no option offers the unlisted id" assertion is a verdict rather than a
 * vacuous pass over an empty list.
 *
 * ## Why nothing is pinned
 *
 * `scripts/sync_provider_models.py` regenerates which rows carry a label on
 * every provider-model sync, so a named id would be asserting the artifact and
 * would go silently vacuous the first time the sync labelled it. See
 * `pickRegistryListingPair` for how the pick stays deterministic.
 *
 * ## Why this bills nothing, even though the ids are real and unvetted
 *
 * The pick is whatever the catalog offers first, so it can be any model at any
 * price — including one `llm-model-policy` would refuse by name. That is safe
 * here and only here: every spec taking this fixture reads a picker, a stored
 * blob or a form, and none of them ever presses Run or Optimize, so no request
 * is built for either id and none reaches a provider. The ids are registered
 * unbilled on exactly that basis, the same basis `openRouterNativeModels` uses.
 * Anyone extending a spec on this fixture to launch something must pick its
 * model from the allowlist instead.
 *
 * ## Why nothing is torn down
 *
 * It creates nothing. The OpenRouter key it depends on is ensured by
 * `openRouterProvider`, which is strictly additive for the reason that fixture
 * documents: a built-in provider is one row per workspace, so deleting it would
 * pull OpenRouter out from under any spec running in parallel.
 */
export const test = baseTest.extend<UnlistedRegistryModelFixtures>({
  unlistedRegistryModel: async ({ openRouterProvider }, use) => {
    expect(
      openRouterProvider.configured,
      'the workspace has an OpenRouter key, or its registry rows reach no picker ' +
        'and both halves of the pair would look unlisted',
    ).toBe(true);

    const registry = await fetchRegistryModels();
    const pair = pickRegistryListingPair(registry, UNLISTED_MODEL_PROVIDER);
    for (const model of [pair.unlisted, pair.listed]) registerUnbilledModel(model);

    // The premise, as a server-side fact, before any browser opens. Each half
    // is asserted on its own: a registry that had labelled every row, and one
    // that had labelled none, both break the pair, and they break it in
    // opposite directions that a single combined assertion could not name.
    const rows = registry[UNLISTED_MODEL_PROVIDER] ?? [];
    const unlistedRow = rows.find((row) => row.id === pair.unlisted);
    const listedRow = rows.find((row) => row.id === pair.listed);
    expect(
      unlistedRow,
      `the registry serves '${pair.unlisted}' under ${UNLISTED_MODEL_PROVIDER}`,
    ).toBeDefined();
    expect(
      listsInPicker(unlistedRow!),
      'and serves it with NO label, which is what keeps it out of every picker',
    ).toBe(false);
    expect(
      listedRow,
      `the registry serves '${pair.listed}' under ${UNLISTED_MODEL_PROVIDER}`,
    ).toBeDefined();
    expect(
      listsInPicker(listedRow!),
      'and the control really does carry a label, so the pickers offer it',
    ).toBe(true);
    expect(
      listedRow!.label,
      'with the label a picker will render for it',
    ).toBe(pair.listedLabel);
    expect(
      pair.unlisted,
      'the two halves are different models — a pair of one proves nothing',
    ).not.toBe(pair.listed);

    await use({ ...pair, registry });
  },
});

export { expect };
