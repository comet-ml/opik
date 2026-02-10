import { useCallback } from "react";
import {
  OPTIMIZE_EVENT_TYPE,
  OptimizeStreamEvent,
  AssertionResult,
  OptimizationChange,
  PromptChange,
  ScalarChange,
  RunStatus,
  RegressionResult,
  RegressionItem,
} from "@/types/agent-intake";

const INTAKE_BASE_URL = "http://localhost:5008";

export type OptimizationResult = {
  success: boolean;
  optimizationId?: string;
  iterations?: number;
  changes: OptimizationChange[];
  promptChanges?: PromptChange[];
  scalarChanges?: ScalarChange[];
  experimentTraces?: Record<string, string>;
  finalAssertionResults?: AssertionResult[];
};

type OptimizeCallbacks = {
  onOptimizationStarted: (expectedBehaviors: string[]) => void;
  onRunStatus: (label: string, iteration: number, status: RunStatus, traceId?: string) => void;
  onRunResult: (
    label: string,
    iteration: number,
    allPassed: boolean,
    assertions: AssertionResult[],
    traceId?: string,
  ) => void;
  onRegressionResult: (iteration: number, result: RegressionResult) => void;
  onOptimizationComplete: (result: OptimizationResult) => void;
};

type BackendAssertionResult = {
  text?: string;
  name?: string;
  passed?: boolean;
  reason?: string;
};

type BackendRegressionItem = {
  item_id: string;
  trace_id?: string;
  reason: string;
  failed_assertions: BackendAssertionResult[];
};

type BackendOptimizeEvent = {
  type: string;
  // optimization_started
  assertions?: BackendAssertionResult[];
  expected_behaviors?: string[];
  // run_status / run_result
  label?: string;
  iteration?: number;
  status?: RunStatus;
  all_passed?: boolean;
  trace_id?: string;
  // regression_result
  run_id?: string;
  items_tested?: number;
  items_passed?: number;
  no_regressions?: boolean;
  regressions?: BackendRegressionItem[];
  items?: BackendRegressionItem[];
  // optimization_complete
  success?: boolean;
  optimization_id?: string;
  iterations?: number;
  changes?: OptimizationChange[];
  prompt_changes?: PromptChange[];
  scalar_changes?: ScalarChange[];
  experiment_traces?: Record<string, string>;
  final_assertion_results?: BackendAssertionResult[];
};

async function parseOptimizeSSEStream(
  response: Response,
  callbacks: OptimizeCallbacks,
  signal: AbortSignal,
): Promise<{ error: string | null }> {
  if (!response.ok || !response.body) {
    return { error: `HTTP error: ${response.status}` };
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const events = buffer.split(/\r?\n\r?\n/);
      buffer = events.pop() || "";

      for (const eventStr of events) {
        if (!eventStr.trim()) continue;

        const lines = eventStr.split(/\r?\n/);
        let data: BackendOptimizeEvent | null = null;

        for (const line of lines) {
          if (line.startsWith("data:")) {
            try {
              data = JSON.parse(line.slice(5).trim()) as BackendOptimizeEvent;
            } catch {
              // ignore parse errors
            }
          }
        }

        if (!data) continue;

        switch (data.type) {
          case OPTIMIZE_EVENT_TYPE.optimization_started: {
            // Backend may send `assertions` array or `expected_behaviors` array
            const behaviors =
              data.expected_behaviors ||
              data.assertions?.map((a) => a.text || a.name || "") ||
              [];
            callbacks.onOptimizationStarted(behaviors);
            break;
          }
          case OPTIMIZE_EVENT_TYPE.run_status:
            console.log('[SSE] run_status:', data.label, 'iteration:', data.iteration, 'status:', data.status);
            if (data.iteration !== undefined && data.status) {
              const label = data.label ?? (data.iteration === 0 ? "Original" : `Iteration ${data.iteration}`);
              callbacks.onRunStatus(label, data.iteration, data.status, data.trace_id);
            }
            break;
          case OPTIMIZE_EVENT_TYPE.run_result: {
            if (data.label !== undefined && data.iteration !== undefined) {
              // Convert backend assertion format to frontend format
              const assertions: AssertionResult[] = (data.assertions || []).map((a) => ({
                name: a.text || a.name || "",
                passed: a.passed,
              }));
              callbacks.onRunResult(
                data.label,
                data.iteration,
                data.all_passed ?? false,
                assertions,
                data.trace_id,
              );
            }
            break;
          }
          case OPTIMIZE_EVENT_TYPE.regression_result: {
            console.log('[SSE] regression_result:', 'iteration:', data.iteration, 'items_tested:', data.items_tested, 'no_regressions:', data.no_regressions);
            if (data.iteration !== undefined) {
              const mapItem = (r: BackendRegressionItem): RegressionItem => ({
                item_id: r.item_id,
                trace_id: r.trace_id,
                reason: r.reason,
                failed_assertions: (r.failed_assertions || []).map((a) => ({
                  name: a.text || a.name || "",
                  passed: a.passed,
                })),
              });
              const regressions = (data.regressions || []).map(mapItem);
              const items = (data.items || []).map(mapItem);
              callbacks.onRegressionResult(data.iteration, {
                run_id: data.run_id,
                iteration: data.iteration,
                items_tested: data.items_tested ?? 0,
                items_passed: data.items_passed ?? 0,
                no_regressions: data.no_regressions ?? true,
                regressions,
                items,
              });
            }
            break;
          }
          case OPTIMIZE_EVENT_TYPE.optimization_complete: {
            const finalAssertions: AssertionResult[] = (data.final_assertion_results || []).map((a) => ({
              name: a.text || a.name || "",
              passed: a.passed,
            }));
            callbacks.onOptimizationComplete({
              success: data.success ?? false,
              optimizationId: data.optimization_id,
              iterations: data.iterations,
              changes: data.changes || [],
              promptChanges: data.prompt_changes,
              scalarChanges: data.scalar_changes,
              experimentTraces: data.experiment_traces,
              finalAssertionResults: finalAssertions,
            });
            break;
          }
          // Ignore other events like "progress", "trace_loaded"
        }
      }
    }
  } catch (error) {
    if (signal.aborted) {
      return { error: null };
    }
    return { error: (error as Error).message };
  }

  return { error: null };
}

export type OptimizeEndpointInfo = {
  name: string;
  url: string;
  secret?: string;
};

export type OptimizeStartRequest = {
  expected_behaviors: string[];
  endpoint?: OptimizeEndpointInfo;
  self_hosted?: boolean;
  max_iterations?: number;
};

export function useOptimizeStart(traceId: string) {
  return useCallback(
    async (
      request: OptimizeStartRequest,
      callbacks: OptimizeCallbacks,
      signal: AbortSignal,
    ) => {
      const response = await fetch(
        `${INTAKE_BASE_URL}/optimize/${traceId}/start`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(request),
          signal,
        },
      );

      return parseOptimizeSSEStream(response, callbacks, signal);
    },
    [traceId],
  );
}

export type CommitRequest = {
  optimization_id: string;
  prompt_names: string[];
  scalar_keys?: string[];
  project_id?: string;
  metadata?: Record<string, unknown>;
};

export type CommitResult = {
  all_success: boolean;
  committed: Array<{
    prompt_name: string;
    prompt_id?: string;
    commit: string;
    version_id?: string;
    deployment_version?: number;
    blueprint_id?: string;
  }>;
  errors: Array<{
    prompt_name: string;
    error: string;
  }>;
  deployment_version?: number;
  blueprint_id?: string;
};

export async function commitOptimization(request: CommitRequest): Promise<CommitResult> {
  const response = await fetch(`${INTAKE_BASE_URL}/optimize/commit`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    throw new Error(`Commit failed: ${response.status}`);
  }

  return response.json();
}
