import type { ExecutionPolicyWrite } from "@/rest_api/api/types/ExecutionPolicyWrite";
import type { EvaluationTestResult } from "../types";

/**
 * Execution policy for test suite items.
 * Mirrors the Fern-generated ExecutionPolicyWrite type.
 */
export type ExecutionPolicy = ExecutionPolicyWrite;

export const DEFAULT_EXECUTION_POLICY: Required<ExecutionPolicy> = {
  runsPerItem: 1,
  passThreshold: 1,
};

/**
 * A single item to be inserted into a test suite via `insert()`.
 */
export interface TestSuiteItem {
  data: Record<string, unknown>;
  assertions?: string[];
  description?: string;
  executionPolicy?: ExecutionPolicy;
}

/**
 * Result of an individual item in the test suite.
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
 * Result of a test suite run.
 */
export type TestSuiteResult = {
  allItemsPassed: boolean;
  itemsPassed: number;
  itemsTotal: number;
  passRate: number | undefined;
  itemResults: Map<string, ItemResult>;
  experimentId: string;
  experimentName?: string;
  experimentUrl?: string;
};
