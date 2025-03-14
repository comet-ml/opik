import { RadarDataPoint } from "@/components/pages-shared/experiments/ExperimentsRadarChart/ExperimentsRadarChart";
import { useMemo } from "react";
import { Experiment } from "@/types/datasets";
import { getUniqueExperiments } from "@/lib/experiments";
import uniq from "lodash/uniq";

type useCompareExperimentsChartsDataArgs = {
  isCompare: boolean;
  experiments: Experiment[];
};

type CompareExperimentsChartsData = {
  radarChartData: RadarDataPoint[];
  radarChartNames: string[];
  barChartData: Record<string, number | string>[];
  barChartNames: string[];
};

const MAX_VISIBLE_ENTITIES = 10;

const useCompareExperimentsChartsData = ({
  isCompare,
  experiments,
}: useCompareExperimentsChartsDataArgs): CompareExperimentsChartsData => {
  const uniqueExperiments = useMemo(() => {
    return getUniqueExperiments(experiments).slice(0, MAX_VISIBLE_ENTITIES);
  }, [experiments]);

  const scoreMap = useMemo(() => {
    if (!isCompare) return {};
    return uniqueExperiments.reduce<Record<string, Record<string, number>>>(
      (acc, e) => {
        acc[e.id] = (e.feedback_scores || [])?.reduce<Record<string, number>>(
          (a, f) => {
            a[f.name] = f.value;
            return a;
          },
          {},
        );
        return acc;
      },
      {},
    );
  }, [uniqueExperiments, isCompare]);

  const scoreColumns = useMemo(() => {
    return uniq(
      Object.values(scoreMap)
        .reduce<string[]>((acc, m) => acc.concat(Object.keys(m)), [])
        .slice(0, MAX_VISIBLE_ENTITIES),
    ).sort();
  }, [scoreMap]);

  const radarChartData = useMemo(() => {
    return scoreColumns.map((name) => {
      const dataPoint: Record<string, number | string> = { name };
      uniqueExperiments.forEach((experiment) => {
        const expName = experiment.name;
        dataPoint[expName] = scoreMap[experiment.id]?.[name] || 0;
      });
      return dataPoint;
    });
  }, [scoreColumns, scoreMap, uniqueExperiments]);

  const radarChartNames = useMemo(() => {
    return uniqueExperiments.map((experiment) => experiment.name);
  }, [uniqueExperiments]);

  const barChartData = useMemo(() => {
    return uniqueExperiments.map((experiment) => {
      const dataPoint: Record<string, number | string> = {
        name: experiment.name,
      };
      scoreColumns.forEach((scoreName) => {
        dataPoint[scoreName] = scoreMap[experiment.id]?.[scoreName] || 0;
      });
      return dataPoint;
    });
  }, [scoreColumns, scoreMap, uniqueExperiments]);

  const barChartNames = useMemo(() => {
    return scoreColumns;
  }, [scoreColumns]);

  return { radarChartData, radarChartNames, barChartData, barChartNames };
};

export default useCompareExperimentsChartsData;
