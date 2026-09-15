import dayjs from "dayjs";
import { getAlphabetLetter } from "@/lib/utils";

export const RUN_NUMBER_PAD = 2;

export const getDefaultExperimentLabel = (promptIndex: number) =>
  getAlphabetLetter(promptIndex).toLowerCase();

export const buildExperimentNameBase = (
  label: string,
  promptIndex: number,
  date = dayjs().format("YYYY-MM-DD"),
) => {
  const trimmed = label.trim();
  return `${trimmed || getDefaultExperimentLabel(promptIndex)}_${date}`;
};

export const composeExperimentName = (base: string, runNumber: number) =>
  `${base}_${String(runNumber).padStart(RUN_NUMBER_PAD, "0")}`;
