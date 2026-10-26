import Mustache from "mustache";
import nunjucks from "nunjucks";
import { PromptType } from "./types";
import { PromptValidationError } from "./errors";

// Nunjucks has no runtime sandbox like Jinja2, so templates are restricted
// statically before rendering: variable substitution needs only plain and
// dotted access, so prototype-chain properties and dynamically computed lookups
// are refused. `parser`/`nodes` are exported at runtime but are not in the
// public type definitions.
interface Jinja2Node {
  value?: unknown;
  val?: Jinja2Node;
  findAll: (type: unknown) => Jinja2Node[];
}

const { parser: nunjucksParser, nodes: nunjucksNodes } = nunjucks as unknown as {
  parser: { parse: (src: string) => Jinja2Node };
  nodes: { LookupVal: unknown; Symbol: unknown; Literal: unknown };
};

// Non-underscore properties that still reach the prototype chain / Function.
const UNSAFE_JINJA2_PROPERTIES = new Set([
  "constructor",
  "prototype",
  "call",
  "apply",
  "bind",
]);

// Host globals that must never be reachable from a template.
const UNSAFE_JINJA2_SYMBOLS = new Set([
  "process",
  "global",
  "globalThis",
  "require",
  "module",
  "exports",
  "eval",
  "Function",
  "Reflect",
  "Proxy",
  "child_process",
  "__dirname",
  "__filename",
]);

function isUnsafeJinja2Property(name: string): boolean {
  // Underscore-prefixed names cover every dunder property (__proto__, etc.),
  // matching the attribute restriction of the Python sandbox.
  return name.startsWith("_") || UNSAFE_JINJA2_PROPERTIES.has(name);
}

/**
 * Statically rejects Jinja2 (Nunjucks) templates that reach beyond the provided
 * variables — prototype-chain access and dynamically computed property lookups.
 * Ordinary substitution, dotted access, literal indexing, loops, conditionals
 * and filters are untouched.
 *
 * @param template - Jinja2 template string
 * @throws PromptValidationError if the template uses a disallowed construct
 */
export function validateJinja2Template(template: string): void {
  let ast: Jinja2Node;
  try {
    ast = nunjucksParser.parse(template);
  } catch {
    // Leave genuine syntax errors to the renderer so its messages are unchanged.
    return;
  }

  for (const lookup of ast.findAll(nunjucksNodes.LookupVal)) {
    const key = lookup.val;
    const isLiteral = key instanceof (nunjucksNodes.Literal as never);
    if (!isLiteral) {
      // A computed key (obj[expr]) cannot be verified statically.
      throw new PromptValidationError(
        "Jinja2 template uses a dynamically computed property access, which is not allowed"
      );
    }
    if (typeof key?.value === "string" && isUnsafeJinja2Property(key.value)) {
      throw new PromptValidationError(
        `Jinja2 template accesses a disallowed property: "${key.value}"`
      );
    }
  }

  for (const symbol of ast.findAll(nunjucksNodes.Symbol)) {
    const name = symbol.value;
    if (typeof name === "string" && UNSAFE_JINJA2_SYMBOLS.has(name)) {
      throw new PromptValidationError(
        `Jinja2 template references a disallowed name: "${name}"`
      );
    }
  }
}

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
