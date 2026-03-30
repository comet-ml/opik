import { useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import cometApi from "@/plugins/comet/api";
import { useActiveWorkspaceName } from "@/store/AppStore";

const IS_DEV = import.meta.env.DEV;
const HEALTH_POLL_INTERVAL_MS = 5000;
const HEALTH_POLL_TIMEOUT_MS = 2 * 60 * 1000; // 2 minutes

interface ComputeResult {
  baseUrl: string | null;
  enabled: boolean;
}

export type AssistantBackendPhase =
  | "idle"
  | "compute"
  | "health"
  | "ready"
  | "error"
  | "disabled";

interface UseAssistantBackendResult {
  backendUrl: string | null;
  isReady: boolean;
  isLoading: boolean;
  error: string | null;
  phase: AssistantBackendPhase;
}

const DEV_RESULT: UseAssistantBackendResult = {
  backendUrl: "/assistant-api",
  isReady: true,
  isLoading: false,
  error: null,
  phase: "ready",
};

const notReadyResult = (
  error: string | null,
  phase: AssistantBackendPhase,
): UseAssistantBackendResult => ({
  backendUrl: null,
  isReady: false,
  isLoading: false,
  error,
  phase,
});

interface UseAssistantBackendOptions {
  enabled?: boolean;
}

export default function useAssistantBackend(
  options?: UseAssistantBackendOptions,
): UseAssistantBackendResult {
  const configEnabled = options?.enabled ?? true;
  const workspaceName = useActiveWorkspaceName();

  // Phase 1: Call compute endpoint to spawn/locate the user's pod
  const {
    data: computeResult,
    isLoading: isComputeLoading,
    error: computeError,
  } = useQuery<ComputeResult>({
    queryKey: ["assistant-compute", { workspaceName }],
    queryFn: async ({ signal }) => {
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

      const baseUrl = data.computeURL.replace(
        /\/api\/get-python-panel-url$/,
        "",
      );
      return { baseUrl, enabled: true };
    },
    enabled: !IS_DEV && configEnabled && !!workspaceName,
    staleTime: Infinity,
    retry: 2,
  });

  const baseUrl = computeResult?.baseUrl ?? null;
  const computeEnabled = computeResult?.enabled ?? false;
  const shouldPollHealth = !IS_DEV && !!baseUrl && computeEnabled;

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

  // Phase 2: Poll health endpoint until the pod is ready
  const { data: healthResult } = useQuery<{ ready: boolean }>({
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
      if (query.state.data?.ready) return false;

      const startedAt = healthPollStartRef.current;
      if (startedAt > 0 && Date.now() - startedAt > HEALTH_POLL_TIMEOUT_MS) {
        return false;
      }

      return HEALTH_POLL_INTERVAL_MS;
    },
  });

  // Dev mode — static URL, no network calls (queries are disabled via enabled: !IS_DEV)
  if (IS_DEV) return DEV_RESULT;

  if (computeResult && !computeResult.enabled)
    return notReadyResult(null, "disabled");
  if (computeError)
    return notReadyResult((computeError as Error).message, "error");
  if (isTimedOut && !healthResult?.ready) {
    return notReadyResult("Assistant pod failed to become ready", "error");
  }

  const isReady = healthResult?.ready === true;

  let phase: AssistantBackendPhase = "idle";
  if (isReady) phase = "ready";
  else if (isComputeLoading) phase = "compute";
  else if (shouldPollHealth) phase = "health";

  return {
    backendUrl: isReady ? baseUrl : null,
    isReady,
    isLoading: isComputeLoading || (shouldPollHealth && !isReady),
    error: null,
    phase,
  };
}
