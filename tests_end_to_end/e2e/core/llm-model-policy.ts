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
  // Request-shape only. playground-model-parameters asserts on the request the
  // browser SENDS and never awaits a completion, and Sonnet 4.6 is the one model
  // exposing both controls under test (thinking effort AND the sampling toggle);
  // its newer siblings set supportsSamplingParams: false. Costs an input token,
  // not a generation — allowed for that reason and no other.
  'Claude Sonnet 4.6',
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
export function assertAllowedModelDisplayName(displayName: string): void {
  const value = (displayName ?? '').trim();
  if (!value) reject('model', displayName, 'empty selection would leave the picker on its default');

  const lowered = value.toLowerCase();
  const hit = FORBIDDEN.find((f) => lowered.includes(f));
  if (hit) reject('model', value, `matches the forbidden fragment "${hit}"`);

  if (!(ALLOWED_MODEL_DISPLAY_NAMES as readonly string[]).includes(value)) {
    reject('model', value, 'not on the allowlist');
  }
}

/** The id variant, for models handed to the SDK instead of picked in the UI. */
export function assertAllowedModelId(modelId: string): void {
  const value = (modelId ?? '').trim();
  if (!value) reject('model id', modelId, 'empty');

  const lowered = value.toLowerCase();
  const hit = FORBIDDEN.find((f) => lowered.includes(f));
  if (hit) reject('model id', value, `matches the forbidden fragment "${hit}"`);

  if (!(ALLOWED_MODEL_IDS as readonly string[]).includes(value)) {
    reject('model id', value, 'not on the allowlist');
  }
}
