import { EvaluationScoreResult } from "@/evaluation/types";
import { MetricComputationError } from "../../errors";
import { extractJsonContentOrRaise } from "../parsingHelpers";
import { logger } from "@/utils/logger";

/**
 * Parses the LLM model output for the Moderation metric.
 *
 * @param content - The raw string output from the model
 * @param name - The name of the metric (for the result object)
 * @returns A score result with value and reason
 * @throws {MetricComputationError} If parsing fails or score is invalid
 */
export function parseModelOutput(
  content: string,
  name: string
): EvaluationScoreResult {
  try {
    const dictContent = extractJsonContentOrRaise(content) as Record<
      string,
      unknown
    >;

    const score = Number(dictContent["score"]);

    if (isNaN(score) || score < 0.0 || score > 1.0) {
      throw new Error(
        `Moderation score must be between 0.0 and 1.0, got ${score}`
      );
    }

    return {
      name,
      value: score,
      reason: String(dictContent["reason"] || ""),
    };
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    logger.error(`Failed to parse model output: ${errorMessage}`);

    throw new MetricComputationError(
      "Failed to calculate moderation score. The model output could not be parsed.",
      error instanceof Error ? error : undefined
    );
  }
}
