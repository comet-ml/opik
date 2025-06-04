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
 *   score(input: T): Promise<ScoreResult | ScoreResult[]> {
 *     // Add your logic here
 *
 *     return {
 *       name: this.name,
 *       value: 0,
 *       reason: "Optional reason for the score"
 *     };
 *   }
 *
 * ```
 */
export abstract class BaseMetric<T extends object = object> {
  /**
   * The name of the metric
   */
  public readonly name: string;

  /**
   * Whether this metric should be tracked in Opik
   */
  public readonly trackMetric: boolean;

  /**
   * Creates a new metric
   *
   * @param name The name of the metric
   * @param trackMetric Whether to track the metric. Defaults to true
   */
  protected constructor(name: string, trackMetric = true) {
    this.name = name;
    this.trackMetric = trackMetric;

    if (trackMetric) {
      const originalScore = this.score;

      // Apply tracking decorator to methods
      this.score = track(
        { name: this.name, type: SpanType.General },
        originalScore,
      );
    }
  }

  /**
   * Calculate a score for the given inputs
   *
   * @param input Arguments required by the specific metric implementation
   * @returns A score result or list of score results
   */
  abstract score(
    input?: T,
  ):
    | EvaluationScoreResult
    | EvaluationScoreResult[]
    | Promise<EvaluationScoreResult>
    | Promise<EvaluationScoreResult[]>;
}
