import { EvaluationScoreResult } from "../../types";
import { BaseMetric } from "../BaseMetric";

/**
 * IsJson metric - checks if a given output string is valid JSON.
 */
export class IsJson extends BaseMetric {
  /**
   * Creates a new IsJson metric
   * @param name Optional name for the metric (defaults to "is_json_metric")
   * @param trackMetric Whether to track the metric
   * @param projectName Optional project name for tracking
   */
  constructor(
    name = "is_json_metric",
    trackMetric = true,
    projectName?: string,
  ) {
    super(name, trackMetric, projectName);
  }

  /**
   * Calculates a score based on whether output is a valid JSON
   * @param output Actual output to evaluate
   * @returns Score result (1.0 if valid json, 0.0 if not valid json)
   */
  async score(output: string): Promise<EvaluationScoreResult> {
    try {
      JSON.parse(output);
      return {
        name: this.name,
        value: 1.0,
        reason: "IsJson: Output is valid JSON.",
      };
    } catch {
      return {
        name: this.name,
        value: 0.0,
        reason: "IsJson: Output is not valid JSON",
      };
    }
  }
}
