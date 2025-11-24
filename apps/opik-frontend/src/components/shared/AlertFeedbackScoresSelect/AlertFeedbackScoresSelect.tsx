import React, { useCallback, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";

import LoadableSelectBox from "@/components/shared/LoadableSelectBox/LoadableSelectBox";
import useFeedbackDefinitionsList from "@/api/feedback-definitions/useFeedbackDefinitionsList";
import useTracesFeedbackScoresNames from "@/api/traces/useTracesFeedbackScoresNames";
import useThreadsFeedbackScoresNames from "@/api/traces/useThreadsFeedbackScoresNames";
import { DropdownOption } from "@/types/shared";
import { ALERT_EVENT_TYPE } from "@/types/alerts";
import useAppStore from "@/store/AppStore";

const DEFAULT_LOADED_FEEDBACK_DEFINITION_ITEMS = 1000;

type AlertFeedbackScoresSelectProps = {
  value: string;
  onChange: (value: string) => void;
  className?: string;
  eventType: ALERT_EVENT_TYPE;
};

const AlertFeedbackScoresSelect: React.FC<AlertFeedbackScoresSelectProps> = ({
  value,
  onChange,
  className,
  eventType,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [isLoadedMore, setIsLoadedMore] = useState(false);

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

  // Fetch actual feedback score names based on trigger type
  const isTraceTrigger = eventType === ALERT_EVENT_TYPE.trace_feedback_score;
  const isThreadTrigger =
    eventType === ALERT_EVENT_TYPE.trace_thread_feedback_score;

  const { data: traceScoresData, isLoading: isLoadingTraceScores } =
    useTracesFeedbackScoresNames(
      { projectId: undefined },
      { enabled: isTraceTrigger },
    );

  const { data: threadScoresData, isLoading: isLoadingThreadScores } =
    useThreadsFeedbackScoresNames(
      { projectId: undefined },
      { enabled: isThreadTrigger },
    );

  const total = feedbackDefinitionsData?.total ?? 0;

  const loadMoreHandler = useCallback(() => setIsLoadedMore(true), []);

  // Merge feedback definitions and actual feedback score names
  const options: DropdownOption<string>[] = useMemo(() => {
    const definitionNames = new Map<
      string,
      { name: string; description?: string }
    >();

    // Add feedback definitions
    (feedbackDefinitionsData?.content || []).forEach((def) => {
      definitionNames.set(def.name, {
        name: def.name,
        description: def.description,
      });
    });

    // Add actual feedback score names from appropriate source
    const scoreNames = isTraceTrigger
      ? traceScoresData?.scores || []
      : isThreadTrigger
        ? threadScoresData?.scores || []
        : [];

    scoreNames.forEach((score) => {
      if (!definitionNames.has(score.name)) {
        definitionNames.set(score.name, { name: score.name });
      }
    });

    // Convert to dropdown options, sorted alphabetically
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
    isTraceTrigger,
    isThreadTrigger,
  ]);

  const isLoading =
    isLoadingDefinitions ||
    (isTraceTrigger && isLoadingTraceScores) ||
    (isThreadTrigger && isLoadingThreadScores);

  return (
    <LoadableSelectBox
      value={value}
      onChange={onChange}
      options={options}
      onLoadMore={
        total > DEFAULT_LOADED_FEEDBACK_DEFINITION_ITEMS && !isLoadedMore
          ? loadMoreHandler
          : undefined
      }
      buttonClassName={className}
      isLoading={isLoading}
      optionsCount={DEFAULT_LOADED_FEEDBACK_DEFINITION_ITEMS}
      placeholder="Select score"
    />
  );
};

export default AlertFeedbackScoresSelect;
