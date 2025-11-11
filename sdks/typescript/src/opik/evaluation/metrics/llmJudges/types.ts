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
 * Few-shot example for Hallucination metric.
 */
export interface FewShotExampleHallucination {
  /** Title/description of the example */
  title?: string;
  /** The input/question */
  input: string;
  /** The context information */
  context: string[];
  /** The output text that was evaluated */
  output: string;
  /** The hallucination score (0.0-1.0, where 1.0 = hallucination detected) */
  score: number;
  /** Explanation for the score */
  reason: string;
}

/**
 * Few-shot example for AnswerRelevance metric with context.
 */
export interface FewShotExampleAnswerRelevanceWithContext {
  /** Title/description of the example */
  title: string;
  /** The input/question */
  input: string;
  /** The output text that was evaluated */
  output: string;
  /** The context information */
  context: string[];
  /** The answer relevance score (0.0-1.0) */
  answer_relevance_score: number;
  /** Explanation for the score */
  reason: string;
}

/**
 * Few-shot example for AnswerRelevance metric without context.
 */
export interface FewShotExampleAnswerRelevanceNoContext {
  /** Title/description of the example */
  title: string;
  /** The input/question */
  input: string;
  /** The output text that was evaluated */
  output: string;
  /** The answer relevance score (0.0-1.0) */
  answer_relevance_score: number;
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
