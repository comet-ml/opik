import { EvaluationScoreResult } from "../types";
import { BaseMetric } from "./BaseMetric";

/**
 * ExactMatch metric - checks if the actual output exactly matches the expected output.
 * Simple metric for exact string matching.
 */
export class ExactMatch extends BaseMetric {
  /**
   * Creates a new ExactMatch metric
   * @param name Optional name for the metric (defaults to "exact_match")
   * @param trackMetric Whether to track the metric
   * @param projectName Optional project name for tracking
   */
  constructor(name = "exact_match", trackMetric = true, projectName?: string) {
    super(name, trackMetric, projectName);
  }

  /**
   * Calculates a score based on exact match between output and expected
   * @param output Actual output to evaluate
   * @param expected Expected output to compare against
   * @returns Score result (1.0 for match, 0.0 for no match)
   */
  async score(
    output: string,
    expected: string
  ): Promise<EvaluationScoreResult> {
    const score = output === expected ? 1.0 : 0.0;
    return {
      name: this.name,
      value: score,
      reason: `Exact match: ${score === 1.0 ? "Match" : "No match"}`,
    };
  }
}
