import React, { useEffect, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import isUndefined from "lodash/isUndefined";
import isNull from "lodash/isNull";
import { ChevronRight } from "lucide-react";

import noDataMetricsImageUrl from "/images/no-data-workspace-metrics.png";
import noDataMetricChartImageUrl from "/images/no-data-workspace-metric-chart.png";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import useWorkspaceMetrics from "@/api/workspaces/useWorkspaceMetrics";
import useWorkspaceMetricsSummary from "@/api/workspaces/useWorkspaceMetricsSummary";
import { calculatePercentageChange, cn, formatNumericData } from "@/lib/utils";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import ViewDetailsButton from "@/components/pages/HomePage/ViewDetailsButton";
import PercentageTrend, {
  PercentageTrendType,
} from "@/components/shared/PercentageTrend/PercentageTrend";
import Loader from "@/components/shared/Loader/Loader";
import ExplainerCallout from "@/components/shared/ExplainerCallout/ExplainerCallout";
import HomePageChart from "@/components/pages/HomePage/HomePageChart";
import { Project } from "@/types/projects";
import { useOpenQuickStartDialog } from "@/components/pages-shared/onboarding/QuickstartDialog/QuickstartDialog";
import {
  getChartData,
  RE_FETCH_INTERVAL,
} from "@/components/pages/HomePage/helpers";

const METRIC_NAME_TO_EXPLAINER_ID_MAP: Record<
  string,
  { explainerId: EXPLAINER_ID; trend: PercentageTrendType }
> = {
  equals_metric: { explainerId: EXPLAINER_ID.metric_equals, trend: "neutral" },
  contains_metric: {
    explainerId: EXPLAINER_ID.metric_contains,
    trend: "neutral",
  },
  regex_match_metric: {
    explainerId: EXPLAINER_ID.metric_regex_match,
    trend: "neutral",
  },
  is_json_metric: { explainerId: EXPLAINER_ID.metric_is_json, trend: "direct" },
  levenshtein_ratio_metric: {
    explainerId: EXPLAINER_ID.metric_levenshtein,
    trend: "direct",
  },
  sentence_bleu_metric: {
    explainerId: EXPLAINER_ID.metric_sentence_bleu,
    trend: "direct",
  },
  corpus_bleu_metric: {
    explainerId: EXPLAINER_ID.metric_corpus_bleu,
    trend: "direct",
  },
  rouge_metric: { explainerId: EXPLAINER_ID.metric_rouge, trend: "direct" },
  hallucination_metric: {
    explainerId: EXPLAINER_ID.metric_hallucination,
    trend: "inverted",
  },
  Hallucination: {
    explainerId: EXPLAINER_ID.metric_hallucination,
    trend: "inverted",
  },
  g_eval_metric: { explainerId: EXPLAINER_ID.metric_g_eval, trend: "neutral" },
  moderation_metric: {
    explainerId: EXPLAINER_ID.metric_moderation,
    trend: "inverted",
  },
  Moderation: {
    explainerId: EXPLAINER_ID.metric_moderation,
    trend: "inverted",
  },
  UsefulnessMetric: {
    explainerId: EXPLAINER_ID.metric_usefulness,
    trend: "direct",
  },
  answer_relevance_metric: {
    explainerId: EXPLAINER_ID.metric_answer_relevance,
    trend: "direct",
  },
  "Answer relevance": {
    explainerId: EXPLAINER_ID.metric_answer_relevance,
    trend: "direct",
  },
  context_precision_metric: {
    explainerId: EXPLAINER_ID.metric_context_precision,
    trend: "direct",
  },
  context_recall_metric: {
    explainerId: EXPLAINER_ID.metric_context_recall,
    trend: "direct",
  },
};

type MetricsOverviewProps = {
  projects: Project[];
  totalProjects: number;
  projectsPending: boolean;
  intervalStart: string | undefined;
  intervalEnd: string | undefined;
};

export const MetricsOverview: React.FC<MetricsOverviewProps> = ({
  projects,
  totalProjects,
  projectsPending,
  intervalStart,
  intervalEnd,
}) => {
  const { open: openQuickstart } = useOpenQuickStartDialog();
  const [selectedMetric, setSelectedMetric] = useState<string | undefined>();
  const projectIds = useMemo(() => {
    return projects.map((p) => p.id);
  }, [projects]);

  const { data, isPending } = useWorkspaceMetricsSummary(
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

  const { data: metricsChartData, isPending: isPendingMetricsChartData } =
    useWorkspaceMetrics(
      {
        projectIds: projectIds,
        name: selectedMetric!,
        intervalStart,
        intervalEnd,
      },
      {
        enabled: Boolean(selectedMetric),
        refetchInterval: RE_FETCH_INTERVAL,
      },
    );

  const chartData = useMemo(
    () => getChartData(metricsChartData, projects),
    [metricsChartData, projects],
  );

  const noMetricsData = !data || data?.length === 0;
  const noMetricsAndProjectsData = noMetricsData && totalProjects === 0;
  const noChartData =
    !metricsChartData ||
    metricsChartData?.length === 0 ||
    chartData.values.length === 0;

  const percentageMap = useMemo(() => {
    const retVal: Record<string, { value?: number; percentage?: number }> = {};

    if (!data?.length) return retVal;

    data.forEach((metric) => {
      retVal[metric.name] = {
        value: metric.current ?? undefined,
        percentage: calculatePercentageChange(metric.previous, metric.current),
      };
    });

    return retVal;
  }, [data]);

  useEffect(() => {
    setSelectedMetric((state) => {
      if (state && data?.some((metric) => metric.name === state)) {
        return state;
      }

      return data?.[0]?.name;
    });
  }, [data]);

  const renderMetricsList = () => {
    return (
      <ul className="flex h-full flex-col gap-2 overflow-y-auto py-5">
        {data?.map((metric) => {
          const { explainerId, trend = "neutral" } =
            METRIC_NAME_TO_EXPLAINER_ID_MAP[metric.name] ?? {};
          const isSelected = selectedMetric === metric.name;

          return (
            <li
              key={metric.name}
              className={cn(
                "border-l-[3px] border-l-transparent cursor-pointer pl-[21px] pr-6 py-1 min-h-16 hover:bg-primary-foreground",
                {
                  "border-l-primary bg-primary-foreground": isSelected,
                },
              )}
              onClick={() => setSelectedMetric(metric.name)}
            >
              <div className="flex items-center gap-1">
                <div
                  className={cn(
                    "comet-body-s-accented truncate text-muted-slate",
                    { "text-foreground": isSelected },
                  )}
                >
                  {metric.name}
                </div>
                {explainerId && (
                  <ExplainerIcon {...EXPLAINERS_MAP[explainerId]} />
                )}
              </div>
              <div className="mt-1 flex items-end gap-3">
                <div
                  className={cn("comet-title-l text-muted-slate", {
                    "text-foreground-secondary": isSelected,
                  })}
                >
                  {isUndefined(metric.current) || isNull(metric.current)
                    ? "-"
                    : formatNumericData(metric.current)}
                </div>
                <PercentageTrend
                  percentage={percentageMap[metric.name]?.percentage}
                  trend={trend}
                  tooltip={`Compares the average value of ${metric.name} between the current and previous periods.`}
                />
              </div>
            </li>
          );
        })}
      </ul>
    );
  };

  const renderChartContainer = () => {
    const { explainerId } =
      METRIC_NAME_TO_EXPLAINER_ID_MAP[selectedMetric ?? ""] ?? {};

    return (
      <>
        <div className="flex items-center justify-between gap-2 pb-4">
          <div className="comet-body-accented truncate">{selectedMetric}</div>
          {!noChartData && <ViewDetailsButton projectsIds={projectIds} />}
        </div>
        <div className="min-h-1 flex-auto">{renderChart()}</div>
        {explainerId && (
          <ExplainerCallout
            {...EXPLAINERS_MAP[explainerId]}
            id="metrics-overview-explainer"
            description={`Check our documentation to learn more about the ${selectedMetric}.`}
          ></ExplainerCallout>
        )}
      </>
    );
  };

  const renderChart = () => {
    if (isPendingMetricsChartData) {
      return <Loader className="size-full" />;
    }

    if (noChartData) {
      return (
        <div className="relative size-full overflow-hidden">
          <img
            className="absolute inset-0 size-full object-fill blur-sm"
            src={noDataMetricChartImageUrl}
            alt="no data image"
          ></img>
          <div className="absolute inset-0 flex flex-col items-center justify-center p-10">
            <h1 className="comet-title-m text-center">Unlock your metrics!</h1>
            <div className="comet-body mt-2 text-center text-muted-slate">
              Integrate your project with Opik to evaluate your AI.
              <br /> Metrics will appear here once data starts flowing.
            </div>
            <Button className="mt-4" onClick={openQuickstart}>
              Get started <ChevronRight className="ml-2 size-4 shrink-0" />
            </Button>
          </div>
        </div>
      );
    }

    return <HomePageChart chartData={chartData} />;
  };

  const renderNoMetricsData = () => {
    const description = noMetricsAndProjectsData
      ? "Log your first project metrics to see them here"
      : "No metrics found for your current filters. This may happen if there are no traces or metrics in the selected range. Try adjusting your filters to explore available data";

    return (
      <div className="relative size-full overflow-hidden">
        <img
          className="absolute inset-0 size-full object-fill blur-sm"
          src={noDataMetricsImageUrl}
          alt="no data image"
        ></img>
        <div className="absolute inset-0 flex flex-col items-center justify-center p-10">
          <h1 className="comet-title-m">No metrics available</h1>
          <div className="comet-body mt-2 max-w-[60%] text-center text-muted-slate">
            {description}
          </div>
          {noMetricsAndProjectsData && (
            <Button className="mt-4" onClick={openQuickstart}>
              Get started <ChevronRight className="ml-2 size-4 shrink-0" />
            </Button>
          )}
        </div>
      </div>
    );
  };

  return (
    <Card className="flex h-[426px]">
      {isPending ? (
        <Loader className="h-[426px] w-full" />
      ) : noMetricsData ? (
        renderNoMetricsData()
      ) : (
        <>
          <div className="w-1/4 min-w-[245px] max-w-[400px] border-r border-border">
            {renderMetricsList()}
          </div>
          <div className="flex min-w-1 flex-auto flex-col justify-stretch px-6 py-5">
            {renderChartContainer()}
          </div>
        </>
      )}
    </Card>
  );
};

export default MetricsOverview;
