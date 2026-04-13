import { useEffect, useRef } from "react";
import { useQueryClient } from "@tanstack/react-query";

import useSandboxPairCode from "@/api/agent-sandbox/useSandboxPairCode";
import useSandboxConnectionStatus from "@/api/agent-sandbox/useSandboxConnectionStatus";
import { AGENT_SANDBOX_KEY } from "@/api/api";
import {
  RunnerConnectionStatus,
  type LocalRunner,
} from "@/types/agent-sandbox";

export interface PairingState {
  projectId: string;
  status: RunnerConnectionStatus;
  pairCode: string | null;
  expiresAt: number | null;
  runnerId: string | null;
  runner: LocalRunner | null;
  requestNewCode: () => void;
}

interface UsePairingStateOptions {
  autoRequestOnMount?: boolean;
}

export default function usePairingState(
  projectId: string,
  { autoRequestOnMount = false }: UsePairingStateOptions = {},
): PairingState {
  const queryClient = useQueryClient();

  const {
    data: pairCodeData,
    isFetching,
    refetch: refetchPairCode,
  } = useSandboxPairCode({ projectId });

  const { data: runnerData } = useSandboxConnectionStatus({ projectId });

  const isConnected = runnerData?.status === RunnerConnectionStatus.CONNECTED;
  const isDisconnected =
    runnerData?.status === RunnerConnectionStatus.DISCONNECTED;

  const expiresAt = pairCodeData
    ? pairCodeData.created_at + pairCodeData.expires_in_seconds * 1000
    : null;

  const isExpired = expiresAt !== null && Date.now() >= expiresAt;

  // Schedule refetch at expiry to get a fresh code
  const refetchRef = useRef(refetchPairCode);
  refetchRef.current = refetchPairCode;

  useEffect(() => {
    if (!expiresAt || isConnected || isExpired) return;

    const remaining = expiresAt - Date.now();
    if (remaining <= 0) return;

    const timer = setTimeout(() => {
      refetchRef.current();
    }, remaining);

    return () => clearTimeout(timer);
  }, [expiresAt, isConnected, isExpired]);

  // Clear stale pair code on connect; fetch a fresh one on disconnect
  useEffect(() => {
    if (isConnected) {
      queryClient.removeQueries({
        queryKey: [AGENT_SANDBOX_KEY, "pair-code", { projectId }],
      });
    } else if (isDisconnected && autoRequestOnMount) {
      refetchRef.current();
    }
  }, [isConnected, isDisconnected, autoRequestOnMount, queryClient, projectId]);

  // Auto-request a code on mount if none exists
  useEffect(() => {
    if (autoRequestOnMount && !pairCodeData && !isConnected) {
      refetchRef.current();
    }
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  let status: RunnerConnectionStatus;
  if (isFetching && !pairCodeData) {
    status = RunnerConnectionStatus.LOADING;
  } else if (isConnected) {
    status = RunnerConnectionStatus.CONNECTED;
  } else if (isDisconnected) {
    status = RunnerConnectionStatus.DISCONNECTED;
  } else if (pairCodeData && !isExpired) {
    status = RunnerConnectionStatus.PAIRING;
  } else if (pairCodeData && isExpired) {
    status = RunnerConnectionStatus.EXPIRED;
  } else {
    status = RunnerConnectionStatus.IDLE;
  }

  return {
    projectId,
    status,
    pairCode:
      status === RunnerConnectionStatus.PAIRING
        ? pairCodeData?.pair_code ?? null
        : null,
    expiresAt: status === RunnerConnectionStatus.PAIRING ? expiresAt : null,
    runnerId: pairCodeData?.runner_id ?? runnerData?.id ?? null,
    runner: runnerData ?? null,
    requestNewCode: refetchPairCode,
  };
}
