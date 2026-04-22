import { z } from "zod";
import { EvaluationScoreResult } from "../types";

const assertionFieldSchema = z.object({
  score: z.boolean(),
  reason: z.string(),
  confidence: z.number().min(0).max(1),
});

/**
 * Encapsulates the JSON schema for LLMJudge structured output.
 *
 * Uses short indexed keys (`assertion_1`, `assertion_2`, ...) that are
 * compatible with all LLM providers while embedding the original assertion
 * text as the Zod field description in the JSON schema.
 */
export class ResponseSchema {
  private readonly fieldMapping: Map<string, string>;
  private readonly schema: z.ZodObject<z.ZodRawShape>;

  constructor(assertions: string[]) {
    this.fieldMapping = new Map(
      assertions.map((assertion, index) => [
        `assertion_${index + 1}`,
        assertion,
      ])
    );

    const shape: z.ZodRawShape = {};
    for (const [key, assertion] of this.fieldMapping) {
      shape[key] = assertionFieldSchema.describe(assertion);
    }
    this.schema = z.object(shape);
  }

  get responseSchema(): z.ZodObject<z.ZodRawShape> {
    return this.schema;
  }

  formatAssertions(): string {
    return [...this.fieldMapping.entries()]
      .map(([key, assertion]) => `- \`${key}\`: ${assertion}`)
      .join("\n");
  }

  parse(response: Record<string, unknown>): EvaluationScoreResult[] {
    const results: EvaluationScoreResult[] = [];

    for (const [fieldKey, assertion] of this.fieldMapping) {
      const field = response[fieldKey];

      if (field === undefined || field === null) {
        results.push({
          name: assertion,
          value: 0,
          reason: `Assertion field missing from LLM response: "${fieldKey}"`,
          scoringFailed: true,
          categoryName: "suite_assertion",
        });
        continue;
      }

      if (typeof field !== "object" || Array.isArray(field)) {
        results.push({
          name: assertion,
          value: 0,
          reason: `Assertion field malformed in LLM response: "${fieldKey}"`,
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
          reason: `Assertion field malformed in LLM response: "${fieldKey}"`,
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
}
