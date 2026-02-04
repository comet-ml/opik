import { useQuery } from "@tanstack/react-query";

const CONFIG_BACKEND_URL = "http://localhost:5050";

export type EvalSuiteItem = {
  id: string;
  suite_id: string;
  input_data: Record<string, unknown>;
  assertions: string[];
  trace_id: string | null;
  created_at: string;
};

export type EvalSuite = {
  id: string;
  name: string;
  project_id: string;
  item_count: number;
  created_at: string;
  created_by: string | null;
};

export type EvalRunStatus = "pending" | "running" | "completed" | "failed";

export type EvalRun = {
  id: string;
  suite_id: string;
  status: EvalRunStatus;
  started_at: string | null;
  completed_at: string | null;
  experiment_id: string | null;
  total_items: number;
  passed_items: number;
  pass_rate: number;
  created_at: string;
  created_by: string | null;
};

export type EvalRunResult = {
  id: string;
  run_id: string;
  item_id: string;
  passed: boolean;
  assertion_results: { name: string; passed: boolean }[];
  trace_id: string | null;
  duration_ms: number | null;
  error_message: string | null;
  input_data?: Record<string, unknown>;
  created_at: string;
};

type UseEvalSuitesParams = {
  projectId: string;
};

const fetchEvalSuites = async (projectId: string): Promise<EvalSuite[]> => {
  const res = await fetch(
    `${CONFIG_BACKEND_URL}/v1/eval-suites?project_id=${projectId}`
  );

  if (!res.ok) {
    throw new Error("Failed to fetch eval suites");
  }

  const { suites }: { suites: EvalSuite[] } = await res.json();
  return suites;
};

const useEvalSuites = (params: UseEvalSuitesParams) => {
  return useQuery({
    queryKey: ["eval-suites", params.projectId],
    queryFn: () => fetchEvalSuites(params.projectId),
  });
};

export default useEvalSuites;

export const fetchEvalSuiteItems = async (
  suiteId: string
): Promise<EvalSuiteItem[]> => {
  const res = await fetch(
    `${CONFIG_BACKEND_URL}/v1/eval-suites/${suiteId}/items`
  );

  if (!res.ok) {
    throw new Error("Failed to fetch eval suite items");
  }

  const { items }: { items: EvalSuiteItem[] } = await res.json();
  return items;
};

type UseEvalSuiteItemsParams = {
  suiteId: string | null;
};

export const useEvalSuiteItems = (params: UseEvalSuiteItemsParams) => {
  return useQuery({
    queryKey: ["eval-suite-items", params.suiteId],
    queryFn: () => fetchEvalSuiteItems(params.suiteId!),
    enabled: !!params.suiteId,
  });
};

export const createEvalSuite = async (
  name: string,
  projectId: string
): Promise<EvalSuite> => {
  const res = await fetch(`${CONFIG_BACKEND_URL}/v1/eval-suites`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name, project_id: projectId }),
  });

  if (!res.ok) {
    throw new Error("Failed to create eval suite");
  }

  return res.json();
};

export const deleteEvalSuiteItem = async (
  suiteId: string,
  itemId: string
): Promise<void> => {
  const res = await fetch(
    `${CONFIG_BACKEND_URL}/v1/eval-suites/${suiteId}/items/${itemId}`,
    { method: "DELETE" }
  );

  if (!res.ok) {
    throw new Error("Failed to delete eval suite item");
  }
};

// Eval Runs API

const fetchEvalRuns = async (suiteId: string): Promise<EvalRun[]> => {
  const res = await fetch(
    `${CONFIG_BACKEND_URL}/v1/eval-suites/${suiteId}/runs`
  );

  if (!res.ok) {
    throw new Error("Failed to fetch eval runs");
  }

  const { runs }: { runs: EvalRun[] } = await res.json();
  return runs;
};

type UseEvalRunsParams = {
  suiteId: string | null;
};

export const useEvalRuns = (params: UseEvalRunsParams) => {
  return useQuery({
    queryKey: ["eval-runs", params.suiteId],
    queryFn: () => fetchEvalRuns(params.suiteId!),
    enabled: !!params.suiteId,
  });
};

const fetchEvalRunResults = async (runId: string): Promise<EvalRunResult[]> => {
  const res = await fetch(
    `${CONFIG_BACKEND_URL}/v1/eval-runs/${runId}/results`
  );

  if (!res.ok) {
    throw new Error("Failed to fetch eval run results");
  }

  const { results }: { results: EvalRunResult[] } = await res.json();
  return results;
};

type UseEvalRunResultsParams = {
  runId: string | null;
};

export const useEvalRunResults = (params: UseEvalRunResultsParams) => {
  return useQuery({
    queryKey: ["eval-run-results", params.runId],
    queryFn: () => fetchEvalRunResults(params.runId!),
    enabled: !!params.runId,
  });
};
