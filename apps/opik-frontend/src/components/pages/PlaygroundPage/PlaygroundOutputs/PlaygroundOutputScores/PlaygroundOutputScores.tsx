import React from "react";
import { Loader2 } from "lucide-react";

import FeedbackScoreTag from "@/components/shared/FeedbackScoreTag/FeedbackScoreTag";
import {
  HoverCard,
  HoverCardContent,
  HoverCardTrigger,
} from "@/components/ui/hover-card";
import { cn } from "@/lib/utils";
import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import { generateTagVariant } from "@/lib/traces";

const MAX_VISIBLE_METRICS = 3;

// Score data from trace feedback
export interface ScoreData {
  value: number;
  reason?: string;
  lastUpdatedAt?: string;
  lastUpdatedBy?: string;
  valueByAuthor?: Record<string, number>;
  category?: string;
}

interface PlaygroundOutputScoresProps {
  metricNames: string[]; // Expected metric names from rules
  metricScores: Record<string, ScoreData>; // Actual scores from trace (keyed by name)
  stale?: boolean;
  className?: string;
}

const PlaygroundOutputScores: React.FC<PlaygroundOutputScoresProps> = ({
  metricNames,
  metricScores,
  stale = false,
  className,
}) => {
  if (metricNames.length === 0) {
    return null;
  }

  const visibleMetrics = metricNames.slice(0, MAX_VISIBLE_METRICS);
  const hiddenMetrics = metricNames.slice(MAX_VISIBLE_METRICS);
  const remainingCount = hiddenMetrics.length;

  const renderMetric = (metricName: string) => {
    const variant = generateTagVariant(metricName);
    const color = (variant && TAG_VARIANTS_COLOR_MAP[variant]) || "#64748b";
    const score = metricScores[metricName];

    // Score has loaded - show the actual value
    if (score) {
      return (
        <FeedbackScoreTag
          key={metricName}
          label={metricName}
          value={score.value}
          reason={score.reason}
          lastUpdatedAt={score.lastUpdatedAt}
          lastUpdatedBy={score.lastUpdatedBy}
          valueByAuthor={score.valueByAuthor}
          category={score.category}
        />
      );
    }

    // Score still loading - show placeholder with spinner
    return (
      <div
        key={metricName}
        className="flex h-6 items-center gap-1.5 rounded-md border border-border px-2"
      >
        <div
          className="rounded-[0.15rem] bg-[var(--bg-color)] p-1"
          style={{ "--bg-color": color } as React.CSSProperties}
        />
        <span className="comet-body-s-accented truncate text-muted-slate">
          {metricName}
        </span>
        <Loader2 className="size-3 animate-spin text-muted-slate" />
      </div>
    );
  };

  return (
    <div
      className={cn("flex flex-wrap gap-1.5", stale && "opacity-50", className)}
    >
      {visibleMetrics.map(renderMetric)}
      {remainingCount > 0 && (
        <HoverCard openDelay={200}>
          <HoverCardTrigger asChild>
            <div
              className="comet-body-s-accented flex h-6 cursor-pointer items-center rounded-md border border-border px-1.5 text-muted-slate"
              tabIndex={0}
            >
              +{remainingCount}
            </div>
          </HoverCardTrigger>
          <HoverCardContent
            side="top"
            align="start"
            className="w-auto max-w-[300px]"
          >
            <div className="flex flex-wrap gap-1.5">
              {hiddenMetrics.map(renderMetric)}
            </div>
          </HoverCardContent>
        </HoverCard>
      )}
    </div>
  );
};

export default PlaygroundOutputScores;
