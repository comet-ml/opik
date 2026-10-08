import { QueryFunctionContext, useQueries } from "@tanstack/react-query";
import sortBy from "lodash/sortBy";

import { getExperimentById } from "@/api/datasets/useExperimentById";
import { buildExperimentName } from "@/lib/experiments";
import { getAlphabetLetter } from "@/lib/utils";
import { PlaygroundLastRun } from "@/store/PlaygroundStore";
import { Experiment } from "@/types/datasets";

export type LastRunExperiment = {
  id: string;
  index: number;
  name?: string;
};

export const getLastRunExperimentLabel = ({ name, index }: LastRunExperiment) =>
  name ?? `Prompt ${getAlphabetLetter(index)} experiment`;

const useLastRunExperiments = (
  lastRun: PlaygroundLastRun | null,
): LastRunExperiment[] => {
  const experiments = sortBy(lastRun?.experiments ?? [], "index");
  const runName = lastRun?.name;

  const serverExperiments = useQueries({
    queries: experiments.map(({ id }) => ({
      queryKey: ["experiment", { experimentId: id }],
      queryFn: (context: QueryFunctionContext) =>
        getExperimentById(context, { experimentId: id }),
      enabled: !runName,
    })),
  });

  return experiments.map((experiment, i) => ({
    ...experiment,
    name: runName
      ? buildExperimentName(runName, experiment.index)
      : (serverExperiments[i]?.data as Experiment | undefined)?.name,
  }));
};

export default useLastRunExperiments;
