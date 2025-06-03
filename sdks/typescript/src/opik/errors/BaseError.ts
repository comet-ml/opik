import { logger } from "@/utils/logger";

/**
 * Base error class for all Opik SDK errors.
 * Provides standardized structure for error handling across the SDK.
 */
export class OpikError extends Error {
  public readonly code: string;
  public readonly statusCode?: number;
  public readonly details?: Record<string, unknown>;
  public readonly originalError?: Error;

  constructor(options: {
    message: string;
    code: string;
    statusCode?: number;
    details?: Record<string, unknown>;
    originalError?: Error;
  }) {
    super(options.message);
    this.name = this.constructor.name;
    this.code = options.code;
    this.statusCode = options.statusCode;
    this.details = options.details;
    this.originalError = options.originalError;

    logger.error(this.message);

    // Captures stack trace properly in TypeScript
    Error.captureStackTrace?.(this, this.constructor);
  }

  /**
   * Converts the error to a JSON object for serialization.
   */
  toJSON() {
    return {
      name: this.name,
      message: this.message,
      code: this.code,
      statusCode: this.statusCode,
      details: this.details,
      originalError: this.originalError,
      stack: this.stack,
    };
  }
}
