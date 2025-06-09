/**
 * Evaluation task function type
 * Takes a dataset item as input and returns a result object
 */
export type EvaluationTask<T = Record<string, unknown>> = (
  datasetItem: T
) => Promise<Record<string, unknown>> | Record<string, unknown>;

/**
 * Type for mapping between dataset keys and scoring metric inputs
 */
export type ScoringKeyMappingType = Record<string, string>;

/**
 * Represents the result of an evaluation experiment
 */
export type EvaluationResult = {
  /** ID of the experiment */
  experimentId: string;

  /** Name of the experiment */
  experimentName?: string;

  /** Test results for all evaluated items */
  testResults: EvaluationTestResult[];

  /** Optional URL to view detailed results in the Opik platform */
  resultUrl?: string;
};

/**
 * Represents the result of a metric calculation.
 */
export type EvaluationScoreResult = {
  /** Name of the metric */
  name: string;

  /** Score value (typically between 0.0 and 1.0) */
  value: number;

  /** Optional reason for the score */
  reason?: string;

  /** Whether the scoring failed */
  scoringFailed?: boolean;
};

/**
 * Represents a single test case in the evaluation.
 */
export type EvaluationTestCase = {
  /** ID of the trace associated with this test case */
  traceId: string;

  /** ID of the dataset item used for this test case */
  datasetItemId: string;

  /** Inputs for scoring metrics */
  scoringInputs: Record<string, unknown>;

  /** Output from the task execution */
  taskOutput: Record<string, unknown>;
};

/**
 * Represents the result of a test case evaluation.
 */
export type EvaluationTestResult = {
  /** The test case this result is for */
  testCase: EvaluationTestCase;

  /** Results from all metrics for this test case */
  scoreResults: EvaluationScoreResult[];
};
