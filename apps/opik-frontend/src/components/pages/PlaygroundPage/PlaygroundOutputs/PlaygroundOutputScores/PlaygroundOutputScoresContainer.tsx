import React, { useMemo, useRef, useEffect } from "react";

import useTraceById from "@/api/traces/useTraceById";
import useRulesList from "@/api/automations/useRulesList";
import useProjectByName from "@/api/projects/useProjectByName";
import useAppStore from "@/store/AppStore";
import { PLAYGROUND_PROJECT_NAME } from "@/constants/shared";
import { EvaluatorsRule, EVALUATORS_RULE_TYPE } from "@/types/automations";
import PlaygroundOutputScores, { ScoreData } from "./PlaygroundOutputScores";

const REFETCH_INTERVAL = 5000; // 5 seconds
const MAX_REFETCH_TIME = 300000; // 5 minutes max polling

// Helper to get score names that a rule will produce
const getScoreNamesFromRule = (rule: EvaluatorsRule): string[] => {
  if (
    rule.type === EVALUATORS_RULE_TYPE.llm_judge ||
    rule.type === EVALUATORS_RULE_TYPE.thread_llm_judge ||
    rule.type === EVALUATORS_RULE_TYPE.span_llm_judge
  ) {
    return rule.code.schema?.map((s) => s.name) || [];
  }
  if (
    rule.type === EVALUATORS_RULE_TYPE.python_code ||
    rule.type === EVALUATORS_RULE_TYPE.thread_python_code ||
    rule.type === EVALUATORS_RULE_TYPE.span_python_code
  ) {
    return rule.code.metric ? [rule.code.metric] : [];
  }
  return [];
};

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

  // Track when polling started for timeout calculation
  const pollingStartTimeRef = useRef<number | null>(null);

  // Reset polling start time when traceId changes
  useEffect(() => {
    pollingStartTimeRef.current = traceId ? Date.now() : null;
  }, [traceId]);

  // Rules are selected if:
  // - selectedRuleIds is undefined (legacy outputs without stored selection - treat as "all")
  // - selectedRuleIds is null (explicitly "all" selected)
  // - selectedRuleIds is a non-empty array (specific rules selected)
  const selectingAllRules = selectedRuleIds == null;
  const hasSelectedRules = selectingAllRules || selectedRuleIds.length > 0;
  const hasSpecificRulesSelected =
    Array.isArray(selectedRuleIds) && selectedRuleIds.length > 0;

  // Fetch playground project to get rules
  const { data: playgroundProject } = useProjectByName(
    { projectName: PLAYGROUND_PROJECT_NAME },
    { enabled: !!workspaceName && hasSelectedRules },
  );

  // Fetch rules to map score names to rule names
  const { data: rulesData } = useRulesList(
    {
      workspaceName,
      projectId: playgroundProject?.id,
      page: 1,
      size: 100,
    },
    {
      enabled: !!playgroundProject?.id && hasSelectedRules,
    },
  );

  const rules = useMemo(() => rulesData?.content || [], [rulesData?.content]);
  const rulesLoaded = rules.length > 0;

  // Get selected rules based on selectedRuleIds
  const selectedRules = useMemo(() => {
    if (!hasSelectedRules || rules.length === 0) return [];

    // If selectedRuleIds is null or undefined (selecting "all"), return all rules
    if (selectingAllRules) {
      return rules;
    }

    return rules.filter((r) => selectedRuleIds.includes(r.id));
  }, [hasSelectedRules, rules, selectedRuleIds, selectingAllRules]);

  // Get expected metric names from selected rules
  const expectedMetricNames = useMemo(() => {
    const names: string[] = [];
    const seen = new Set<string>();

    for (const rule of selectedRules) {
      const scoreNames = getScoreNamesFromRule(rule);
      for (const name of scoreNames) {
        if (!seen.has(name)) {
          seen.add(name);
          names.push(name);
        }
      }
    }

    return names.sort((a, b) => a.localeCompare(b));
  }, [selectedRules]);

  // Calculate expected score names for polling logic
  const expectedScoreNames = useMemo(() => {
    return new Set(expectedMetricNames);
  }, [expectedMetricNames]);

  // Check if selected rules were deleted
  const selectedRulesDeleted =
    hasSpecificRulesSelected && rulesLoaded && selectedRules.length === 0;

  // Store values in refs for access in refetchInterval
  const expectedScoreNamesRef = useRef<Set<string>>(expectedScoreNames);
  expectedScoreNamesRef.current = expectedScoreNames;

  // Fetch trace to get scores
  const { data: trace } = useTraceById(
    { traceId: traceId! },
    {
      enabled: !!traceId && hasSelectedRules && !selectedRulesDeleted,
      refetchInterval: (query) => {
        const startTime = pollingStartTimeRef.current || Date.now();
        const elapsedTime = Date.now() - startTime;

        // Stop if exceeded max polling time
        if (elapsedTime > MAX_REFETCH_TIME) {
          return false;
        }

        const data = query.state.data;
        const receivedScores = data?.feedback_scores ?? [];
        const expectedNames = expectedScoreNamesRef.current;

        // If specific rules were selected, wait for all expected scores
        if (expectedNames.size > 0) {
          const receivedNames = new Set(receivedScores.map((s) => s.name));
          const allExpectedReceived = [...expectedNames].every((name) =>
            receivedNames.has(name),
          );
          if (allExpectedReceived) {
            return false;
          }
        } else {
          // For "all" selection or legacy data, stop as soon as we have any scores
          if (receivedScores.length > 0) {
            return false;
          }
        }

        return REFETCH_INTERVAL;
      },
    },
  );

  // Build metric scores from trace feedback
  const metricScores: Record<string, ScoreData> = useMemo(() => {
    const feedbackScores = trace?.feedback_scores ?? [];
    const scores: Record<string, ScoreData> = {};

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

  // Don't render if no rules selected or no trace yet
  if (!hasSelectedRules) {
    return null;
  }

  return (
    <PlaygroundOutputScores
      metricNames={expectedMetricNames}
      metricScores={metricScores}
      stale={stale}
      className={className}
    />
  );
};

export default PlaygroundOutputScoresContainer;
