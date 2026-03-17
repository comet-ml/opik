import { LLMJudge } from "./LLMJudge";

export function resolveEvaluators(
  assertions: string[] | undefined,
  evaluators: LLMJudge[] | undefined,
  context: string
): LLMJudge[] | undefined {
  if (assertions?.length && evaluators?.length) {
    throw new Error(
      `Cannot specify both 'assertions' and 'evaluators' for ${context}. ` +
        `Use 'assertions' for a shorthand or 'evaluators' for full control, but not both.`
    );
  }
  if (assertions?.length) {
    return [new LLMJudge({ assertions })];
  }
  if (evaluators?.length) {
    validateEvaluators(evaluators, context);
    return evaluators;
  }
  return undefined;
}

export function validateEvaluators(
  evaluators: unknown[],
  context: string
): void {
  for (const evaluator of evaluators) {
    if (!(evaluator instanceof LLMJudge)) {
      throw new TypeError(
        `Only LLMJudge evaluators are supported for ${context}. ` +
          `Received: ${typeof evaluator === "object" && evaluator !== null ? evaluator.constructor.name : typeof evaluator}`
      );
    }
  }
}

function assertPositiveInteger(
  value: number | undefined,
  fieldName: string,
  context: string
): void {
  if (value !== undefined && (!Number.isInteger(value) || value < 1)) {
    throw new RangeError(
      `${fieldName} must be a positive integer for ${context}. Received: ${value}`
    );
  }
}

export function validateExecutionPolicy(
  policy: { runsPerItem?: number; passThreshold?: number },
  context: string
): void {
  assertPositiveInteger(policy.runsPerItem, "runsPerItem", context);
  assertPositiveInteger(policy.passThreshold, "passThreshold", context);

  if (
    policy.runsPerItem !== undefined &&
    policy.passThreshold !== undefined &&
    policy.passThreshold > policy.runsPerItem
  ) {
    throw new RangeError(
      `passThreshold (${policy.passThreshold}) cannot exceed runsPerItem (${policy.runsPerItem}) for ${context}`
    );
  }
}
