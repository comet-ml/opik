import React from "react";

import { AggregatedCandidate } from "@/types/optimizations";
import {
  CandidateDataPoint,
  buildTrialCardModel,
} from "./optimizationChartUtils";

/**
 * Shared shell sizing for the trial card. The visual chrome (border, bg,
 * radius, shadow) comes from the wrapper that hosts this content —
 * `Popover`/`PopoverContent` for the hover tooltip (ChartTooltip), a styled
 * portal div for the pinned best-trial card (useBestTrialCard) — so this only
 * pins the Figma width and inner padding.
 */
export const TRIAL_CARD_SHELL_CLASS = "w-[220px] p-1.5 shadow-md";

/** Card width in px — keep in sync with the `w-[220px]` in the shell class. */
export const TRIAL_CARD_WIDTH = 220;

type TrialCardProps = {
  candidate: AggregatedCandidate;
  status: CandidateDataPoint["status"];
  stepIndex: number;
  isTestSuite?: boolean;
  isBest?: boolean;
};

/**
 * Content of a single trial card — the header (trial name + status dot + label,
 * e.g. "Passed step 1", "Discarded in step 2", "Best trial") and the metric
 * rows. Rendered inside a core Popover (hover tooltip) or Card (pinned best).
 */
const TrialCard: React.FC<TrialCardProps> = ({
  candidate,
  status,
  stepIndex,
  isTestSuite,
  isBest,
}) => {
  const { title, statusLabel, dotColor, dotRingColor, rows } =
    buildTrialCardModel({ candidate, status, stepIndex, isTestSuite, isBest });

  return (
    <>
      <div className="flex min-h-[22px] items-center gap-1.5 px-1">
        <span className="comet-body-xs-accented flex-1 truncate text-foreground">
          {title}
        </span>
        <span className="flex shrink-0 items-center gap-1.5">
          <span
            className="size-1.5 shrink-0 rounded-full"
            style={{
              backgroundColor: dotColor,
              boxShadow: dotRingColor ? `0 0 0 2px ${dotRingColor}` : undefined,
            }}
          />
          <span className="comet-body-xs-accented whitespace-nowrap text-foreground">
            {statusLabel}
          </span>
        </span>
      </div>
      <div className="py-1">
        <div className="h-px w-full bg-border" />
      </div>
      {rows.map((row) => (
        <div key={row.label} className="flex h-6 items-center gap-1.5 px-1">
          <span className="comet-body-xs flex-1 truncate text-muted-slate">
            {row.label}
          </span>
          <span className="comet-body-xs-accented shrink-0 text-right text-foreground">
            {row.value}
          </span>
        </div>
      ))}
    </>
  );
};

export default TrialCard;
