/**
 * Thrown when a requested prompt or version is not found in the backend
 */
export class PromptNotFoundError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "PromptNotFoundError";
    Object.setPrototypeOf(this, PromptNotFoundError.prototype);
  }
}

/**
 * Thrown when template placeholder validation fails or variables are missing
 */
export class PromptPlaceholderError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "PromptPlaceholderError";
    Object.setPrototypeOf(this, PromptPlaceholderError.prototype);
  }
}

/**
 * Thrown for general prompt validation failures (invalid template syntax, etc.)
 */
export class PromptValidationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "PromptValidationError";
    Object.setPrototypeOf(this, PromptValidationError.prototype);
  }
}
