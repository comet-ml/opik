import { LLMJudge } from "../suite_evaluators/LLMJudge";
import { EvaluatorItemWriteType } from "@/rest_api/api/types/EvaluatorItemWriteType";
import type { EvaluatorItemWrite } from "@/rest_api/api/types/EvaluatorItemWrite";
import { EvaluatorItemPublicType } from "@/rest_api/api/types/EvaluatorItemPublicType";
import type { ExecutionPolicyPublic } from "@/rest_api/api/types/ExecutionPolicyPublic";
import type { ExecutionPolicyWrite } from "@/rest_api/api/types/ExecutionPolicyWrite";
import { DEFAULT_EXECUTION_POLICY, type ExecutionPolicy } from "./types";
import { logger } from "@/utils/logger";

/**
 * Structural type that both EvaluatorItemWrite and EvaluatorItemPublic satisfy.
 * Used to accept evaluators from either dataset version metadata or item-level metadata.
 */
export interface EvaluatorItemLike {
  name: string;
  type: string;
  config: Record<string, unknown>;
}

/**
 * Serializes LLMJudge instances to the EvaluatorItemWrite API format.
 */
export function serializeEvaluators(
  evaluators: LLMJudge[]
): EvaluatorItemWrite[] {
  return evaluators.map((e) => ({
    name: e.name,
    type: EvaluatorItemWriteType.LlmJudge,
    config: e.toConfig(),
  }));
}

/**
 * Deserializes evaluator metadata into LLMJudge instances.
 * Accepts both EvaluatorItemPublic (from version metadata) and EvaluatorItemWrite (from item metadata).
 * Only "llm_judge" type is supported; other types are skipped with a warning.
 */
export function deserializeEvaluators(
  evaluators: EvaluatorItemLike[],
  evaluatorModel?: string
): LLMJudge[] {
  const results: LLMJudge[] = [];
  for (const evaluator of evaluators) {
    if (evaluator.type === EvaluatorItemPublicType.LlmJudge) {
      results.push(
        LLMJudge.fromConfig(
          evaluator.config,
          evaluatorModel ? { model: evaluatorModel } : undefined
        )
      );
    } else {
      logger.warn(
        `Unsupported evaluator type: ${evaluator.type}. Skipping.`
      );
    }
  }
  return results;
}

/**
 * Resolves an ExecutionPolicyPublic (with optional fields) to a fully-populated policy.
 * Missing fields fall back to DEFAULT_EXECUTION_POLICY values.
 */
export function resolveExecutionPolicy(
  policy: ExecutionPolicyPublic | undefined
): Required<{ runsPerItem: number; passThreshold: number }> {
  return {
    runsPerItem: policy?.runsPerItem ?? DEFAULT_EXECUTION_POLICY.runsPerItem,
    passThreshold:
      policy?.passThreshold ?? DEFAULT_EXECUTION_POLICY.passThreshold,
  };
}

/**
 * Resolves an item-level execution policy against a suite-level default.
 * Missing fields in the item policy fall back to the provided suite-level defaults.
 */
export function resolveItemExecutionPolicy(
  itemPolicy: ExecutionPolicyWrite | undefined,
  defaultPolicy: Required<ExecutionPolicy>
): Required<ExecutionPolicy> {
  if (!itemPolicy) return defaultPolicy;
  return {
    runsPerItem: itemPolicy.runsPerItem ?? defaultPolicy.runsPerItem,
    passThreshold: itemPolicy.passThreshold ?? defaultPolicy.passThreshold,
  };
}
