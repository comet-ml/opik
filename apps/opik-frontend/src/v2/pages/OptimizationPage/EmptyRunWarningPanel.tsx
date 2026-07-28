import React, { useMemo, useState } from "react";
import { TriangleAlert } from "lucide-react";

import { Button } from "@/ui/button";
import { Optimization, OptimizationScoringHealth } from "@/types/optimizations";
import useOptimizationStudioLogs from "@/api/optimizations/useOptimizationStudioLogs";
import { convertTerminalOutputToHtml } from "@/lib/terminalOutput";
import OptimizationLogsFullscreenDialog from "@/v2/pages-shared/optimizations/OptimizationLogs/OptimizationLogsFullscreenDialog";
import { getEmptyRunWarningMessage } from "./optimizationOverviewHelpers";

type EmptyRunWarningPanelProps = {
  optimization: Optimization;
  /**
   * Exact scoring-health counts from the backend (OPIK-7159 Wave 2). When
   * present and `total_count > 0`, the panel body shows the exact failed/total
   * numbers. When absent, falls back to the Wave-1 heuristic copy.
   */
  scoringHealth?: OptimizationScoringHealth;
};

/**
 * Shown when a run ends in COMPLETED but produced no usable scores (the
 * heuristic in {@link ./optimizationOverviewHelpers}.computeEmptyRunWarning).
 * This is the FE half of the OPIK-7029 "silent COMPLETED" gap: without it the
 * run looks like a plain empty run with no explanation.
 *
 * It mirrors the ERROR-gated {@link ./RunErrorPanel} markup but reads as a
 * WARNING (amber) rather than a failure (destructive), because the run did
 * technically complete — the scores are simply unusable.
 *
 * When `scoringHealth` is provided (OPIK-7159 Wave 2), the body shows the
 * exact failed/total item count. Otherwise, the Wave-1 heuristic copy is used.
 */
const EmptyRunWarningPanel: React.FC<EmptyRunWarningPanelProps> = ({
  optimization,
  scoringHealth,
}) => {
  // Body message: exact count when scoring_health is present, else the heuristic
  // copy. Returns null ONLY when the backend explicitly says nothing failed
  // (failed_count === 0) — in that case we must NOT contradict it with a warning,
  // so the panel renders nothing (the client heuristic and the item-level count
  // can legitimately diverge).
  const warningMessage = getEmptyRunWarningMessage(scoringHealth);
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

  if (warningMessage === null) {
    return null;
  }

  return (
    <>
      {/* Amber (warning-box tokens), not the red `--warning`/destructive scale:
          the run completed, the scores are just unusable — see the FE-1 card. */}
      <div className="rounded-lg border border-warning-box-icon-bg/40 bg-warning-box-bg p-4">
        <div className="mb-1 flex items-center gap-2">
          <TriangleAlert className="size-4 shrink-0 text-warning-box-icon-text" />
          <h3 className="comet-body-s-accented text-warning-box-text">
            No usable scores
          </h3>
        </div>
        <p className="comet-body-xs whitespace-pre-wrap break-words text-warning-box-text">
          {warningMessage}
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
