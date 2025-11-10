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
import { getSourceObjValue } from "@/utils/common";
import ora from "ora";

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
    experiment: Experiment
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
    const startTime = performance.now();

    const experimentItemReferences: ExperimentItemReferences[] = [];

    const datasetItems = await this.dataset.getItems(this.nbSamples);
    const totalItems = datasetItems.length;

    const loggerLevel = logger.settings.minLevel;
    logger.settings.minLevel = 6;
    const spinner = ora({
      text: `Evaluating dataset (0/${totalItems} items)`,
    }).start();

    const testResults: EvaluationTestResult[] = [];

    for (let i = 0; i < datasetItems.length; i++) {
      const datasetItem = datasetItems[i];
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
          endTime: new Date(),
        });
      } catch (error) {
        const errorMessage =
          error instanceof Error ? error.message : String(error);
        logger.error(
          `Error processing dataset item: ${datasetItem.id} - ${errorMessage}`
        );

        if (error instanceof Error) {
          this.rootTrace.update({
            errorInfo: {
              message: error.message,
              exceptionType: error.name,
              traceback: error.stack ?? "",
            },
            endTime: new Date(),
          });
        }
      }

      experimentItemReferences.push(
        new ExperimentItemReferences({
          datasetItemId: datasetItem.id,
          traceId: this.rootTrace.data.id,
        })
      );

      // Update spinner text with current progress
      spinner.text = `Evaluating dataset (${i + 1}/${totalItems} items, ${Math.round(((i + 1) / totalItems) * 100)}%)`;
    }

    const endTime = performance.now();
    const totalTimeSeconds = (endTime - startTime) / 1000;

    // Complete the spinner with success message
    spinner.succeed(
      `Evaluation complete: ${totalItems} items processed in ${totalTimeSeconds.toFixed(2)}s`
    );
    logger.settings.minLevel = loggerLevel;

    this.experiment.insert(experimentItemReferences);

    await this.client.flush();

    return EvaluationResultProcessor.processResults(
      testResults,
      this.experiment,
      totalTimeSeconds
    );
  }

  /**
   * Execute a single evaluation task
   *
   * @param datasetItem The dataset item to evaluate
   * @returns The test result
   */
  private async executeTask(
    datasetItem: DatasetItemData & T
  ): Promise<EvaluationTestResult> {
    let taskOutput: Record<string, unknown> = {};
    const scoreResults: EvaluationScoreResult[] = [];

    logger.debug(`Starting evaluation task on dataset item ${datasetItem.id}`);
    taskOutput = await track(
      { name: "llm_task", type: SpanType.General },
      this.task
    )(datasetItem);
    logger.debug(`Finished evaluation task on dataset item ${datasetItem.id}`);

    const scoringInputs = this.prepareScoringInputs(datasetItem, taskOutput);

    const testCase: EvaluationTestCase = {
      traceId: this.rootTrace.data.id,
      datasetItemId: datasetItem.id as string,
      scoringInputs,
      taskOutput,
    };

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
    testCase: EvaluationTestCase
  ): Promise<EvaluationTestResult> {
    const scoreResults: EvaluationScoreResult[] = [];
    const { scoringInputs } = testCase;

    for (const metric of this.scoringMetrics) {
      logger.debug(`Calculating score for metric ${metric.name}`);

      try {
        validateRequiredArguments(metric, scoringInputs);
        const metricResults = await metric.score(scoringInputs);
        const resultArray = Array.isArray(metricResults)
          ? metricResults
          : [metricResults];

        scoreResults.push(...resultArray);
      } catch (error) {
        const errorMessage =
          error instanceof Error ? error.message : String(error);
        logger.error(`Metric ${metric.name} failed: ${errorMessage}`);
      }

      logger.debug(`Finished calculating score for metric ${metric.name}`);
    }

    scoreResults.forEach((score) =>
      this.rootTrace?.score({
        name: score.name,
        value: score.value,
        reason: score.reason,
      })
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
    taskOutput: Record<string, unknown>
  ): Record<string, unknown> {
    const combined = { ...datasetItem, ...taskOutput };

    if (!this.scoringKeyMapping) {
      return combined;
    }
    const mapped: Record<string, unknown> = { ...combined };

    for (const [targetKey, sourceKey] of Object.entries(
      this.scoringKeyMapping
    )) {
      const value = getSourceObjValue(combined, sourceKey);

      if (value !== undefined) {
        mapped[targetKey] = value;
      }
    }

    return mapped;
  }
}
