import React from "react";
import { cn } from "@/lib/utils";

// Placeholder bar heights, as a share of the plot height.
const BARS = [0.45, 0.7, 0.55, 0.85, 0.6, 0.75];

/**
 * Loading placeholder from the chart widget tokens: grid and bars in the skeleton colours, the bars breathing and
 * one sheen sweeping across (chart-skeleton.css; still under reduced motion).
 */
const ChartSkeleton: React.FC<{ className?: string }> = ({ className }) => (
  <div className={cn("relative overflow-hidden", className)} aria-hidden>
    <svg className="size-full" viewBox="0 0 120 60" preserveAspectRatio="none">
      {[12, 27, 42].map((y) => (
        <line
          key={y}
          x1="0"
          x2="120"
          y1={y}
          y2={y}
          stroke="var(--chart-skeleton-fill)"
          strokeWidth="1"
          vectorEffect="non-scaling-stroke"
        />
      ))}
      {BARS.map((share, index) => (
        <rect
          key={index}
          className="chart-skeleton-breathe-y"
          x={6 + index * 19}
          y={57 - share * 50}
          width="11"
          height={share * 50}
          rx="1"
          fill="var(--chart-skeleton-mark)"
        />
      ))}
    </svg>
    <div className="chart-skeleton-sheen" />
  </div>
);

export default ChartSkeleton;
