import { OpikError } from "../BaseError";
import { experimentErrorMessages } from "./errorMessages";

const experimentErrorCodes = {
  EXPERIMENT_NOT_FOUND: "EXPERIMENT_NOT_FOUND",
  INVALID_CONFIG_TYPE: "INVALID_CONFIG_TYPE",
  CONFIG_PROMPTS_CONFLICT: "CONFIG_PROMPTS_CONFLICT",
};

export class ExperimentNotFoundError extends OpikError {
  constructor(name: string) {
    super({
      message: experimentErrorMessages.EXPERIMENT_NOT_FOUND(name),
      code: experimentErrorCodes.EXPERIMENT_NOT_FOUND,
    });
  }
}

/**
 * Error thrown when experiment configuration validation fails.
 * This includes invalid config types and conflicts between parameters.
 */
export class ExperimentConfigError extends OpikError {
  constructor(message: string, code: string) {
    super({
      message,
      code,
    });
  }

  /**
   * Creates an error for invalid config type
   */
  static invalidConfigType(type: string): ExperimentConfigError {
    return new ExperimentConfigError(
      experimentErrorMessages.INVALID_CONFIG_TYPE(type),
      experimentErrorCodes.INVALID_CONFIG_TYPE
    );
  }

  /**
   * Creates an error for prompts parameter conflict
   */
  static promptsConflict(): ExperimentConfigError {
    return new ExperimentConfigError(
      experimentErrorMessages.CONFIG_PROMPTS_CONFLICT(),
      experimentErrorCodes.CONFIG_PROMPTS_CONFLICT
    );
  }
}
