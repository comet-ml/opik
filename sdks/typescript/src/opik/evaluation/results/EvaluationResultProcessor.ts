import { logger } from "@/utils/logger";
import { Experiment } from "../../experiment/Experiment";
import { EvaluationResult, EvaluationTestResult } from "../types";
import chalk from "chalk";
import boxen from "boxen";

/**
 * Helper class to process evaluation results and generate summary statistics
 */
export class EvaluationResultProcessor {
  /**
   * Process test results into a final evaluation result
   *
   * @param testResults Array of test results
   * @param experiment The experiment used for evaluation
   * @returns Processed evaluation result with summaries
   */
  /**
   * Calculate average scores across all test results for each metric
   *
   * @param testResults Array of test results
   * @returns Map of metric names to their average scores
   */
  private static calculateAverageScores(
    testResults: EvaluationTestResult[]
  ): Map<string, number> {
    // If there are no test results, return an empty map
    if (!testResults || testResults.length === 0) {
      return new Map<string, number>();
    }

    const metricScores = new Map<string, { sum: number; count: number }>();

    // Gather all scores by metric name
    for (const result of testResults) {
      // Skip if result or scoreResults is undefined or empty
      if (!result || !result.scoreResults || result.scoreResults.length === 0) {
        continue;
      }

      for (const score of result.scoreResults) {
        // Skip failed or invalid scores
        if (!score || score.scoringFailed || typeof score.value !== "number") {
          continue;
        }

        const current = metricScores.get(score.name) || { sum: 0, count: 0 };
        current.sum += score.value;
        current.count += 1;
        metricScores.set(score.name, current);
      }
    }

    // Calculate averages
    const averages = new Map<string, number>();
    metricScores.forEach((value, key) => {
      averages.set(key, value.count > 0 ? value.sum / value.count : 0);
    });

    return averages;
  }

  /**
   * Format a score value as a colored string based on its value
   *
   * @param score The score value
   * @returns Formatted score string
   */
  private static formatScore(score: number): string {
    // Format to 4 decimal places
    const formatted = score.toFixed(4);
    return formatted;
  }

  /**
   * Format time in seconds to HH:MM:SS format
   *
   * @param seconds Time in seconds
   * @returns Formatted time string
   */
  private static formatTime(seconds: number): string {
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    const secs = Math.floor(seconds % 60);
    return `${hours.toString().padStart(2, "0")}:${minutes.toString().padStart(2, "0")}:${secs.toString().padStart(2, "0")}`;
  }

  /**
   * Generate a formatted log table for evaluation results
   *
   * @param testResults Array of test results
   * @param experiment The experiment used for evaluation
   * @param averageScores Map of average scores by metric name
   * @param totalTime Total execution time in seconds
   */
  private static async generateResultTable(
    testResults: EvaluationTestResult[],
    experiment: Experiment,
    averageScores: Map<string, number>,
    totalTime: number
  ): Promise<void> {
    // Check if there are no test results
    if (testResults.length === 0) {
      logger.info("\nNo test results available to display.");
      return;
    }

    // Check if there are no metrics
    if (averageScores.size === 0) {
      logger.info("\nNo metrics available to display.");
      return;
    }

    // Create the content for boxen
    const metricNames = [...averageScores.keys()].sort();
    const timeFormatted = this.formatTime(totalTime);

    // Create content lines
    const content = [
      chalk.bold(`Total time:        ${timeFormatted}`),
      chalk.bold(`Number of samples: ${testResults.length}`),
      "",
      ...metricNames.map((metric) => {
        const score = this.formatScore(averageScores.get(metric) || 0);
        return chalk.green(`${metric}: ${score} (avg)`);
      }),
    ].join("\n");

    // Create and log the boxen display
    const boxDisplay = boxen(content, {
      title: `${experiment.name} (${testResults.length} samples)`,
      titleAlignment: "left",
      padding: 1,
      margin: 0,
      borderColor: "cyan",
      borderStyle: "round",
    });

    logger.info("\n" + boxDisplay + "\n");
    logger.info(chalk.blue("Uploading results to Opik ... "));
  }

  /**
   * Display the link to the Opik dashboard for the experiment
   *
   * @param experiment The experiment to display the link for
   */
  private static async displayExperimentLink(
    experiment: Experiment
  ): Promise<void> {
    const experimentUrl = await experiment.getUrl();
    logger.info(
      `View the results \u001B]8;;${experimentUrl}\u0007${chalk.blue.underline("in your Opik dashboard")}\u001B]8;;\u0007`
    );
  }

  public static async processResults(
    testResults: EvaluationTestResult[],
    experiment: Experiment,
    totalTime: number = 0
  ): Promise<EvaluationResult> {
    // Calculate average scores for each metric
    const averageScores = this.calculateAverageScores(testResults);

    // Generate and log the results table
    await this.generateResultTable(
      testResults,
      experiment,
      averageScores,
      totalTime
    );

    // Display the experiment link
    await this.displayExperimentLink(experiment);

    return {
      experimentId: experiment.id,
      experimentName: experiment.name,
      testResults,
    };
  }
}
