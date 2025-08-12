import React, { useCallback, useMemo, useState } from "react";
import isEmpty from "lodash/isEmpty";
import isString from "lodash/isString";
import uniq from "lodash/uniq";
import last from "lodash/last";

import NoData from "@/components/shared/NoData/NoData";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { cn, getTextWidth } from "@/lib/utils";
import { Spinner } from "@/components/ui/spinner";
import FeedbackScoresChartContent, {
  ChartData,
} from "./FeedbackScoresChartContent";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { DropdownOption } from "@/types/shared";

type FeedbackScoresChartContainerProps = {
  className: string;
  chartData?: ChartData;
  chartId: string;
  chartName?: string | DropdownOption<string>[];
  subtitle?: string;
};

const FeedbackScoresChartContainer: React.FC<
  FeedbackScoresChartContainerProps
> = ({ className, chartData, chartId, chartName, subtitle }) => {
  const isPending = !chartData;
  const noData = useMemo(() => {
    if (isPending) return false;

    return chartData.data.every((record) => isEmpty(record.scores));
  }, [chartData?.data, isPending]);

  const [width, setWidth] = useState<number>(0);
  const { ref } = useObserveResizeNode<HTMLDivElement>((node) =>
    setWidth(node.clientWidth),
  );

  const { nameElement, tooltip } = useMemo(() => {
    if (isString(chartName)) {
      return {
        nameElement: <span className="truncate">{chartName}</span>,
        tooltip: chartName,
      };
    }

    if (!chartName?.length) {
      return {
        nameElement: <span></span>,
        tooltip: "",
      };
    }

    const labels = chartName.map((o) => o.value);
    const fullName = labels.join(" / ");
    const tooltip = chartName.map((o) => `${o.label}: ${o.value}`).join(" / ");

    // For single item or when width not available
    if (width === 0 || chartName.length <= 1) {
      return {
        nameElement: <span className="truncate">{fullName}</span>,
        tooltip,
      };
    }
    const ELLIPSIS = "...";
    const SEPARATOR = "/";
    const CONTAINER_WIDTH = width - 32; // Account for padding
    const ELLIPSIS_WIDTH = 13;
    const SEPARATOR_WITH_GAPS_WIDTH = 6 + 4 + 4;

    const uniqNames = uniq(labels);
    const widthMap = getTextWidth(uniqNames, { font: "500 14px Inter" }).reduce<
      Record<string, number>
    >(
      (acc, w, i) => {
        acc[uniqNames[i]] = w;
        return acc;
      },
      {
        [ELLIPSIS]: ELLIPSIS_WIDTH,
      },
    );

    const getWidth = (names: string[]) =>
      names.reduce(
        (sum, name, index, all) =>
          sum +
          (widthMap[name] || 0) +
          (index !== all.length - 1 ? SEPARATOR_WITH_GAPS_WIDTH : 0),
        0,
      );

    let names = labels.slice();
    const onlyLast = [ELLIPSIS, last(labels) as string];

    if (CONTAINER_WIDTH < getWidth(["", ...onlyLast])) {
      // check the case if the last item is too long to fit into the container
      names = onlyLast;
    } else {
      while (CONTAINER_WIDTH < getWidth(names)) {
        if (names.length === 2) break;

        if (names[1] === ELLIPSIS) {
          if (names.length === 3) {
            break;
          } else {
            names.splice(2, 1);
          }
        } else {
          names.splice(1, 1, ELLIPSIS);
        }
      }
    }

    return {
      nameElement: (
        <div className="inline-flex max-w-full items-center gap-1">
          {names.map((name, index, all) => {
            const isLast = index === all.length - 1;
            const moreThanTwo = all.length > 2;
            return (
              <>
                <span
                  className={cn("truncate", {
                    "shrink-0": name === ELLIPSIS || (isLast && moreThanTwo),
                  })}
                >
                  {name}
                </span>
                {!isLast && <span className="shrink-0">{SEPARATOR}</span>}
              </>
            );
          })}
        </div>
      ),
      tooltip,
    };
  }, [chartName, width]);

  const renderContent = useCallback(() => {
    if (isPending) {
      return (
        <div className="flex size-full min-h-32 items-center justify-center">
          <Spinner />
        </div>
      );
    }

    if (noData) {
      return (
        <NoData
          className="min-h-32 text-light-slate"
          message="No scores to show"
        />
      );
    }

    return (
      <FeedbackScoresChartContent
        chartId={chartId}
        chartData={chartData}
        containerWidth={width}
      />
    );
  }, [isPending, noData, chartData, chartId, width]);

  return (
    <Card className={cn("min-w-[400px]", className)} ref={ref}>
      <CardHeader className="space-y-0.5 px-4 pt-3">
        <CardTitle className="comet-body-s-accented">
          <TooltipWrapper content={tooltip}>{nameElement}</TooltipWrapper>
        </CardTitle>
        {subtitle && (
          <CardDescription className="comet-body-xs text-xs">
            {subtitle}
          </CardDescription>
        )}
      </CardHeader>
      <CardContent className="px-4 pb-3">{renderContent()}</CardContent>
    </Card>
  );
};

export default FeedbackScoresChartContainer;
