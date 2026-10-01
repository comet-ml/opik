/**
 * Parameters a calling surface may not be able to store, and so must not offer a control for.
 *
 * Only the ones whose control is *not* already governed by the config carrying the key: a panel
 * renders one slider per parameter its config holds, which is enough for the surfaces whose config
 * simply omits what they cannot keep. These are the exceptions — the effort dropdowns and the
 * Anthropic sampling pair are gated on the model's capabilities instead, and the runner controls
 * (plus Anthropic's max output tokens) fall back to a default rather than hiding, so absence from
 * the config says nothing.
 *
 * The plain max-output-tokens sliders check this list too, so it still holds when a config carries
 * the key. Temperature is left out on purpose: every surface stores it, and listing it would also
 * mean teaching the Claude sampling choice about it.
 */
export type ModelConfigParam =
  | "topP"
  | "maxCompletionTokens"
  | "reasoningEffort"
  | "thinkingEffort"
  | "throttling"
  | "maxConcurrentRequests";

/**
 * An evaluator rule persists only LlmAsJudgeModelParameters — name, temperature, seed and the
 * free-form custom_parameters. Everything else the rule form used to render was dropped on save.
 * The Anthropic effort is kept because it is saved inside custom_parameters.output_config.
 */
export const RULE_UNSUPPORTED_PARAMS: ReadonlySet<ModelConfigParam> = new Set([
  "topP",
  "maxCompletionTokens",
  "reasoningEffort",
  "throttling",
  "maxConcurrentRequests",
]);

/**
 * The optimizer forwards model parameters to the provider but schedules its own runs; throttling
 * and max concurrency belong to the playground's batch runner and reach nothing from here.
 */
export const OPTIMIZATION_UNSUPPORTED_PARAMS: ReadonlySet<ModelConfigParam> =
  new Set(["throttling", "maxConcurrentRequests"]);

/**
 * A run against a dataset executes on the server, which schedules its own work: these two control
 * the browser's batch loop and reach nothing once the run leaves the page.
 */
export const BACKEND_RUN_UNSUPPORTED_PARAMS: ReadonlySet<ModelConfigParam> =
  new Set(["throttling", "maxConcurrentRequests"]);
