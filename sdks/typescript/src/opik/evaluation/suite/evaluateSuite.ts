import { Dataset } from "@/dataset/Dataset";
import { EvaluationResult, EvaluationTask } from "../types";
import { OpikClient } from "@/client/Client";
import { OpikSingleton } from "@/client/SingletonClient";
import { EvaluationEngine } from "../engine/EvaluationEngine";
import { BaseMetric } from "../metrics/BaseMetric";
import { logger } from "@/utils/logger";
import type { Prompt } from "@/prompt/Prompt";
import { DatasetItemData } from "@/dataset/DatasetItem";
import {
  deserializeEvaluators,
  resolveExecutionPolicy,
  resolveItemExecutionPolicy,
} from "./suiteHelpers";
import type { ExecutionPolicy } from "./types";

export interface EvaluateSuiteOptions<T = Record<string, unknown>> {
  /** The dataset to evaluate against */
  dataset: Dataset<T extends DatasetItemData ? T : DatasetItemData & T>;

  /** The specific LLM task to perform */
  task: EvaluationTask<T>;

  /** Optional name for this evaluation experiment */
  experimentName?: string;

  /** Optional project identifier */
  projectName?: string;

  /** Optional configuration settings for the experiment */
  experimentConfig?: Record<string, unknown>;

  /** Optional array of Prompt objects to link with the experiment */
  prompts?: Prompt[];

  /** Optional model name override for all evaluators */
  evaluatorModel?: string;

  /** Optional Opik client instance */
  client?: OpikClient;

  /** Optional list of tags to associate with the experiment */
  tags?: string[];
}

/**
 * Run an evaluation suite using evaluators and execution policy stored in the dataset version metadata.
 *
 * Pre-processes item-level evaluators and execution policies before passing them to the engine.
 * Items are fetched once via getRawItems() and passed as prefetchedItems to avoid duplicate API calls.
 */
export async function evaluateSuite<T = Record<string, unknown>>(
  options: EvaluateSuiteOptions<T>
): Promise<EvaluationResult> {
  if (!options.dataset) {
    throw new Error("Dataset is required for evaluation suite");
  }
  if (!options.task) {
    throw new Error("Task function is required for evaluation suite");
  }

  const client = options.client ?? OpikSingleton.getInstance();

  // Read version info and extract suite-level evaluators + execution policy
  const versionInfo = await options.dataset.getVersionInfo();
  const suiteEvaluators = versionInfo?.evaluators
    ? deserializeEvaluators(versionInfo.evaluators, options.evaluatorModel)
    : [];
  const suitePolicy = resolveExecutionPolicy(versionInfo?.executionPolicy);

  // Fetch raw items with full metadata (evaluators, executionPolicy preserved)
  const rawItems = await options.dataset.getRawItems();

  // Build per-item metrics and policy maps
  const itemMetricsMap = new Map<string, BaseMetric[]>();
  const itemPolicyMap = new Map<string, Required<ExecutionPolicy>>();

  for (const item of rawItems) {
    const itemEvaluators = item.evaluators
      ? deserializeEvaluators(item.evaluators, options.evaluatorModel)
      : [];
    const mergedMetrics = [...suiteEvaluators, ...itemEvaluators];
    itemMetricsMap.set(item.id, mergedMetrics);

    const resolvedPolicy = resolveItemExecutionPolicy(
      item.executionPolicy,
      suitePolicy
    );
    itemPolicyMap.set(item.id, resolvedPolicy);
  }

  // Convert raw items to flat content for the engine
  const prefetchedItems = rawItems.map((item) => item.getContent(true));

  // Create experiment
  const experiment = await client.createExperiment({
    name: options.experimentName,
    datasetName: options.dataset.name,
    experimentConfig: options.experimentConfig,
    prompts: options.prompts,
    datasetVersionId: versionInfo?.id,
    evaluationMethod: "evaluation_suite",
    tags: options.tags,
  });

  try {
    const engineOptions = {
      suiteMode: true,
      dataset: options.dataset,
      task: options.task,
      scoringMetrics: suiteEvaluators,
      projectName: options.projectName,
      executionPolicy: suitePolicy,
      prefetchedItems,
      itemMetricsMap,
      itemPolicyMap,
    };

    const engine = new EvaluationEngine<T>(engineOptions, client, experiment);
    return await engine.execute();
  } catch (error) {
    logger.error(`Error during evaluation suite: ${error}`);
    throw error;
  }
}
