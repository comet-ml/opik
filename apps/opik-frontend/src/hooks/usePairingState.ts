import useSandboxConnectionStatus from "@/api/agent-sandbox/useSandboxConnectionStatus";
import {
  RunnerConnectionStatus,
  type LocalRunner,
  type LocalRunnerType,
} from "@/types/agent-sandbox";

export interface PairingState {
  projectId: string;
  status: RunnerConnectionStatus;
  runnerId: string | null;
  runner: LocalRunner | null;
}

export default function usePairingState(
  projectId: string,
  runnerType?: LocalRunnerType,
): PairingState {
  const { data: runnerData } = useSandboxConnectionStatus({
    projectId,
    runnerType,
  });

  const isConnected = runnerData?.status === RunnerConnectionStatus.CONNECTED;
  const isDisconnected =
    runnerData?.status === RunnerConnectionStatus.DISCONNECTED;

  let status: RunnerConnectionStatus;
  if (isConnected) {
    status = RunnerConnectionStatus.CONNECTED;
  } else if (isDisconnected) {
    status = RunnerConnectionStatus.DISCONNECTED;
  } else {
    status = RunnerConnectionStatus.IDLE;
  }

  return {
    projectId,
    status,
    runnerId: runnerData?.id ?? null,
    runner: runnerData ?? null,
  };
}
