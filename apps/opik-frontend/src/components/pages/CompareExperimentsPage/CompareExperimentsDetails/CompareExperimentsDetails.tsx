import React, { useEffect, useMemo } from "react";
import sortBy from "lodash/sortBy";
import { BooleanParam, useQueryParam } from "use-query-params";
import { Maximize2, Minimize2, PenLine } from "lucide-react";

import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import FeedbackScoreTag from "@/components/shared/FeedbackScoreTag/FeedbackScoreTag";
import { Experiment } from "@/types/datasets";
import { Button } from "@/components/ui/button";
import DateTag from "@/components/shared/DateTag/DateTag";
import useCompareExperimentsChartsData from "@/components/pages/CompareExperimentsPage/CompareExperimentsDetails/useCompareExperimentsChartsData";
import ExperimentsRadarChart from "@/components/pages-shared/experiments/ExperimentsRadarChart/ExperimentsRadarChart";
import ExperimentsBarChart from "@/components/pages-shared/experiments/ExperimentsBarChart/ExperimentsBarChart";
import NavigationTag from "@/components/shared/NavigationTag";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ExperimentTag from "@/components/shared/ExperimentTag/ExperimentTag";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import { AggregatedFeedbackScore } from "@/types/shared";
import { generateExperimentIdFilter } from "@/lib/filters";
import ViewSelector, {
  VIEW_TYPE,
} from "@/components/pages-shared/dashboards/ViewSelector/ViewSelector";
import { Separator } from "@/components/ui/separator";
import ExperimentTagsList from "@/components/pages/CompareExperimentsPage/ExperimentTagsList";

type ExperimentScoreTagsProps = {
  experiment: Experiment;
};

const ExperimentScoreTags: React.FunctionComponent<
  ExperimentScoreTagsProps
> = ({ experiment }) => {
  const markScores = (
    scores: AggregatedFeedbackScore[] | undefined,
    isFeedbackScore: boolean,
  ) =>
    (scores ?? []).map((score) => ({
      ...score,
      isFeedbackScore,
    }));

  const allScores = sortBy(
    [
      ...markScores(experiment?.feedback_scores, true),
      ...markScores(experiment?.experiment_scores, false),
    ],
    "name",
  );

  return (
    <>
      {allScores.map((score) => (
        <FeedbackScoreTag
          key={score.name + score.value}
          label={score.isFeedbackScore ? `${score.name} (avg)` : score.name}
          value={score.value}
        />
      ))}
    </>
  );
};

type CompareExperimentsDetailsProps = {
  experimentsIds: string[];
  experiments: Experiment[];
  isPending: boolean;
  view: VIEW_TYPE;
  onViewChange: (value: VIEW_TYPE) => void;
};

const CompareExperimentsDetails: React.FunctionComponent<
  CompareExperimentsDetailsProps
> = ({ experiments, experimentsIds, isPending, view, onViewChange }) => {
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

  const experimentTracesSearch = useMemo(
    () => ({
      traces_filters: generateExperimentIdFilter(experimentsIds[0]),
    }),
    [experimentsIds],
  );

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
          <ExperimentTag experimentName={experiments[1]?.name} />
        ) : (
          <ExperimentTag count={experimentsIds.length - 1} />
        );

      return (
        <div className="flex h-11 items-center gap-2">
          <span className="text-nowrap">Baseline of</span>
          <ExperimentTag experimentName={experiment?.name} />
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
            <ExperimentScoreTags experiment={experiment} />
          </div>
        </div>
      );
    }
  };

  const renderCharts = () => {
    if (!isCompare || !showCharts || isPending || view === VIEW_TYPE.DASHBOARDS)
      return null;

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
        <div className="flex shrink-0 items-center gap-2">
          {isCompare &&
            view !== VIEW_TYPE.DASHBOARDS &&
            renderCompareFeedbackScoresButton()}
          {isCompare && view !== VIEW_TYPE.DASHBOARDS && (
            <Separator orientation="vertical" className="mx-2 h-6" />
          )}
          <ViewSelector value={view} onChange={onViewChange} />
        </div>
      </div>
      <div className="mb-1 flex gap-2 overflow-x-auto">
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
        {!isCompare && experiment?.project_id && (
          <NavigationTag
            resource={RESOURCE_TYPE.traces}
            id={experiment.project_id}
            name="Traces"
            search={experimentTracesSearch}
            tooltipContent="View all traces for this experiment"
          />
        )}
      </div>
      {!isCompare && experiment && (
        <ExperimentTagsList
          tags={experiment?.tags ?? []}
          experimentId={experiment.id}
          experiment={experiment}
        />
      )}
      {renderSubSection()}
      {renderCharts()}
    </div>
  );
};

export default CompareExperimentsDetails;
