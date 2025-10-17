import { EvaluationScoreResult } from "@/evaluation/types";
import { MetricComputationError } from "../../errors";
import { extractJsonContentOrRaise } from "../parsingHelpers";
import { logger } from "@/utils/logger";

/**
 * Parses the LLM model output for the Hallucination metric.
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

    const rawScore = dictContent["score"];

    // Check for null, undefined, or missing score before converting to number
    if (rawScore === null || rawScore === undefined) {
      throw new Error(`Hallucination score is required but got ${rawScore}`);
    }

    const score = Number(rawScore);

    // Check for NaN after conversion (catches "NaN" strings and invalid values)
    if (isNaN(score) || score < 0.0 || score > 1.0) {
      throw new Error(
        `Hallucination score must be between 0.0 and 1.0, got ${score}`
      );
    }

    // Reason can be a string or an array of strings
    let reason = "";
    const reasonValue = dictContent["reason"];

    if (Array.isArray(reasonValue)) {
      // Map to string and join with space, preserving empty strings
      reason = reasonValue.map((item) => String(item)).join(" ");
    } else if (reasonValue) {
      reason = String(reasonValue);
    }

    return {
      name,
      value: score,
      reason,
    };
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    logger.error(`Failed to parse model output: ${errorMessage}`);

    throw new MetricComputationError(
      "Failed to calculate hallucination score. The model output could not be parsed.",
      error instanceof Error ? error : undefined
    );
  }
}
