import { useEffect, useRef } from "react";

import usePairingState from "@/hooks/usePairingState";
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
    runnerId: pairing.runnerId,
  };
}

export default function useRunnerBridgeSync({
  projectId,
  onStateChanged,
}: UseRunnerBridgeSyncOptions) {
  const onStateChangedRef = useRef(onStateChanged);
  onStateChangedRef.current = onStateChanged;

  const pairing = usePairingState(projectId ?? "", "connect");

  useEffect(() => {
    if (!pairing.projectId) return;
    onStateChangedRef.current(toBridgeState(pairing));
  }, [pairing.projectId, pairing.status, pairing.runnerId]); // eslint-disable-line react-hooks/exhaustive-deps

  // No-op: pairing is now CLI-initiated, but the sidebar may still emit this event
  const handleRequestPair = () => {};

  return { handleRequestPair };
}
