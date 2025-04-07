export type FeatureToggles = Record<FeatureToggleKeys, boolean>;

export enum FeatureToggleKeys {
  PYTHON_EVALUATOR_ENABLED = "python_evaluator_enabled",
  GUARDRAILS_ENABLED = "guardrails_enabled",
}
