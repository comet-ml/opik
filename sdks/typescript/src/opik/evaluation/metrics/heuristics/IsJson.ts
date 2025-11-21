import { z } from "zod";
import { EvaluationScoreResult } from "../../types";
import { BaseMetric } from "../BaseMetric";

const validationSchema = z.object({
  output: z.unknown(),
});
type Input = z.infer<typeof validationSchema>;

/**
 * IsJson metric - checks if a given output string is valid JSON.
 */
export class IsJson extends BaseMetric {
  /**
   * Creates a new IsJson metric
   * @param name Optional name for the metric (defaults to "is_json_metric")
   * @param trackMetric Whether to track the metric
   */
  constructor(name = "is_json_metric", trackMetric = true) {
    super(name, trackMetric);
  }

  public validationSchema = validationSchema;

  /**
   * Calculates a score based on whether output is a valid JSON
   * @param input Actual output to evaluate
   * @returns Score result (1.0 if valid json, 0.0 if not valid json)
   */
  async score(input: Input): Promise<EvaluationScoreResult> {
    const { output } = input;

    try {
      JSON.parse(output as string);
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
