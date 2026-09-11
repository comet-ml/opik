import { useMemo } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import useRulesList from "@/api/automations/useRulesList";
import { EVALUATORS_RULE_TYPE } from "@/types/automations";
import useAppStore from "@/store/AppStore";

type EntityType = "trace" | "thread" | "span";

const STALE_TIME = 5 * 60 * 1000; // 5 minutes - rules don't change frequently

const ENTITY_TYPE_TO_RULE_TYPES: Record<EntityType, EVALUATORS_RULE_TYPE[]> = {
  trace: [EVALUATORS_RULE_TYPE.llm_judge, EVALUATORS_RULE_TYPE.python_code],
  thread: [
    EVALUATORS_RULE_TYPE.thread_llm_judge,
    EVALUATORS_RULE_TYPE.thread_python_code,
  ],
  span: [
    EVALUATORS_RULE_TYPE.span_llm_judge,
    EVALUATORS_RULE_TYPE.span_python_code,
  ],
};

type UseFilteredRulesListParams = {
  projectId: string;
  entityType: EntityType;
  enabled?: boolean;
};

export default function useFilteredRulesList({
  projectId,
  entityType,
  enabled = true,
}: UseFilteredRulesListParams) {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { data, isLoading } = useRulesList(
    {
      workspaceName,
      projectId,
      page: 1,
      size: 1000,
    },
    {
      enabled,
      placeholderData: keepPreviousData,
      staleTime: STALE_TIME,
    },
  );

  const rules = useMemo(() => {
    const allRules = data?.content || [];
    const allowedTypes = ENTITY_TYPE_TO_RULE_TYPES[entityType];
    return allRules.filter((rule) =>
      allowedTypes.includes(rule.type as EVALUATORS_RULE_TYPE),
    );
  }, [data?.content, entityType]);

  return { rules, isLoading };
}
