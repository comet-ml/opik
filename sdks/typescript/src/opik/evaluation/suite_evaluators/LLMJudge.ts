import { Output } from "ai";
import { BaseSuiteEvaluator } from "./BaseSuiteEvaluator";
import type { EvaluationScoreResult } from "../types";
import { resolveModel } from "@/evaluation/models/modelsFactory";
import type { OpikBaseModel } from "@/evaluation/models/OpikBaseModel";
import type { LLMJudgeConfig } from "./llmJudgeConfig";
import { SYSTEM_PROMPT, USER_PROMPT_TEMPLATE } from "./llmJudgeTemplate";
import { ResponseSchema } from "./llmJudgeParsers";
import { logger } from "@/utils/logger";

const DEFAULT_REASONING_EFFORT = "low";

export interface LLMJudgeOptions {
  assertions: string[];
  name?: string;
  model?: string;
  track?: boolean;
  projectName?: string;
  seed?: number;
  temperature?: number;
  reasoningEffort?: string;
}

function asRecord(value: unknown): Record<string, unknown> {
  if (typeof value === "object" && value !== null) {
    return value as Record<string, unknown>;
  }
  return {};
}

export class LLMJudge extends BaseSuiteEvaluator {
  public readonly assertions: string[];
  public readonly modelName: string;
  public readonly seed?: number;
  public readonly temperature?: number;
  public readonly reasoningEffort: string;
  public readonly projectName?: string;

  private readonly model: OpikBaseModel;
  private readonly responseSchema: ResponseSchema;

  constructor(options: LLMJudgeOptions) {
    super(options.name ?? "llm_judge", options.track ?? true);

    if (options.assertions.length === 0) {
      throw new Error("LLMJudge requires at least one assertion");
    }
    for (const assertion of options.assertions) {
      if (typeof assertion !== "string" || assertion.trim() === "") {
        throw new Error(
          `LLMJudge assertions must be non-empty strings. Received: ${JSON.stringify(assertion)}`
        );
      }
    }

    this.assertions = options.assertions;
    this.modelName = options.model ?? "gpt-5-nano";
    this.seed = options.seed;
    this.temperature = options.temperature;
    this.reasoningEffort =
      options.reasoningEffort ?? DEFAULT_REASONING_EFFORT;
    this.projectName = options.projectName;

    this.model = resolveModel(this.modelName, {
      trackGenerations: options.track ?? true,
    });
    this.responseSchema = new ResponseSchema(this.assertions);
  }

  toConfig(): LLMJudgeConfig {
    const userContent = USER_PROMPT_TEMPLATE.replace(
      "{assertions}",
      this.responseSchema.formatAssertions()
    );

    return {
      version: "1.0.0",
      name: this.name,
      model: {
        name: this.modelName,
        ...(this.temperature !== undefined && {
          temperature: this.temperature,
        }),
        ...(this.seed !== undefined && { seed: this.seed }),
        customParameters: { reasoning_effort: this.reasoningEffort },
      },
      // NOTE: For test suite evaluators, no consumer reads these messages —
      // the backend replaces them with its own hardcoded copy. Included here
      // because the shared LlmAsJudgeCode schema requires @NotNull messages.
      // The prompt is duplicated in: Python SDK (metric.py), TS SDK
      // (llmJudgeTemplate.ts), FE (assertion-converters.ts), and BE
      // (TestSuitePromptConstants.java). See OPIK-5735.
      messages: [
        { role: "SYSTEM", content: SYSTEM_PROMPT },
        { role: "USER", content: userContent },
      ],
      variables: {
        input: "input",
        output: "output",
      },
      schema: this.assertions.map((assertion) => ({
        name: assertion,
        type: "BOOLEAN" as const,
        description: assertion,
      })),
    };
  }

  private hasSameSettings(other: LLMJudge): boolean {
    return (
      this.modelName === other.modelName &&
      this.temperature === other.temperature &&
      this.seed === other.seed &&
      this.reasoningEffort === other.reasoningEffort &&
      this.trackMetric === other.trackMetric
    );
  }

  static merged(judges: LLMJudge[]): LLMJudge | undefined {
    if (judges.length <= 1) return undefined;

    const first = judges[0];
    if (!judges.every((j) => first.hasSameSettings(j))) {
      return undefined;
    }

    const seen = new Set<string>();
    const mergedAssertions: string[] = [];
    for (const judge of judges) {
      for (const assertion of judge.assertions) {
        if (!seen.has(assertion)) {
          seen.add(assertion);
          mergedAssertions.push(assertion);
        }
      }
    }

    return new LLMJudge({
      assertions: mergedAssertions,
      name: first.name,
      model: first.modelName,
      seed: first.seed,
      temperature: first.temperature,
      reasoningEffort: first.reasoningEffort,
      track: first.trackMetric,
    });
  }

  static fromConfig(
    config: LLMJudgeConfig | Record<string, unknown>,
    options?: { model?: string; track?: boolean }
  ): LLMJudge {
    const configSchema = (config.schema ?? []) as Array<{
      name: string;
      description?: string;
    }>;
    const model = asRecord(config.model);
    const assertions = configSchema.map(
      (item) => item.description ?? item.name
    );

    const customParameters = asRecord(model.customParameters);
    const reasoningEffort =
      typeof customParameters.reasoning_effort === "string"
        ? customParameters.reasoning_effort
        : undefined;

    return new LLMJudge({
      assertions,
      name: typeof config.name === "string" ? config.name : "llm_judge",
      model:
        options?.model ??
        (typeof model.name === "string" ? model.name : "gpt-5-nano"),
      temperature:
        typeof model.temperature === "number" ? model.temperature : undefined,
      seed: typeof model.seed === "number" ? model.seed : undefined,
      reasoningEffort,
      track: options?.track ?? true,
    });
  }

  async score(_input: unknown): Promise<EvaluationScoreResult[]> {
    const input = asRecord(_input);
    const inputStr =
      typeof input.input === "string"
        ? input.input
        : JSON.stringify(input.input ?? "");
    const outputStr =
      typeof input.output === "string"
        ? input.output
        : JSON.stringify(input.output ?? "");

    const userContent = USER_PROMPT_TEMPLATE.replace("{input}", inputStr)
      .replace("{output}", outputStr)
      .replace("{assertions}", this.responseSchema.formatAssertions());

    try {
      const providerResponse = await this.model.generateProviderResponse(
        [
          { role: "system", content: SYSTEM_PROMPT },
          { role: "user", content: userContent },
        ],
        {
          ...(this.temperature !== undefined && {
            temperature: this.temperature,
          }),
          ...(this.seed !== undefined && { seed: this.seed }),
          reasoning_effort: this.reasoningEffort,
          output: Output.object({ schema: this.responseSchema.responseSchema }),
        }
      );

      const parsedOutput = asRecord(asRecord(providerResponse).output);
      return this.responseSchema.parse(parsedOutput);
    } catch (error) {
      logger.debug(
        `LLMJudge scoring failed: ${error instanceof Error ? error.message : String(error)}`
      );

      return this.assertions.map((assertion) => ({
        name: assertion,
        value: 0,
        reason: `LLM scoring failed: ${error instanceof Error ? error.message : String(error)}`,
        scoringFailed: true,
      }));
    }
  }
}
