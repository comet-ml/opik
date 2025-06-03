import { EvaluationScoreResult } from "../../types";
import { BaseMetric } from "../BaseMetric";

/**
 * Contains metric - checks if the actual output contains the expected string.
 * Simple metric for substring matching.
 */
export class Contains extends BaseMetric {
  private caseSensitive: boolean;

  /**
   * Creates a new Contains metric
   * @param name Optional name for the metric (defaults to "contains")
   * @param trackMetric Whether to track the metric
   * @param projectName Optional project name for tracking
   * @param caseSensitive Whether the match should be case-sensitive (defaults to false)
   */
  constructor(
    name = "contains",
    trackMetric = true,
    projectName?: string,
    caseSensitive = false,
  ) {
    super(name, trackMetric, projectName);
    this.caseSensitive = caseSensitive;
  }

  /**
   * Calculates a score based on whether output contains expected
   * @param output Actual output to evaluate
   * @param expected Expected string to find in output
   * @returns Score result (1.0 if found, 0.0 if not found)
   */
  async score(
    output: string,
    expected: string,
  ): Promise<EvaluationScoreResult> {
    const handledOutput = this.caseSensitive ? output : output.toLowerCase();
    const handledExpected = this.caseSensitive
      ? expected
      : expected.toLowerCase();

    const contains = handledOutput.includes(handledExpected);

    if (contains) {
      return {
        name: this.name,
        value: 1.0,
        reason: `Contains: "${expected}" found in output.`,
      };
    }

    return {
      name: this.name,
      value: 0.0,
      reason: `Contains: "${expected}" not found in output.`,
    };
  }
}
