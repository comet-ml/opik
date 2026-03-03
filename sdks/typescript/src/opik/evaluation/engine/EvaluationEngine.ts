import { OpikClient } from "@/client/Client";
import { Dataset } from "../../dataset/Dataset";
import { DatasetVersion } from "../../dataset/DatasetVersion";
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
import type { ExecutionPolicy } from "../suite/types";

type DatasetOrVersion<T extends DatasetItemData> =
  | Dataset<T>
  | DatasetVersion<T>;

interface ProgressTracker {
  update(completedRuns: number, itemIndex: number): void;
  complete(elapsedSeconds: number): void;
}

/**
 * Extended options that include suite-specific fields.
 */
export type EvaluationEngineOptions<T = Record<string, unknown>> =
  EvaluateOptions<T> & {
    /** Explicit flag: engine runs in evaluation suite mode. */
    suiteMode?: boolean;
    /** Suite execution policy (runsPerItem, passThreshold). When omitted, behaves as standard evaluate. */
    executionPolicy?: Required<ExecutionPolicy>;
    /** Pre-fetched dataset items to avoid duplicate API calls. */
    prefetchedItems?: (DatasetItemData & T & { id: string })[];
    /** Per-item metrics map. Key is dataset item ID, value is the metrics to use for that item. */
    itemMetricsMap?: Map<string, BaseMetric[]>;
    /** Per-item execution policy map. Key is dataset item ID, value is the resolved policy. */
    itemPolicyMap?: Map<string, Required<ExecutionPolicy>>;
  };

/**
 * Core evaluation engine.
 *
 * Orchestrates dataset item evaluation: fetches items, runs the task per item
 * (potentially multiple times in suite mode), scores results, and writes
 * experiment references.
 *
 * In suite mode (`suiteMode: true`), each item may be executed multiple times
 * according to its execution policy, and each result receives a `trialId`.
 * In standard mode, each item is executed exactly once.
 */
export class EvaluationEngine<T = Record<string, unknown>> {
  private readonly client: OpikClient;
  private readonly dataset: DatasetOrVersion<
    T extends DatasetItemData ? T : DatasetItemData & T
  >;
  private readonly task: EvaluationTask<T>;
  private readonly scoringMetrics: BaseMetric[];
  private readonly projectName?: string;
  private readonly nbSamples?: number;
  private readonly scoringKeyMapping?: ScoringKeyMappingType;
  private readonly experiment: Experiment;
  private readonly suiteMode: boolean;
  private readonly executionPolicy?: Required<ExecutionPolicy>;
  private readonly prefetchedItems?: (DatasetItemData & T & { id: string })[];
  private readonly itemMetricsMap?: Map<string, BaseMetric[]>;
  private readonly itemPolicyMap?: Map<string, Required<ExecutionPolicy>>;

  constructor(
    options: EvaluationEngineOptions<T>,
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
    this.suiteMode = options.suiteMode ?? false;
    this.executionPolicy = options.executionPolicy;
    this.prefetchedItems = options.prefetchedItems;
    this.itemMetricsMap = options.itemMetricsMap;
    this.itemPolicyMap = options.itemPolicyMap;
  }

  /**
   * Execute the evaluation process.
   *
   * 1. Fetch dataset items
   * 2. For each item, run the task (once in standard mode, N times in suite mode)
   * 3. Score each run, record experiment references
   * 4. Insert references and return aggregated results
   */
  public async execute(): Promise<EvaluationResult> {
    const items = await this.getDatasetItems();
    const totalRuns = this.calculateTotalRuns(items);
    const progress = this.createProgressTracker(items.length, totalRuns);
    const startTime = performance.now();

    const testResults: EvaluationTestResult[] = [];
    const experimentRefs: ExperimentItemReferences[] = [];
    let completedRuns = 0;

    for (let i = 0; i < items.length; i++) {
      const item = items[i];
      const runsPerItem = this.getRunsPerItem(item);
      const metrics = this.getItemMetrics(item);

      for (let runIndex = 0; runIndex < runsPerItem; runIndex++) {
        try {
          const testResult = await this.executeItemRun(
            item,
            metrics,
            runIndex,
            experimentRefs
          );
          testResults.push(testResult);
        } catch {
          // Error already logged and trace updated in executeItemRun.
          // Experiment ref already persisted via its finally block.
          // Continue processing remaining items (matches Python SDK executor pattern).
        }
        completedRuns++;
      }

      progress.update(completedRuns, i);
    }

    this.experiment.insert(experimentRefs);
    await this.client.flush();

    const elapsedSeconds = (performance.now() - startTime) / 1000;
    progress.complete(elapsedSeconds);

    return EvaluationResultProcessor.processResults(
      testResults,
      this.experiment,
      elapsedSeconds
    );
  }

  private async getDatasetItems(): Promise<
    (DatasetItemData & T & { id: string })[]
  > {
    return this.prefetchedItems ?? (await this.dataset.getItems(this.nbSamples));
  }

  private calculateTotalRuns(items: { id: string }[]): number {
    const defaultRunsPerItem = this.executionPolicy?.runsPerItem ?? 1;

    if (this.itemPolicyMap) {
      return items.reduce((sum, item) => {
        const policy = this.itemPolicyMap!.get(item.id);
        return sum + (policy?.runsPerItem ?? defaultRunsPerItem);
      }, 0);
    }

    return items.length * defaultRunsPerItem;
  }

  private getRunsPerItem(item: { id: string }): number {
    return (
      this.itemPolicyMap?.get(item.id)?.runsPerItem ??
      this.executionPolicy?.runsPerItem ??
      1
    );
  }

  private getItemMetrics(item: { id: string }): BaseMetric[] | undefined {
    return this.itemMetricsMap?.get(item.id);
  }

  private createProgressTracker(
    totalItems: number,
    totalRuns: number
  ): ProgressTracker {
    const savedLogLevel = logger.settings.minLevel;
    logger.settings.minLevel = 6;

    const spinnerLabel = this.suiteMode
      ? `Evaluating dataset (0/${totalRuns} runs across ${totalItems} items)`
      : `Evaluating dataset (0/${totalItems} items)`;
    const spinner = ora({ text: spinnerLabel }).start();

    return {
      update: (completedRuns: number, itemIndex: number) => {
        spinner.text = this.suiteMode
          ? `Evaluating dataset (${completedRuns}/${totalRuns} runs across ${totalItems} items, ${Math.round((completedRuns / totalRuns) * 100)}%)`
          : `Evaluating dataset (${itemIndex + 1}/${totalItems} items, ${Math.round(((itemIndex + 1) / totalItems) * 100)}%)`;
      },
      complete: (elapsedSeconds: number) => {
        spinner.succeed(
          this.suiteMode
            ? `Evaluation complete: ${totalRuns} runs across ${totalItems} items processed in ${elapsedSeconds.toFixed(2)}s`
            : `Evaluation complete: ${totalItems} items processed in ${elapsedSeconds.toFixed(2)}s`
        );
        logger.settings.minLevel = savedLogLevel;
      },
    };
  }

  private async executeItemRun(
    datasetItem: DatasetItemData & T & { id: string },
    metrics: BaseMetric[] | undefined,
    runIndex: number,
    experimentRefs: ExperimentItemReferences[]
  ): Promise<EvaluationTestResult> {
    const trace = this.client.trace({
      projectName: this.projectName,
      name: "evaluation_task",
      createdBy: "evaluation",
      input: datasetItem,
    });
    trackStorage.enterWith({ trace });

    try {
      const testResult = await this.executeTask(datasetItem, metrics, trace);

      if (this.suiteMode) {
        testResult.trialId = runIndex;
        const itemPolicy = this.itemPolicyMap?.get(datasetItem.id);
        if (itemPolicy) {
          testResult.resolvedExecutionPolicy = itemPolicy;
        }
      }

      trace.update({
        output: testResult.testCase.taskOutput,
        endTime: new Date(),
      });

      return testResult;
    } catch (error) {
      const errorMessage =
        error instanceof Error ? error.message : String(error);
      logger.error(
        `Error processing dataset item: ${datasetItem.id} (run ${runIndex}) - ${errorMessage}`
      );

      if (error instanceof Error) {
        trace.update({
          errorInfo: {
            message: error.message,
            exceptionType: error.name,
            traceback: error.stack ?? "",
          },
          endTime: new Date(),
        });
      }

      throw error;
    } finally {
      experimentRefs.push(
        new ExperimentItemReferences({
          datasetItemId: datasetItem.id,
          traceId: trace.data.id,
          projectName: trace.data.projectName,
        })
      );
    }
  }

  private async executeTask(
    datasetItem: DatasetItemData & T & { id: string },
    metrics: BaseMetric[] | undefined,
    trace: Trace
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
      traceId: trace.data.id,
      datasetItemId: datasetItem.id,
      scoringInputs,
      taskOutput,
    };

    const effectiveMetrics = metrics ?? this.scoringMetrics;

    if (effectiveMetrics.length > 0) {
      return this.calculateScores(testCase, effectiveMetrics, trace);
    }

    return {
      testCase,
      scoreResults,
    };
  }

  @track({ name: "metrics_calculation", type: SpanType.General })
  private async calculateScores(
    testCase: EvaluationTestCase,
    metrics: BaseMetric[] | undefined,
    trace: Trace
  ): Promise<EvaluationTestResult> {
    const scoreResults: EvaluationScoreResult[] = [];
    const { scoringInputs } = testCase;
    const effectiveMetrics = metrics ?? this.scoringMetrics;

    for (const metric of effectiveMetrics) {
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
      trace.score({
        name: score.name,
        value: score.value,
        reason: score.reason,
        categoryName: score.categoryName,
      })
    );

    return {
      testCase,
      scoreResults,
    };
  }

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
