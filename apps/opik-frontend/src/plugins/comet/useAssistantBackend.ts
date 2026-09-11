import { useCallback, useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import cometApi from "@/plugins/comet/api";
import { useActiveWorkspaceName } from "@/store/AppStore";
import { IS_ASSISTANT_DEV } from "@/plugins/comet/constants/assistant";

const HEALTH_POLL_INTERVAL_MS = 1000;
const HEALTH_POLL_TIMEOUT_MS = 2 * 60 * 1000; // 2 minutes
const HEALTH_KEEPALIVE_MS = 30_000; // 30s keepalive after ready

interface ComputeResult {
  baseUrl: string | null;
  enabled: boolean;
}

const getComputeQueryKey = (workspaceName: string) =>
  ["assistant-compute", { workspaceName }] as const;

const fetchAssistantCompute = async (
  workspaceName: string,
  signal?: AbortSignal,
): Promise<ComputeResult> => {
  const { data } = await cometApi.get<{
    computeURL: string;
    enabled: boolean;
  }>("/opik/ollie/compute", {
    headers: { "Comet-Workspace": workspaceName },
    signal,
  });

  if (!data.enabled) {
    return { baseUrl: null, enabled: false };
  }

  const baseUrl = data.computeURL.replace(/\/api\/get-python-panel-url$/, "");
  return { baseUrl, enabled: true };
};

export type AssistantBackendPhase =
  | "idle"
  | "compute"
  | "health"
  | "ready"
  | "error"
  | "disabled";

interface UseAssistantBackendResult {
  backendUrl: string | null;
  probeUrl: string | null;
  isReady: boolean;
  isLoading: boolean;
  error: string | null;
  phase: AssistantBackendPhase;
  retry: () => void;
  retryCount: number;
}

const DEV_RESULT: UseAssistantBackendResult = {
  backendUrl: "/assistant-api",
  probeUrl: "/assistant-api",
  isReady: true,
  isLoading: false,
  error: null,
  phase: "ready",
  retry: () => {},
  retryCount: 0,
};

const notReadyResult = (
  error: string | null,
  phase: AssistantBackendPhase,
  retry: () => void,
  retryCount: number,
  probeUrl: string | null = null,
): UseAssistantBackendResult => ({
  backendUrl: null,
  probeUrl,
  isReady: false,
  isLoading: false,
  error,
  phase,
  retry,
  retryCount,
});

interface UseAssistantBackendOptions {
  enabled?: boolean;
}

export default function useAssistantBackend(
  options?: UseAssistantBackendOptions,
): UseAssistantBackendResult {
  const configEnabled = options?.enabled ?? true;
  const workspaceName = useActiveWorkspaceName();

  const {
    data: computeResult,
    isLoading: isComputeLoading,
    error: computeError,
  } = useQuery<ComputeResult>({
    queryKey: getComputeQueryKey(workspaceName),
    queryFn: ({ signal }) => fetchAssistantCompute(workspaceName, signal),
    enabled: !IS_ASSISTANT_DEV && configEnabled && !!workspaceName,
    staleTime: Infinity,
    retry: 2,
  });

  const baseUrl = computeResult?.baseUrl ?? null;
  const computeEnabled = computeResult?.enabled ?? false;
  const shouldPollHealth = !IS_ASSISTANT_DEV && !!baseUrl && computeEnabled;

  // Track when health polling started — only reset when baseUrl changes
  // (not on transient shouldPollHealth flickers)
  const healthPollStartRef = useRef<number>(0);
  const trackedBaseUrlRef = useRef<string | null>(null);
  if (baseUrl !== trackedBaseUrlRef.current) {
    trackedBaseUrlRef.current = baseUrl;
    healthPollStartRef.current = baseUrl ? Date.now() : 0;
  }

  // Force a re-render when the timeout deadline is reached, since
  // refetchInterval returning false stops triggering renders
  const [isTimedOut, setIsTimedOut] = useState(false);
  useEffect(() => {
    if (!shouldPollHealth) {
      setIsTimedOut(false);
      return;
    }
    const startedAt = healthPollStartRef.current;
    if (startedAt === 0) return;
    const remaining = HEALTH_POLL_TIMEOUT_MS - (Date.now() - startedAt);
    if (remaining <= 0) {
      setIsTimedOut(true);
      return;
    }
    const id = setTimeout(() => setIsTimedOut(true), remaining);
    return () => clearTimeout(id);
  }, [shouldPollHealth, baseUrl]);

  const [retryCount, setRetryCount] = useState(0);
  const queryClient = useQueryClient();

  // Ref avoids stale closure — retry() stays stable across renders
  const isComputeLoadingRef = useRef(isComputeLoading);
  isComputeLoadingRef.current = isComputeLoading;

  // Core reset — used by both auto-retry and manual retry.
  // Does not touch retryCount so auto-recovery doesn't escalate the UI.
  const resetBackend = useCallback(() => {
    if (isComputeLoadingRef.current) return;

    setIsTimedOut(false);
    healthPollStartRef.current = 0;
    trackedBaseUrlRef.current = null;
    // resetQueries (not invalidateQueries): clears data immediately so
    // dead pod URL doesn't linger while refetch is in-flight.
    queryClient.resetQueries({ queryKey: ["assistant-health"] });
    queryClient.resetQueries({ queryKey: ["assistant-compute"] });
    queryClient.resetQueries({ queryKey: ["assistant-manifest"] });
  }, [queryClient]);

  // User-initiated retry — increments retryCount for UI escalation
  const retry = useCallback(() => {
    setRetryCount((c) => c + 1);
    resetBackend();
  }, [resetBackend]);

  const { data: healthResult, error: healthError } = useQuery<{
    ready: boolean;
  }>({
    queryKey: ["assistant-health", { baseUrl }],
    queryFn: async ({ signal }) => {
      const res = await fetch(`${baseUrl}/health/ready`, {
        credentials: "include",
        signal,
      });
      if (!res.ok) throw new Error(`Health check failed: ${res.status}`);
      const body = (await res.json()) as { status: string };
      return { ready: body.status === "ok" };
    },
    enabled: shouldPollHealth,
    staleTime: 0,
    retry: 2,
    refetchInterval: (query) => {
      // Pod ready and healthy — slow keepalive
      if (query.state.data?.ready && query.state.status !== "error") {
        return HEALTH_KEEPALIVE_MS;
      }

      // Timeout only during initial startup (data was never ready)
      if (!query.state.data?.ready) {
        const startedAt = healthPollStartRef.current;
        if (startedAt > 0 && Date.now() - startedAt > HEALTH_POLL_TIMEOUT_MS) {
          return false;
        }
      }

      // Fast poll: initial startup or recovery after keepalive failure
      return HEALTH_POLL_INTERVAL_MS;
    },
  });

  const isReady = healthResult?.ready === true;

  // Pod lifecycle: "never" → "ready" → "down" → "ready" → ...
  // Combines previous prevReadyRef + wasReadyRef into one state machine.
  const podStateRef = useRef<"never" | "ready" | "down">("never");

  if (isReady && podStateRef.current !== "ready") {
    if (podStateRef.current === "down" && retryCount > 0) {
      queueMicrotask(() => setRetryCount(0));
    }
    podStateRef.current = "ready";
  }

  // Auto-retry when keepalive detects pod death (ready → error).
  // Uses resetBackend (not retry) so retryCount stays 0 — the user
  // gets a fresh "retry now" prompt if auto-recovery fails.
  if (
    podStateRef.current === "ready" &&
    healthError &&
    !isComputeLoadingRef.current
  ) {
    podStateRef.current = "down";
    queueMicrotask(() => resetBackend());
  }

  // Dev mode or local override — static URL, no network calls
  if (IS_ASSISTANT_DEV) return DEV_RESULT;

  if (computeResult && !computeResult.enabled)
    return notReadyResult(null, "disabled", retry, retryCount);
  if (computeError)
    return notReadyResult(
      (computeError as Error).message,
      "error",
      retry,
      retryCount,
    );
  if (isTimedOut && !healthResult?.ready) {
    return notReadyResult(
      "Assistant pod failed to become ready",
      "error",
      retry,
      retryCount,
    );
  }

  let phase: AssistantBackendPhase = "idle";
  if (isReady) phase = "ready";
  else if (isComputeLoading) phase = "compute";
  else if (shouldPollHealth) phase = "health";

  return {
    backendUrl: isReady ? baseUrl : null,
    probeUrl: computeEnabled ? baseUrl : null,
    isReady,
    isLoading: isComputeLoading || (shouldPollHealth && !isReady),
    error: null,
    phase,
    retry,
    retryCount,
  };
}

// Shares the `assistant-compute` queryKey so the sidebar's later subscription
// reuses the prewarmed cache instead of re-issuing the request. Plugin-level
// gating (OSS vs Comet) is handled by whether the AssistantPrewarmer plugin
// component is registered in the store.
export function usePrewarmAssistantCompute(): void {
  const workspaceName = useActiveWorkspaceName();
  const queryClient = useQueryClient();

  useEffect(() => {
    if (IS_ASSISTANT_DEV) return;
    if (!workspaceName) return;
    queryClient.prefetchQuery({
      queryKey: getComputeQueryKey(workspaceName),
      queryFn: ({ signal }) => fetchAssistantCompute(workspaceName, signal),
      staleTime: Infinity,
    });
  }, [workspaceName, queryClient]);
}
