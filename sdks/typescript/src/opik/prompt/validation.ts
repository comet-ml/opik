import Mustache from "mustache";
import { PromptType } from "./types";
import { PromptValidationError } from "./errors";

/**
 * Extracts root variable names from a Mustache template using the Mustache parser.
 * Handles dotted notation by extracting root variables (e.g., user.name -> user).
 * Captures variables ({{name}}), sections ({{#section}}), and unescaped variables ({{&var}}).
 *
 * @param template - Mustache template string
 * @returns Set of unique root variable names found in template
 *
 * @example
 * ```typescript
 * extractMustachePlaceholders("Hello {{name}}, {{user.email}}")
 * // Returns: Set {"name", "user"}
 * ```
 */
export function extractMustachePlaceholders(template: string): Set<string> {
  try {
    // Parse template using Mustache parser and extract variable names
    // Token format: [type, name, start, end, ...]
    // Types: 'name' (variable), '#' (section), '&' (unescaped), '^' (inverted section)
    const tokens = Mustache.parse(template);
    const variables = tokens
      .filter((token) => {
        const type = token[0];
        return type === "name" || type === "#" || type === "&" || type === "^";
      })
      .map((token) => {
        const fullName = token[1] as string;
        // Extract root variable from dotted notation (e.g., "user.name" -> "user")
        const rootVariable = fullName.split(".")[0];
        return rootVariable;
      });

    return new Set(variables);
  } catch (error) {
    throw new PromptValidationError(
      `Invalid Mustache template syntax: ${error instanceof Error ? error.message : String(error)}`
    );
  }
}

/**
 * Validates that provided variables match template placeholders.
 * Checks for missing required variables. Extra variables are allowed as they
 * may be used in conditional sections or nested contexts.
 * Only validates Mustache templates - Jinja2 has its own runtime validation.
 *
 * @param template - Template string
 * @param variables - Object with variable values
 * @param templateType - Type of template (mustache or jinja2)
 * @throws PromptValidationError if validation fails
 *
 * @example
 * ```typescript
 * validateTemplateVariables(
 *   "Hello {{name}}",
 *   { name: "World" },
 *   "mustache"
 * );
 * // Passes validation
 *
 * validateTemplateVariables(
 *   "Hello {{name}}",
 *   {},
 *   "mustache"
 * );
 * // Throws: PromptValidationError about missing variable "name"
 * ```
 */
export function validateTemplateVariables(
  template: string,
  variables: Record<string, unknown>,
  templateType: PromptType
): void {
  // Only validate Mustache templates - Jinja2 has its own runtime validation
  if (templateType !== "mustache") {
    return;
  }

  const templatePlaceholders = extractMustachePlaceholders(template);
  const providedVariables = new Set(Object.keys(variables));

  // Check for missing required variables
  const missingVariables = new Set(
    [...templatePlaceholders].filter((key) => !providedVariables.has(key))
  );

  // Only error on missing variables - extra variables are allowed
  // as they may be used in conditional sections or nested object contexts
  if (missingVariables.size > 0) {
    const errors: string[] = [];

    errors.push(
      `Missing required variables: ${[...missingVariables].join(", ")}`
    );

    errors.push(
      `Template placeholders: {${[...templatePlaceholders].join(", ")}}`
    );
    errors.push(`Provided variables: {${[...providedVariables].join(", ")}}`);

    throw new PromptValidationError(
      `Template variables validation failed:\n${errors.join("\n")}`
    );
  }
}
