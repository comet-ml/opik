import Mustache from "mustache";
import { ContextData } from "@/types/structured-completion";

/**
 * Render a template string with context data.
 * Uses mustache syntax: {{variableName}} or {{nested.path}}
 *
 * For complex objects, they are JSON-stringified automatically.
 * This matches the backend behavior in OnlineScoringEngine.java
 *
 * @param template - String containing {{variable}} placeholders
 * @param context - Data object to inject into the template
 * @returns Rendered string with variables replaced
 */
export const renderTemplate = (
  template: string,
  context: ContextData
): string => {
  if (!context || Object.keys(context).length === 0) {
    return template;
  }

  // Prepare context: stringify complex objects for proper injection
  const preparedContext: Record<string, string> = {};

  for (const [key, value] of Object.entries(context)) {
    if (value === null || value === undefined) {
      preparedContext[key] = "";
    } else if (typeof value === "object") {
      // Stringify objects/arrays like backend does
      preparedContext[key] = JSON.stringify(value, null, 2);
    } else {
      preparedContext[key] = String(value);
    }
  }

  // Disable HTML escaping (we want raw JSON)
  Mustache.escape = (text: string) => text;

  return Mustache.render(template, preparedContext);
};

/**
 * Flatten nested object paths for template rendering.
 * Converts { trace: { input: { messages: [...] } } }
 * to { "trace.input.messages": "[...]", "trace.input": "{...}", "trace": "{...}" }
 *
 * This allows templates like {{trace.input.messages}} to work.
 */
export const flattenContextPaths = (
  obj: Record<string, unknown>,
  prefix: string = ""
): Record<string, unknown> => {
  const result: Record<string, unknown> = {};

  for (const [key, value] of Object.entries(obj)) {
    const fullKey = prefix ? `${prefix}.${key}` : key;
    result[fullKey] = value;

    if (value && typeof value === "object" && !Array.isArray(value)) {
      Object.assign(
        result,
        flattenContextPaths(value as Record<string, unknown>, fullKey)
      );
    }
  }

  // Also include the original structure for simple {{key}} access
  if (!prefix) {
    Object.assign(result, obj);
  }

  return result;
};
