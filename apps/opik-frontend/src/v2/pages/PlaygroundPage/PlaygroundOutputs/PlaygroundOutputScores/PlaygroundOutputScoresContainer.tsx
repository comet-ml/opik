import React, { useMemo, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import isArray from "lodash/isArray";

import useTraceById from "@/api/traces/useTraceById";
import useRulesList from "@/api/automations/useRulesList";
import useOnlineScoringStatuses, {
  onlineScoringStatusesKey,
  UseOnlineScoringStatusesResponse,
} from "@/api/automations/useOnlineScoringStatuses";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import { useSelectedRuleIds } from "@/store/PlaygroundStore";
import { getScoreNamesFromRule } from "@/lib/rules";
import { EvaluatorsRule, OnlineScoringStatus } from "@/types/automations";
import { Trace } from "@/types/traces";
import {
  isAlwaysRunRule,
  isTraceRule,
} from "@/v2/pages/PlaygroundPage/metricSelection";
import PlaygroundOutputScores, { ScoreData } from "./PlaygroundOutputScores";
import {
  REFETCH_INTERVAL,
  isFinalStatus,
  isRetryingRecently,
  lateWaitEndOf,
  scoringPollInterval,
  useWaitOver,
  waitEndOf,
} from "./scoringStatus";

const scoreNamesOf = (rules: EvaluatorsRule[]) =>
  [...new Set(rules.flatMap((rule) => getScoreNamesFromRule(rule)))].sort(
    (a, b) => a.localeCompare(b),
  );

const pickedRuleIdsOf = (trace: Trace): string[] => {
  const picked: unknown = get(trace.metadata, "selected_rule_ids");
  return isArray(picked) ? picked : [];
};

const scoringRulesOf = (rules: EvaluatorsRule[], pickedRuleIds: string[]) => {
  const pickedIds = new Set(pickedRuleIds);
  return rules.filter(
    (rule) =>
      isTraceRule(rule) && (pickedIds.has(rule.id) || isAlwaysRunRule(rule)),
  );
};

const statusByRuleIdOf = (
  statuses: UseOnlineScoringStatusesResponse | undefined,
): Record<string, OnlineScoringStatus> =>
  Object.fromEntries(
    (statuses?.content ?? []).map((status) => [status.rule_id, status]),
  );

// The rules the trace still waits on: those that have neither stored their scores nor said they never will.
const pendingRulesOf = (
  trace: Trace,
  rules: EvaluatorsRule[],
  statusByRuleId: Record<string, OnlineScoringStatus>,
) => {
  const scoringRules = scoringRulesOf(rules, pickedRuleIdsOf(trace));
  const knownNames = scoreNamesOf(scoringRules);
  const receivedNames = new Set(
    (trace.feedback_scores ?? []).map((s) => s.name),
  );
  return scoringRules.filter((rule) => {
    const status = statusByRuleId[rule.id];
    if (isFinalStatus(status)) return false;
    const names = getScoreNamesFromRule(rule);
    // A Python rule's score names can't be predicted, so only its status or the lack of anything
    // else to wait on keeps it pending.
    if (names.length === 0) return knownNames.length === 0 || !!status;
    return names.some((name) => !receivedNames.has(name));
  });
};

const pollIntervalOf = (
  trace: Trace | undefined,
  rulesIncomplete: boolean,
  rules: EvaluatorsRule[],
  statusByRuleId: Record<string, OnlineScoringStatus>,
  waitEnd: number,
  lateWaitEnd: number,
) => {
  // Unloaded picks are unknown, not empty; an incomplete list may hide a scoring rule.
  if (!trace || rulesIncomplete) {
    return waitEnd > Date.now() ? REFETCH_INTERVAL : false;
  }

  const pending = pendingRulesOf(trace, rules, statusByRuleId);
  if (pending.length === 0) return false;
  return scoringPollInterval({
    waitEnd,
    lateWaitEnd,
    retrying: pending.some((rule) =>
      isRetryingRecently(statusByRuleId[rule.id]),
    ),
    // Only a rule whose score names are known is still owed a score: a Python rule with no status may well have
    // scored already.
    owed: pending.some((rule) => getScoreNamesFromRule(rule).length > 0),
  });
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
  const [openedAt] = useState(Date.now);
  const queryClient = useQueryClient();

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

  const rulesIncomplete = rulesPending || rulesTruncated;

  const { data: trace } = useTraceById(
    { traceId: traceId! },
    {
      enabled: !!traceId && mayBeScored,
      refetchInterval: (query) =>
        pollIntervalOf(
          query.state.data,
          rulesIncomplete,
          rules,
          // The statuses query is declared below, as it waits on the trace, so read it from the cache.
          statusByRuleIdOf(
            queryClient.getQueryData<UseOnlineScoringStatusesResponse>(
              onlineScoringStatusesKey(traceId!),
            ),
          ),
          waitEndOf(query.state.data?.end_time, openedAt),
          lateWaitEndOf(query.state.data?.end_time, openedAt),
        ),
    },
  );

  const waitEnd = waitEndOf(trace?.end_time, openedAt);
  const lateWaitEnd = lateWaitEndOf(trace?.end_time, openedAt);

  const traceScoringRules = useMemo(
    () => (trace ? scoringRulesOf(rules, pickedRuleIdsOf(trace)) : []),
    [rules, trace],
  );

  const { data: statusesData } = useOnlineScoringStatuses(
    { traceId: traceId! },
    {
      enabled: !!traceId && !!trace && traceScoringRules.length > 0,
      refetchInterval: (query) =>
        pollIntervalOf(
          trace,
          rulesIncomplete,
          rules,
          statusByRuleIdOf(query.state.data),
          waitEnd,
          lateWaitEnd,
        ),
    },
  );

  const statusByRuleId = useMemo(
    () => statusByRuleIdOf(statusesData),
    [statusesData],
  );

  const waitOver = useWaitOver(waitEnd);

  // Only the rules scoring this trace, as another rule may emit the same score name.
  const metricRuleIds = useMemo(
    () =>
      Object.fromEntries(
        traceScoringRules.flatMap((rule) =>
          getScoreNamesFromRule(rule).map((name) => [name, rule.id]),
        ),
      ),
    [traceScoringRules],
  );

  // The picker stands in until the trace loads, so picked metrics show pending from the start.
  // It never affects when polling stops. A row that was not run never gets a trace, and the
  // picker is free to change once the run is over, so it does not stand in there.
  const expectedMetricNames = useMemo(() => {
    const pickedRuleIds = trace
      ? pickedRuleIdsOf(trace)
      : notRun
        ? []
        : (pickerRuleIds ?? []);
    return scoreNamesOf(scoringRulesOf(rules, pickedRuleIds));
  }, [rules, trace, notRun, pickerRuleIds]);

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

  // A rule whose score names can't be predicted gets a chip named after it, so its status shows.
  const unnamedRuleStatuses = useMemo(
    () =>
      Object.fromEntries(
        traceScoringRules
          .filter(
            (rule) =>
              getScoreNamesFromRule(rule).length === 0 &&
              statusByRuleId[rule.id],
          )
          .map((rule) => [rule.name, statusByRuleId[rule.id]]),
      ),
    [traceScoringRules, statusByRuleId],
  );

  // Combine expected metric names (from rule analysis) with actual score names (from trace)
  // This ensures Python evaluator scores are shown even if they couldn't be predicted
  const allMetricNames = useMemo(() => {
    const actualScoreNames = Object.keys(metricScores);
    const combined = new Set([
      ...expectedMetricNames,
      ...actualScoreNames,
      ...Object.keys(unnamedRuleStatuses),
    ]);
    return [...combined].sort((a, b) => a.localeCompare(b));
  }, [expectedMetricNames, metricScores, unnamedRuleStatuses]);

  const metricStatuses = useMemo(
    () => ({
      ...unnamedRuleStatuses,
      ...Object.fromEntries(
        allMetricNames.flatMap((name) => {
          const status = statusByRuleId[metricRuleIds[name]];
          return status ? [[name, status]] : [];
        }),
      ),
    }),
    [allMetricNames, metricRuleIds, statusByRuleId, unnamedRuleStatuses],
  );

  return (
    <PlaygroundOutputScores
      metricNames={allMetricNames}
      metricScores={metricScores}
      metricRuleIds={metricRuleIds}
      metricStatuses={metricStatuses}
      waitOver={waitOver && !!traceId}
      notRun={notRun}
      className={className}
    />
  );
};

export default PlaygroundOutputScoresContainer;
