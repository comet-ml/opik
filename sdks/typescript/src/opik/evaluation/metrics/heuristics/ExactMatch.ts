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
export class ExactMatch extends BaseMetric {
  /**
   * Creates a new ExactMatch metric
   * @param name Optional name for the metric (defaults to "exact_match")
   * @param trackMetric Whether to track the metric
   */
  constructor(name = "exact_match", trackMetric = true) {
    super(name, trackMetric);
  }

  public validationSchema = validationSchema;

  /**
   * Calculates a score based on exact match between output and expected
   * @param input Actual output to evaluate, must include `output` and `expected` properties
   * @returns Score result (1.0 for match, 0.0 for no match)
   */
  async score(input: Input): Promise<EvaluationScoreResult> {
    const { output, expected } = input;
    const score = output === expected ? 1.0 : 0.0;

    return {
      name: this.name,
      value: score,
      reason: `Exact match: ${score === 1.0 ? "Match" : "No match"}`,
    };
  }
}
