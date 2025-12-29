import React, { useCallback, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";

import LoadableSelectBox from "@/components/shared/LoadableSelectBox/LoadableSelectBox";
import useFeedbackDefinitionsList from "@/api/feedback-definitions/useFeedbackDefinitionsList";
import useTracesFeedbackScoresNames from "@/api/traces/useTracesFeedbackScoresNames";
import useThreadsFeedbackScoresNames from "@/api/traces/useThreadsFeedbackScoresNames";
import useExperimentsFeedbackScoresNames from "@/api/datasets/useExperimentsFeedbackScoresNames";
import useSpansFeedbackScoresNames from "@/api/traces/useSpansFeedbackScoresNames";
import { DropdownOption } from "@/types/shared";
import useAppStore from "@/store/AppStore";

const DEFAULT_LOADED_FEEDBACK_DEFINITION_ITEMS = 1000;

export enum ScoreSource {
  TRACES = "traces",
  THREADS = "threads",
  EXPERIMENTS = "experiments",
  SPANS = "spans",
}

export type ScoreSourceType = ScoreSource;

interface BaseFeedbackDefinitionsAndScoresSelectBoxProps {
  className?: string;
  disabled?: boolean;
  scoreSource: ScoreSourceType;
  entityIds?: string[];
  placeholder?: string;
}

interface SingleSelectProps
  extends BaseFeedbackDefinitionsAndScoresSelectBoxProps {
  value: string;
  onChange: (value: string) => void;
  multiselect?: false;
}

interface MultiSelectProps
  extends BaseFeedbackDefinitionsAndScoresSelectBoxProps {
  value: string[];
  onChange: (value: string[]) => void;
  multiselect: true;
  showSelectAll?: boolean;
}

export type FeedbackDefinitionsAndScoresSelectBoxProps =
  | SingleSelectProps
  | MultiSelectProps;

const FeedbackDefinitionsAndScoresSelectBox: React.FC<
  FeedbackDefinitionsAndScoresSelectBoxProps
> = (props) => {
  const { className, disabled, scoreSource, entityIds, placeholder } = props;
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [isLoadedMore, setIsLoadedMore] = useState(false);

  // Extract IDs based on score source
  const projectId = entityIds?.[0]; // For traces/threads/spans (single project)
  const experimentsIds = entityIds; // For experiments (array)

  // Fetch feedback definitions
  const { data: feedbackDefinitionsData, isLoading: isLoadingDefinitions } =
    useFeedbackDefinitionsList(
      {
        workspaceName,
        page: 1,
        size: isLoadedMore ? 10000 : DEFAULT_LOADED_FEEDBACK_DEFINITION_ITEMS,
      },
      {
        placeholderData: keepPreviousData,
      },
    );

  const { data: traceScoresData, isLoading: isLoadingTraceScores } =
    useTracesFeedbackScoresNames(
      { projectId },
      {
        enabled: scoreSource === ScoreSource.TRACES,
        placeholderData: keepPreviousData,
      },
    );

  const { data: threadScoresData, isLoading: isLoadingThreadScores } =
    useThreadsFeedbackScoresNames(
      { projectId },
      {
        enabled: scoreSource === ScoreSource.THREADS,
        placeholderData: keepPreviousData,
      },
    );

  const { data: experimentScoresData, isLoading: isLoadingExperimentScores } =
    useExperimentsFeedbackScoresNames(
      { experimentsIds },
      {
        enabled: scoreSource === ScoreSource.EXPERIMENTS,
        placeholderData: keepPreviousData,
      },
    );

  const { data: spanScoresData, isLoading: isLoadingSpanScores } =
    useSpansFeedbackScoresNames(
      { projectId },
      {
        enabled: scoreSource === ScoreSource.SPANS,
        placeholderData: keepPreviousData,
      },
    );

  const total = feedbackDefinitionsData?.total ?? 0;

  const loadMoreHandler = useCallback(() => setIsLoadedMore(true), []);

  const options: DropdownOption<string>[] = useMemo(() => {
    const definitionNames = new Map<
      string,
      { name: string; description?: string }
    >();

    (feedbackDefinitionsData?.content || []).forEach((def) => {
      definitionNames.set(def.name, {
        name: def.name,
        description: def.description,
      });
    });

    const scoreDataBySource = {
      [ScoreSource.TRACES]: traceScoresData?.scores,
      [ScoreSource.THREADS]: threadScoresData?.scores,
      [ScoreSource.EXPERIMENTS]: experimentScoresData?.scores,
      [ScoreSource.SPANS]: spanScoresData?.scores,
    };

    const scoreNames = scoreDataBySource[scoreSource] || [];

    scoreNames.forEach((score) => {
      if (!definitionNames.has(score.name)) {
        definitionNames.set(score.name, { name: score.name });
      }
    });

    return Array.from(definitionNames.values())
      .sort((a, b) => a.name.localeCompare(b.name))
      .map((item) => ({
        value: item.name,
        label: item.name,
        description: item.description,
      }));
  }, [
    feedbackDefinitionsData?.content,
    traceScoresData?.scores,
    threadScoresData?.scores,
    experimentScoresData?.scores,
    spanScoresData?.scores,
    scoreSource,
  ]);

  const loadingStatesBySource = {
    [ScoreSource.TRACES]: isLoadingTraceScores,
    [ScoreSource.THREADS]: isLoadingThreadScores,
    [ScoreSource.EXPERIMENTS]: isLoadingExperimentScores,
    [ScoreSource.SPANS]: isLoadingSpanScores,
  };

  const isLoading = isLoadingDefinitions || loadingStatesBySource[scoreSource];

  const loadableSelectBoxProps = props.multiselect
    ? {
        options,
        value: props.value,
        placeholder: placeholder || "Select scores",
        onChange: props.onChange,
        multiselect: true as const,
        showSelectAll: props.showSelectAll,
      }
    : {
        options,
        value: props.value,
        placeholder: placeholder || "Select score",
        onChange: props.onChange,
        multiselect: false as const,
      };

  return (
    <LoadableSelectBox
      {...loadableSelectBoxProps}
      onLoadMore={
        total > DEFAULT_LOADED_FEEDBACK_DEFINITION_ITEMS && !isLoadedMore
          ? loadMoreHandler
          : undefined
      }
      buttonClassName={className}
      disabled={disabled}
      isLoading={isLoading}
      optionsCount={DEFAULT_LOADED_FEEDBACK_DEFINITION_ITEMS}
      showTooltip
      minWidth={280}
      align="start"
    />
  );
};

export default FeedbackDefinitionsAndScoresSelectBox;
