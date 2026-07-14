import React from "react";
import { Maximize2, Scale } from "lucide-react";

import { HoverCard, HoverCardContent, HoverCardTrigger } from "@/ui/hover-card";
import OptimizationConfigPill, {
  CONFIG_PILL_ICON_CLASS,
} from "@/v2/pages-shared/optimizations/OptimizationConfigPill";
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
        <OptimizationConfigPill
          className="cursor-pointer hover:bg-muted"
          icon={<Scale className={CONFIG_PILL_ICON_CLASS} />}
          suffix={<Maximize2 className="size-3 shrink-0 text-muted-slate" />}
        >
          {metric.label}
        </OptimizationConfigPill>
      </button>
    </HoverCardTrigger>
    <HoverCardContent align="start" className="w-72 p-1.5">
      <MetricPopoverContent metric={metric} />
    </HoverCardContent>
  </HoverCard>
);

export default OptimizationMetricPill;
