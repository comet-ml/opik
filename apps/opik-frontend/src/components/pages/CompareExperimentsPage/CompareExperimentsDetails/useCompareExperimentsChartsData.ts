import { useMemo } from "react";
import { Experiment } from "@/types/datasets";
import uniq from "lodash/uniq";
import { BarDataPoint, RadarDataPoint } from "@/types/chart";

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
    return experimentsList.reduce<Record<string, Record<string, number>>>(
      (acc, e) => {
        const scoreMap: Record<string, number> = {};

        // Get all unique score names
        const scoreNames = new Set<string>();
        e.feedback_scores?.forEach((score) => scoreNames.add(score.name));

        // For each score name, add all aggregates
        scoreNames.forEach((scoreName) => {
          const preComputedAggregates =
            e.pre_computed_metric_aggregates?.[scoreName];

          if (
            preComputedAggregates &&
            Object.keys(preComputedAggregates).length > 0
          ) {
            // Add avg from feedback_scores first
            const avgValue = e.feedback_scores?.find(
              (s) => s.name === scoreName,
            )?.value;
            if (avgValue !== undefined) {
              scoreMap[`${scoreName} (avg)`] = avgValue;
            }

            // Add all pre-computed aggregates
            Object.keys(preComputedAggregates).forEach((aggregateKey) => {
              if (aggregateKey !== "avg") {
                // Skip avg since we already added it
                scoreMap[`${scoreName} (${aggregateKey})`] =
                  preComputedAggregates[aggregateKey];
              }
            });
          } else {
            // Only has avg - add it without suffix
            const avgValue = e.feedback_scores?.find(
              (s) => s.name === scoreName,
            )?.value;
            if (avgValue !== undefined) {
              scoreMap[scoreName] = avgValue;
            }
          }
        });

        acc[e.id] = scoreMap;
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
