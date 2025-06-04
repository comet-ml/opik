import { OpikClient } from "@/client/Client";
import { Dataset } from "../../dataset/Dataset";
import { Experiment } from "../../experiment/Experiment";
import { BaseMetric } from "../metrics/BaseMetric";
import {
  EvaluationResult,
  EvaluationScoreResult,
  EvaluationTask,
  EvaluationTestCase,
  EvaluationTestResult,
  ScoringKeyMappingType,
} from "../types";
import { logger } from "@/utils/logger";
import { EvaluateOptions } from "../evaluate";
import { validateRequiredArguments } from "../metrics/argumentsValidator";
import { Trace } from "@/tracer/Trace";
import { track, trackStorage } from "@/decorators/track";
import { EvaluationResultProcessor } from "../results";
import { DatasetItemData } from "../../dataset/DatasetItem";
import { ExperimentItemReferences } from "@/experiment";
import { SpanType } from "@/rest_api/api";

/**
 * Core class that handles the evaluation process
 */
export class EvaluationEngine<T = Record<string, unknown>> {
  private readonly client: OpikClient;
  private readonly dataset: Dataset<
    T extends DatasetItemData ? T : DatasetItemData & T
  >;
  private readonly task: EvaluationTask<T>;
  private readonly scoringMetrics: BaseMetric[];
  private readonly projectName?: string;
  private readonly nbSamples?: number;
  private readonly scoringKeyMapping?: ScoringKeyMappingType;
  private readonly experiment: Experiment;
  private rootTrace!: Trace;

  /**
   * Create a new EvaluationEngine
   *
   * @param options Configuration options for the evaluation process
   * @param client Opik client instance
   * @param experiment Experiment instance
   */
  constructor(
    options: EvaluateOptions<T>,
    client: OpikClient,
    experiment: Experiment,
  ) {
    this.client = client;
    this.dataset = options.dataset;
    this.experiment = experiment;
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
  public async execute(): Promise<EvaluationResult> {
    // Record start time
    const startTime = performance.now();

    // Process dataset items
    const datasetItems = await this.dataset.getItems(this.nbSamples);

    // Execute tasks sequentially (one thread in MVP)
    const testResults: EvaluationTestResult[] = [];

    for (const datasetItem of datasetItems) {
      try {
        this.rootTrace = this.client.trace({
          projectName: this.projectName,
          name: `evaluation_task`,
          createdBy: "evaluation",
          input: datasetItem,
        });
        trackStorage.enterWith({ trace: this.rootTrace });
        const testResult = await this.executeTask(datasetItem);
        testResults.push(testResult);

        this.rootTrace.update({
          output: testResult.testCase.taskOutput,
        });
        this.rootTrace.end();
      } catch (error) {
        logger.error(`Error processing dataset item: ${datasetItem.id}`, error);

        if (error instanceof Error) {
          this.rootTrace.update({
            errorInfo: {
              message: error.message,
              exceptionType: error.name,
              traceback: error.stack ?? "",
            },
          });
        }
        this.rootTrace.end();
      }
    }

    const experimentItemReferences = testResults.map(
      (testResult) =>
        new ExperimentItemReferences({
          datasetItemId: testResult.testCase.datasetItemId,
          traceId: testResult.testCase.traceId,
        }),
    );
    this.experiment.insert(experimentItemReferences);

    await this.client.flush();

    // Calculate total execution time in seconds
    const endTime = performance.now();
    const totalTimeSeconds = (endTime - startTime) / 1000;

    return EvaluationResultProcessor.processResults(
      testResults,
      this.experiment,
      totalTimeSeconds,
    );
  }

  /**
   * Execute a single evaluation task
   *
   * @param datasetItem The dataset item to evaluate
   * @returns The test result
   */
  private async executeTask(
    datasetItem: DatasetItemData & T,
  ): Promise<EvaluationTestResult> {
    let taskOutput: Record<string, unknown> = {};
    const scoreResults: EvaluationScoreResult[] = [];

    logger.info(`Starting evaluation task on dataset item ${datasetItem.id}`);
    taskOutput = await track(
      { name: "llm_task", type: SpanType.General },
      this.task,
    )(datasetItem);
    logger.info(`Finished evaluation task on dataset item ${datasetItem.id}`);
    // Map scoring keys if needed
    const scoringInputs = this.prepareScoringInputs(datasetItem, taskOutput);

    // Create test case
    const testCase: EvaluationTestCase = {
      traceId: this.rootTrace.data.id,
      datasetItemId: datasetItem.id as string,
      scoringInputs,
      taskOutput,
    };

    // Calculate scores for each metric
    if (this.scoringMetrics.length > 0) {
      return this.calculateScores(testCase);
    }

    return {
      testCase,
      scoreResults,
    };
  }

  /**
   * Calculate scores for all metrics
   *
   * @param testCase The test case to calculate scores for
   * @returns Array of score results
   */
  @track({ name: "metrics_calculation", type: SpanType.General })
  private async calculateScores(
    testCase: EvaluationTestCase,
  ): Promise<EvaluationTestResult> {
    const scoreResults: EvaluationScoreResult[] = [];
    const { scoringInputs } = testCase;

    for (const metric of this.scoringMetrics) {
      // Check if all required arguments exist in the scoring inputs
      validateRequiredArguments(metric, scoringInputs);

      // If all required arguments exist, call the metric's score method
      logger.info(`Calculating score for metric ${metric.name}`);

      const metricResults = await metric.score(scoringInputs);
      const resultArray = Array.isArray(metricResults)
        ? metricResults
        : [metricResults];

      // Add all results to the array
      scoreResults.push(...resultArray);
      logger.info(`Finished calculating score for metric ${metric.name}`);
    }

    scoreResults.forEach((score) =>
      this.rootTrace?.score({
        name: score.name,
        value: score.value,
        reason: score.reason,
      }),
    );

    return {
      testCase,
      scoreResults,
    };
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
    taskOutput: Record<string, unknown>,
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
      this.scoringKeyMapping,
    )) {
      if (sourceKey in combined) {
        mapped[targetKey] = combined[sourceKey];
      }
    }

    return mapped;
  }
}
