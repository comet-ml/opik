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

/**
 * Thrown when attempting to access a prompt with a different template structure
 * than what exists. Template structure (text vs chat) is immutable after creation.
 */
export class PromptTemplateStructureMismatch extends Error {
  public readonly promptName: string;
  public readonly existingStructure: string;
  public readonly attemptedStructure: string;

  constructor(
    promptName: string,
    existingStructure: string,
    attemptedStructure: string
  ) {
    const message = `Prompt '${promptName}' has template_structure='${existingStructure}' but attempted to access as '${attemptedStructure}'. Template structure is immutable after creation.`;
    super(message);
    this.name = "PromptTemplateStructureMismatch";
    this.promptName = promptName;
    this.existingStructure = existingStructure;
    this.attemptedStructure = attemptedStructure;
    Object.setPrototypeOf(this, PromptTemplateStructureMismatch.prototype);
  }
}
