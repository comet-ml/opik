/**
 * Error messages for experiment operations.
 * Centralizing messages here makes localization and maintenance easier.
 */
export const experimentErrorMessages = {
  EXPERIMENT_NOT_FOUND: (name: string) =>
    `Experiment with name '${name}' not found`,
};
