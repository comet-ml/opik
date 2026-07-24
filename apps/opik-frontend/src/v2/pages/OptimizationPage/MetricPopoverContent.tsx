import React from "react";

import { Separator } from "@/ui/separator";
import {
  OptimizationMetricItem,
  METRIC_PARAMETER_LABELS,
  formatMetricParameterValue,
} from "./optimizationHeaderConfig";

type MetricPopoverContentProps = {
  metric: OptimizationMetricItem;
};

/**
 * Read-only summary of a metric's configuration, shown inside the pill popover.
 * Layout mirrors the Figma spec (686-53189): no title (the pill already names
 * the metric), each parameter is an accented label over a muted value, and a
 * divider separates the parameter blocks.
 */
const MetricPopoverContent: React.FC<MetricPopoverContentProps> = ({
  metric,
}) => {
  const entries = Object.entries(metric.parameters ?? {}).filter(
    ([, value]) => value !== undefined,
  );

  if (entries.length === 0) {
    return (
      <p className="comet-body-xs p-1 text-muted-slate">
        No additional configuration
      </p>
    );
  }

  return (
    <dl className="flex flex-col">
      {entries.map(([key, value], index) => (
        <React.Fragment key={key}>
          {index > 0 && <Separator className="my-1 bg-border" />}
          <dt className="comet-body-xs-accented px-1 pb-0.5 pt-1 leading-4 text-foreground">
            {METRIC_PARAMETER_LABELS[key] ?? key}
          </dt>
          <dd className="comet-body-xs whitespace-pre-wrap break-words p-1 leading-4 text-muted-slate">
            {formatMetricParameterValue(value)}
          </dd>
        </React.Fragment>
      ))}
    </dl>
  );
};

export default MetricPopoverContent;
