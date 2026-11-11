import stringify from "fast-json-stable-stringify";
import { JSONParsingError } from "../errors";

/**
 * Extracts and parses JSON content from a string.
 *
 * This function attempts to parse the content as JSON. If direct parsing fails,
 * it falls back to scanning every `{` candidate and letting `JSON.parse`
 * decide each one, which handles cases where the LLM wraps JSON in
 * additional text.
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
    // Fall back to scanning every `{` candidate; JSON.parse decides each one
    return extractPresumablyJsonDictOrRaise(content);
  }
}

/**
 * Attempts to extract a JSON object from text by scanning every `{`
 * candidate and parsing each one.
 *
 * This is a fallback parsing strategy for when LLMs return JSON wrapped in
 * additional explanatory text. When the text holds several complete JSON
 * objects, they are only accepted when every object parses to the same value
 * (compared via stable stringification, so key order does not matter but
 * `true` and `1` stay distinct). An unparseable or unterminated candidate
 * fails the whole parse instead of being skipped, so a cut-off trailing
 * object can never be silently ignored.
 *
 * @param content - The string content to parse
 * @returns The parsed JSON object
 * @throws {JSONParsingError} If extraction or parsing fails
 */
function extractPresumablyJsonDictOrRaise(content: string): unknown {
  try {
    const objects = new Map<string, unknown>();
    for (let start = content.indexOf("{"); start !== -1; ) {
      const [value, end] = parseObjectAt(content, start);
      objects.set(stringify(value), value);
      start = content.indexOf("{", end + 1);
    }
    if (objects.size === 0) {
      throw new Error("No valid JSON object found in content");
    }
    if (objects.size > 1) {
      throw new Error(
        "Multiple differing JSON objects found in content; refusing to guess"
      );
    }
    return objects.values().next().value;
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    throw new JSONParsingError(
      `Failed to extract JSON from content: ${errorMessage}`,
      error instanceof Error ? error : undefined
    );
  }
}

/**
 * Parses the JSON object opened at `start`, extending the candidate to each
 * following `}` until it parses. The first prefix that parses is the whole
 * object, so `JSON.parse` stays the only judge of valid JSON and string and
 * escape handling is not hand-coded a second time.
 *
 * @returns The parsed value and the index of its closing brace.
 * @throws When no closing brace yields a valid object.
 */
function parseObjectAt(content: string, start: number): [unknown, number] {
  for (
    let end = content.indexOf("}", start);
    end !== -1;
    end = content.indexOf("}", end + 1)
  ) {
    try {
      return [JSON.parse(content.slice(start, end + 1)), end];
    } catch {
      // Not closed yet; extend to the next candidate close brace.
    }
  }
  throw new Error("Unterminated or invalid JSON object found in content");
}
