import React, { useCallback } from "react";
import { useNavigate } from "@tanstack/react-router";

import { ToastAction } from "@/ui/toast";
import { useToast } from "@/ui/use-toast";
import useAppStore from "@/store/AppStore";
import { EVALUATORS_RULE_SCOPE } from "@/types/automations";
import { LOGS_TYPE } from "@/constants/traces";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/v2/constants/explainers";

const EXPLAINER_ID_BY_SCOPE: Record<EVALUATORS_RULE_SCOPE, EXPLAINER_ID> = {
  [EVALUATORS_RULE_SCOPE.trace]:
    EXPLAINER_ID.i_added_edited_a_new_online_evaluation_rule_now_what,
  [EVALUATORS_RULE_SCOPE.thread]:
    EXPLAINER_ID.i_added_edited_a_new_online_evaluation_thread_level_rule_now_what,
  [EVALUATORS_RULE_SCOPE.span]:
    EXPLAINER_ID.i_added_edited_a_new_online_evaluation_span_level_rule_now_what,
};

const LOGS_TYPE_BY_SCOPE: Record<EVALUATORS_RULE_SCOPE, LOGS_TYPE> = {
  [EVALUATORS_RULE_SCOPE.trace]: LOGS_TYPE.traces,
  [EVALUATORS_RULE_SCOPE.thread]: LOGS_TYPE.threads,
  [EVALUATORS_RULE_SCOPE.span]: LOGS_TYPE.spans,
};

const useRuleCreatedToast = () => {
  const { toast } = useToast();
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  return useCallback(
    (scope: EVALUATORS_RULE_SCOPE, projectIds: string[]) => {
      const explainer = EXPLAINERS_MAP[EXPLAINER_ID_BY_SCOPE[scope]];
      const actions =
        projectIds.length === 1
          ? [
              <ToastAction
                variant="link"
                size="sm"
                className="px-0"
                altText="Go to project"
                key="Go to project"
                onClick={() =>
                  navigate({
                    to: "/$workspaceName/projects/$projectId/logs",
                    params: { projectId: projectIds[0], workspaceName },
                    search: { logsType: LOGS_TYPE_BY_SCOPE[scope] },
                  })
                }
              >
                Go to project
              </ToastAction>,
            ]
          : undefined;

      toast({
        title: explainer.title,
        description: explainer.description,
        actions,
      });
    },
    [toast, navigate, workspaceName],
  );
};

export default useRuleCreatedToast;
