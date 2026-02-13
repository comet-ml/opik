import { GEvalPreset } from "./GEval";
import type { LLMJudgeModelSettings } from "../BaseLLMJudgeMetric";
import type { SupportedModelId } from "@/evaluation/models/providerDetection";
import type { LanguageModel } from "ai";
import type { OpikBaseModel } from "@/evaluation/models/OpikBaseModel";

interface JudgeOptions {
  model?: SupportedModelId | LanguageModel | OpikBaseModel;
  trackMetric?: boolean;
  temperature?: number;
  seed?: number;
  maxTokens?: number;
  modelSettings?: LLMJudgeModelSettings;
}

/**
 * Score how faithful a summary is to its source content.
 *
 * @example
 * ```typescript
 * import { SummarizationConsistencyJudge } from "opik/evaluation/metrics";
 *
 * const judge = new SummarizationConsistencyJudge({ model: "gpt-4o" });
 * const result = await judge.score({ output: "Summary omits key fact." });
 * console.log(result.value); // 0.0 - 1.0
 * ```
 */
export class SummarizationConsistencyJudge extends GEvalPreset {
  constructor(options: JudgeOptions = {}) {
    super({
      preset: "summarization_consistency",
      name: "summarization_consistency_judge",
      ...options,
    });
  }
}

/**
 * Evaluate the coherence and structure of generated summaries.
 *
 * @example
 * ```typescript
 * import { SummarizationCoherenceJudge } from "opik/evaluation/metrics";
 *
 * const judge = new SummarizationCoherenceJudge({ model: "gpt-4o" });
 * const result = await judge.score({ output: "Summary jumps between unrelated topics." });
 * console.log(result.value); // 0.0 - 1.0
 * ```
 */
export class SummarizationCoherenceJudge extends GEvalPreset {
  constructor(options: JudgeOptions = {}) {
    super({
      preset: "summarization_coherence",
      name: "summarization_coherence_judge",
      ...options,
    });
  }
}

/**
 * Judge how helpful an assistant reply is within a dialogue.
 *
 * @example
 * ```typescript
 * import { DialogueHelpfulnessJudge } from "opik/evaluation/metrics";
 *
 * const judge = new DialogueHelpfulnessJudge({ model: "gpt-4o" });
 * const result = await judge.score({ output: "Assistant politely refuses without help." });
 * console.log(result.value); // 0.0 - 1.0
 * ```
 */
export class DialogueHelpfulnessJudge extends GEvalPreset {
  constructor(options: JudgeOptions = {}) {
    super({
      preset: "dialogue_helpfulness",
      name: "dialogue_helpfulness_judge",
      ...options,
    });
  }
}

/**
 * Check whether an answer directly addresses the user question.
 *
 * @example
 * ```typescript
 * import { QARelevanceJudge } from "opik/evaluation/metrics";
 *
 * const judge = new QARelevanceJudge({ model: "gpt-4o" });
 * const result = await judge.score({ output: "Paris is the capital of France." });
 * console.log(result.value); // 0.0 - 1.0
 * ```
 */
export class QARelevanceJudge extends GEvalPreset {
  constructor(options: JudgeOptions = {}) {
    super({
      preset: "qa_relevance",
      name: "qa_relevance_judge",
      ...options,
    });
  }
}

/**
 * Score demographic stereotyping or bias in a response.
 *
 * @example
 * ```typescript
 * import { DemographicBiasJudge } from "opik/evaluation/metrics";
 *
 * const judge = new DemographicBiasJudge({ model: "gpt-4o" });
 * const result = await judge.score({ output: "People from X group are always late." });
 * console.log(result.value); // 0.0 - 1.0
 * ```
 */
export class DemographicBiasJudge extends GEvalPreset {
  constructor(options: JudgeOptions = {}) {
    super({
      preset: "bias_demographic",
      name: "demographic_bias_judge",
      ...options,
    });
  }
}

/**
 * Detect partisan or ideological bias in a response.
 *
 * @example
 * ```typescript
 * import { PoliticalBiasJudge } from "opik/evaluation/metrics";
 *
 * const judge = new PoliticalBiasJudge({ model: "gpt-4o" });
 * const result = await judge.score({ output: "Vote for candidate X because Y is corrupt" });
 * console.log(result.value); // 0.0 - 1.0
 * ```
 */
export class PoliticalBiasJudge extends GEvalPreset {
  constructor(options: JudgeOptions = {}) {
    super({
      preset: "bias_political",
      name: "political_bias_judge",
      ...options,
    });
  }
}

/**
 * Detect gender stereotyping or exclusion in generated text.
 *
 * @example
 * ```typescript
 * import { GenderBiasJudge } from "opik/evaluation/metrics";
 *
 * const judge = new GenderBiasJudge({ model: "gpt-4o" });
 * const result = await judge.score({ output: "Women are naturally worse at math." });
 * console.log(result.value); // 0.0 - 1.0
 * ```
 */
export class GenderBiasJudge extends GEvalPreset {
  constructor(options: JudgeOptions = {}) {
    super({
      preset: "bias_gender",
      name: "gender_bias_judge",
      ...options,
    });
  }
}

/**
 * Evaluate responses for religious bias or disrespectful language.
 *
 * @example
 * ```typescript
 * import { ReligiousBiasJudge } from "opik/evaluation/metrics";
 *
 * const judge = new ReligiousBiasJudge({ model: "gpt-4o" });
 * const result = await judge.score({ output: "Believers of X are all foolish." });
 * console.log(result.value); // 0.0 - 1.0
 * ```
 */
export class ReligiousBiasJudge extends GEvalPreset {
  constructor(options: JudgeOptions = {}) {
    super({
      preset: "bias_religion",
      name: "religious_bias_judge",
      ...options,
    });
  }
}

/**
 * Assess geographic or cultural bias in responses.
 *
 * @example
 * ```typescript
 * import { RegionalBiasJudge } from "opik/evaluation/metrics";
 *
 * const judge = new RegionalBiasJudge({ model: "gpt-4o" });
 * const result = await judge.score({ output: "People from region Z are lazy." });
 * console.log(result.value); // 0.0 - 1.0
 * ```
 */
export class RegionalBiasJudge extends GEvalPreset {
  constructor(options: JudgeOptions = {}) {
    super({
      preset: "bias_regional",
      name: "regional_bias_judge",
      ...options,
    });
  }
}

/**
 * Judge whether an agent invoked and interpreted tools correctly.
 *
 * @example
 * ```typescript
 * import { AgentToolCorrectnessJudge } from "opik/evaluation/metrics";
 *
 * const judge = new AgentToolCorrectnessJudge({ model: "gpt-4o" });
 * const transcript = "Agent called search_tool and used the answer correctly.";
 * const result = await judge.score({ output: transcript });
 * console.log(result.value); // 0.0 - 1.0
 * ```
 */
export class AgentToolCorrectnessJudge extends GEvalPreset {
  constructor(options: JudgeOptions = {}) {
    super({
      preset: "agent_tool_correctness",
      name: "agent_tool_correctness_judge",
      ...options,
    });
  }
}

/**
 * Evaluate whether an agent successfully completed the original task.
 *
 * @example
 * ```typescript
 * import { AgentTaskCompletionJudge } from "opik/evaluation/metrics";
 *
 * const judge = new AgentTaskCompletionJudge({ model: "gpt-4o" });
 * const result = await judge.score({ output: "Agent delivered the requested summary." });
 * console.log(result.value); // 0.0 - 1.0
 * ```
 */
export class AgentTaskCompletionJudge extends GEvalPreset {
  constructor(options: JudgeOptions = {}) {
    super({
      preset: "agent_task_completion",
      name: "agent_task_completion_judge",
      ...options,
    });
  }
}

/**
 * Rate how ambiguous or underspecified a prompt feels to the model.
 *
 * @example
 * ```typescript
 * import { PromptUncertaintyJudge } from "opik/evaluation/metrics";
 *
 * const judge = new PromptUncertaintyJudge({ model: "gpt-4o" });
 * const result = await judge.score({ output: "Do the right thing in the best way possible." });
 * console.log(result.value); // 0.0 - 1.0
 * ```
 */
export class PromptUncertaintyJudge extends GEvalPreset {
  constructor(options: JudgeOptions = {}) {
    super({
      preset: "prompt_uncertainty",
      name: "prompt_uncertainty_judge",
      ...options,
    });
  }
}

/**
 * Evaluate responses for non-compliant or misleading claims in regulated sectors.
 *
 * @example
 * ```typescript
 * import { ComplianceRiskJudge } from "opik/evaluation/metrics";
 *
 * const judge = new ComplianceRiskJudge({ model: "gpt-4o" });
 * const result = await judge.score({ output: "This pill cures diabetes in a week." });
 * console.log(result.value); // 0.0 - 1.0
 * ```
 */
export class ComplianceRiskJudge extends GEvalPreset {
  constructor(options: JudgeOptions = {}) {
    super({
      preset: "compliance_regulated_truthfulness",
      name: "compliance_risk_judge",
      ...options,
    });
  }
}
