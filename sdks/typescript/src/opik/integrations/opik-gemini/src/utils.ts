/**
 * Strip "models/" prefix from Gemini model names
 * Example: "models/gemini-pro" → "gemini-pro"
 */
export const normalizeModelName = (
  modelName: string | undefined
): string | undefined => {
  if (!modelName) return undefined;
  return modelName.replace(/^models\//, "");
};

/**
 * Convert Gemini safety settings to metadata format
 */
export const formatSafetySettings = (
  safetySettings: Record<string, unknown>[] | undefined
): Record<string, unknown> | undefined => {
  if (!safetySettings || !Array.isArray(safetySettings)) {
    return undefined;
  }

  return {
    safety_settings: safetySettings,
  };
};

/**
 * Extract tool calls from Gemini response parts
 */
export const extractToolCalls = (
  parts: Array<Record<string, unknown>> | undefined
): Array<Record<string, unknown>> => {
  if (!parts || !Array.isArray(parts)) {
    return [];
  }

  return parts
    .filter((part) => "functionCall" in part || "function_call" in part)
    .map((part) => part.functionCall || part.function_call) as Array<
    Record<string, unknown>
  >;
};

/**
 * Check if a value is an async iterable
 */
export const isAsyncIterable = (
  value: unknown
): value is AsyncIterable<unknown> => {
  return (
    value !== null &&
    typeof value === "object" &&
    Symbol.asyncIterator in value &&
    typeof (value as AsyncIterable<unknown>)[Symbol.asyncIterator] ===
      "function"
  );
};

/**
 * Flatten nested object into flat structure with dot notation
 * Example: { a: { b: 1 } } → { "prefix.a.b": 1 }
 */
export const flattenObject = (
  obj: Record<string, unknown>,
  prefix = ""
): Record<string, unknown> => {
  const result: Record<string, unknown> = {};

  for (const [key, value] of Object.entries(obj)) {
    const newKey = prefix ? `${prefix}.${key}` : key;

    if (
      value &&
      typeof value === "object" &&
      !Array.isArray(value) &&
      !(value instanceof Date)
    ) {
      Object.assign(
        result,
        flattenObject(value as Record<string, unknown>, newKey)
      );
    } else {
      result[newKey] = value;
    }
  }

  return result;
};
