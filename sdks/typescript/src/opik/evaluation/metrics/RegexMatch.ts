import { EvaluationScoreResult } from "../types";
import { BaseMetric } from "./BaseMetric";

/**
 * RegexMatch metric - checks if the actual output matches a regex pattern.
 * Useful for flexible pattern matching.
 */
export class RegexMatch extends BaseMetric {
  /**
   * Creates a new RegexMatch metric
   * @param name Optional name for the metric (defaults to "regex_match")
   * @param trackMetric Whether to track the metric
   * @param projectName Optional project name for tracking
   */
  constructor(name = "regex_match", trackMetric = true, projectName?: string) {
    super(name, trackMetric, projectName);
  }

  /**
   * Calculates a score based on regex match
   * @param output Actual output to evaluate
   * @param pattern Regex pattern to match against (as string)
   * @returns Score result (1.0 for match, 0.0 for no match)
   */
  async score(output: string, pattern: string): Promise<EvaluationScoreResult> {
    // Implementation will be added later
    throw new Error("Method not implemented");
  }
}
