import { useMemo } from "react";
import { Experiment } from "@/types/datasets";
import uniq from "lodash/uniq";
import { BarDataPoint, RadarDataPoint } from "@/types/chart";
import { AggregatedFeedbackScore } from "@/types/shared";

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

  const scoreMap = useMemo(() => {
    if (!isCompare) return {};

    const createScoresMap = (
      scores: AggregatedFeedbackScore[] | undefined,
      addAvgSuffix: boolean,
    ): Record<string, number> =>
      (scores || []).reduce<Record<string, number>>((acc, score) => {
        const key = addAvgSuffix ? `${score.name} (avg)` : score.name;
        acc[key] = score.value;
        return acc;
      }, {});

    return experimentsList.reduce<Record<string, Record<string, number>>>(
      (acc, e) => {
        const feedbackScoresMap = createScoresMap(e.feedback_scores, true);
        const experimentScoresMap = createScoresMap(e.experiment_scores, false);
        acc[e.id] = { ...feedbackScoresMap, ...experimentScoresMap };
        return acc;
      },
      {},
    );
  }, [experimentsList, isCompare]);

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
