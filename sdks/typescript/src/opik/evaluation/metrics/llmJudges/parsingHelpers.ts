import { JSONParsingError } from "../errors";

/**
 * Extracts and parses JSON content from a string.
 *
 * This function attempts to parse the content as JSON. If direct parsing fails,
 * it tries to extract JSON by finding the first `{` and last `}` characters,
 * which handles cases where the LLM wraps JSON in additional text.
 *
 * When the extracted text holds several complete JSON objects, they are only
 * accepted when every object parses to the same value (compared on the parsed
 * values, so key order does not matter but `true` and `1` stay distinct).
 * Differing objects fail instead of guessing, so a verdict-shaped object quoted
 * from the evaluated answer can never win over the judge's real verdict.
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
 *
 * // A judge repeating its own verdict verbatim
 * extractJsonContentOrRaise('{"score": 0.8}\n{"score": 0.8}');
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
 * additional explanatory text.
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

    const jsonString = content.substring(firstBrace, lastBrace + 1);
    try {
      return JSON.parse(jsonString);
    } catch {
      // Several complete JSON objects glued together (e.g. a judge repeating
      // its verdict) also fail the single-parse fast path; resolve them below.
      return extractRepeatedJsonObjectOrRaise(jsonString);
    }
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    throw new JSONParsingError(
      `Failed to extract JSON from content: ${errorMessage}`,
      error instanceof Error ? error : undefined,
    );
  }
}

/**
 * Finds the index of the `}` closing the object opened at `start`, skipping
 * over braces inside string literals.
 *
 * @returns The index of the matching close brace, or -1 when unbalanced.
 */
function findMatchingBrace(text: string, start: number): number {
  let depth = 0;
  let inString = false;
  let escaped = false;

  for (let i = start; i < text.length; i++) {
    const char = text[i];
    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (char === "\\") {
        escaped = true;
      } else if (char === '"') {
        inString = false;
      }
    } else if (char === '"') {
      inString = true;
    } else if (char === "{") {
      depth++;
    } else if (char === "}") {
      depth--;
      if (depth === 0) {
        return i;
      }
    }
  }
  return -1;
}

/**
 * Compares two parsed JSON values structurally: object key order does not
 * matter, but JSON types are kept distinct (`true` is not `1`, `null` is not
 * `0`, and array order matters).
 */
function jsonValuesEqual(left: unknown, right: unknown): boolean {
  if (typeof left !== typeof right) {
    return false;
  }
  if (left === null || right === null) {
    return left === right;
  }
  if (Array.isArray(left) || Array.isArray(right)) {
    return (
      Array.isArray(left) &&
      Array.isArray(right) &&
      left.length === right.length &&
      left.every((value, index) => jsonValuesEqual(value, right[index]))
    );
  }
  if (typeof left === "object") {
    const leftObject = left as Record<string, unknown>;
    const rightObject = right as Record<string, unknown>;
    const leftKeys = Object.keys(leftObject);
    const rightKeys = Object.keys(rightObject);
    return (
      leftKeys.length === rightKeys.length &&
      leftKeys.every(
        (key) =>
          Object.prototype.hasOwnProperty.call(rightObject, key) &&
          jsonValuesEqual(leftObject[key], rightObject[key]),
      )
    );
  }
  return left === right;
}

/**
 * Parses text holding several complete JSON objects and returns their shared
 * value when every object agrees; throws when they differ or when fewer than
 * two complete objects are found.
 */
function extractRepeatedJsonObjectOrRaise(jsonString: string): unknown {
  const objects: unknown[] = [];
  let cursor = 0;

  while (cursor < jsonString.length) {
    const start = jsonString.indexOf("{", cursor);
    if (start === -1) {
      break;
    }
    const end = findMatchingBrace(jsonString, start);
    if (end === -1) {
      break;
    }
    let parsed: unknown;
    try {
      parsed = JSON.parse(jsonString.substring(start, end + 1));
    } catch {
      // An unparseable candidate makes the output ambiguous: fail closed.
      throw new Error(
        "Multiple JSON objects found in content but one could not be parsed",
      );
    }
    objects.push(parsed);
    cursor = end + 1;
  }

  if (objects.length < 2) {
    throw new Error("No valid JSON object found in content");
  }
  const [first, ...rest] = objects;
  if (!rest.every((object) => jsonValuesEqual(first, object))) {
    throw new Error(
      "Multiple differing JSON objects found in content; refusing to guess",
    );
  }
  return first;
}
