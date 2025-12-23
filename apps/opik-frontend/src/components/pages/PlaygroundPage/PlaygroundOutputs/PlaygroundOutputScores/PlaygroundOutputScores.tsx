import React, { useMemo, useRef, useEffect } from "react";
import { Loader2 } from "lucide-react";

import useTraceById from "@/api/traces/useTraceById";
import useRulesList from "@/api/automations/useRulesList";
import useProjectByName from "@/api/projects/useProjectByName";
import useAppStore from "@/store/AppStore";
import FeedbackScoreTag from "@/components/shared/FeedbackScoreTag/FeedbackScoreTag";
import {
  HoverCard,
  HoverCardContent,
  HoverCardTrigger,
} from "@/components/ui/hover-card";
import { cn } from "@/lib/utils";
import { TraceFeedbackScore } from "@/types/traces";
import { PLAYGROUND_PROJECT_NAME } from "@/constants/shared";
import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import { generateTagVariant } from "@/lib/traces";
import { EvaluatorsRule, EVALUATORS_RULE_TYPE } from "@/types/automations";

const MAX_VISIBLE_METRICS = 3;

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

interface PlaygroundOutputScoresProps {
  traceId: string | null;
  selectedRuleIds: string[] | null | undefined;
  stale?: boolean;
  className?: string;
}

const REFETCH_INTERVAL = 5000; // 5 seconds
const MAX_REFETCH_TIME = 300000; // 5 minutes max polling

const PlaygroundOutputScores: React.FC<PlaygroundOutputScoresProps> = ({
  traceId,
  selectedRuleIds,
  stale = false,
  className,
}) => {
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
  const hasSelectedRules =
    selectedRuleIds === undefined ||
    selectedRuleIds === null ||
    selectedRuleIds.length > 0;

  // Fetch playground project to get rules
  const { data: playgroundProject } = useProjectByName(
    { projectName: PLAYGROUND_PROJECT_NAME },
    { enabled: !!workspaceName && hasSelectedRules },
  );

  // Fetch rules to map score names to rule names
  const { data: rulesData, isSuccess: rulesLoaded } = useRulesList(
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

  // Get selected rules - only use specific rule IDs, not "all"
  // When selectedRuleIds is null/undefined (meaning "all" was selected or legacy data),
  // we'll only show scores that actually exist in the trace, not loading spinners
  const selectedRules = useMemo(() => {
    if (!hasSelectedRules || rules.length === 0) return [];

    // If selectedRuleIds is null or undefined, return empty - we'll handle this case
    // differently by only showing actual scores from the trace
    if (selectedRuleIds === null || selectedRuleIds === undefined) {
      return [];
    }

    return rules.filter((r) => selectedRuleIds.includes(r.id));
  }, [hasSelectedRules, rules, selectedRuleIds]);

  // When "all" was selected (null), we need to know which rules produced the scores
  // to display the rule name instead of the score name
  const scoreNameToRuleName = useMemo(() => {
    const mapping: Record<string, string> = {};
    for (const rule of rules) {
      const scoreNames = getScoreNamesFromRule(rule);
      for (const scoreName of scoreNames) {
        mapping[scoreName] = rule.name;
      }
    }
    return mapping;
  }, [rules]);

  // Fetch trace to get scores
  const { data: trace } = useTraceById(
    { traceId: traceId! },
    {
      enabled: !!traceId && hasSelectedRules,
      refetchInterval: (query) => {
        // Stop refetching if we have scores or exceeded max time
        const data = query.state.data;
        const hasScores =
          data?.feedback_scores && data.feedback_scores.length > 0;

        // Use ref to track elapsed time since polling started
        const startTime = pollingStartTimeRef.current || Date.now();
        const elapsedTime = Date.now() - startTime;

        if (hasScores || elapsedTime > MAX_REFETCH_TIME) {
          return false;
        }
        return REFETCH_INTERVAL;
      },
    },
  );

  const feedbackScores: TraceFeedbackScore[] = useMemo(() => {
    return trace?.feedback_scores ?? [];
  }, [trace?.feedback_scores]);

  // Create a map from score name to score for quick lookup
  const scoresByName = useMemo(() => {
    const map: Record<string, TraceFeedbackScore> = {};
    for (const score of feedbackScores) {
      map[score.name] = score;
    }
    return map;
  }, [feedbackScores]);

  // Build list of expected metrics with their display info, sorted by rule name
  const expectedMetrics = useMemo(() => {
    if (!rulesLoaded) return [];

    const metrics: Array<{
      scoreName: string;
      ruleName: string;
      score: TraceFeedbackScore | null;
    }> = [];

    // If selectedRuleIds is null/undefined (meaning "all" was selected or legacy data),
    // only show scores that actually exist in the trace - no loading spinners
    if (selectedRuleIds === null || selectedRuleIds === undefined) {
      for (const score of feedbackScores) {
        const ruleName = scoreNameToRuleName[score.name] || score.name;
        metrics.push({
          scoreName: score.name,
          ruleName,
          score,
        });
      }
    } else {
      // Specific rules were selected - show loading spinners for expected scores
      for (const rule of selectedRules) {
        const scoreNames = getScoreNamesFromRule(rule);
        for (const scoreName of scoreNames) {
          metrics.push({
            scoreName,
            ruleName: rule.name,
            score: scoresByName[scoreName] || null,
          });
        }
      }
    }

    // Sort by rule name alphabetically
    return metrics.sort((a, b) => a.ruleName.localeCompare(b.ruleName));
  }, [
    rulesLoaded,
    selectedRules,
    scoresByName,
    selectedRuleIds,
    feedbackScores,
    scoreNameToRuleName,
  ]);

  // Don't render anything if no rules are selected or no traceId
  if (!hasSelectedRules || !traceId) {
    return null;
  }

  // Don't render if rules loaded but no expected metrics (and we have specific rules selected)
  if (
    rulesLoaded &&
    expectedMetrics.length === 0 &&
    selectedRuleIds !== null &&
    selectedRuleIds !== undefined
  ) {
    return null;
  }

  const visibleMetrics = expectedMetrics.slice(0, MAX_VISIBLE_METRICS);
  const hiddenMetrics = expectedMetrics.slice(MAX_VISIBLE_METRICS);
  const remainingCount = hiddenMetrics.length;

  const renderMetric = ({
    scoreName,
    ruleName,
    score,
  }: {
    scoreName: string;
    ruleName: string;
    score: TraceFeedbackScore | null;
  }) => {
    const variant = generateTagVariant(ruleName);
    const color = (variant && TAG_VARIANTS_COLOR_MAP[variant]) || "#64748b";

    // Score has loaded - show the actual value
    if (score) {
      return (
        <FeedbackScoreTag
          key={scoreName}
          label={ruleName}
          value={score.value}
          reason={score.reason}
          lastUpdatedAt={score.last_updated_at}
          lastUpdatedBy={score.last_updated_by}
          valueByAuthor={score.value_by_author}
          category={score.category_name}
        />
      );
    }

    // Score still loading - show placeholder with spinner
    return (
      <div
        key={scoreName}
        className="flex h-6 items-center gap-1.5 rounded-md border border-border px-2"
      >
        <div
          className="rounded-[0.15rem] bg-[var(--bg-color)] p-1"
          style={{ "--bg-color": color } as React.CSSProperties}
        />
        <span className="comet-body-s-accented truncate text-muted-slate">
          {ruleName}
        </span>
        <Loader2 className="size-3 animate-spin text-muted-slate" />
      </div>
    );
  };

  return (
    <div
      className={cn("flex flex-wrap gap-1.5", stale && "opacity-50", className)}
    >
      {visibleMetrics.map(renderMetric)}
      {remainingCount > 0 && (
        <HoverCard openDelay={200}>
          <HoverCardTrigger asChild>
            <div className="comet-body-s-accented flex h-6 cursor-default items-center rounded-md border border-border px-1.5 text-muted-slate">
              +{remainingCount}
            </div>
          </HoverCardTrigger>
          <HoverCardContent
            side="top"
            align="start"
            className="w-auto max-w-[300px]"
          >
            <div className="flex flex-wrap gap-1.5">
              {hiddenMetrics.map(renderMetric)}
            </div>
          </HoverCardContent>
        </HoverCard>
      )}
    </div>
  );
};

export default PlaygroundOutputScores;
