import { BaseMetric } from "../metrics/BaseMetric";
import type { EvaluationScoreResult } from "../types";
import type { LLMJudgeConfig } from "./llmJudgeConfig";
import { z } from "zod";

export abstract class BaseSuiteEvaluator extends BaseMetric {
  public readonly validationSchema = z.object({}).passthrough();

  protected constructor(name: string, trackMetric = true) {
    super(name, trackMetric);
  }

  abstract toConfig(): LLMJudgeConfig;

  /**
   * Score the given input and return one or more assertion results.
   *
   * Each returned `EvaluationScoreResult.value` MUST be `0` (failed) or `1`
   * (passed) — suite evaluator outputs are routed to the assertion-results
   * endpoint where they are persisted as `passed | failed`. Non-binary values
   * are coerced to `failed` and logged as a warning.
   */
  abstract score(
    input: unknown
  ):
    | EvaluationScoreResult
    | EvaluationScoreResult[]
    | Promise<EvaluationScoreResult>
    | Promise<EvaluationScoreResult[]>;
}
