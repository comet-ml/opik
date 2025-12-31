import React, { useMemo } from "react";

import {
  HoverCard,
  HoverCardContent,
  HoverCardTrigger,
} from "@/components/ui/hover-card";
import { cn } from "@/lib/utils";
import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import { generateTagVariant } from "@/lib/traces";
import { FeedbackScoreValueByAuthorMap } from "@/types/traces";
import MetricTag from "./MetricTag";

const MAX_VISIBLE_METRICS = 3;

// Score data from trace feedback
export interface ScoreData {
  value: number;
  reason?: string;
  lastUpdatedAt?: string;
  lastUpdatedBy?: string;
  valueByAuthor?: FeedbackScoreValueByAuthorMap;
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
  const { metricColors, visibleMetrics, hiddenMetrics, remainingCount } =
    useMemo(() => {
      const colors = Object.fromEntries(
        metricNames.map((name) => {
          const variant = generateTagVariant(name);
          return [name, TAG_VARIANTS_COLOR_MAP[variant ?? "gray"]];
        }),
      );

      const visible = metricNames.slice(0, MAX_VISIBLE_METRICS);
      const hidden = metricNames.slice(MAX_VISIBLE_METRICS);

      return {
        metricColors: colors,
        visibleMetrics: visible,
        hiddenMetrics: hidden,
        remainingCount: hidden.length,
      };
    }, [metricNames]);

  if (metricNames.length === 0) {
    return null;
  }

  return (
    <div
      className={cn("flex flex-wrap gap-1.5", stale && "opacity-50", className)}
    >
      {visibleMetrics.map((metricName) => (
        <MetricTag
          key={metricName}
          metricName={metricName}
          color={metricColors[metricName]}
          score={metricScores[metricName]}
        />
      ))}
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
              {hiddenMetrics.map((metricName) => (
                <MetricTag
                  key={metricName}
                  metricName={metricName}
                  color={metricColors[metricName]}
                  score={metricScores[metricName]}
                />
              ))}
            </div>
          </HoverCardContent>
        </HoverCard>
      )}
    </div>
  );
};

export default PlaygroundOutputScores;
