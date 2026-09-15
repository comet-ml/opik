import React, { useMemo, useRef, useEffect } from "react";

import useTraceById from "@/api/traces/useTraceById";
import useRulesList from "@/api/automations/useRulesList";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import { getScoreNamesFromRule } from "@/lib/rules";
import { EvaluatorsRule } from "@/types/automations";
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

  const selectedRuleIdsSet = useMemo(
    () => new Set(selectedRuleIds ?? []),
    [selectedRuleIds],
  );

  // A dataset run is logged as an experiment trace, so every enabled rule targeting experiments
  // scores it alongside the picked ones. Both sets are known up front, so both get a pending tag
  // and both are awaited, or the cell would stop refetching before the unpicked scores land.
  const scoringRules = useMemo(
    () =>
      rules.filter(
        (rule) =>
          isTraceRule(rule) &&
          (selectedRuleIdsSet.has(rule.id) || isAlwaysRunRule(rule)),
      ),
    [rules, selectedRuleIdsSet],
  );

  // With no rule to score this trace there is nothing to poll for. Two cases leave us unable to
  // tell: the list is still loading, or it is capped at one page and a scoring rule may sit beyond
  // it. Both keep polling. A failed lookup stops it, since guessing would poll to the timeout.
  const rulesPending = !!activeProjectId && !rulesLoaded && !rulesFailed;
  const rulesTruncated = (rulesData?.total ?? 0) > rules.length;
  const hasScoringRules =
    rulesPending || rulesTruncated || scoringRules.length > 0;

  const expectedMetricNames = useMemo(
    () => scoreNamesOf(scoringRules),
    [scoringRules],
  );

  const awaitedScoreNames = useMemo(
    () => new Set(expectedMetricNames),
    [expectedMetricNames],
  );

  const awaitedScoreNamesRef = useRef(awaitedScoreNames);
  awaitedScoreNamesRef.current = awaitedScoreNames;

  const rulesTruncatedRef = useRef(rulesTruncated);
  rulesTruncatedRef.current = rulesTruncated;

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

        // Those names come from page one of the rules list. When the list is capped, a rule
        // beyond it may still score this trace, so they are not a complete stop condition.
        if (awaitedNames.size > 0 && !rulesTruncatedRef.current) {
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
