import React from "react";
import { Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";
import { useActiveProjectId } from "@/store/AppStore";
import useAgentInsightsJob from "@/api/signals/useAgentInsightsJob";
import useDiagnosticsRunState from "@/hooks/useDiagnosticsRunState";
import useDiagnosticsSeen from "@/hooks/useDiagnosticsSeen";

// How long after the automatic run is enqueued its report can still land.
// A later manual run will usually fall outside the window and get the
// plain dot instead of the pulse.
const AUTO_RUN_RESULT_WINDOW_MS = 40 * 60 * 1000;

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

  const scanMs = job?.last_scan_at ? Date.parse(job.last_scan_at) : 0;
  const autoRunAt = job?.auto_first_run_at
    ? Date.parse(job.auto_first_run_at)
    : 0;
  const hasUnseen =
    !isRunning && scanMs > 0 && (!lastSeen || scanMs > Date.parse(lastSeen));

  // Only the free automatic run pulses, every other report gets the plain dot
  const isAutoFirstRunResult =
    autoRunAt > 0 &&
    scanMs >= autoRunAt &&
    scanMs - autoRunAt < AUTO_RUN_RESULT_WINDOW_MS;

  const showSpinner = isRunning && !collapsed;
  if (!showSpinner && !hasUnseen) return null;

  const indicator = showSpinner ? (
    <Loader2 className="size-3 animate-spin text-primary" />
  ) : (
    <span
      className={cn(
        "size-1.5 rounded-full bg-primary",
        hasUnseen &&
          isAutoFirstRunResult &&
          "text-primary motion-safe:animate-beacon-pulse",
      )}
    />
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
