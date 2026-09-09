/**
 * Parameters a calling surface may not be able to store, and so must not offer a control for.
 *
 * Only the ones whose control is *not* already governed by the config carrying the key: a panel
 * renders one slider per parameter its config holds, which is enough for the surfaces whose config
 * simply omits what they cannot keep. These five are the exceptions — the effort dropdowns and the
 * Anthropic sampling pair are gated on the model's capabilities instead, and the two runner
 * controls fall back to a default rather than hiding, so absence from the config says nothing.
 */
export type ModelConfigParam =
  | "topP"
  | "reasoningEffort"
  | "thinkingEffort"
  | "throttling"
  | "maxConcurrentRequests";

/**
 * An evaluator rule persists only LlmAsJudgeModelParameters — name, temperature, seed and the
 * free-form custom_parameters. Everything else the rule form used to render was dropped on save.
 */
export const RULE_UNSUPPORTED_PARAMS: ReadonlySet<ModelConfigParam> = new Set([
  "topP",
  "reasoningEffort",
  "thinkingEffort",
  "throttling",
  "maxConcurrentRequests",
]);

/**
 * The optimizer forwards model parameters to the provider but schedules its own runs; throttling
 * and max concurrency belong to the playground's batch runner and reach nothing from here.
 */
export const OPTIMIZATION_UNSUPPORTED_PARAMS: ReadonlySet<ModelConfigParam> =
  new Set(["throttling", "maxConcurrentRequests"]);
