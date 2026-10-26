import Mustache from "mustache";
import nunjucks from "nunjucks";
import { PromptValidationError } from "./errors";
import { validateTemplateVariables } from "./validation";
import type { PromptType, PromptVariables } from "./types";

type MemberLookup = (obj: unknown, val: string | number) => unknown;

// nunjucks resolves `a.b` / `a[b]` through runtime.memberLookup at render time,
// but it is not part of the public type definitions.
const nunjucksRuntime = (
  nunjucks as unknown as { runtime: { memberLookup: MemberLookup } }
).runtime;

function hasOwn(obj: object, key: string | number): boolean {
  return Object.prototype.hasOwnProperty.call(obj, key);
}

// Own-property-only access: inherited/prototype members (`constructor`,
// `__proto__`, …) resolve to undefined, while genuine data fields carrying
// those names are returned unchanged. This mirrors the Python SandboxedEnvironment,
// which renders own fields like `_id` but not prototype-chain attributes.
function ownProperty(obj: unknown, key: string | number): unknown {
  if (obj === undefined || obj === null) {
    return undefined;
  }
  const type = typeof obj;
  if ((type === "object" || type === "function") && !hasOwn(obj as object, key)) {
    return undefined;
  }
  return (obj as Record<string | number, unknown>)[key];
}

// Jinja2 templates render through a dedicated Environment with no loader, which
// closes {% include %} / {% import %} / {% extends %} (no filesystem access).
// autoescape stays on so rendered output is unchanged. The attribute-resolving
// filters read members directly, so they are reduced to own-property access to
// match the render guard in renderJinja2Template.
const jinja2Env = new nunjucks.Environment(null, { autoescape: true });
jinja2Env.addFilter("selectattr", (arr: unknown[], attr: string) =>
  arr.filter((item) => Boolean(ownProperty(item, attr)))
);
jinja2Env.addFilter("rejectattr", (arr: unknown[], attr: string) =>
  arr.filter((item) => !ownProperty(item, attr))
);

function renderJinja2Template(
  template: string,
  variables: PromptVariables
): string {
  // memberLookup is shared module state; rendering is synchronous, so restrict
  // it to own-property access only for this call and always restore it. This
  // blocks prototype-chain access on globals, literals and data (closing
  // `{{ range.constructor(...) }}` and similar) without rejecting own fields.
  const originalMemberLookup = nunjucksRuntime.memberLookup;
  nunjucksRuntime.memberLookup = (obj, val) => {
    if (obj === undefined || obj === null) {
      return undefined;
    }
    const type = typeof obj;
    if (
      (type === "object" || type === "function") &&
      !hasOwn(obj as object, val)
    ) {
      return undefined;
    }
    return originalMemberLookup(obj, val);
  };
  try {
    return jinja2Env.renderString(template, variables);
  } finally {
    nunjucksRuntime.memberLookup = originalMemberLookup;
  }
}

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
        return renderJinja2Template(template, variables);

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
