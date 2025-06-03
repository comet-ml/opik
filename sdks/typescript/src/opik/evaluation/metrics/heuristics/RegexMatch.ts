import { EvaluationScoreResult } from "../../types";
import { BaseMetric } from "../BaseMetric";

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
   * @param pattern Regex pattern to match against
   * @param flags Optional regex flags (e.g., 'i' for case-insensitive)
   * @returns Score result (1.0 for match, 0.0 for no match)
   */
  async score(
    output: string,
    pattern: RegExp | string,
    flags?: string,
  ): Promise<EvaluationScoreResult> {
    try {
      let regex: RegExp | undefined;

      if (typeof pattern === "string" && flags) {
        regex = new RegExp(pattern, flags);
      } else {
        regex = new RegExp(pattern);
      }

      const isMatch = regex!.test(output);

      return {
        name: this.name,
        value: isMatch ? 1.0 : 0.0,
        reason: isMatch
          ? `Regex: Output matches the regex pattern: ${pattern}`
          : `Regex: Output does not match the regex pattern: ${pattern}`,
      };
      //   ALEX
    } catch (e) {
      throw e;
    }
  }
}
