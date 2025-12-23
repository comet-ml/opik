import React, { useMemo, useRef, useEffect } from "react";
import { Loader2 } from "lucide-react";

import useTraceById from "@/api/traces/useTraceById";
import useRulesList from "@/api/automations/useRulesList";
import useProjectByName from "@/api/projects/useProjectByName";
import useAppStore from "@/store/AppStore";
import FeedbackScoreTag from "@/components/shared/FeedbackScoreTag/FeedbackScoreTag";
import { cn } from "@/lib/utils";
import { TraceFeedbackScore } from "@/types/traces";
import { PLAYGROUND_PROJECT_NAME } from "@/constants/shared";
import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import { generateTagVariant } from "@/lib/traces";
import { EvaluatorsRule, EVALUATORS_RULE_TYPE } from "@/types/automations";

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
  selectedRuleIds: string[] | null;
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

  // Rules are selected if selectedRuleIds is null (all selected) or has items
  const hasSelectedRules =
    selectedRuleIds === null || selectedRuleIds.length > 0;

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

  // Get selected rules
  const selectedRules = useMemo(() => {
    if (!hasSelectedRules || rules.length === 0) return [];

    if (selectedRuleIds === null) {
      return rules;
    }

    return rules.filter((r) => selectedRuleIds.includes(r.id));
  }, [hasSelectedRules, rules, selectedRuleIds]);

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

    // Sort by rule name alphabetically
    return metrics.sort((a, b) => a.ruleName.localeCompare(b.ruleName));
  }, [rulesLoaded, selectedRules, scoresByName]);

  // Don't render anything if no rules are selected or no traceId
  if (!hasSelectedRules || !traceId) {
    return null;
  }

  // Don't render if rules loaded but no expected metrics
  if (rulesLoaded && expectedMetrics.length === 0) {
    return null;
  }

  return (
    <div
      className={cn("flex flex-wrap gap-1.5", stale && "opacity-50", className)}
    >
      {expectedMetrics.map(({ scoreName, ruleName, score }) => {
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
      })}
    </div>
  );
};

export default PlaygroundOutputScores;
