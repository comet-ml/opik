/**
 * Few-shot example for Moderation metric.
 */
export interface FewShotExampleModeration {
  /** The output text that was evaluated */
  output: string;
  /** The moderation score (0.0-1.0) */
  score: number;
  /** Explanation for the score */
  reason: string;
}

/**
 * Standard response format for LLM judge metrics.
 *
 * This interface represents the expected structure of responses
 * from language models when used as judges for evaluation.
 */
export interface LLMJudgeResponseFormat {
  /** Numeric score value */
  score: number;
  /** Explanation or justification for the score */
  reason: string;
}
