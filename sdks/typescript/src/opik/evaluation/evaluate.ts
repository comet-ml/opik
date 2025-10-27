import { Dataset } from "../dataset/Dataset";
import {
  EvaluationResult,
  EvaluationTask,
  ScoringKeyMappingType,
} from "./types";
import { BaseMetric } from "./metrics/BaseMetric";
import { logger } from "@/utils/logger";
import { EvaluationEngine } from "./engine/EvaluationEngine";
import { OpikSingleton } from "@/client/SingletonClient";
import { DatasetItemData } from "../dataset/DatasetItem";
import { OpikClient } from "@/client/Client";
import type { Prompt } from "@/prompt/Prompt";

export interface EvaluateOptions<T = Record<string, unknown>> {
  /** The dataset to evaluate against, containing inputs and expected outputs */
  dataset: Dataset<T extends DatasetItemData ? T : DatasetItemData & T>;

  /** The specific LLM task to perform (e.g., classification, generation, question-answering) */
  task: EvaluationTask<T>;

  /** Optional array of metrics to evaluate model performance (e.g., accuracy, F1 score) */
  scoringMetrics?: BaseMetric[];

  /** Optional name for this evaluation experiment for tracking and reporting */
  experimentName?: string;

  /** Optional project identifier to associate this experiment with */
  projectName?: string;

  /** Optional configuration settings for the experiment as key-value pairs */
  experimentConfig?: Record<string, unknown>;

  /** Optional array of Prompt objects to link with the experiment for tracking */
  prompts?: Prompt[];

  /** Optional number of samples to evaluate from the dataset (defaults to all if not specified) */
  nbSamples?: number;

  /**
   * Optional Opik client instance to use for tracking
   */
  client?: OpikClient;

  /**
   * Optional mapping between dataset keys and scoring metric inputs
   * Allows renaming keys from dataset or task output to match what metrics expect
   */
  scoringKeyMapping?: ScoringKeyMappingType;
}

export async function evaluate<T = Record<string, unknown>>(
  options: EvaluateOptions<T>
): Promise<EvaluationResult> {
  // Validate required parameters
  if (!options.dataset) {
    throw new Error("Dataset is required for evaluation");
  }

  if (!options.task) {
    throw new Error("Task function is required for evaluation");
  }

  // Get Opik client
  const client = options.client ?? OpikSingleton.getInstance();

  // Create experiment for this evaluation run
  const experiment = await client.createExperiment({
    name: options.experimentName,
    datasetName: options.dataset.name,
    experimentConfig: options.experimentConfig,
    prompts: options.prompts,
  });

  try {
    // Create and run the evaluation engine
    const engine = new EvaluationEngine<T>(options, client, experiment);

    logger.info("Starting evaluation");
    return engine.execute();
  } catch (error) {
    logger.error(`Error during evaluation: ${error}`);
    throw error;
  }
}
