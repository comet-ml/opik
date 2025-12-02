import { useMemo } from "react";
import { keepPreviousData } from "@tanstack/react-query";

import {
  COLUMN_EXPERIMENT_SCORES_ID,
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_TYPE,
  DynamicColumn,
  SCORE_TYPE_EXPERIMENT,
} from "@/types/shared";
import useExperimentsFeedbackScoresNames from "@/api/datasets/useExperimentsFeedbackScoresNames";

export const useExperimentsFeedbackScores = () => {
  const { data: feedbackScoresData, isPending: isFeedbackScoresPending } =
    useExperimentsFeedbackScoresNames(
      {},
      {
        placeholderData: keepPreviousData,
        refetchInterval: 30000,
      },
    );

  const dynamicScoresColumns = useMemo(() => {
    return (feedbackScoresData?.scores ?? [])
      .sort((c1, c2) => c1.name.localeCompare(c2.name))
      .map<DynamicColumn>((c) => {
        const prefix =
          c.type === SCORE_TYPE_EXPERIMENT
            ? COLUMN_EXPERIMENT_SCORES_ID
            : COLUMN_FEEDBACK_SCORES_ID;
        return {
          id: `${prefix}.${c.name}`,
          label: c.name,
          columnType: COLUMN_TYPE.number,
          type: c.type,
        };
      });
  }, [feedbackScoresData?.scores]);

  return {
    feedbackScoresData,
    isFeedbackScoresPending,
    dynamicScoresColumns,
  };
};
