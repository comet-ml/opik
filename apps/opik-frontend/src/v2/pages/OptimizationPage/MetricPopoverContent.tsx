import React from "react";

import {
  OptimizationMetricItem,
  METRIC_PARAMETER_LABELS,
  formatMetricParameterValue,
} from "./optimizationHeaderConfig";

type MetricPopoverContentProps = {
  metric: OptimizationMetricItem;
};

/** Read-only summary of a metric's configuration, shown inside the pill popover. */
const MetricPopoverContent: React.FC<MetricPopoverContentProps> = ({
  metric,
}) => {
  const entries = Object.entries(metric.parameters ?? {}).filter(
    ([, value]) => value !== undefined,
  );

  return (
    <div className="flex flex-col gap-3">
      <h4 className="comet-body-s-accented text-foreground">{metric.label}</h4>
      {entries.length === 0 ? (
        <p className="comet-body-xs text-muted-slate">
          No additional configuration
        </p>
      ) : (
        <dl className="flex flex-col gap-2">
          {entries.map(([key, value]) => (
            <div key={key} className="flex flex-col gap-0.5">
              <dt className="comet-body-xs text-muted-slate">
                {METRIC_PARAMETER_LABELS[key] ?? key}
              </dt>
              <dd className="comet-body-xs whitespace-pre-wrap break-words text-foreground">
                {formatMetricParameterValue(value)}
              </dd>
            </div>
          ))}
        </dl>
      )}
    </div>
  );
};

export default MetricPopoverContent;
