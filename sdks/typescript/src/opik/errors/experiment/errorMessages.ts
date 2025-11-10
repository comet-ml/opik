/**
 * Error messages for experiment operations.
 * Centralizing messages here makes localization and maintenance easier.
 */
export const experimentErrorMessages = {
  EXPERIMENT_NOT_FOUND: (name: string) =>
    `Experiment with name '${name}' not found`,

  INVALID_CONFIG_TYPE: (type: string) =>
    `experimentConfig must be a plain object, but ${type} was provided. ` +
    `Please provide a valid configuration object like { model: 'gpt-4', temperature: 0.7 }`,

  CONFIG_PROMPTS_CONFLICT: () =>
    "Cannot specify both 'prompts' parameter and 'experimentConfig.prompts'. " +
    "Choose one approach:\n" +
    "  1. Use prompts parameter: buildMetadataAndPromptVersions(config, [prompt1, prompt2])\n" +
    "  2. Use config only: buildMetadataAndPromptVersions({ ...config, prompts: {...} })\n" +
    "\nThe prompts parameter is recommended for prompt version tracking.",
};
