import { JSONParsingError } from "../errors";

/**
 * Extracts and parses JSON content from a string.
 *
 * This function attempts to parse the content as JSON. If direct parsing fails,
 * it tries to extract JSON by finding the first `{` and last `}` characters,
 * which handles cases where the LLM wraps JSON in additional text.
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
    return JSON.parse(jsonString);
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    throw new JSONParsingError(
      `Failed to extract JSON from content: ${errorMessage}`,
      error instanceof Error ? error : undefined
    );
  }
}
