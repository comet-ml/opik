/**
 * Base error class for all model-related errors.
 */
export class ModelError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ModelError";

    // Maintains proper stack trace for where our error was thrown (only available on V8)
    if (Error.captureStackTrace) {
      Error.captureStackTrace(this, ModelError);
    }
  }
}

/**
 * Error thrown when model generation fails.
 *
 * This can happen due to API errors, network issues, invalid inputs, etc.
 */
export class ModelGenerationError extends ModelError {
  /**
   * Creates a new ModelGenerationError.
   *
   * @param message - Error message describing what went wrong
   * @param cause - Optional underlying error that caused this error
   */
  constructor(
    message: string,
    public readonly cause?: Error
  ) {
    super(message);
    this.name = "ModelGenerationError";

    if (Error.captureStackTrace) {
      Error.captureStackTrace(this, ModelGenerationError);
    }
  }
}

/**
 * Error thrown when model configuration is invalid.
 */
export class ModelConfigurationError extends ModelError {
  constructor(message: string) {
    super(message);
    this.name = "ModelConfigurationError";

    if (Error.captureStackTrace) {
      Error.captureStackTrace(this, ModelConfigurationError);
    }
  }
}
