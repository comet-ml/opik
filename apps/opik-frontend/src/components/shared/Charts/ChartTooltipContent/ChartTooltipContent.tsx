import React from "react";
import * as RechartsPrimitive from "recharts";
import isUndefined from "lodash/isUndefined";
import { getPayloadConfigFromPayload, useChart } from "@/components/ui/chart";
import {
  Tooltip,
  TooltipContent,
  TooltipPortal,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import {
  NameType,
  Payload,
  ValueType,
} from "recharts/types/component/DefaultTooltipContent";
import isFunction from "lodash/isFunction";

export type ChartTooltipRenderHeaderArguments = {
  payload: Payload<ValueType, NameType>[];
};

export type ChartTooltipRenderValueArguments = {
  value: ValueType;
};

type ChartTooltipContentProps = {
  renderHeader?: ({
    payload,
  }: ChartTooltipRenderHeaderArguments) => React.ReactNode;

  renderValue?: ({
    value,
  }: ChartTooltipRenderValueArguments) => React.ReactNode;
} & React.ComponentProps<typeof RechartsPrimitive.Tooltip> &
  React.ComponentProps<"div">;

const ChartTooltipContent = React.forwardRef<
  HTMLDivElement,
  ChartTooltipContentProps
>(
  (
    { active, payload, color, renderHeader, renderValue: parentRenderValue },
    ref,
  ) => {
    const { config } = useChart();

    if (!active || !payload?.length) {
      return null;
    }

    const renderValue = (value: ValueType) => {
      if (isFunction(parentRenderValue)) {
        return parentRenderValue({ value });
      }

      return value.toLocaleString();
    };

    return (
      <Tooltip open>
        <TooltipTrigger asChild>
          <div className="size-0.5 bg-transparent"></div>
        </TooltipTrigger>
        <TooltipPortal>
          <TooltipContent className="min-w-32 max-w-72 px-1 py-1.5 will-change-transform">
            <div ref={ref} className="grid items-start gap-1.5">
              {isFunction(renderHeader) && (
                <div className="mb-1 max-w-full overflow-hidden border-b px-2 pt-0.5">
                  {renderHeader({ payload })}
                </div>
              )}

              <div className="grid gap-1.5">
                {payload.map((item, idx) => {
                  const configKey = `${item.name || item.dataKey || "value"}`;
                  const key = `${configKey}${idx}`;
                  const itemConfig = getPayloadConfigFromPayload(
                    config,
                    item,
                    configKey,
                  );
                  const indicatorColor =
                    color || item.payload.fill || item.color;

                  return (
                    <div
                      key={key}
                      className="flex h-6 w-full flex-wrap items-center gap-1.5 px-2"
                    >
                      <div
                        className="size-2 shrink-0 rounded-full border-[--color-border] bg-[--color-bg]"
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
                        {!isUndefined(item.value) && (
                          <span className="comet-body-xs">
                            {renderValue(item.value)}
                          </span>
                        )}
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          </TooltipContent>
        </TooltipPortal>
      </Tooltip>
    );
  },
);
ChartTooltipContent.displayName = "ChartTooltipContent";

export default ChartTooltipContent;
