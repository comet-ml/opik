export type FeatureToggles = Record<FeatureToggleKeys, boolean>;

export enum FeatureToggleKeys {
  PYTHON_EVALUATOR_ENABLED = "python_evaluator_enabled",
  GUARDRAILS_ENABLED = "guardrails_enabled",
  TOGGLE_OPIK_AI_ENABLED = "opik_aienabled",
  TOGGLE_ALERTS_ENABLED = "alerts_enabled",
  WELCOME_WIZARD_ENABLED = "welcome_wizard_enabled",
  CSV_UPLOAD_ENABLED = "csv_upload_enabled",
  EXPORT_ENABLED = "export_enabled",
  OPTIMIZATION_STUDIO_ENABLED = "optimization_studio_enabled",
}
