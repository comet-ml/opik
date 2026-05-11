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

  /**
   * Test results for all evaluated items, including failed ones.
   * Items whose task threw will have a synthetic score named
   * {@link TASK_ERROR_SCORE_NAME} with `scoringFailed: true`.
   */
  testResults: EvaluationTestResult[];

  /** Optional URL to view detailed results in the Opik platform */
  resultUrl?: string;

  /** Errors encountered during evaluation (task failures, API errors, etc.) */
  errors: EvaluationError[];
};

/**
 * Represents an error that occurred during evaluation of a single dataset item run.
 */
export type EvaluationError = {
  /** ID of the dataset item that failed */
  datasetItemId: string;

  /** Run index (0-based) within the item's execution */
  runIndex: number;

  /** Human-readable error message */
  message: string;

  /** Original error object, if available */
  error?: Error;
};

/**
 * Reserved score name injected into failed task runs.
 *
 * When a task throws, the engine adds a synthetic score with this name and
 * `scoringFailed: true` so failed items remain visible in experiment results.
 * Consumers can filter on this name to distinguish task-level failures from
 * real metric scores.
 *
 * Note: coordinate with the Python SDK before renaming — picking a stable,
 * collision-resistant name (OPIK-6437).
 */
export const TASK_ERROR_SCORE_NAME = "__opik_task_error__";

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

  /**
   * Whether the scoring failed due to a task-level error rather than a metric
   * failure. When `true`, `name` will equal {@link TASK_ERROR_SCORE_NAME},
   * which is a reserved name injected by the engine — user-defined metrics
   * should never produce a score with that name.
   */
  scoringFailed?: boolean;

  /** Optional category name for grouping scores */
  categoryName?: string;
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

  /** Run index (0, 1, 2...) for multi-run test suites */
  trialId?: number;

  /** Resolved per-item execution policy (set by engine in suite mode). */
  resolvedExecutionPolicy?: { runsPerItem: number; passThreshold: number };
};
