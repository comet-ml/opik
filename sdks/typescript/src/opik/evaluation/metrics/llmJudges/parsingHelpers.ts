import equal from "fast-deep-equal";
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
    // JSON document (e.g. prose-wrapped output). The diagnostic is kept so a
    // later "no valid object" error stays actionable.
    let fastPathDiagnostic: unknown;
    try {
      return JSON.parse(content.substring(firstBrace, lastBrace + 1));
    } catch (error) {
      fastPathDiagnostic = error;
      // Fall through to the candidate scan below.
    }

    return extractUnambiguousJsonObjectOrRaise(
      content,
      firstBrace,
      fastPathDiagnostic
    );
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
 * instead of guessing by position. A brace-balanced span that fails to parse
 * is a malformed verdict and also raises: silently skipping it could score
 * garbled model output as a successful verdict. A `{` that never closes ends
 * the scan instead: everything after it is inside that span, so a nested
 * object within it must not be mistaken for a top-level verdict.
 *
 * Each `{` is scanned with fresh string state (a quote in surrounding prose
 * must not swallow a later verdict), and the number of candidates is bounded
 * so brace-heavy malformed output cannot trigger quadratic rescanning.
 *
 * @param content - The string content to scan
 * @param firstBrace - Index of the first `{` in the content
 * @param fastPathDiagnostic - The error from the failed full-span parse, if any
 * @returns The parsed JSON object
 * @throws {Error} If no JSON object is found or the objects differ
 */
function extractUnambiguousJsonObjectOrRaise(
  content: string,
  firstBrace: number,
  fastPathDiagnostic?: unknown
): unknown {
  let reference: unknown = undefined;
  let foundAny = false;
  let candidates = 0;

  let index = firstBrace;
  while (index !== -1) {
    candidates += 1;
    if (candidates > MAX_JSON_OBJECT_CANDIDATES) {
      throw new Error(
        "Too many JSON object candidates in LLM output; refusing to scan further"
      );
    }

    const end = findJsonValueEnd(content, index);

    if (end !== -1) {
      let parsed: unknown;
      try {
        parsed = JSON.parse(content.substring(index, end + 1));
      } catch {
        throw new Error("Malformed JSON object in LLM output");
      }

      if (isPlainObject(parsed)) {
        if (!foundAny) {
          reference = parsed;
          foundAny = true;
        } else if (!equal(reference, parsed)) {
          throw new Error(
            "Ambiguous LLM output: found several different JSON objects; refusing to pick one by position"
          );
        }
      }

      index = content.indexOf("{", end + 1);
    } else {
      // This `{` never closes, so the rest of the content sits inside it:
      // no later `{` can start a separate top-level object. Stepping inside
      // would let a nested object pose as the verdict, so the scan stops.
      break;
    }
  }

  if (!foundAny) {
    const detail =
      fastPathDiagnostic instanceof Error
        ? ` (full-span parse error: ${fastPathDiagnostic.message})`
        : "";
    throw new Error(`No valid JSON object found in content${detail}`);
  }

  return reference;
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

/**
 * Caps the number of `{` candidates examined in one scan. Each candidate
 * scan is linear in the remaining content, so the bound keeps brace-heavy
 * malformed output from degrading into quadratic rescanning; well-formed
 * judge verdicts never approach it.
 */
const MAX_JSON_OBJECT_CANDIDATES = 64;

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
