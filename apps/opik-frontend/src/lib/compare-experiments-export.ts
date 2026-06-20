import { Experiment } from "@/types/datasets";

export const getExperimentExportIds = (
  comparisonExperimentIds: string[],
  localExperiments: Experiment[],
  rowExperimentIds: string[],
) =>
  Array.from(
    new Set([
      ...comparisonExperimentIds,
      ...localExperiments.map((experiment) => experiment.id),
      ...rowExperimentIds,
    ]),
  );

export const getExperimentExportPrefix = (
  experimentId: string,
  nameMap: Record<string, string>,
) => {
  const experimentName = nameMap[experimentId];

  if (experimentName) return `${experimentName}.`;

  const usedNames = new Set(Object.values(nameMap));
  let fallbackName = `unknown(${experimentId})`;
  let suffix = 2;

  while (usedNames.has(fallbackName)) {
    fallbackName = `unknown(${experimentId}) ${suffix}`;
    suffix += 1;
  }

  return `${fallbackName}.`;
};
