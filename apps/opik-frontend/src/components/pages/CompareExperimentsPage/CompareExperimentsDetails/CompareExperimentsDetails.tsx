import React, { useEffect } from "react";
import sortBy from "lodash/sortBy";
import { BooleanParam, useQueryParam } from "use-query-params";
import { FlaskConical, Maximize2, Minimize2, PenLine } from "lucide-react";

import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import FeedbackScoreTag from "@/components/shared/FeedbackScoreTag/FeedbackScoreTag";
import { Experiment } from "@/types/datasets";
import { Tag } from "@/components/ui/tag";
import { Button } from "@/components/ui/button";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import DateTag from "@/components/shared/DateTag/DateTag";
import useCompareExperimentsChartsData from "@/components/pages/CompareExperimentsPage/CompareExperimentsDetails/useCompareExperimentsChartsData";
import ExperimentsRadarChart from "@/components/pages-shared/experiments/ExperimentsRadarChart/ExperimentsRadarChart";
import ExperimentsBarChart from "@/components/pages-shared/experiments/ExperimentsBarChart/ExperimentsBarChart";
import NavigationTag from "@/components/shared/NavigationTag";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

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

  const {
    radarChartData,
    radarChartKeys,
    barChartData,
    barChartKeys,
    experimentLabelsMap,
  } = useCompareExperimentsChartsData({
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
          <Tag size="md" variant="gray" className="flex items-center gap-2">
            <FlaskConical className="size-4 shrink-0" />
            <div className="truncate">{experiments[1]?.name}</div>
          </Tag>
        ) : (
          <Tag size="md" variant="gray">
            {`${experimentsIds.length - 1} experiments`}
          </Tag>
        );

      return (
        <div className="flex h-11 items-center gap-2">
          <span className="text-nowrap">Baseline of</span>
          <Tag size="md" variant="gray" className="flex items-center gap-2">
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
          <TooltipWrapper content="Feedback scores">
            <PenLine className="size-4 shrink-0" />
          </TooltipWrapper>
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
                  chartId="feedback-scores-radar-chart"
                  data={radarChartData}
                  keys={radarChartKeys}
                  experimentLabelsMap={experimentLabelsMap}
                />
              </div>
            )}
            <div className="min-w-[400px] flex-1">
              <ExperimentsBarChart
                name="Feedback scores distribution"
                chartId="feedback-scores-bar-chart"
                data={barChartData}
                keys={barChartKeys}
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
        {!isCompare && (
          <DateTag
            date={experiment?.created_at}
            resource={RESOURCE_TYPE.experiment}
          />
        )}
        <NavigationTag
          id={experiment?.dataset_id}
          name={experiment?.dataset_name}
          resource={RESOURCE_TYPE.dataset}
        />
        {experiment?.prompt_versions &&
          experiment.prompt_versions.length > 0 && (
            <NavigationTag
              id={experiment.prompt_versions[0].prompt_id}
              name={experiment.prompt_versions[0].prompt_name}
              resource={RESOURCE_TYPE.prompt}
            />
          )}
      </div>
      {renderSubSection()}
      {renderCharts()}
    </div>
  );
};

export default CompareExperimentsDetails;
