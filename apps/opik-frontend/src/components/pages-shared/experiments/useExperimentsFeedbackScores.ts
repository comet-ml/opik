import { useMemo } from "react";
import { keepPreviousData } from "@tanstack/react-query";

import {
  COLUMN_TYPE,
  DynamicColumn,
  SCORE_TYPE_FEEDBACK,
} from "@/types/shared";
import useExperimentsFeedbackScoresNames from "@/api/datasets/useExperimentsFeedbackScoresNames";
import { buildScoreColumnId, buildScoreLabel } from "./scoresUtils";

interface UseExperimentsFeedbackScoresOptions {
  experimentIds?: string[];
  refetchInterval?: number;
}

export const useExperimentsFeedbackScores = (
  options: UseExperimentsFeedbackScoresOptions = {},
) => {
  const { experimentIds, refetchInterval = 30000 } = options;

  const { data: feedbackScoresData, isPending: isFeedbackScoresPending } =
    useExperimentsFeedbackScoresNames(
      {
        experimentsIds: experimentIds,
      },
      {
        placeholderData: keepPreviousData,
        refetchInterval,
      },
    );

  const dynamicScoresColumns = useMemo(() => {
    return (feedbackScoresData?.scores ?? [])
      .sort((c1, c2) => c1.name.localeCompare(c2.name))
      .map<DynamicColumn>((c) => {
        const scoreType = c.type || SCORE_TYPE_FEEDBACK;
        return {
          id: buildScoreColumnId(c.name, scoreType),
          label: buildScoreLabel(c.name, scoreType),
          columnType: COLUMN_TYPE.number,
          type: scoreType,
        };
      });
  }, [feedbackScoresData?.scores]);

  return {
    feedbackScoresData,
    isFeedbackScoresPending,
    dynamicScoresColumns,
  };
};
