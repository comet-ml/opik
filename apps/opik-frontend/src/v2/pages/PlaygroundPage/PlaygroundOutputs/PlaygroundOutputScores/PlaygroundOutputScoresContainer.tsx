import React, { useMemo, useRef, useEffect } from "react";
import get from "lodash/get";

import useTraceById from "@/api/traces/useTraceById";
import useRulesList from "@/api/automations/useRulesList";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import { useSelectedRuleIds } from "@/store/PlaygroundStore";
import { getScoreNamesFromRule } from "@/lib/rules";
import { EvaluatorsRule } from "@/types/automations";
import { Trace } from "@/types/traces";
import {
  isAlwaysRunRule,
  isTraceRule,
} from "@/v2/pages/PlaygroundPage/metricSelection";
import PlaygroundOutputScores, { ScoreData } from "./PlaygroundOutputScores";

const REFETCH_INTERVAL = 5000;
const MAX_REFETCH_TIME = 300000;

const scoreNamesOf = (rules: EvaluatorsRule[]) =>
  [...new Set(rules.flatMap((rule) => getScoreNamesFromRule(rule)))].sort(
    (a, b) => a.localeCompare(b),
  );

const pickedRuleIdsOf = (trace: Trace): string[] => {
  const picked: unknown = get(trace.metadata, "selected_rule_ids");
  return Array.isArray(picked) ? picked : [];
};

const scoringRulesOf = (rules: EvaluatorsRule[], pickedRuleIds: string[]) => {
  const pickedIds = new Set(pickedRuleIds);
  return rules.filter(
    (rule) =>
      isTraceRule(rule) && (pickedIds.has(rule.id) || isAlwaysRunRule(rule)),
  );
};

interface PlaygroundOutputScoresContainerProps {
  traceId: string | null;
  notRun?: boolean;
  className?: string;
}

const PlaygroundOutputScoresContainer: React.FC<
  PlaygroundOutputScoresContainerProps
> = ({ traceId, notRun = false, className }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();
  const pickerRuleIds = useSelectedRuleIds();
  const pollingStartTimeRef = useRef<number | null>(null);

  useEffect(() => {
    pollingStartTimeRef.current = traceId ? Date.now() : null;
  }, [traceId]);

  const {
    data: rulesData,
    isSuccess: rulesLoaded,
    isError: rulesFailed,
  } = useRulesList(
    {
      workspaceName,
      projectId: activeProjectId ?? undefined,
      page: 1,
      size: 100,
    },
    {
      enabled: !!activeProjectId,
    },
  );

  const rules = useMemo(() => rulesData?.content || [], [rulesData?.content]);

  // Any trace rule may have been picked; which ones only shows once the trace loads. A loading or
  // capped list may hide one, so both fetch. A failed lookup does not, or it would poll to timeout.
  const rulesPending = !!activeProjectId && !rulesLoaded && !rulesFailed;
  const rulesTruncated = (rulesData?.total ?? 0) > rules.length;
  const mayBeScored =
    rulesPending || rulesTruncated || rules.some((rule) => isTraceRule(rule));

  const { data: trace } = useTraceById(
    { traceId: traceId! },
    {
      enabled: !!traceId && mayBeScored,
      refetchInterval: (query) => {
        const elapsed =
          Date.now() - (pollingStartTimeRef.current || Date.now());
        if (elapsed > MAX_REFETCH_TIME) return false;

        const loadedTrace = query.state.data;
        // Unloaded picks are unknown, not empty; an incomplete list may hide a scoring rule.
        if (!loadedTrace || rulesPending || rulesTruncated) {
          return REFETCH_INTERVAL;
        }

        const scoringRules = scoringRulesOf(
          rules,
          pickedRuleIdsOf(loadedTrace),
        );
        if (scoringRules.length === 0) return false;

        // A Python rule's score names can't be predicted, so an empty set is no reason to stop.
        const awaitedNames = scoreNamesOf(scoringRules);
        const receivedNames = new Set(
          (loadedTrace.feedback_scores ?? []).map((s) => s.name),
        );
        if (
          awaitedNames.length > 0 &&
          awaitedNames.every((name) => receivedNames.has(name))
        ) {
          return false;
        }

        return REFETCH_INTERVAL;
      },
    },
  );

  // The picker stands in until the trace loads, so picked metrics show pending from the start.
  // It never affects when polling stops.
  const expectedMetricNames = useMemo(
    () =>
      scoreNamesOf(
        scoringRulesOf(
          rules,
          trace ? pickedRuleIdsOf(trace) : pickerRuleIds ?? [],
        ),
      ),
    [rules, trace, pickerRuleIds],
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

  return (
    <PlaygroundOutputScores
      metricNames={allMetricNames}
      metricScores={metricScores}
      notRun={notRun}
      className={className}
    />
  );
};

export default PlaygroundOutputScoresContainer;
