export type FeatureToggles = Record<FeatureToggleKeys, boolean>;

export enum FeatureToggleKeys {
  PYTHON_EVALUATOR_ENABLED = "python_evaluator_enabled",
  GUARDRAILS_ENABLED = "guardrails_enabled",
  TOGGLE_OPIK_AI_ENABLED = "opik_aienabled",
  TOGGLE_ALERTS_ENABLED = "alerts_enabled",
  WELCOME_WIZARD_ENABLED = "welcome_wizard_enabled",
  CSV_UPLOAD_ENABLED = "csv_upload_enabled",
  EXPORT_ENABLED = "export_enabled",
  DATASET_VERSIONING_ENABLED = "dataset_versioning_enabled",
  SPAN_LLM_AS_JUDGE_ENABLED = "span_llm_as_judge_enabled",
  SPAN_USER_DEFINED_METRIC_PYTHON_ENABLED = "span_user_defined_metric_python_enabled",
  OPTIMIZATION_STUDIO_ENABLED = "optimization_studio_enabled",
  // LLM Provider feature flags
  OPENAI_PROVIDER_ENABLED = "openai_provider_enabled",
  ANTHROPIC_PROVIDER_ENABLED = "anthropic_provider_enabled",
  GEMINI_PROVIDER_ENABLED = "gemini_provider_enabled",
  OPENROUTER_PROVIDER_ENABLED = "openrouter_provider_enabled",
  VERTEXAI_PROVIDER_ENABLED = "vertexai_provider_enabled",
  BEDROCK_PROVIDER_ENABLED = "bedrock_provider_enabled",
  CUSTOMLLM_PROVIDER_ENABLED = "customllm_provider_enabled",
  COLLABORATORS_TAB_ENABLED = "collaborators_tab_enabled",
}
