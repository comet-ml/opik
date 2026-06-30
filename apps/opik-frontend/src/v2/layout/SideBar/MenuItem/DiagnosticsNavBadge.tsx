import React from "react";
import { Loader2 } from "lucide-react";
import { useActiveProjectId } from "@/store/AppStore";
import useAgentInsightsJob from "@/api/signals/useAgentInsightsJob";
import useDiagnosticsRunState from "@/hooks/useDiagnosticsRunState";
import useDiagnosticsSeen from "@/hooks/useDiagnosticsSeen";

type DiagnosticsNavBadgeProps = {
  collapsed: boolean;
};

const DiagnosticsNavBadge: React.FC<DiagnosticsNavBadgeProps> = ({
  collapsed,
}) => {
  const projectId = useActiveProjectId();
  const { isRunning } = useDiagnosticsRunState(projectId ?? "");
  const { data: job } = useAgentInsightsJob(
    { projectId: projectId ?? "" },
    { enabled: Boolean(projectId) },
  );
  const { lastSeen } = useDiagnosticsSeen(projectId ?? "");

  if (!projectId) return null;

  const scanAt = job?.last_scan_at;
  const hasUnseen =
    !isRunning &&
    Boolean(scanAt) &&
    (!lastSeen || Date.parse(scanAt!) > Date.parse(lastSeen));

  const showSpinner = isRunning && !collapsed;
  if (!showSpinner && !hasUnseen) return null;

  const indicator = showSpinner ? (
    <Loader2 className="size-3 animate-spin text-primary" />
  ) : (
    <span className="size-1.5 rounded-full bg-primary" />
  );

  return collapsed ? (
    <span className="absolute right-0.5 top-0.5 flex items-center justify-center">
      {indicator}
    </span>
  ) : (
    <span className="ml-auto flex shrink-0 items-center justify-center pl-1">
      {indicator}
    </span>
  );
};

export default DiagnosticsNavBadge;
