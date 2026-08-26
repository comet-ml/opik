import React, { useMemo, useRef, useEffect } from "react";

import useTraceById from "@/api/traces/useTraceById";
import useRulesList from "@/api/automations/useRulesList";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import { getScoreNamesFromRule } from "@/lib/rules";
import {
  EVAL_TRIGGER_SCOPE,
  EVALUATORS_RULE_TYPE,
  EvaluatorsRule,
} from "@/types/automations";
import PlaygroundOutputScores, { ScoreData } from "./PlaygroundOutputScores";

const REFETCH_INTERVAL = 5000;
const MAX_REFETCH_TIME = 300000;

// Thread and span rules write their scores elsewhere, so their names would never arrive on the
// trace and would keep the cell polling to the timeout.
const TRACE_RULE_TYPES: string[] = [
  EVALUATORS_RULE_TYPE.llm_judge,
  EVALUATORS_RULE_TYPE.python_code,
];

const scoreNamesOf = (rules: EvaluatorsRule[]) =>
  [...new Set(rules.flatMap((rule) => getScoreNamesFromRule(rule)))].sort(
    (a, b) => a.localeCompare(b),
  );

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
  const activeProjectId = useActiveProjectId();
  const pollingStartTimeRef = useRef<number | null>(null);

  useEffect(() => {
    pollingStartTimeRef.current = traceId ? Date.now() : null;
  }, [traceId]);

  const { data: rulesData, isSuccess: rulesLoaded } = useRulesList(
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

  const selectedRuleIdsSet = useMemo(
    () => new Set(selectedRuleIds ?? []),
    [selectedRuleIds],
  );

  // Only the selected rules are known to run up front, so only they get a pending tag.
  const selectedRules = useMemo(
    () => rules.filter((rule) => selectedRuleIdsSet.has(rule.id)),
    [rules, selectedRuleIdsSet],
  );

  // A dataset run is logged as an experiment trace, so every enabled rule targeting experiments
  // scores it too. Those scores arrive without being announced, so polling has to wait for them
  // as well or the cell stops refetching before they land.
  const scoringRules = useMemo(
    () =>
      rules.filter(
        (rule) =>
          TRACE_RULE_TYPES.includes(rule.type) &&
          (selectedRuleIdsSet.has(rule.id) ||
            (rule.enabled !== false &&
              (rule.trigger_scope === EVAL_TRIGGER_SCOPE.experiment ||
                rule.trigger_scope === EVAL_TRIGGER_SCOPE.both))),
      ),
    [rules, selectedRuleIdsSet],
  );

  // With no rule to score this trace there is nothing to poll for. Until the rules arrive we
  // cannot tell, so the query stays enabled while the list is still loading.
  const rulesPending = !!activeProjectId && !rulesLoaded;
  const hasScoringRules = rulesPending || scoringRules.length > 0;

  const expectedMetricNames = useMemo(
    () => scoreNamesOf(selectedRules),
    [selectedRules],
  );

  const awaitedScoreNames = useMemo(
    () => new Set(scoreNamesOf(scoringRules)),
    [scoringRules],
  );

  const awaitedScoreNamesRef = useRef(awaitedScoreNames);
  awaitedScoreNamesRef.current = awaitedScoreNames;

  const { data: trace } = useTraceById(
    { traceId: traceId! },
    {
      enabled: !!traceId && hasScoringRules,
      refetchInterval: (query) => {
        const elapsed =
          Date.now() - (pollingStartTimeRef.current || Date.now());
        if (elapsed > MAX_REFETCH_TIME) return false;

        const receivedScores = query.state.data?.feedback_scores ?? [];
        const awaitedNames = awaitedScoreNamesRef.current;

        if (awaitedNames.size > 0) {
          const receivedNames = new Set(receivedScores.map((s) => s.name));
          if ([...awaitedNames].every((name) => receivedNames.has(name))) {
            return false;
          }
        }
        // The awaited set can still be empty while rules are scoring — a Python rule's score
        // names cannot be extracted statically — so an empty set is not a reason to stop. The
        // case where nothing scores the trace at all is handled by the `enabled` gate above.

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
