import React, { useEffect } from "react";
import sortBy from "lodash/sortBy";
import { BooleanParam, useQueryParam } from "use-query-params";
import { FlaskConical, Maximize2, Minimize2, PenLine } from "lucide-react";

import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import FeedbackScoreTag from "@/components/shared/FeedbackScoreTag/FeedbackScoreTag";
import { Experiment } from "@/types/datasets";
import { Tag } from "@/components/ui/tag";
import { Button } from "@/components/ui/button";
import ResourceLink, {
  RESOURCE_TYPE,
} from "@/components/shared/ResourceLink/ResourceLink";
import DateTag from "@/components/shared/DateTag/DateTag";
import useCompareExperimentsChartsData from "./hooks/useCompareExperimentsChartsData";
import ExperimentsRadarChart from "@/components/pages-shared/experiments/ExperimentsRadarChart/ExperimentsRadarChart";
import ExperimentsBarChart from "@/components/pages-shared/experiments/ExperimentsBarChart/ExperimentsBarChart";

type CompareExperimentsDetailsProps = {
  experimentsIds: string[];
  experiments: Experiment[];
  isPending: boolean;
};

const CompareExperimentsDetails: React.FunctionComponent<
  CompareExperimentsDetailsProps
> = ({ experiments, experimentsIds, isPending }) => {
  const setBreadcrumbParam = useBreadcrumbsStore((state) => state.setParam);

  const isCompare = experimentsIds.length > 1;

  const experiment = experiments[0];

  const title = !isCompare
    ? experiment?.name
    : `Compare (${experimentsIds.length})`;

  const [showCharts = true, setShowCharts] = useQueryParam(
    "chartsExpanded",
    BooleanParam,
    {
      updateType: "replaceIn",
    },
  );

  useEffect(() => {
    title && setBreadcrumbParam("compare", "compare", title);
    return () => setBreadcrumbParam("compare", "compare", "");
  }, [title, setBreadcrumbParam]);

  const { radarChartData, radarChartNames, barChartData, barChartNames } =
    useCompareExperimentsChartsData({
      isCompare,
      experiments,
    });

  const renderCompareFeedbackScoresButton = () => {
    if (!isCompare) return null;

    const text = showCharts ? "Collapse charts" : "Expand charts";
    const Icon = showCharts ? Minimize2 : Maximize2;

    return (
      <Button
        variant="outline"
        size="sm"
        onClick={() => {
          setShowCharts(!showCharts);
        }}
      >
        <Icon className="mr-2 size-4 shrink-0" />
        {text}
      </Button>
    );
  };

  const renderSubSection = () => {
    if (isCompare) {
      const tag =
        experimentsIds.length === 2 ? (
          <Tag size="lg" variant="gray" className="flex items-center gap-2">
            <FlaskConical className="size-4 shrink-0" />
            <div className="truncate">{experiments[1]?.name}</div>
          </Tag>
        ) : (
          <Tag size="lg" variant="gray">
            {`${experimentsIds.length - 1} experiments`}
          </Tag>
        );

      return (
        <div className="flex h-11 items-center gap-2">
          <span className="text-nowrap">Baseline of</span>
          <Tag size="lg" variant="gray" className="flex items-center gap-2">
            <FlaskConical className="size-4 shrink-0" />
            <div className="truncate">{experiment?.name}</div>
          </Tag>
          <span className="text-nowrap">compared against</span>
          {tag}
        </div>
      );
    } else {
      return (
        <div className="flex h-11 items-center gap-2">
          <PenLine className="size-4 shrink-0" />
          <div className="flex gap-1 overflow-x-auto">
            {sortBy(experiment?.feedback_scores ?? [], "name").map(
              (feedbackScore) => {
                return (
                  <FeedbackScoreTag
                    key={feedbackScore.name + feedbackScore.value}
                    label={feedbackScore.name}
                    value={feedbackScore.value}
                  />
                );
              },
            )}
          </div>
        </div>
      );
    }
  };

  const renderCharts = () => {
    if (!isCompare || !showCharts || isPending) return null;

    return (
      <div className="mb-2 mt-4 overflow-auto">
        {experiments.length ? (
          <div
            className="flex flex-row gap-4"
            style={{ "--chart-height": "240px" } as React.CSSProperties}
          >
            {radarChartData.length > 1 && (
              <div className="w-1/3 min-w-[400px]">
                <ExperimentsRadarChart
                  name="Feedback scores"
                  description="Top 10 metrics"
                  chartId="feedback-scores-radar-chart"
                  data={radarChartData}
                  names={radarChartNames}
                />
              </div>
            )}
            <div className="min-w-[400px] flex-1">
              <ExperimentsBarChart
                name="Feedback scores distribution"
                description="Last 10 experiments"
                chartId="feedback-scores-bar-chart"
                data={barChartData}
                names={barChartNames}
              />
            </div>
          </div>
        ) : (
          <div className="flex h-28 items-center justify-center text-muted-slate">
            No chart data for selected experiments
          </div>
        )}
      </div>
    );
  };

  return (
    <div className="pb-4 pt-6">
      <div className="mb-4 flex min-h-8 items-center justify-between">
        <h1 className="comet-title-l truncate break-words">{title}</h1>
        {renderCompareFeedbackScoresButton()}
      </div>
      <div className="mb-1 flex gap-4 overflow-x-auto">
        {!isCompare && <DateTag date={experiment?.created_at} />}
        <ResourceLink
          id={experiment?.dataset_id}
          name={experiment?.dataset_name}
          resource={RESOURCE_TYPE.dataset}
          asTag
        />
      </div>
      {renderSubSection()}
      {renderCharts()}
    </div>
  );
};

export default CompareExperimentsDetails;
