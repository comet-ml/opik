import { track } from "@/decorators/track";
import { EvaluationScoreResult } from "../types";
import { SpanType } from "@/rest_api/api";
import { z } from "zod";

export abstract class BaseMetric<T extends z.AnyZodObject = z.AnyZodObject> {
  /**
   * The name of the metric
   */
  public readonly name: string;

  /**
   * Whether this metric should be tracked
   */
  public readonly trackMetric: boolean;

  /**
   * Zod schema for validating input parameters to the score method
   */
  public abstract readonly validationSchema: T;

  protected constructor(name: string, trackMetric = true) {
    this.name = name;
    this.trackMetric = trackMetric;

    if (trackMetric) {
      const originalScore = this.score.bind(this);
      this.score = track(
        { name: this.name, type: SpanType.General },
        originalScore
      );
    }
  }

  /**
   * Compute the score using validated input
   * @param input - The validated input of type inferred from the schema
   */
  abstract score(
    input: unknown
  ):
    | EvaluationScoreResult
    | EvaluationScoreResult[]
    | Promise<EvaluationScoreResult>
    | Promise<EvaluationScoreResult[]>;
}
