import { Experiment } from "@/types/datasets";

export const getUniqueExperiments = (
  experiments: Experiment[],
): Experiment[] => {
  const nameOccurrences = new Map<string, number>();

  return experiments.map((experiment) => {
    const { name } = experiment;
    const currentCount = nameOccurrences.get(name) ?? 0;

    nameOccurrences.set(name, currentCount + 1);

    return {
      ...experiment,
      name: currentCount > 0 ? `${name}${currentCount}` : name,
    };
  });
};
