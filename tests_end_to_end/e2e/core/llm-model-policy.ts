/**
 * The only models the e2e suite is allowed to drive, and the guard that enforces it.
 *
 * Two separate failure modes make an allowlist worth having over careful call
 * sites:
 *
 *  1. The model pickers default to the FIRST entry of a provider's list in
 *     `providerModels.ts`, which is the newest and most expensive model — today
 *     "GPT 6 Astra". Any path that opens a picker and submits without a
 *     successful selection silently runs on it.
 *  2. Provider lists are regenerated as vendors ship models, so today's cheap
 *     default is tomorrow's flagship. A spec pinned by display name keeps
 *     working while quietly changing what it costs.
 *
 * A spec that asks for something off this list is a bug in the spec, so this
 * throws rather than falling back — a loud failure is recoverable, an expensive
 * silent success is not.
 */

/** Display names as rendered in the model pickers. */
export const ALLOWED_MODEL_DISPLAY_NAMES = [
  'Claude Haiku 4.5',
  'GPT 4o Mini',
  // ensureModelAvailable's OpenRouter fallback returns this id as the display
  // name, because that is what the Custom Provider renders in the picker.
  'openai/gpt-4o-mini',
  // playground-model-parameters only: it is the one model exposing both
  // controls under test (thinking effort AND the sampling toggle), and the spec
  // now stubs the completion at the browser, so selecting it bills nothing.
  'Claude Sonnet 4.6',
  // The @provider-sanity matrix (data/playground-models.yaml) exists to prove
  // each provider can still be reached, so a real call is the point of it. It
  // runs on its own cadence, outside the t1/t2/t3 ladder, on a cheap model per
  // provider.
  'Gemini 2.5 Flash',
] as const;

/** Fully-qualified ids passed to the SDK/LiteLLM rather than picked in the UI. */
export const ALLOWED_MODEL_IDS = [
  'anthropic/claude-haiku-4-5',
  'openai/gpt-4o-mini',
  'openai/gpt-5-mini',
] as const;

/** Substrings that must never appear in a model this suite selects. */
const FORBIDDEN = [
  'astra', // the current default-and-most-expensive OpenAI entry
  'opus',
  'fable',
  'gpt-6',
  'gpt 6',
  'o1',
  'o3',
  'pro',
];

/**
 * Models a spec seeded on a provider that cannot bill — an unreachable base
 * URL, or the local mock gateway. Populated at seed time by the helpers that
 * create them (see fixtures/provider-key.fixture.ts), so the exemption covers
 * exactly the models the run created and nothing else.
 *
 * A registry rather than a name pattern: "looks like a fake model" is precisely
 * the judgement an allowlist exists to avoid making, and the seeded names are
 * namespaced per run (`${testNamespace}-dead-model`), so no fixed list can
 * anticipate them.
 */
const unbilledModels = new Set<string>();

/**
 * Declare that `name` runs on a provider that cannot reach a paid API, so the
 * guard should let it through. Called by the seeding helpers, not by specs.
 */
export function registerUnbilledModel(name: string): void {
  const trimmed = (name ?? '').trim();
  if (trimmed) unbilledModels.add(trimmed);
}

/** True when a seeding helper registered this model as unable to bill. */
export function isUnbilledModel(name: string): boolean {
  return unbilledModels.has((name ?? '').trim());
}

function reject(kind: string, value: string, reason: string): never {
  throw new Error(
    `[llm-model-policy] refusing to select ${kind} "${value}": ${reason}. ` +
      `Allowed display names: ${ALLOWED_MODEL_DISPLAY_NAMES.join(', ')}. ` +
      `Allowed ids: ${ALLOWED_MODEL_IDS.join(', ')}.`,
  );
}

/**
 * Assert a model display name is one the suite may drive. Call this in every POM
 * that selects a model, so an unpinned or drifted picker fails the spec instead
 * of running on the provider's newest model.
 */
function assertAllowed(
  kind: string,
  value: string,
  allowed: readonly string[],
  emptyReason: string,
): void {
  const trimmed = (value ?? '').trim();
  if (!trimmed) reject(kind, value, emptyReason);

  // A model the run itself seeded on a provider that cannot bill.
  if (isUnbilledModel(trimmed)) return;

  const lowered = trimmed.toLowerCase();
  const hit = FORBIDDEN.find((f) => lowered.includes(f));
  if (hit) reject(kind, trimmed, `matches the forbidden fragment "${hit}"`);

  if (!allowed.includes(trimmed)) reject(kind, trimmed, 'not on the allowlist');
}

/**
 * Assert a model display name is one the suite may drive. Call this in every POM
 * that selects a model, so an unpinned or drifted picker fails the spec instead
 * of running on the provider's newest model.
 */
export function assertAllowedModelDisplayName(displayName: string): void {
  assertAllowed(
    'model',
    displayName,
    ALLOWED_MODEL_DISPLAY_NAMES,
    'empty selection would leave the picker on its default',
  );
}

/** The id variant, for models handed to the SDK instead of picked in the UI. */
export function assertAllowedModelId(modelId: string): void {
  assertAllowed('model id', modelId, ALLOWED_MODEL_IDS, 'empty');
}
