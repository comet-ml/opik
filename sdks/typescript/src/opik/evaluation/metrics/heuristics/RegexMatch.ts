import { z } from "zod";
import { EvaluationScoreResult } from "../../types";
import { BaseMetric } from "../BaseMetric";

const validationSchema = z.object({
  output: z.string(),
  pattern: z.string(),
  flags: z.string().optional(),
});

type Input = z.infer<typeof validationSchema>;

/**
 * RegexMatch metric - checks if the actual output matches a regex pattern.
 * Useful for flexible pattern matching.
 */
export class RegexMatch extends BaseMetric {
  /**
   * Creates a new RegexMatch metric
   * @param name Optional name for the metric (defaults to "regex_match")
   * @param trackMetric Whether to track the metric
   */
  constructor(name = "regex_match", trackMetric = true) {
    super(name, trackMetric);
  }

  public validationSchema = validationSchema;

  /**
   * Calculates a score based on regex match
   * @param input Actual output to evaluate, must include `output`, `pattern`, and optional `flags` properties
   * @returns Score result (1.0 for match, 0.0 for no match)
   */
  async score(input: Input): Promise<EvaluationScoreResult> {
    const { output, pattern, flags } = input;

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
  }
}
