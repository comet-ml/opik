export type FeatureToggles = Record<FeatureToggleKeys, boolean>;

export enum FeatureToggleKeys {
  PYTHON_EVALUATOR_ENABLED = "python_evaluator_enabled",
  GUARDRAILS_ENABLED = "guardrails_enabled",
  TOGGLE_OPIK_AI_ENABLED = "opik_aienabled",
  TOGGLE_ALERTS_ENABLED = "alerts_enabled",
}
