import deepEqual from "fast-deep-equal";
import { z } from "zod";
import { EvaluationScoreResult } from "../../types";
import { BaseMetric } from "../BaseMetric";

const validationSchema = z.object({
  output: z.unknown(),
  expected: z.unknown(),
});
type Input = z.infer<typeof validationSchema>;

/**
 * ExactMatch metric - checks if the actual output exactly matches the expected output.
 * Simple metric for exact string matching.
 */
const isJsonLike = (value: unknown, ancestors = new Set<object>()): boolean => {
  if (
    value === null ||
    typeof value === "string" ||
    typeof value === "boolean"
  ) {
    return true;
  }

  if (typeof value === "number") {
    return Number.isFinite(value);
  }

  if (typeof value !== "object" || ancestors.has(value)) {
    return false;
  }

  const isArray = Array.isArray(value);
  const prototype = Object.getPrototypeOf(value);
  if (
    (isArray && prototype !== Array.prototype) ||
    (!isArray && prototype !== Object.prototype)
  ) {
    return false;
  }

  ancestors.add(value);
  const values = isArray ? value : Object.values(value);
  const arrayKeys = isArray ? Object.keys(value) : [];
  const isDenseArray =
    !isArray ||
    (arrayKeys.length === value.length &&
      arrayKeys.every((key, index) => key === String(index)));
  const result =
    isDenseArray && values.every((item) => isJsonLike(item, ancestors));
  ancestors.delete(value);
  return result;
};

export class ExactMatch extends BaseMetric {
  private caseSensitive: boolean;

  /**
   * Creates a new ExactMatch metric
   * @param name Optional name for the metric (defaults to "exact_match")
   * @param trackMetric Whether to track the metric
   * @param caseSensitive Whether string matching should be case-sensitive (defaults to true)
   */
  constructor(name = "exact_match", trackMetric = true, caseSensitive = true) {
    super(name, trackMetric);
    this.caseSensitive = caseSensitive;
  }

  public validationSchema = validationSchema;

  /**
   * Calculates a score based on exact match between output and expected
   * @param input Actual output to evaluate, must include `output` and `expected` properties
   * @returns Score result (1.0 for match, 0.0 for no match)
   */
  async score(input: Input): Promise<EvaluationScoreResult> {
    const { output, expected } = input;
    const stringMatch =
      !this.caseSensitive &&
      typeof output === "string" &&
      typeof expected === "string" &&
      output.toLowerCase() === expected.toLowerCase();
    const contentMatch =
      isJsonLike(output) && isJsonLike(expected) && deepEqual(output, expected);
    const score =
      output === expected || stringMatch || contentMatch ? 1.0 : 0.0;

    return {
      name: this.name,
      value: score,
      reason: `Exact match: ${score === 1.0 ? "Match" : "No match"}`,
    };
  }
}
