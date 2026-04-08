import { useEffect, useRef } from "react";

import usePairingState from "@/hooks/usePairingState";
import { RunnerConnectionStatus } from "@/types/agent-sandbox";
import type { RunnerBridgeState } from "@/types/assistant-sidebar";

interface UseRunnerBridgeSyncOptions {
  projectId: string | null;
  onStateChanged: (state: RunnerBridgeState) => void;
}

function toBridgeState(
  pairing: ReturnType<typeof usePairingState>,
): RunnerBridgeState {
  return {
    projectId: pairing.projectId,
    status: pairing.status,
    pairCode: pairing.pairCode,
    expiresAt: pairing.expiresAt,
    runnerId: pairing.runnerId,
  };
}

export default function useRunnerBridgeSync({
  projectId,
  onStateChanged,
}: UseRunnerBridgeSyncOptions) {
  const onStateChangedRef = useRef(onStateChanged);
  onStateChangedRef.current = onStateChanged;

  const pairing = usePairingState(projectId ?? "");
  const pairingRef = useRef(pairing);
  pairingRef.current = pairing;

  // Broadcast pairing state to the sidebar on status transitions
  useEffect(() => {
    if (!pairing.projectId) return;
    onStateChangedRef.current(toBridgeState(pairing));
  }, [pairing.projectId, pairing.status, pairing.pairCode, pairing.runnerId]); // eslint-disable-line react-hooks/exhaustive-deps

  // Handle pairing requests from the sidebar (runner:request-pair)
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const handleRequestPair = useRef((_data: { projectId: string }) => {
    const current = pairingRef.current;
    if (!current.projectId) return;
    if (current.status === RunnerConnectionStatus.PAIRING) {
      onStateChangedRef.current(toBridgeState(current));
    } else {
      current.requestNewCode();
    }
  }).current;

  return { handleRequestPair };
}
