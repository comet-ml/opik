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
  outputReady?: boolean;
}

const REFETCH_INTERVAL = 5000; // 5 seconds
const MAX_REFETCH_TIME = 300000; // 5 minutes max polling

const PlaygroundOutputScores: React.FC<PlaygroundOutputScoresProps> = ({
  traceId,
  selectedRuleIds,
  stale = false,
  className,
  outputReady = false,
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
  const selectingAllRules =
    selectedRuleIds == null;
  const hasSelectedRules = selectingAllRules || selectedRuleIds.length > 0;

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
    if (selectingAllRules) {
      return [];
    }

    return rules.filter((r) => selectedRuleIds.includes(r.id));
  }, [hasSelectedRules, rules, selectedRuleIds, selectingAllRules]);

  // Calculate expected score names from selected rules (for polling logic)
  const expectedScoreNames = useMemo(() => {
    const names = new Set<string>();
    for (const rule of selectedRules) {
      const scoreNames = getScoreNamesFromRule(rule);
      for (const scoreName of scoreNames) {
        names.add(scoreName);
      }
    }
    return names;
  }, [selectedRules]);

  // Track if we're waiting for specific rules to load
  // This prevents premature polling stop when rules haven't loaded yet
  // Note: if rulesLoaded is true but selectedRules is empty, the rules were deleted
  const hasSpecificRulesSelected =
    Array.isArray(selectedRuleIds) && selectedRuleIds.length > 0;
  const rulesStillLoading =
    hasSpecificRulesSelected && !rulesLoaded && selectedRules.length === 0;

  // Store values in refs for access in refetchInterval
  const expectedScoreNamesRef = useRef<Set<string>>(expectedScoreNames);
  expectedScoreNamesRef.current = expectedScoreNames;
  const rulesStillLoadingRef = useRef(rulesStillLoading);
  rulesStillLoadingRef.current = rulesStillLoading;

  // Fetch trace to get scores
  const { data: trace } = useTraceById(
    { traceId: traceId! },
    {
      enabled: !!traceId && hasSelectedRules,
      refetchInterval: (query) => {
        // Use ref to track elapsed time since polling started
        const startTime = pollingStartTimeRef.current || Date.now();
        const elapsedTime = Date.now() - startTime;

        // Always stop if we've exceeded max polling time
        if (elapsedTime > MAX_REFETCH_TIME) {
          return false;
        }

        // If rules are still loading, keep polling
        if (rulesStillLoadingRef.current) {
          return REFETCH_INTERVAL;
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

  // Build list of expected metrics with their display info, sorted by score name
  const expectedMetrics = useMemo(() => {
    const metrics: Array<{
      scoreName: string;
      score: TraceFeedbackScore | null;
    }> = [];

    // If selectedRuleIds is null/undefined (meaning "all" was selected or legacy data),
    // only show scores that actually exist in the trace - no loading spinners
    if (selectingAllRules) {
      for (const score of feedbackScores) {
        metrics.push({
          scoreName: score.name,
          score,
        });
      }
    } else {
      // Specific rules were selected - need rules to load to know expected scores
      if (!rulesLoaded) return [];

      for (const rule of selectedRules) {
        const scoreNames = getScoreNamesFromRule(rule);
        for (const scoreName of scoreNames) {
          metrics.push({
            scoreName,
            score: scoresByName[scoreName] || null,
          });
        }
      }
    }

    // Sort by score name alphabetically
    return metrics.sort((a, b) => a.scoreName.localeCompare(b.scoreName));
  }, [
    rulesLoaded,
    selectedRules,
    scoresByName,
    selectingAllRules,
    feedbackScores,
  ]);

  // Don't render anything if no rules are selected
  if (!hasSelectedRules) {
    return null;
  }

  // For specific rules selected: show when output is ready (even before traceId)
  // For "all" selection: only show when we have traceId (to get actual scores)
  const hasSpecificRules = !selectingAllRules;
  const canShowMetrics = traceId || (outputReady && hasSpecificRules);

  if (!canShowMetrics) {
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
    score,
  }: {
    scoreName: string;
    score: TraceFeedbackScore | null;
  }) => {
    const variant = generateTagVariant(scoreName);
    const color = (variant && TAG_VARIANTS_COLOR_MAP[variant]) || "#64748b";

    // Score has loaded - show the actual value
    if (score) {
      return (
        <FeedbackScoreTag
          key={scoreName}
          label={scoreName}
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
          {scoreName}
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
            <div
              className="comet-body-s-accented flex h-6 cursor-pointer items-center rounded-md border border-border px-1.5 text-muted-slate"
              tabIndex={0}
            >
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
