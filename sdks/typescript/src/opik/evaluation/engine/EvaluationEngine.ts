import { OpikClient } from "@/client/Client";
import { Dataset } from "../../dataset/Dataset";
import { Experiment } from "../../experiment/Experiment";
import { BaseMetric } from "../metrics/BaseMetric";
import {
  EvaluationScoreResult,
  EvaluationTask,
  EvaluationTestCase,
  EvaluationTestResult,
  ScoringKeyMappingType,
} from "../types";
import { logger } from "@/utils/logger";
import { EvaluateOptions } from "../evaluate";

/**
 * Core class that handles the evaluation process
 */
export class EvaluationEngine {
  private readonly client: OpikClient;
  private readonly experiment: Experiment;
  private readonly dataset: Dataset;
  private readonly task: EvaluationTask;
  private readonly scoringMetrics: BaseMetric[];
  private readonly projectName?: string;
  private readonly nbSamples?: number;
  private readonly scoringKeyMapping?: ScoringKeyMappingType;

  /**
   * Create a new EvaluationEngine
   *
   * @param options Configuration options for the evaluation process
   * @param client Opik client instance
   * @param experiment Experiment instance
   */
  constructor(
    options: EvaluateOptions,
    client: OpikClient,
    experiment: Experiment
  ) {
    this.client = client;
    this.experiment = experiment;
    this.dataset = options.dataset;
    this.task = options.task;
    this.scoringMetrics = options.scoringMetrics || [];
    this.projectName = options.projectName;
    this.nbSamples = options.nbSamples;
    this.scoringKeyMapping = options.scoringKeyMapping;
  }

  /**
   * Execute the evaluation process
   *
   * @returns An array of test results
   */
  public async execute(): Promise<EvaluationTestResult[]> {
    // Process dataset items
    const datasetItems = await this.dataset.getItems(this.nbSamples);

    // Execute tasks sequentially (one thread in MVP)
    const testResults: EvaluationTestResult[] = [];

    for (const datasetItem of datasetItems) {
      try {
        const testResult = await this.executeTask(datasetItem);
        testResults.push(testResult);
      } catch (error) {
        logger.error(`Error processing dataset item: ${datasetItem.id}`, error);
      }
    }

    return testResults;
  }

  /**
   * Execute a single evaluation task
   *
   * @param datasetItem The dataset item to evaluate
   * @returns The test result
   */
  private async executeTask(
    datasetItem: Record<string, unknown>
  ): Promise<EvaluationTestResult> {
    let taskOutput: Record<string, unknown> = {};
    let scoreResults: EvaluationScoreResult[] = [];

    try {
      // Execute the task and capture the output
      taskOutput = await this.task(datasetItem);

      // Map scoring keys if needed
      const scoringInputs = this.prepareScoringInputs(datasetItem, taskOutput);

      // Calculate scores for each metric
      if (this.scoringMetrics.length > 0) {
        scoreResults = await this.calculateScores(scoringInputs);
      }

      // Create test case
      const testCase: EvaluationTestCase = {
        traceId: "traceId",
        datasetItemId: datasetItem.id as string,
        scoringInputs,
        taskOutput,
      };

      return {
        testCase,
        scoreResults,
      };
    } catch (error) {
      logger.error("Error executing task", error);

      // Create a failed test case
      const testCase: EvaluationTestCase = {
        traceId: "traceId",
        datasetItemId: datasetItem.id as string,
        scoringInputs: {},
        taskOutput: {},
      };

      return {
        testCase,
        scoreResults: [
          {
            name: "task_execution",
            value: 0,
            reason: `Task execution failed: ${error}`,
            scoringFailed: true,
          },
        ],
      };
    } finally {
      /// end trace
    }
  }

  /**
   * Calculate scores for all metrics
   *
   * @param scoringInputs Inputs for the scoring metrics
   * @returns Array of score results
   */
  private async calculateScores(
    scoringInputs: Record<string, unknown>
  ): Promise<EvaluationScoreResult[]> {
    const scoreResults: EvaluationScoreResult[] = [];

    for (const metric of this.scoringMetrics) {
      try {
        const metricResults = await metric.score(scoringInputs);
        const resultArray = Array.isArray(metricResults)
          ? metricResults
          : [metricResults];

        // Add all results to the array
        scoreResults.push(...resultArray);
      } catch (error) {
        scoreResults.push({
          name: metric.name,
          value: 0,
          reason: `Metric calculation failed: ${error}`,
          scoringFailed: true,
        });
      }
    }

    return scoreResults;
  }

  /**
   * Prepare inputs for scoring metrics by combining dataset item and task output
   * and applying key mapping if provided
   *
   * @param datasetItem The dataset item
   * @param taskOutput Output from the task execution
   * @returns Combined and mapped inputs for scoring metrics
   */
  private prepareScoringInputs(
    datasetItem: Record<string, unknown>,
    taskOutput: Record<string, unknown>
  ): Record<string, unknown> {
    // Combine dataset item and task output (task output has priority)
    const combined = { ...datasetItem, ...taskOutput };

    // If no mapping provided, return combined inputs
    if (!this.scoringKeyMapping) {
      return combined;
    }

    // Apply key mapping
    const mapped: Record<string, unknown> = { ...combined };

    for (const [targetKey, sourceKey] of Object.entries(
      this.scoringKeyMapping
    )) {
      if (sourceKey in combined) {
        mapped[targetKey] = combined[sourceKey];
      }
    }

    return mapped;
  }
}
