/**
 * Base error class for all metric-related errors.
 */
export class MetricError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "MetricError";

    // Maintains proper stack trace for where our error was thrown (only available on V8)
    if (Error.captureStackTrace) {
      Error.captureStackTrace(this, MetricError);
    }
  }
}

/**
 * Error thrown when metric computation fails.
 *
 * This can happen due to invalid inputs, model failures, or parsing errors.
 */
export class MetricComputationError extends MetricError {
  /**
   * Creates a new MetricComputationError.
   *
   * @param message - Error message describing what went wrong
   * @param cause - Optional underlying error that caused this error
   */
  constructor(
    message: string,
    public readonly cause?: Error
  ) {
    super(message);
    this.name = "MetricComputationError";

    if (Error.captureStackTrace) {
      Error.captureStackTrace(this, MetricComputationError);
    }
  }
}

/**
 * Error thrown when JSON parsing fails.
 *
 * This typically occurs when LLM responses cannot be parsed as valid JSON.
 */
export class JSONParsingError extends MetricError {
  /**
   * Creates a new JSONParsingError.
   *
   * @param message - Error message describing what went wrong
   * @param cause - Optional underlying error that caused this error
   */
  constructor(
    message: string,
    public readonly cause?: Error
  ) {
    super(message);
    this.name = "JSONParsingError";

    if (Error.captureStackTrace) {
      Error.captureStackTrace(this, JSONParsingError);
    }
  }
}
