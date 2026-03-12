import { LLMJudge } from "./LLMJudge";
import type { ExecutionPolicy } from "../suite/types";

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

export function validateSuiteItems(items: unknown[]): void {
  const validKeys = new Set([
    "data",
    "assertions",
    "description",
    "executionPolicy",
  ]);

  for (let i = 0; i < items.length; i++) {
    if (typeof items[i] !== "object" || items[i] === null) {
      throw new TypeError(`Item at index ${i} must be an object`);
    }
    const item = items[i] as Record<string, unknown>;
    if (!("data" in item)) {
      throw new Error(`Item at index ${i} is missing required key 'data'`);
    }
    if (typeof item.data !== "object" || item.data === null) {
      throw new TypeError(`Item at index ${i} 'data' must be an object`);
    }
    for (const key of Object.keys(item)) {
      if (!validKeys.has(key)) {
        throw new Error(
          `Item at index ${i} has unknown key: '${key}'. ` +
            `Valid keys are: data, assertions, description, executionPolicy`
        );
      }
    }
    if ("assertions" in item) {
      if (!Array.isArray(item.assertions)) {
        throw new TypeError(
          `Item at index ${i} 'assertions' must be an array`
        );
      }
      for (let j = 0; j < (item.assertions as unknown[]).length; j++) {
        if (typeof (item.assertions as unknown[])[j] !== "string") {
          throw new TypeError(
            `Item at index ${i} 'assertions[${j}]' must be a string`
          );
        }
      }
    }
    if (item.executionPolicy) {
      validateExecutionPolicy(
        item.executionPolicy as ExecutionPolicy,
        `Item at index ${i} executionPolicy`
      );
    }
  }
}
