import { createLink, logger } from "@/utils/logger";
import { Experiment } from "../../experiment/Experiment";
import {
  EvaluationError,
  EvaluationResult,
  EvaluationTestResult,
  TASK_ERROR_SCORE_NAME
} from "../types";
import chalk from "chalk";
import boxen from "boxen";

type MetricScoreStats = {
  /** Average of the computed scores; undefined when every score failed. */
  average: number | undefined;
  /** Number of scores that could not be computed. */
  failed: number;
};

/**
 * Helper class to process evaluation results and generate summary statistics
 */
export class EvaluationResultProcessor {
  /**
   * Per-metric average over the scores that were computed, plus how many
   * scores failed. The average is undefined when every score failed.
   */
  private static calculateScoreStats(
    testResults: EvaluationTestResult[]
  ): Map<string, MetricScoreStats> {
    const totals = new Map<
      string,
      { sum: number; count: number; failed: number }
    >();

    for (const result of testResults ?? []) {
      for (const score of result?.scoreResults ?? []) {
        // Task failures are reported separately, not as a metric.
        if (!score || score.name === TASK_ERROR_SCORE_NAME) {
          continue;
        }

        const current = totals.get(score.name) || {
          sum: 0,
          count: 0,
          failed: 0
        };
        if (score.scoringFailed) {
          current.failed += 1;
        } else if (typeof score.value === "number") {
          current.sum += score.value;
          current.count += 1;
        }
        totals.set(score.name, current);
      }
    }

    const stats = new Map<string, MetricScoreStats>();
    totals.forEach((value, key) => {
      stats.set(key, {
        average: value.count > 0 ? value.sum / value.count : undefined,
        failed: value.failed
      });
    });

    return stats;
  }

  private static formatScore(score: number): string {
    const formatted = score.toFixed(4);
    return formatted;
  }

  private static formatTime(seconds: number): string {
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    const secs = Math.floor(seconds % 60);
    return `${hours.toString().padStart(2, "0")}:${minutes.toString().padStart(2, "0")}:${secs.toString().padStart(2, "0")}`;
  }

  private static async generateResultTable(
    testResults: EvaluationTestResult[],
    experiment: Experiment,
    scoreStats: Map<string, MetricScoreStats>,
    totalTime: number,
    experimentUrl?: string
  ) {
    if (testResults.length === 0) {
      logger.info("\nNo test results available to display.");
      return;
    }

    const metricNames = [...scoreStats.keys()].sort();
    const timeFormatted = this.formatTime(totalTime);

    const contentLines: string[] = [];

    if (experimentUrl) {
      contentLines.push(
        chalk.bold.cyan(
          createLink(experimentUrl, "View results in Opik dashboard")
        ),
        ""
      );
    }

    contentLines.push(
      chalk.bold(`Total time:        ${timeFormatted}`),
      chalk.bold(`Number of samples: ${testResults.length}`)
    );

    if (metricNames.length > 0) {
      contentLines.push("");
      for (const metric of metricNames) {
        const { average, failed } = scoreStats.get(metric)!;
        const score =
          average === undefined ? "None" : this.formatScore(average);
        let line = chalk.green(`${metric}: ${score} (avg)`);
        if (failed > 0) {
          line += chalk.red(` - ${failed} failed`);
        }
        contentLines.push(line);
      }
    }

    const content = contentLines.join("\n");
    const experimentName = await experiment.ensureNameLoaded();

    const boxDisplay = boxen(content, {
      title: `${experimentName} (${testResults.length} samples)`,
      titleAlignment: "left",
      padding: 1,
      margin: 0,
      borderColor: "cyan",
      borderStyle: "round",
    });

    logger.info("\n" + boxDisplay + "\n");
  }

  public static async processResults(
    testResults: EvaluationTestResult[],
    experiment: Experiment,
    totalTime: number = 0,
    errors: EvaluationError[] = []
  ): Promise<EvaluationResult> {
    const scoreStats = this.calculateScoreStats(testResults);

    let experimentUrl: string | undefined;
    try {
      experimentUrl = await experiment.getUrl();
    } catch {
      logger.debug("Could not resolve experiment URL, skipping dashboard link");
    }

    await this.generateResultTable(
      testResults,
      experiment,
      scoreStats,
      totalTime,
      experimentUrl
    );

    const experimentName = await experiment.ensureNameLoaded();

    return {
      experimentId: experiment.id,
      experimentName: experimentName,
      testResults,
      resultUrl: experimentUrl,
      errors,
    };
  }
}
