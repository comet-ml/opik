import { Experiment } from "../../experiment/Experiment";
import { EvaluationResult, EvaluationTestResult } from "../types";

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
  public static processResults(
    testResults: EvaluationTestResult[],
    experiment: Experiment
  ): EvaluationResult {
    return {
      experimentId: experiment.id,
      experimentName: experiment.name,
      testResults,
    };
  }
}
