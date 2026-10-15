import { useMemo } from "react";
import pick from "lodash/pick";

import useTracesOrSpansList, {
  TRACE_DATA_TYPE,
} from "@/hooks/useTracesOrSpansList";
import { EVALUATORS_RULE_SCOPE } from "@/types/automations";
import { JsonObject } from "@/types/shared";
import { LOGS_SOURCE } from "@/types/traces";
import { mergeSampleEntities } from "@/lib/traces";
import { SCOPE_ENTITY_LABEL } from "@/v2/pages-shared/automations/RuleEditorPanel/constants";

const SAMPLE_SIZE = 100;
const SAMPLE_ROOT_KEYS = ["input", "output", "metadata"];

const RESERVED_VARIABLES: Partial<Record<EVALUATORS_RULE_SCOPE, JsonObject>> = {
  [EVALUATORS_RULE_SCOPE.trace]: {
    trace: "The full trace, with its spans",
    spans: "All the spans of the trace",
  },
  [EVALUATORS_RULE_SCOPE.span]: {
    span: "The full span",
  },
};

const DECISION_MODEL_RESERVED_VARIABLES: Partial<
  Record<EVALUATORS_RULE_SCOPE, JsonObject>
> = {
  [EVALUATORS_RULE_SCOPE.trace]: {
    spans: "All the spans of the trace",
  },
};

export type RuleSampleData = {
  treeData: JsonObject;
  emptyState: string;
};

const useRuleSampleData = (
  projectId: string,
  scope: EVALUATORS_RULE_SCOPE,
  isDecisionModelRule = false,
): RuleSampleData | null => {
  const isThreadScope = scope === EVALUATORS_RULE_SCOPE.thread;
  const enabled = Boolean(projectId) && !isThreadScope;
  const entityLabel = SCOPE_ENTITY_LABEL[scope];

  const { data, isPending } = useTracesOrSpansList(
    {
      projectId,
      type:
        scope === EVALUATORS_RULE_SCOPE.span
          ? TRACE_DATA_TYPE.spans
          : TRACE_DATA_TYPE.traces,
      page: 1,
      size: SAMPLE_SIZE,
      // The backend returns a truncated input or output as a plain string, which hides its fields.
      truncate: false,
      stripAttachments: true,
      logsSource: LOGS_SOURCE.sdk,
    },
    { enabled },
  );

  return useMemo(() => {
    if (isThreadScope) return null;

    const treeData = mergeSampleEntities(
      (data?.content ?? []).map((entity) => pick(entity, SAMPLE_ROOT_KEYS)),
    );

    return {
      treeData: {
        ...treeData,
        ...(isDecisionModelRule
          ? DECISION_MODEL_RESERVED_VARIABLES
          : RESERVED_VARIABLES)[scope],
      },
      emptyState:
        enabled && isPending
          ? `Loading fields from the latest ${entityLabel}...`
          : `No ${entityLabel} to show fields from yet. Type a path, for example input.question.`,
    };
  }, [
    isThreadScope,
    enabled,
    isPending,
    data,
    entityLabel,
    scope,
    isDecisionModelRule,
  ]);
};

export default useRuleSampleData;
