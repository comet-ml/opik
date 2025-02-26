import React, { useEffect, useMemo } from "react";
import sortBy from "lodash/sortBy";
import uniq from "lodash/uniq";
import isUndefined from "lodash/isUndefined";
import { BooleanParam, useQueryParam } from "use-query-params";
import { FlaskConical, Maximize2, Minimize2, PenLine } from "lucide-react";

import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import FeedbackScoreTag from "@/components/shared/FeedbackScoreTag/FeedbackScoreTag";
import { Experiment } from "@/types/datasets";
import { TableBody, TableCell, TableRow } from "@/components/ui/table";
import { Tag } from "@/components/ui/tag";
import { Button } from "@/components/ui/button";
import ResourceLink, {
  RESOURCE_TYPE,
} from "@/components/shared/ResourceLink/ResourceLink";
import DateTag from "@/components/shared/DateTag/DateTag";
import CompareRadarChart from "./charts/CompareRadarChart";
import CompareChartContainer from "./charts/CompareChartContainer";
import { getExperimentColorsConfig } from "@/lib/charts";

type CompareExperimentsDetailsProps = {
  experimentsIds: string[];
  experiments: Experiment[];
};

const CompareExperimentsDetails: React.FunctionComponent<
  CompareExperimentsDetailsProps
> = ({ experiments, experimentsIds }) => {
  const setBreadcrumbParam = useBreadcrumbsStore((state) => state.setParam);

  const isCompare = experimentsIds.length > 1;

  const experiment = experiments[0];

  const title = !isCompare
    ? experiment?.name
    : `Compare (${experimentsIds.length})`;

  const [showCompareFeedback = false, setShowCompareFeedback] = useQueryParam(
    "scoreTable",
    BooleanParam,
    {
      updateType: "replaceIn",
    },
  );

  useEffect(() => {
    title && setBreadcrumbParam("compare", "compare", title);
    return () => setBreadcrumbParam("compare", "compare", "");
  }, [title, setBreadcrumbParam]);

  const scoreMap = useMemo(() => {
    return !isCompare
      ? {}
      : experiments.reduce<Record<string, Record<string, number>>>((acc, e) => {
          acc[e.id] = (e.feedback_scores || [])?.reduce<Record<string, number>>(
            (a, f) => {
              a[f.name] = f.value;
              return a;
            },
            {},
          );

          return acc;
        }, {});
  }, [isCompare, experiments]);

  const scoreColumns = useMemo(() => {
    return uniq(
      Object.values(scoreMap).reduce<string[]>(
        (acc, m) => acc.concat(Object.keys(m)),
        [],
      ),
    ).sort();
  }, [scoreMap]);

  const radarData = useMemo(() => {
    if (!isCompare) return [];

    return scoreColumns.map((name) => {
      const dataPoint: Record<string, string | number> = { name };
      experiments.forEach((exp) => {
        dataPoint[exp.name] = scoreMap[exp.id]?.[name] || 0;
      });
      return dataPoint;
    });
  }, [scoreColumns, experiments, scoreMap, isCompare]);

  const experimentColors = useMemo(() => {
    return getExperimentColorsConfig(experiments.map((exp) => exp.name));
  }, [experiments]);

  const renderCompareFeedbackScoresButton = () => {
    if (!isCompare) return null;

    const text = showCompareFeedback
      ? "Collapse feedback scores"
      : "Expand feedback scores";
    const Icon = showCompareFeedback ? Minimize2 : Maximize2;

    return (
      <Button
        variant="outline"
        size="sm"
        onClick={() => {
          setShowCompareFeedback(!showCompareFeedback);
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
          <Tag
            size="lg"
            variant="gray"
            className="flex items-center gap-2"
            style={{
              backgroundColor: `${experimentColors[experiments[1]?.name]
                ?.color}15`,
              color: experimentColors[experiments[1]?.name]?.color,
              borderColor: experimentColors[experiments[1]?.name]?.color,
              borderWidth: "1px",
              borderStyle: "solid",
            }}
          >
            <FlaskConical
              className="size-4 shrink-0"
              style={{ color: experimentColors[experiments[1]?.name]?.color }}
            />
            <div className="truncate">{experiments[1]?.name}</div>
          </Tag>
        ) : (
          <Tag size="lg" variant="gray">
            {`${experimentsIds.length - 1} experiments`}
          </Tag>
        );

      return (
        <>
          <div className="flex h-11 items-center gap-2">
            <span className="text-nowrap">Baseline of</span>
            <Tag
              size="lg"
              variant="gray"
              className="flex items-center gap-2"
              style={{
                backgroundColor: `${experimentColors[experiment?.name]
                  ?.color}15`,
                color: experimentColors[experiment?.name]?.color,
                borderColor: experimentColors[experiment?.name]?.color,
                borderWidth: "1px",
                borderStyle: "solid",
              }}
            >
              <FlaskConical
                className="size-4 shrink-0"
                style={{ color: experimentColors[experiment?.name]?.color }}
              />
              <div className="truncate">{experiment?.name}</div>
            </Tag>
            <span className="text-nowrap">compared against</span>
            {tag}
          </div>
          <CompareChartContainer title="Feedback Score Comparison">
            <CompareRadarChart data={radarData} experiments={experiments} />
          </CompareChartContainer>
        </>
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

  const renderCompareFeedbackScores = () => {
    if (!isCompare || !showCompareFeedback) return null;

    return (
      <div className="mb-2 mt-4 max-h-[227px] overflow-auto rounded-md border">
        {experiments.length ? (
          <table className="min-w-full table-fixed caption-bottom text-sm">
            <TableBody>
              {experiments.map((e) => (
                <TableRow key={e.id}>
                  <TableCell>
                    <div
                      className="flex h-14 min-w-20 items-center truncate p-2"
                      data-cell-wrapper="true"
                    >
                      {e.name}
                    </div>
                  </TableCell>
                  {scoreColumns.map((id) => {
                    const value = scoreMap[e.id]?.[id];

                    return (
                      <TableCell key={id}>
                        <div
                          className="flex h-14 min-w-20 items-center truncate p-2"
                          data-cell-wrapper="true"
                        >
                          {isUndefined(value) ? (
                            "–"
                          ) : (
                            <FeedbackScoreTag
                              key={id + value}
                              label={id}
                              value={value}
                            />
                          )}
                        </div>
                      </TableCell>
                    );
                  })}
                </TableRow>
              ))}
            </TableBody>
          </table>
        ) : (
          <div className="flex h-28 items-center justify-center text-muted-slate">
            No feedback scores for selected experiments
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
      {renderCompareFeedbackScores()}
    </div>
  );
};

export default CompareExperimentsDetails;
