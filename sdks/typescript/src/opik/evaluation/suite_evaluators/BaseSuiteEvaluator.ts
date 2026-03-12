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

  abstract score(
    input: unknown
  ):
    | EvaluationScoreResult
    | EvaluationScoreResult[]
    | Promise<EvaluationScoreResult>
    | Promise<EvaluationScoreResult[]>;
}
