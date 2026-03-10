import { z } from "zod";
import { EvaluationScoreResult } from "../types";

const assertionFieldSchema = z.object({
  score: z.boolean(),
  reason: z.string(),
  confidence: z.number().min(0).max(1),
});

/**
 * Builds a Zod schema that expects one object field per assertion.
 * Each field is an object with { score: boolean, reason: string, confidence: number }.
 */
export function buildResponseSchema(
  assertions: string[]
): z.ZodObject<z.ZodRawShape> {
  const shape: z.ZodRawShape = {};
  for (const assertion of assertions) {
    shape[assertion] = assertionFieldSchema;
  }
  return z.object(shape);
}

/**
 * Parses the LLM response object into an array of EvaluationScoreResult.
 *
 * For each assertion (in order), extracts score/reason/confidence from the response.
 * If a field is missing or malformed, returns a scoringFailed result with value 0.
 * Boolean true maps to value 1, false maps to value 0.
 */
export function parseResponse(
  response: Record<string, unknown>,
  assertions: string[]
): EvaluationScoreResult[] {
  const results: EvaluationScoreResult[] = [];

  for (const assertion of assertions) {
    const field = response[assertion];

    // Missing field
    if (field === undefined || field === null) {
      results.push({
        name: assertion,
        value: 0,
        reason: `Assertion field missing from LLM response: "${assertion}"`,
        scoringFailed: true,
        categoryName: "suite_assertion",
      });
      continue;
    }

    // Malformed field (not an object with the expected shape)
    if (typeof field !== "object" || Array.isArray(field)) {
      results.push({
        name: assertion,
        value: 0,
        reason: `Assertion field malformed in LLM response: "${assertion}"`,
        scoringFailed: true,
        categoryName: "suite_assertion",
      });
      continue;
    }

    const parsed = assertionFieldSchema.safeParse(field);

    if (!parsed.success) {
      results.push({
        name: assertion,
        value: 0,
        reason: `Assertion field malformed in LLM response: "${assertion}"`,
        scoringFailed: true,
        categoryName: "suite_assertion",
      });
      continue;
    }

    results.push({
      name: assertion,
      value: parsed.data.score ? 1 : 0,
      reason: parsed.data.reason,
      categoryName: "suite_assertion",
    });
  }

  return results;
}
