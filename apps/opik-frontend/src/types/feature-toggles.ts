export interface FeatureToggles {
  python_evaluator_enabled: boolean;
  guardrails_enabled: boolean;
}

export enum FeatureToggleKey {
  PYTHON_EVALUATOR = "python_evaluator_enabled",
  GUARDRAILS = "guardrails_enabled",
}
