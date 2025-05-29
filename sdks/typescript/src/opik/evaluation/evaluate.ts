import { Dataset } from "../dataset/Dataset";
import {
  EvaluationResult,
  EvaluationTask,
  ScoringKeyMappingType,
} from "./types";
import { BaseMetric } from "./metrics/BaseMetric";
import { logger } from "@/utils/logger";
import { EvaluationEngine } from "./engine/EvaluationEngine";
import { EvaluationResultProcessor } from "./results/EvaluationResultProcessor";
import { generateId } from "@/utils/generateId";
import { OpikSingleton } from "@/client/SingletonClient";

/**
 * Performs evaluation of an LLM task on a dataset with specified metrics.
 *
 * @param options Configuration options for the evaluation process
 * @returns Result of the evaluation with metrics and statistics
 *
 * @example
 * ```typescript
 * import { evaluate, ExactMatch } from "opik";
 *
 * // Define a task that takes a dataset item and returns a result
 * const myTask = async (datasetItem) => {
 *   const { input } = datasetItem;
 *   // Process the input with your LLM or other logic
 *   const output = await myLLM.process(input);
 *   return { output };
 * };
 *
 * // Run evaluation
 * const result = await evaluate({
 *   dataset: myDataset,
 *   task: myTask,
 *   scoringMetrics: [new ExactMatch("output_match", "expected_output", "output")],
 *   experimentName: "My First Evaluation",
 *   projectName: "My Project",
 *   verbose: 1
 * });
 *
 * console.log(`Overall score: ${result.mean}`);
 * ```
 */

export interface EvaluateOptions {
  /** The dataset to evaluate against, containing inputs and expected outputs */
  dataset: Dataset;

  /** The specific LLM task to perform (e.g., classification, generation, question-answering) */
  task: EvaluationTask;

  /** Optional array of metrics to evaluate model performance (e.g., accuracy, F1 score) */
  scoringMetrics?: BaseMetric[];

  /** Optional name for this evaluation experiment for tracking and reporting */
  experimentName?: string;

  /** Optional project identifier to associate this experiment with */
  projectName?: string;

  /** Optional configuration settings for the experiment as key-value pairs */
  experimentConfig?: Record<string, unknown>;

  /** Optional number of samples to evaluate from the dataset (defaults to all if not specified) */
  nbSamples?: number;

  /**
   * Optional mapping between dataset keys and scoring metric inputs
   * Allows renaming keys from dataset or task output to match what metrics expect
   */
  scoringKeyMapping?: ScoringKeyMappingType;
}

export async function evaluate(
  options: EvaluateOptions
): Promise<EvaluationResult> {
  // Validate required parameters
  if (!options.dataset) {
    throw new Error("Dataset is required for evaluation");
  }

  if (!options.task) {
    throw new Error("Task function is required for evaluation");
  }

  // Set defaults for optional parameters
  const experimentName = options.experimentName || `Evaluation-${generateId()}`;

  // Get Opik client
  const client = OpikSingleton.getInstance();

  // Create experiment for this evaluation run
  const experiment = await client.createExperiment({
    name: experimentName,
    datasetName: options.dataset.name,
    experimentConfig: options.experimentConfig,
  });

  try {
    // Create and run the evaluation engine
    const engine = new EvaluationEngine(options, client, experiment);
    const testResults = await engine.execute();

    // Process results into final format
    const evaluationResult = EvaluationResultProcessor.processResults(
      testResults,
      experiment
    );

    return evaluationResult;
  } catch (error) {
    logger.error(`Error during evaluation: ${error}`);
    throw error;
  }
}

/**
 * Exports all evaluation components
 */
export * from "./metrics/BaseMetric";
export * from "./metrics/ExactMatch";
export * from "./metrics/Contains";
export * from "./metrics/RegexMatch";
export * from "./types";
