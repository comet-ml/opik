import { useMemo } from "react";

import { ANNOTATION_QUEUE_SCOPE } from "@/types/annotation-queues";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { LOGS_SOURCE } from "@/types/traces";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import useTracesFeedbackScoresNames from "@/api/traces/useTracesFeedbackScoresNames";
import useSpansFeedbackScoresNames from "@/api/traces/useSpansFeedbackScoresNames";
import useThreadsFeedbackScoresNames from "@/api/traces/useThreadsFeedbackScoresNames";
import { ChipDefinition } from "@/shared/filter-chips/types";
import { buildTraceChipDefinitions } from "@/v2/pages-shared/traces/traceChipDefinitions";
import { buildThreadChipDefinitions } from "@/v2/pages-shared/traces/threadChipDefinitions";
import { toAutomationChipDefinitions } from "@/v2/pages-shared/annotation-queues/automationFilters";

/**
 * The Logs page filters for the queue's scope, minus those that only make sense for picking out specific
 * items. Options come from SDK-logged traffic, the only traffic automation routes.
 */
const useAutomationChipDefinitions = (
  projectId: string,
  scope: ANNOTATION_QUEUE_SCOPE,
): ChipDefinition[] => {
  const isThreadScope = scope === ANNOTATION_QUEUE_SCOPE.THREAD;
  const isGuardrailsEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.GUARDRAILS_ENABLED,
  );

  const { data: traceScores, isPending: isTraceScoresPending } =
    useTracesFeedbackScoresNames(
      { projectId },
      { enabled: Boolean(projectId) && !isThreadScope },
    );
  const { data: spanScores, isPending: isSpanScoresPending } =
    useSpansFeedbackScoresNames(
      { projectId },
      { enabled: Boolean(projectId) && !isThreadScope },
    );
  const { data: threadScores, isPending: isThreadScoresPending } =
    useThreadsFeedbackScoresNames(
      { projectId },
      { enabled: Boolean(projectId) && isThreadScope },
    );

  return useMemo(() => {
    const definitions = isThreadScope
      ? buildThreadChipDefinitions({
          projectId,
          threadScoreOptions: {
            items: (threadScores?.scores ?? []).map((s) => s.name),
            isLoading: isThreadScoresPending,
          },
        })
      : buildTraceChipDefinitions({
          projectId,
          traceScoreOptions: {
            items: (traceScores?.scores ?? []).map((s) => s.name),
            isLoading: isTraceScoresPending,
          },
          spanScoreOptions: {
            items: (spanScores?.scores ?? []).map((s) => s.name),
            isLoading: isSpanScoresPending,
          },
          isGuardrailsEnabled,
          logsSource: LOGS_SOURCE.sdk,
        });

    return toAutomationChipDefinitions(definitions);
  }, [
    isThreadScope,
    projectId,
    threadScores,
    isThreadScoresPending,
    traceScores,
    isTraceScoresPending,
    spanScores,
    isSpanScoresPending,
    isGuardrailsEnabled,
  ]);
};

export default useAutomationChipDefinitions;
