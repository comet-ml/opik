import React, { useMemo, useState } from "react";
import { Info, LucideIcon, TriangleAlert } from "lucide-react";

import { Button } from "@/ui/button";
import { Optimization, OptimizationScoringHealth } from "@/types/optimizations";
import useOptimizationStudioLogs from "@/api/optimizations/useOptimizationStudioLogs";
import { convertTerminalOutputToHtml } from "@/lib/terminalOutput";
import OptimizationLogsFullscreenDialog from "@/v2/pages-shared/optimizations/OptimizationLogs/OptimizationLogsFullscreenDialog";
import {
  EMPTY_RUN_CAUSE,
  EmptyRunCause,
  getEmptyRunMessage,
  getEmptyRunTitle,
} from "./optimizationOverviewHelpers";

type EmptyRunWarningPanelProps = {
  optimization: Optimization;
  /** Drives both the copy and the severity, see {@link EMPTY_RUN_CAUSE}. */
  cause: EmptyRunCause;
  /**
   * Exact failed/total counts from the backend (OPIK-7159 Wave 2), falling back
   * to the Wave-1 copy when absent. Only consulted for SCORING_FAILED.
   */
  scoringHealth?: OptimizationScoringHealth;
};

type EmptyRunAppearance = {
  Icon: LucideIcon;
  container: string;
  iconColor: string;
  title: string;
  body: string;
};

/**
 * SCORING_FAILED uses the amber warning-box tokens rather than the destructive
 * scale: the run did complete, the scores are just unusable.
 */
const APPEARANCE: Record<
  Exclude<EmptyRunCause, typeof EMPTY_RUN_CAUSE.NONE>,
  EmptyRunAppearance
> = {
  [EMPTY_RUN_CAUSE.NO_CANDIDATES]: {
    Icon: Info,
    container: "border-border bg-soft-background",
    iconColor: "text-muted-slate",
    title: "text-foreground",
    body: "text-muted-slate",
  },
  [EMPTY_RUN_CAUSE.SCORING_FAILED]: {
    Icon: TriangleAlert,
    container: "border-warning-box-icon-bg/40 bg-warning-box-bg",
    iconColor: "text-warning-box-icon-text",
    title: "text-warning-box-text",
    body: "text-warning-box-text",
  },
};

/**
 * Shown when a run ends in COMPLETED with nothing usable on screen, per
 * computeEmptyRunCause, which would otherwise look empty with no explanation
 * (the OPIK-7029 "silent COMPLETED" gap). Severity follows the cause: a warning
 * with a re-run call to action for SCORING_FAILED, a neutral note otherwise.
 */
const EmptyRunWarningPanel: React.FC<EmptyRunWarningPanelProps> = ({
  optimization,
  cause,
  scoringHealth,
}) => {
  // Null when the backend reports no failed item: it wins over the client
  // classifier, so the panel stays silent rather than contradicting it.
  const message = getEmptyRunMessage(cause, scoringHealth);
  const [open, setOpen] = useState(false);
  const { data, dataUpdatedAt } = useOptimizationStudioLogs(
    { optimizationId: optimization.id },
    { enabled: Boolean(optimization.id), retry: false },
  );

  const logContent = data?.content ?? "";
  const logHtml = useMemo(
    () => convertTerminalOutputToHtml(logContent),
    [logContent],
  );

  if (message === null || cause === EMPTY_RUN_CAUSE.NONE) {
    return null;
  }

  const { Icon, container, iconColor, title, body } = APPEARANCE[cause];

  return (
    <>
      <div className={`rounded-lg border p-4 ${container}`}>
        <div className="mb-1 flex items-center gap-2">
          <Icon className={`size-4 shrink-0 ${iconColor}`} />
          <h3 className={`comet-body-s-accented ${title}`}>
            {getEmptyRunTitle(cause)}
          </h3>
        </div>
        <p className={`comet-body-xs whitespace-pre-wrap break-words ${body}`}>
          {message}
        </p>
        {logContent && (
          <Button
            variant="outline"
            size="sm"
            className="mt-3"
            onClick={() => setOpen(true)}
          >
            View logs
          </Button>
        )}
      </div>
      <OptimizationLogsFullscreenDialog
        open={open}
        onOpenChange={setOpen}
        onClose={() => {}}
        logContent={logContent}
        logHtml={logHtml}
        isInProgress={false}
        lastUpdatedAt={
          dataUpdatedAt ? new Date(dataUpdatedAt).toISOString() : null
        }
        hasNewLogs={false}
        initialScrollRatio={1}
      />
    </>
  );
};

export default EmptyRunWarningPanel;
