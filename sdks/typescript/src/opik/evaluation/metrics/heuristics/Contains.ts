import { EvaluationScoreResult } from "../../types";
import { BaseMetric } from "../BaseMetric";

interface ContainsMetricArgs {
  output: string;
  substring: string;
}

/**
 * Contains metric - checks if the actual output contains the substring string.
 * Simple metric for substring matching.
 */
export class Contains extends BaseMetric<ContainsMetricArgs> {
  private caseSensitive: boolean;

  /**
   * Creates a new Contains metric
   * @param name Optional name for the metric (defaults to "contains")
   * @param trackMetric Whether to track the metric
   * @param caseSensitive Whether the match should be case-sensitive (defaults to false)
   */
  constructor(name = "contains", trackMetric = true, caseSensitive = false) {
    super(name, trackMetric);
    this.caseSensitive = caseSensitive;
  }

  /**
   * Calculates a score based on whether output contains substring
   * @param input Actual output to evaluate, must include `output` and `substring` properties
   * @returns Score result (1.0 if found, 0.0 if not found)
   */
  async score(input: ContainsMetricArgs): Promise<EvaluationScoreResult> {
    const { output, substring } = input;

    const handledOutput = this.caseSensitive ? output : output.toLowerCase();
    const handledExpected = this.caseSensitive
      ? substring
      : substring.toLowerCase();

    const contains = handledOutput.includes(handledExpected);

    if (contains) {
      return {
        name: this.name,
        value: 1.0,
        reason: `Contains: "${substring}" found in output.`,
      };
    }

    return {
      name: this.name,
      value: 0.0,
      reason: `Contains: "${substring}" not found in output.`,
    };
  }
}
