import { track } from "@/decorators/track";
import { EvaluationScoreResult } from "../types";
import { SpanType } from "@/rest_api/api";

/**
 * Abstract base class for all metrics. When creating a new metric, you should extend
 * this class and implement the abstract methods.
 *
 * @example
 * ```typescript
 * class MyCustomMetric extends BaseMetric {
 *   constructor(name: string, track: boolean = true) {
 *     super(name, track);
 *   }
 *
 *   async score(input: string, output: string, ...ignoredArgs: any[]): Promise<ScoreResult | ScoreResult[]> {
 *     // Add your logic here
 *
 *     return {
 *       name: this.name,
 *       value: 0,
 *       reason: "Optional reason for the score"
 *     };
 *   }
 * }
 * ```
 */
export abstract class BaseMetric {
  /**
   * The name of the metric
   */
  public readonly name: string;

  /**
   * Whether this metric should be tracked in Opik
   */
  public readonly trackMetric: boolean;

  /**
   * Optional project name for tracking when there is no parent span/trace
   */
  public readonly projectName?: string;

  /**
   * Creates a new metric
   *
   * @param name The name of the metric
   * @param trackMetric Whether to track the metric. Defaults to true
   * @param projectName Optional project name to track the metric in when there is no parent span/trace
   */
  constructor(name: string, trackMetric = true, projectName?: string) {
    this.name = name;
    this.trackMetric = trackMetric;
    this.projectName = projectName;

    if (!trackMetric && projectName !== undefined) {
      throw new Error(
        "projectName can be set only when 'trackMetric' is set to true"
      );
    }

    if (trackMetric) {
      const originalScore = this.score;
      const originalAScore = this.ascore;

      // Apply tracking decorator to methods
      this.score = track(
        { name: this.name, projectName, type: SpanType.General },
        originalScore
      );
      this.ascore = track(
        { name: this.name, projectName, type: SpanType.General },
        originalAScore
      );
    }
  }

  /**
   * Calculate a score for the given inputs
   *
   * @param args Arguments required by the specific metric implementation
   * @returns A score result or list of score results
   */
  abstract score(
    ...args: unknown[]
  ): Promise<EvaluationScoreResult | EvaluationScoreResult[]>;

  /**
   * Asynchronous version of the score method
   * By default, this calls the synchronous score method
   *
   * @param args Arguments required by the specific metric implementation
   * @returns A score result or list of score results
   */
  async ascore(
    ...args: unknown[]
  ): Promise<EvaluationScoreResult | EvaluationScoreResult[]> {
    return this.score(...args);
  }
}
