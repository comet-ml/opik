import { JSONParsingError } from "../errors";

/**
 * Extracts and parses JSON content from a string.
 *
 * This function attempts to parse the content as JSON. If direct parsing fails,
 * it tries to extract JSON by finding the first `{` and last `}` characters,
 * which handles cases where the LLM wraps JSON in additional text. If that
 * also fails, it scans for repeated top-level JSON objects: identical repeats
 * (occasionally emitted by reasoning models) are accepted, while different
 * objects are ambiguous and raise an error.
 *
 * @param content - The string content to parse
 * @returns The parsed JSON object
 * @throws {JSONParsingError} If parsing fails
 *
 * @example
 * ```typescript
 * // Direct JSON
 * extractJsonContentOrRaise('{"score": 0.8, "reason": "Good"}');
 *
 * // JSON wrapped in text
 * extractJsonContentOrRaise('Here is the result: {"score": 0.8, "reason": "Good"}');
 * ```
 */
export function extractJsonContentOrRaise(content: string): unknown {
  try {
    return JSON.parse(content);
  } catch {
    // Try to extract JSON from text by finding first { and last }
    return extractPresumablyJsonDictOrRaise(content);
  }
}

/**
 * Attempts to extract a JSON object from text by finding the first `{` and last `}`.
 *
 * This is a fallback parsing strategy for when LLMs return JSON wrapped in
 * additional explanatory text. When the brace span holds more than one
 * top-level JSON object, identical repeats are accepted but different objects
 * are ambiguous (the first may be text the judge quoted from the evaluated
 * answer), so parsing fails instead of picking one by position.
 *
 * @param content - The string content to parse
 * @returns The parsed JSON object
 * @throws {JSONParsingError} If extraction or parsing fails
 */
function extractPresumablyJsonDictOrRaise(content: string): unknown {
  try {
    const firstBrace = content.indexOf("{");
    const lastBrace = content.lastIndexOf("}");

    if (firstBrace === -1 || lastBrace === -1 || firstBrace >= lastBrace) {
      throw new Error("No valid JSON object found in content");
    }

    // Fast path: the span from the first `{` to the last `}` is a single
    // JSON document (e.g. prose-wrapped output).
    try {
      return JSON.parse(content.substring(firstBrace, lastBrace + 1));
    } catch {
      // Fall through to the candidate scan below.
    }

    return extractSingleRepeatedJsonObjectOrRaise(content, firstBrace);
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    throw new JSONParsingError(
      `Failed to extract JSON from content: ${errorMessage}`,
      error instanceof Error ? error : undefined
    );
  }
}

/**
 * Scans the content for top-level JSON objects, starting from each `{`.
 *
 * Reasoning models occasionally repeat their verdict object (`{...}\n{...}`),
 * which is fine: when every candidate parses to the same value, the first one
 * is returned. Two different objects are ambiguous, so an error is raised
 * instead of guessing by position.
 *
 * @param content - The string content to scan
 * @param firstBrace - Index of the first `{` in the content
 * @returns The parsed JSON object
 * @throws {Error} If no JSON object is found or the objects differ
 */
function extractSingleRepeatedJsonObjectOrRaise(
  content: string,
  firstBrace: number
): unknown {
  const found: unknown[] = [];
  const seen = new Set<string>();

  let index = firstBrace;
  while (index !== -1) {
    const end = findJsonValueEnd(content, index);

    if (end !== -1) {
      let parsed: unknown;
      try {
        parsed = JSON.parse(content.substring(index, end + 1));
      } catch {
        parsed = undefined;
      }

      if (isPlainObject(parsed)) {
        // Compare parsed values rather than raw strings, so key order does
        // not matter while types stay distinct (`true` and `1` differ).
        const key = stableStringify(parsed);
        if (!seen.has(key)) {
          seen.add(key);
          found.push(parsed);

          if (found.length > 1) {
            throw new Error(
              "Ambiguous LLM output: found several different JSON objects; refusing to pick one by position"
            );
          }
        }
      }

      index = content.indexOf("{", end + 1);
    } else {
      index = content.indexOf("{", index + 1);
    }
  }

  if (found.length === 0) {
    throw new Error("No valid JSON object found in content");
  }

  return found[0];
}

/**
 * Finds the index of the closing brace matching the `{` at `startIndex`,
 * skipping over string literals (with escape handling). Returns -1 when the
 * braces never balance.
 */
function findJsonValueEnd(content: string, startIndex: number): number {
  let depth = 0;
  let inString = false;
  let escaped = false;

  for (let i = startIndex; i < content.length; i++) {
    const char = content[i];

    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (char === "\\") {
        escaped = true;
      } else if (char === '"') {
        inString = false;
      }
      continue;
    }

    if (char === '"') {
      inString = true;
    } else if (char === "{") {
      depth += 1;
    } else if (char === "}") {
      depth -= 1;
      if (depth === 0) {
        return i;
      }
    }
  }

  return -1;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/**
 * Serializes a JSON value with object keys sorted recursively, so two parsed
 * values can be compared for equality regardless of key order. Types are
 * preserved: `true` and `1` stringify differently.
 */
function stableStringify(value: unknown): string {
  if (Array.isArray(value)) {
    return `[${value.map(stableStringify).join(",")}]`;
  }

  if (isPlainObject(value)) {
    const entries = Object.entries(value).sort(([a], [b]) =>
      a < b ? -1 : a > b ? 1 : 0
    );
    return `{${entries
      .map(
        ([key, entryValue]) =>
          `${JSON.stringify(key)}:${stableStringify(entryValue)}`
      )
      .join(",")}}`;
  }

  return JSON.stringify(value) ?? "null";
}
