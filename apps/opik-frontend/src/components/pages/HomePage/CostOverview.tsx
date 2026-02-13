import React, { useMemo } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import isNumber from "lodash/isNumber";

import noDataCostsImageUrl from "/images/no-data-workspace-costs.png";
import { Card } from "@/components/ui/card";
import useWorkspaceCostSummary from "@/api/workspaces/useWorkspaceCostSummary";
import useWorkspaceCost from "@/api/workspaces/useWorkspaceCost";
import ViewDetailsButton from "@/components/pages/HomePage/ViewDetailsButton";
import Loader from "@/components/shared/Loader/Loader";
import HomePageChart from "@/components/pages/HomePage/HomePageChart";
import { Project } from "@/types/projects";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import ExplainerCallout from "@/components/shared/ExplainerCallout/ExplainerCallout";
import PercentageTrend from "@/components/shared/PercentageTrend/PercentageTrend";
import { formatCost } from "@/lib/money";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { calculatePercentageChange } from "@/lib/utils";
import {
  getChartData,
  RE_FETCH_INTERVAL,
} from "@/components/pages/HomePage/helpers";
import { ChartTooltipRenderValueArguments } from "@/components/shared/Charts/ChartTooltipContent/ChartTooltipContent";

type CostOverviewProps = {
  projects: Project[];
  projectsPending: boolean;
  intervalStart: string | undefined;
  intervalEnd: string | undefined;
};

export const CostOverview: React.FC<CostOverviewProps> = ({
  projects,
  projectsPending,
  intervalStart,
  intervalEnd,
}) => {
  const projectIds = useMemo(() => {
    return projects.map((p) => p.id);
  }, [projects]);

  const { data, isPending } = useWorkspaceCostSummary(
    {
      projectIds: projectIds,
      intervalStart,
      intervalEnd,
    },
    {
      placeholderData: keepPreviousData,
      refetchInterval: RE_FETCH_INTERVAL,
      enabled: !projectsPending,
    },
  );

  const { data: costsChartData, isPending: isPendingCostsChartData } =
    useWorkspaceCost(
      {
        projectIds: projectIds,
        intervalStart,
        intervalEnd,
      },
      {
        enabled: !projectsPending,
        refetchInterval: RE_FETCH_INTERVAL,
      },
    );

  const chartData = useMemo(
    () => getChartData(costsChartData, projects, 0),
    [costsChartData, projects],
  );

  const noChartData =
    !costsChartData ||
    costsChartData?.length === 0 ||
    chartData.values.length === 0;

  const renderChartContainer = () => {
    return (
      <>
        <div className="flex items-center justify-between gap-2 pb-4">
          <div>
            <div className="flex items-center gap-2">
              {!isPending && (
                <TooltipWrapper
                  content={formatCost(data?.current, { modifier: "full" })}
                >
                  <div className="comet-title-l text-foreground-secondary">
                    {isNumber(data?.current)
                      ? formatCost(data?.current, {
                          modifier: "kFormat",
                          noValue: "$0",
                        })
                      : "$ -"}
                  </div>
                </TooltipWrapper>
              )}
              <PercentageTrend
                percentage={calculatePercentageChange(
                  data?.previous,
                  data?.current,
                )}
                trend="inverted"
                tooltip="Compares the total cost between the current and previous periods."
              />
            </div>
            <div className="comet-body-s text-muted-foreground">
              Total estimated spend
            </div>
          </div>
          {!noChartData && <ViewDetailsButton projectsIds={projectIds} />}
        </div>
        <div className="min-h-1 flex-auto">{renderChart()}</div>
        <ExplainerCallout
          {...EXPLAINERS_MAP[EXPLAINER_ID.hows_the_cost_estimated]}
          id="cost-overview-explainer"
          description={`Check our documentation to learn more about the cost.`}
        ></ExplainerCallout>
      </>
    );
  };

  const renderChart = () => {
    if (isPendingCostsChartData) {
      return <Loader className="size-full" />;
    }

    return (
      <HomePageChart
        chartData={chartData}
        renderValue={({ value }: ChartTooltipRenderValueArguments) =>
          formatCost(value as number, { modifier: "kFormat", noValue: "$0" })
        }
        customYTickFormatter={(value) =>
          formatCost(value as number, { modifier: "kFormat", noValue: "$0" })
        }
      />
    );
  };

  const renderNoCostsData = () => {
    return (
      <div className="relative size-full overflow-hidden">
        <img
          className="absolute inset-0 size-full object-fill blur-sm"
          src={noDataCostsImageUrl}
          alt="no data image"
        ></img>
        <div className="absolute inset-0 flex flex-col items-center justify-center p-10">
          <h1 className="comet-title-m">No costs available</h1>
          <div className="comet-body mt-2 max-w-[60%] text-center text-muted-slate">
            No data found for your current filters. This may happen if there are
            no LLM calls in the selected time range. Try adjusting your filters
            to explore available data
          </div>
        </div>
      </div>
    );
  };

  return (
    <div className="pt-6">
      <h2 className="comet-title-s truncate break-words pb-3 pt-2 text-base">
        Cost overview
      </h2>
      <Card className="flex h-[426px]">
        {isPendingCostsChartData ? (
          <Loader className="h-[426px] w-full" />
        ) : noChartData ? (
          renderNoCostsData()
        ) : (
          <div className="flex min-w-1 flex-auto flex-col justify-stretch px-6 py-5">
            {renderChartContainer()}
          </div>
        )}
      </Card>
    </div>
  );
};

export default CostOverview;
