import { createLink, logger } from "@/utils/logger";
import { Experiment } from "../../experiment/Experiment";
import { EvaluationError, EvaluationResult, EvaluationTestResult } from "../types";
import chalk from "chalk";
import boxen from "boxen";

/**
 * Helper class to process evaluation results and generate summary statistics
 */
export class EvaluationResultProcessor {
  private static calculateAverageScores(
    testResults: EvaluationTestResult[]
  ): Map<string, number> {
    if (!testResults || testResults.length === 0) {
      return new Map<string, number>();
    }

    const metricScores = new Map<string, { sum: number; count: number }>();

    for (const result of testResults) {
      if (!result || !result.scoreResults || result.scoreResults.length === 0) {
        continue;
      }

      for (const score of result.scoreResults) {
        if (!score || score.scoringFailed || typeof score.value !== "number") {
          continue;
        }

        const current = metricScores.get(score.name) || { sum: 0, count: 0 };
        current.sum += score.value;
        current.count += 1;
        metricScores.set(score.name, current);
      }
    }

    const averages = new Map<string, number>();
    metricScores.forEach((value, key) => {
      averages.set(key, value.count > 0 ? value.sum / value.count : 0);
    });

    return averages;
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
    averageScores: Map<string, number>,
    totalTime: number,
    experimentUrl?: string
  ) {
    if (testResults.length === 0) {
      logger.info("\nNo test results available to display.");
      return;
    }

    const metricNames = [...averageScores.keys()].sort();
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
        const score = this.formatScore(averageScores.get(metric) || 0);
        contentLines.push(chalk.green(`${metric}: ${score} (avg)`));
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
    const averageScores = this.calculateAverageScores(testResults);

    let experimentUrl: string | undefined;
    try {
      experimentUrl = await experiment.getUrl();
    } catch {
      logger.debug("Could not resolve experiment URL, skipping dashboard link");
    }

    await this.generateResultTable(
      testResults,
      experiment,
      averageScores,
      totalTime,
      experimentUrl
    );

    const experimentName = await experiment.ensureNameLoaded();

    return {
      experimentId: experiment.id,
      experimentName: experimentName,
      testResults,
      errors,
    };
  }
}
