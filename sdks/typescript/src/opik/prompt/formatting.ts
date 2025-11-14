import Mustache from "mustache";
import nunjucks from "nunjucks";
import { PromptValidationError } from "./errors";
import { validateTemplateVariables } from "./validation";
import type { PromptType, PromptVariables } from "./types";

/**
 * Format a prompt template by substituting variables.
 * Validates that all template placeholders are provided (for Mustache templates).
 *
 * @param template - Template text with placeholders
 * @param variables - Object with values to substitute into template
 * @param type - Template engine type (mustache or jinja2)
 * @returns Formatted prompt text with variables substituted
 * @throws PromptValidationError if template processing or validation fails
 */
export function formatPromptTemplate(
  template: string,
  variables: PromptVariables,
  type: PromptType
): string {
  try {
    // Validate variables match template placeholders (Mustache only)
    validateTemplateVariables(template, variables, type);

    switch (type) {
      case "mustache":
        return Mustache.render(
          template,
          variables,
          {},
          {
            escape: (value: string) => value,
          }
        );

      case "jinja2":
        return nunjucks.renderString(template, variables);

      default:
        return template;
    }
  } catch (error) {
    // Re-throw PromptValidationError as-is
    if (error instanceof PromptValidationError) {
      throw error;
    }

    // Wrap other errors
    const errorMessage = error instanceof Error ? error.message : String(error);
    throw new PromptValidationError(
      `Failed to format prompt template: ${errorMessage}`
    );
  }
}
