import React from "react";
import * as RechartsPrimitive from "recharts";
import { getPayloadConfigFromPayload, useChart } from "@/components/ui/chart";

const ExperimentChartTooltipContent = React.forwardRef<
  HTMLDivElement,
  React.ComponentProps<typeof RechartsPrimitive.Tooltip> &
    React.ComponentProps<"div">
>(({ active, payload, color }, ref) => {
  const { config } = useChart();

  if (!active || !payload?.length) {
    return null;
  }

  const { experimentName, createdDate } = payload[0].payload;

  return (
    <div
      ref={ref}
      className="grid min-w-32 max-w-72 items-start gap-1.5 rounded-lg border border-border/50 bg-background px-1 py-1.5 shadow-xl"
    >
      <div className="mb-1 max-w-full overflow-hidden border-b px-2 pt-0.5">
        <div className="comet-body-xs-accented mb-0.5 truncate">
          {experimentName}
        </div>
        <div className="comet-body-xs mb-1 text-light-slate">{createdDate}</div>
      </div>
      <div className="grid gap-1.5">
        {payload.map((item) => {
          const key = `${item.name || item.dataKey || "value"}`;
          const itemConfig = getPayloadConfigFromPayload(config, item, key);
          const indicatorColor = color || item.payload.fill || item.color;

          return (
            <div
              key={key}
              className="flex h-6 w-full flex-wrap items-center gap-1.5 px-2"
            >
              <div
                className="size-2 shrink-0 rounded-[2px] border-[--color-border] bg-[--color-bg]"
                style={
                  {
                    "--color-bg": indicatorColor,
                    "--color-border": indicatorColor,
                  } as React.CSSProperties
                }
              />
              <div className="flex flex-1 items-center justify-between gap-2 leading-none">
                <div className="grid gap-1.5">
                  <span className="comet-body-xs truncate text-muted-slate">
                    {itemConfig?.label || item.name}
                  </span>
                </div>
                {item.value && (
                  <span className="comet-body-xs-accented">
                    {item.value.toLocaleString()}
                  </span>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
});
ExperimentChartTooltipContent.displayName = "ExperimentChartTooltipContent";

export default ExperimentChartTooltipContent;
