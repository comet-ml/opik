import type { ExecutionPolicyWrite } from "@/rest_api/api/types/ExecutionPolicyWrite";
import type { EvaluationTestResult } from "../types";

/**
 * Execution policy for evaluation suite items.
 * Mirrors the Fern-generated ExecutionPolicyWrite type.
 */
export type ExecutionPolicy = ExecutionPolicyWrite;

export const DEFAULT_EXECUTION_POLICY: Required<ExecutionPolicy> = {
  runsPerItem: 1,
  passThreshold: 1,
};

/**
 * Result of an individual item in the evaluation suite.
 */
export type ItemResult = {
  datasetItemId: string;
  passed: boolean;
  runsPassed: number;
  runsTotal: number;
  passThreshold: number;
  testResults: EvaluationTestResult[];
};

/**
 * Result of an evaluation suite run.
 */
export type EvaluationSuiteResult = {
  allItemsPassed: boolean;
  itemsPassed: number;
  itemsTotal: number;
  passRate: number;
  itemResults: Map<string, ItemResult>;
  experimentId: string;
  experimentName?: string;
  experimentUrl?: string;
};
