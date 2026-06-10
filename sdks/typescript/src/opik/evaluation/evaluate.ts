import { Dataset } from "../dataset/Dataset";
import { DatasetVersion } from "../dataset/DatasetVersion";
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

type DatasetOrVersion<T extends DatasetItemData> =
  | Dataset<T>
  | DatasetVersion<T>;

export interface EvaluateOptions<T = Record<string, unknown>> {
  /** The dataset or dataset version to evaluate against, containing inputs and expected outputs */
  dataset: DatasetOrVersion<T extends DatasetItemData ? T : DatasetItemData & T>;

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

  /** Optional list of tags to associate with the experiment */
  tags?: string[];

  /** Number of concurrent task executions (default: 16, matching Python SDK) */
  taskThreads?: number;

  /** Optional agent configuration blueprint ID to link with the experiment */
  blueprintId?: string;
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

  // Wait for all prompts to be ready
  if (options.prompts) {
    await Promise.all(options.prompts.map((prompt) => prompt.ready()));
  }

  // Get Opik client
  const client = options.client ?? OpikSingleton.getInstance();

  // Resolve agent config blueprint if provided
  let experimentConfig = options.experimentConfig;
  if (options.blueprintId) {
    const agentConfig: Record<string, string> = { _blueprint_id: options.blueprintId };
    try {
      const blueprint = await client.api.agentConfigs.getBlueprintById(options.blueprintId);
      if (blueprint.name) agentConfig.blueprint_version = blueprint.name;
    } catch (error) {
      logger.debug(`Failed to fetch blueprint ${options.blueprintId}: ${error}`);
    }
    experimentConfig = { ...experimentConfig, agent_configuration: agentConfig };
  }

  // Get version info for experiment linking
  const versionInfo = await options.dataset.getVersionInfo();

  // Create experiment for this evaluation run
  const experiment = await client.createExperiment({
    name: options.experimentName,
    datasetName: options.dataset.name,
    experimentConfig,
    prompts: options.prompts,
    datasetVersionId: versionInfo?.id,
    tags: options.tags,
    projectName: options.projectName,
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
