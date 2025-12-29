import React, { useMemo, useRef, useEffect } from "react";

import useTraceById from "@/api/traces/useTraceById";
import useRulesList from "@/api/automations/useRulesList";
import useProjectByName from "@/api/projects/useProjectByName";
import useAppStore from "@/store/AppStore";
import { PLAYGROUND_PROJECT_NAME } from "@/constants/shared";
import { getScoreNamesFromRule } from "@/lib/rules";
import PlaygroundOutputScores, { ScoreData } from "./PlaygroundOutputScores";

const REFETCH_INTERVAL = 5000;
const MAX_REFETCH_TIME = 300000;

interface PlaygroundOutputScoresContainerProps {
  traceId: string | null;
  selectedRuleIds: string[] | null | undefined;
  stale?: boolean;
  className?: string;
}

const PlaygroundOutputScoresContainer: React.FC<
  PlaygroundOutputScoresContainerProps
> = ({ traceId, selectedRuleIds, stale = false, className }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const pollingStartTimeRef = useRef<number | null>(null);

  useEffect(() => {
    pollingStartTimeRef.current = traceId ? Date.now() : null;
  }, [traceId]);

  const shouldShowMetrics =
    selectedRuleIds === null ||
    (Array.isArray(selectedRuleIds) && selectedRuleIds.length > 0);

  const hasRulesSelected =
    selectedRuleIds == null || selectedRuleIds.length > 0;

  const { data: playgroundProject } = useProjectByName(
    { projectName: PLAYGROUND_PROJECT_NAME },
    { enabled: !!workspaceName && shouldShowMetrics && hasRulesSelected },
  );

  const { data: rulesData } = useRulesList(
    {
      workspaceName,
      projectId: playgroundProject?.id,
      page: 1,
      size: 100,
    },
    {
      enabled: !!playgroundProject?.id && shouldShowMetrics && hasRulesSelected,
    },
  );

  const rules = useMemo(() => rulesData?.content || [], [rulesData?.content]);

  const selectedRules = useMemo(() => {
    if (!shouldShowMetrics || !hasRulesSelected || !rules.length) return [];
    if (selectedRuleIds == null) return rules;
    return rules.filter((r) => selectedRuleIds.includes(r.id));
  }, [shouldShowMetrics, hasRulesSelected, rules, selectedRuleIds]);

  const expectedMetricNames = useMemo(() => {
    const allNames = selectedRules.flatMap((rule) =>
      getScoreNamesFromRule(rule),
    );
    return [...new Set(allNames)].sort((a, b) => a.localeCompare(b));
  }, [selectedRules]);

  const expectedScoreNamesRef = useRef<Set<string>>(new Set());
  expectedScoreNamesRef.current = new Set(expectedMetricNames);

  const { data: trace } = useTraceById(
    { traceId: traceId! },
    {
      enabled: !!traceId && hasRulesSelected,
      refetchInterval: (query) => {
        const elapsed =
          Date.now() - (pollingStartTimeRef.current || Date.now());
        if (elapsed > MAX_REFETCH_TIME) return false;

        const receivedScores = query.state.data?.feedback_scores ?? [];
        const expectedNames = expectedScoreNamesRef.current;

        if (expectedNames.size > 0) {
          const receivedNames = new Set(receivedScores.map((s) => s.name));
          if ([...expectedNames].every((name) => receivedNames.has(name))) {
            return false;
          }
        }
        // Note: We don't stop polling just because scores exist when expectedNames
        // is empty. This prevents a race condition where pre-existing scores or
        // scores from Python rules (whose names can't be extracted statically)
        // would stop polling before all rules finish loading or executing.

        return REFETCH_INTERVAL;
      },
    },
  );

  const metricScores = useMemo(() => {
    const scores: Record<string, ScoreData> = {};
    const feedbackScores = trace?.feedback_scores ?? [];

    for (const score of feedbackScores) {
      scores[score.name] = {
        value: score.value,
        reason: score.reason,
        lastUpdatedAt: score.last_updated_at,
        lastUpdatedBy: score.last_updated_by,
        valueByAuthor: score.value_by_author,
        category: score.category_name,
      };
    }

    return scores;
  }, [trace?.feedback_scores]);

  // Combine expected metric names (from rule analysis) with actual score names (from trace)
  // This ensures Python evaluator scores are shown even if they couldn't be predicted
  const allMetricNames = useMemo(() => {
    const actualScoreNames = Object.keys(metricScores);
    const combined = new Set([...expectedMetricNames, ...actualScoreNames]);
    return [...combined].sort((a, b) => a.localeCompare(b));
  }, [expectedMetricNames, metricScores]);

  // Don't show metrics if there's no output yet or no rules selected
  if (!shouldShowMetrics) {
    return null;
  }

  return (
    <PlaygroundOutputScores
      metricNames={allMetricNames}
      metricScores={metricScores}
      stale={stale}
      className={className}
    />
  );
};

export default PlaygroundOutputScoresContainer;
