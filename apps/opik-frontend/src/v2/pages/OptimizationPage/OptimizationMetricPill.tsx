import React from "react";
import { Gauge, Maximize2 } from "lucide-react";

import { HoverCard, HoverCardContent, HoverCardTrigger } from "@/ui/hover-card";
import { Tag } from "@/ui/tag";
import { OptimizationMetricItem } from "./optimizationHeaderConfig";
import MetricPopoverContent from "./MetricPopoverContent";

type OptimizationMetricPillProps = {
  metric: OptimizationMetricItem;
};

/**
 * Metric pill in the run header. Hovering opens a popover with the metric's
 * config; the popover stays open while the cursor is inside it (Radix HoverCard).
 */
const OptimizationMetricPill: React.FC<OptimizationMetricPillProps> = ({
  metric,
}) => (
  <HoverCard openDelay={150} closeDelay={150}>
    <HoverCardTrigger asChild>
      <button type="button" aria-label={`Metric: ${metric.label}`}>
        <Tag
          variant="default"
          size="md"
          className="cursor-pointer hover:bg-muted"
        >
          <span className="flex items-center gap-1.5">
            <Gauge className="size-3.5 shrink-0 text-muted-slate" />
            <span className="truncate">{metric.label}</span>
            <Maximize2 className="size-3 shrink-0 text-muted-slate" />
          </span>
        </Tag>
      </button>
    </HoverCardTrigger>
    <HoverCardContent align="start" className="w-72">
      <MetricPopoverContent metric={metric} />
    </HoverCardContent>
  </HoverCard>
);

export default OptimizationMetricPill;
