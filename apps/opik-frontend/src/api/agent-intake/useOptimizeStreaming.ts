import { useCallback } from "react";
import {
  OPTIMIZE_EVENT_TYPE,
  OptimizeStreamEvent,
  AssertionResult,
  OptimizationChange,
  RunStatus,
} from "@/types/agent-intake";

const INTAKE_BASE_URL = "http://localhost:5008";

type OptimizeCallbacks = {
  onOptimizationStarted: (expectedBehaviors: string[]) => void;
  onRunStatus: (label: string, iteration: number, status: RunStatus) => void;
  onRunResult: (
    label: string,
    iteration: number,
    allPassed: boolean,
    assertions: AssertionResult[],
  ) => void;
  onOptimizationComplete: (success: boolean, changes: OptimizationChange[]) => void;
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
        let data: OptimizeStreamEvent | null = null;

        for (const line of lines) {
          if (line.startsWith("data:")) {
            try {
              data = JSON.parse(line.slice(5).trim()) as OptimizeStreamEvent;
            } catch {
              // ignore parse errors
            }
          }
        }

        if (!data) continue;

        switch (data.type) {
          case OPTIMIZE_EVENT_TYPE.optimization_started:
            callbacks.onOptimizationStarted(data.expected_behaviors);
            break;
          case OPTIMIZE_EVENT_TYPE.run_status:
            callbacks.onRunStatus(data.label, data.iteration, data.status);
            break;
          case OPTIMIZE_EVENT_TYPE.run_result:
            callbacks.onRunResult(
              data.label,
              data.iteration,
              data.all_passed,
              data.assertions,
            );
            break;
          case OPTIMIZE_EVENT_TYPE.optimization_complete:
            callbacks.onOptimizationComplete(data.success, data.changes);
            break;
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

export function useOptimizeStart(traceId: string) {
  return useCallback(
    async (callbacks: OptimizeCallbacks, signal: AbortSignal) => {
      const response = await fetch(
        `${INTAKE_BASE_URL}/optimize/${traceId}/start`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          signal,
        },
      );

      return parseOptimizeSSEStream(response, callbacks, signal);
    },
    [traceId],
  );
}
