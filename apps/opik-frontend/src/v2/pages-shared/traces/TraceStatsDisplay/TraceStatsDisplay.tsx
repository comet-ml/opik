import React from "react";
import { Clock, Coins, Hash } from "lucide-react";
import isNumber from "lodash/isNumber";
import isUndefined from "lodash/isUndefined";

import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { formatDate, formatDuration } from "@/lib/date";
import { formatCost } from "@/lib/money";

type TraceStatsDisplayProps = {
  duration?: number | null;
  startTime?: string;
  endTime?: string;
  totalTokens?: number;
  estimatedCost?: number;
};

const statClassName =
  "comet-body-xs-accented flex items-center gap-1 text-muted-slate";

const TraceStatsDisplay: React.FC<TraceStatsDisplayProps> = ({
  duration,
  startTime,
  endTime,
  totalTokens,
  estimatedCost,
}) => {
  const formattedDuration = formatDuration(duration);

  const formattedStart = startTime
    ? formatDate(startTime, { includeSeconds: true })
    : "";
  const formattedEnd = endTime
    ? formatDate(endTime, { includeSeconds: true })
    : "";

  const durationTooltip = (
    <div>
      Duration in seconds: {formattedDuration}
      {formattedStart && (
        <p>
          {formattedStart}
          {formattedEnd ? ` - ${formattedEnd}` : ""}
        </p>
      )}
    </div>
  );

  return (
    <>
      {isNumber(duration) && (
        <TooltipWrapper content={durationTooltip}>
          <div className={statClassName}>
            <Clock className="size-3 shrink-0" /> {formattedDuration}
          </div>
        </TooltipWrapper>
      )}
      {isNumber(totalTokens) && (
        <TooltipWrapper content={`Total amount of tokens: ${totalTokens}`}>
          <div className={statClassName}>
            <Hash className="size-3 shrink-0" /> {totalTokens}
          </div>
        </TooltipWrapper>
      )}
      {!isUndefined(estimatedCost) && (
        <TooltipWrapper
          content={`Estimated cost ${formatCost(estimatedCost, {
            modifier: "full",
          })}`}
        >
          <div className={statClassName}>
            <Coins className="size-3 shrink-0" /> {formatCost(estimatedCost)}
          </div>
        </TooltipWrapper>
      )}
    </>
  );
};

export default TraceStatsDisplay;
