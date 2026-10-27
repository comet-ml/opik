import Mustache from "mustache";
import nunjucks from "nunjucks";
import { PromptValidationError } from "./errors";
import { validateTemplateVariables } from "./validation";
import type { PromptType, PromptVariables } from "./types";

type MemberLookup = (obj: unknown, val: string | number) => unknown;

interface NunjucksContext {
  ctx: object;
  env: { globals: object };
}
interface NunjucksFrame {
  lookup: (name: string) => unknown;
}
type ContextOrFrameLookup = (
  context: NunjucksContext,
  frame: NunjucksFrame,
  name: string
) => unknown;

// nunjucks resolves `a.b` / `a[b]` through runtime.memberLookup and bare names
// through runtime.contextOrFrameLookup at render time, but neither is part of
// the public type definitions.
const nunjucksRuntime = (
  nunjucks as unknown as {
    runtime: {
      memberLookup: MemberLookup;
      contextOrFrameLookup: ContextOrFrameLookup;
    };
  }
).runtime;

function hasOwn(obj: object, key: string | number): boolean {
  return Object.prototype.hasOwnProperty.call(obj, key);
}

// Prototype-chain gadget keys: when reached via inheritance (rather than as an
// own data field) they expose engine internals, so they are withheld.
function isUnsafeInheritedKey(key: string | number): boolean {
  const name = String(key);
  return name.startsWith("__") || name === "constructor" || name === "prototype";
}

// Resolve `obj[key]` without exposing members that escape the supplied data.
// Own properties always resolve, so genuine fields such as `_id` or a field
// literally named `constructor` render. Inherited members resolve too — ordinary
// methods and getters (array/Date methods, `length`, class getters, string
// methods) work regardless of receiver type — except prototype-chain gadget keys
// (`constructor`, `prototype`, `__proto__`, dunders) reached via the prototype
// chain, which return undefined. This mirrors the Python SandboxedEnvironment,
// which renders own fields and object methods but not dunder/prototype access.
function resolveSafeProperty<T>(
  obj: unknown,
  key: string | number,
  resolve: (target: unknown, key: string | number) => T
): T | undefined {
  if (obj === undefined || obj === null) {
    return undefined;
  }
  if (!hasOwn(obj as object, key) && isUnsafeInheritedKey(key)) {
    return undefined;
  }
  return resolve(obj, key);
}

function ownProperty(obj: unknown, key: string | number): unknown {
  return resolveSafeProperty(
    obj,
    key,
    (target, k) => (target as Record<string | number, unknown>)[k]
  );
}

// Jinja2 templates render through a dedicated Environment with an empty loader
// list, which closes {% include %} / {% import %} / {% extends %} (no filesystem
// access; an empty array, not null, since null falls back to a default loader).
// autoescape stays on so rendered output is unchanged. The attribute-resolving
// filters read members directly, so they go through the same safe-property guard
// as the member lookups in renderJinja2Template.
const jinja2Env = new nunjucks.Environment([], { autoescape: true });
jinja2Env.addFilter("selectattr", (arr: unknown[], attr: string) =>
  arr.filter((item) => Boolean(ownProperty(item, attr)))
);
jinja2Env.addFilter("rejectattr", (arr: unknown[], attr: string) =>
  arr.filter((item) => !ownProperty(item, attr))
);

// Cap range() like the Python SandboxedEnvironment (MAX_RANGE = 100000) so an
// oversized range cannot build a huge array and stall the process.
const MAX_RANGE = 100000;
jinja2Env.addGlobal(
  "range",
  (start: number, stop?: number, step?: number): number[] => {
    let from = start;
    let to = stop;
    let by = step;
    if (to === undefined) {
      to = from;
      from = 0;
      by = 1;
    } else if (by === undefined) {
      // Only an omitted step defaults to 1; everything else is validated below.
      by = 1;
    }
    // Number.isFinite rejects non-numbers (null/NaN/Infinity) without coercion.
    // A null/NaN step would otherwise stall a descending range until the cap or
    // silently produce wrong results, and a non-finite bound is meaningless.
    if (!Number.isFinite(by) || by === 0) {
      throw new Error("range() step must be a non-zero finite number");
    }
    if (!Number.isFinite(from) || !Number.isFinite(to)) {
      throw new Error("range() bounds must be finite numbers");
    }
    // Enforce the cap while iterating so the element count never relies on a
    // `to - from` subtraction (which can overflow to Infinity for large
    // opposite-signed bounds and wrongly reject a small range).
    const result: number[] = [];
    for (let i = from; by > 0 ? i < to : i > to; i += by) {
      if (result.length >= MAX_RANGE) {
        throw new Error(`range() produces more than ${MAX_RANGE} values`);
      }
      result.push(i);
    }
    return result;
  }
);

function renderJinja2Template(
  template: string,
  variables: PromptVariables
): string {
  // memberLookup and contextOrFrameLookup are shared module state; rendering is
  // synchronous, so route them through the guards for this call and always
  // restore them. memberLookup withholds prototype-chain gadget keys on globals,
  // literals, primitives and data (closing `{{ range.constructor(...) }}` and
  // similar) without rejecting own fields or ordinary inherited methods/getters.
  const originalMemberLookup = nunjucksRuntime.memberLookup;
  const originalContextOrFrameLookup = nunjucksRuntime.contextOrFrameLookup;
  nunjucksRuntime.memberLookup = (obj, val) =>
    resolveSafeProperty(obj, val, (target, key) =>
      originalMemberLookup(target, key)
    );
  // A bare top-level name is resolved against a plain object copy of the
  // variables, which would otherwise expose Object.prototype members such as
  // `constructor` or `valueOf`. Frame variables use a null-prototype object, so
  // only own names on the user variables or the environment globals resolve.
  nunjucksRuntime.contextOrFrameLookup = (context, frame, name) => {
    const frameValue = frame.lookup(name);
    if (frameValue !== undefined) {
      return frameValue;
    }
    if (hasOwn(context.ctx, name)) {
      return (context.ctx as Record<string, unknown>)[name];
    }
    if (hasOwn(context.env.globals, name)) {
      return (context.env.globals as Record<string, unknown>)[name];
    }
    return undefined;
  };
  try {
    return jinja2Env.renderString(template, variables);
  } finally {
    nunjucksRuntime.memberLookup = originalMemberLookup;
    nunjucksRuntime.contextOrFrameLookup = originalContextOrFrameLookup;
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
