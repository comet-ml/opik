import { EvaluationScoreResult } from "../types";
import { BaseMetric } from "./BaseMetric";

/**
 * Contains metric - checks if the actual output contains the expected string.
 * Simple metric for substring matching.
 */
export class Contains extends BaseMetric {
  /**
   * Creates a new Contains metric
   * @param name Optional name for the metric (defaults to "contains")
   * @param trackMetric Whether to track the metric
   * @param projectName Optional project name for tracking
   */
  constructor(name = "contains", trackMetric = true, projectName?: string) {
    super(name, trackMetric, projectName);
  }

  /**
   * Calculates a score based on whether output contains expected
   * @param output Actual output to evaluate
   * @param expected Expected string to find in output
   * @returns Score result (1.0 if found, 0.0 if not found)
   */
  async score(
    output: string,
    expected: string
  ): Promise<EvaluationScoreResult> {
    // Implementation will be added later
    throw new Error("Method not implemented");
  }
}
