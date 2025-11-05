import { useMemo } from "react";
import { Experiment } from "@/types/datasets";
import uniq from "lodash/uniq";
import { BarDataPoint, RadarDataPoint } from "@/types/chart";
import { normalizeFeedbackScores } from "@/lib/feedback-scores";

export type ExperimentLabelsMap = Record<string, string>;
type UseCompareExperimentsChartsDataArgs = {
  isCompare: boolean;
  experiments: Experiment[];
};

type CompareExperimentsChartsData = {
  radarChartData: RadarDataPoint[];
  radarChartKeys: string[];
  barChartData: BarDataPoint[];
  barChartKeys: string[];
  experimentLabelsMap: ExperimentLabelsMap;
};

const MAX_VISIBLE_ENTITIES = 10;

const useCompareExperimentsChartsData = ({
  isCompare,
  experiments,
}: UseCompareExperimentsChartsDataArgs): CompareExperimentsChartsData => {
  const experimentsList = useMemo(() => {
    return experiments.slice(0, MAX_VISIBLE_ENTITIES);
  }, [experiments]);

  const normalizedScoreMap = useMemo(() => {
    if (!isCompare) return {};
    return experimentsList.reduce<
      Record<string, Record<string, Record<string, number>>>
    >((acc, e) => {
      acc[e.id] = normalizeFeedbackScores(
        e.feedback_scores,
        e.pre_computed_metric_aggregates,
      );
      return acc;
    }, {});
  }, [experimentsList, isCompare]);

  const scoreMap = useMemo(() => {
    if (!isCompare) return {};
    const flatMap: Record<string, Record<string, number>> = {};

    Object.entries(normalizedScoreMap).forEach(([experimentId, normalized]) => {
      const scoreMap: Record<string, number> = {};

      Object.entries(normalized).forEach(([scoreName, aggregates]) => {
        const hasMultipleAggregates = Object.keys(aggregates).length > 1;
        Object.keys(aggregates).forEach((aggregateType) => {
          const key = hasMultipleAggregates
            ? `${scoreName} (${aggregateType})`
            : scoreName;
          scoreMap[key] = aggregates[aggregateType];
        });
      });

      flatMap[experimentId] = scoreMap;
    });

    return flatMap;
  }, [normalizedScoreMap, isCompare]);

  const scoreColumns = useMemo(() => {
    return uniq(
      Object.values(scoreMap)
        .reduce<string[]>((acc, m) => acc.concat(Object.keys(m)), [])
        .slice(0, MAX_VISIBLE_ENTITIES),
    ).sort();
  }, [scoreMap]);

  const radarChartData = useMemo(() => {
    return scoreColumns.map((name) => {
      const dataPoint: RadarDataPoint = { name };
      experimentsList.forEach((experiment) => {
        dataPoint[experiment.id] = scoreMap[experiment.id]?.[name] || 0;
      });
      return dataPoint;
    });
  }, [scoreColumns, scoreMap, experimentsList]);

  const radarChartKeys = useMemo(() => {
    return experimentsList.map((experiment) => experiment.id);
  }, [experimentsList]);

  const barChartData = useMemo(() => {
    return experimentsList.map((experiment) => {
      const dataPoint: BarDataPoint = {
        name: experiment.name,
      };
      scoreColumns.forEach((scoreName) => {
        dataPoint[scoreName] = scoreMap[experiment.id]?.[scoreName] || 0;
      });
      return dataPoint;
    });
  }, [scoreColumns, scoreMap, experimentsList]);

  const barChartKeys = useMemo(() => {
    return scoreColumns;
  }, [scoreColumns]);

  const experimentLabelsMap = useMemo(() => {
    const map: Record<string, string> = {};
    experimentsList.forEach((experiment) => {
      map[experiment.id] = experiment.name;
    });
    return map;
  }, [experimentsList]);

  return {
    radarChartData,
    radarChartKeys,
    barChartData,
    barChartKeys,
    experimentLabelsMap,
  };
};

export default useCompareExperimentsChartsData;
