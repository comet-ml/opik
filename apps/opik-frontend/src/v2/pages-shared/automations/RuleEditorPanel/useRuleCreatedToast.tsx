import React, { useCallback } from "react";
import { useNavigate } from "@tanstack/react-router";

import { ToastAction } from "@/ui/toast";
import { useToast } from "@/ui/use-toast";
import useAppStore from "@/store/AppStore";
import { EVAL_TRIGGER_SCOPE, EVALUATORS_RULE_SCOPE } from "@/types/automations";
import { LOGS_TYPE } from "@/constants/traces";

const LOGS_PAGE = "See the scores on the Logs page.";

const TRACE_DESCRIPTION_BY_TRIGGER_SCOPE: Record<EVAL_TRIGGER_SCOPE, string> = {
  [EVAL_TRIGGER_SCOPE.production]: `This rule now scores new production traces. ${LOGS_PAGE}`,
  [EVAL_TRIGGER_SCOPE.experiment]:
    "This rule now scores new experiment traces. See the scores in the experiment results.",
  [EVAL_TRIGGER_SCOPE.both]:
    "This rule now scores new production and experiment traces. See the scores on the Logs page and in the experiment results.",
};

const getDescription = (
  scope: EVALUATORS_RULE_SCOPE,
  triggerScope: EVAL_TRIGGER_SCOPE,
) => {
  if (scope === EVALUATORS_RULE_SCOPE.thread) {
    return `This rule now scores new threads. ${LOGS_PAGE}`;
  }
  if (scope === EVALUATORS_RULE_SCOPE.span) {
    return `This rule now scores new spans. ${LOGS_PAGE}`;
  }
  return TRACE_DESCRIPTION_BY_TRIGGER_SCOPE[triggerScope];
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
    (
      scope: EVALUATORS_RULE_SCOPE,
      triggerScope: EVAL_TRIGGER_SCOPE,
      projectIds: string[],
      enabled: boolean,
    ) => {
      if (!enabled) {
        toast({
          title: "Evaluation rule saved",
          description:
            "This rule is disabled, so it does not score anything. Enable it to start scoring.",
        });
        return;
      }
      const hasLogs =
        scope !== EVALUATORS_RULE_SCOPE.trace ||
        triggerScope !== EVAL_TRIGGER_SCOPE.experiment;
      const actions =
        hasLogs && projectIds.length === 1
          ? [
              <ToastAction
                variant="link"
                size="sm"
                className="px-0"
                altText="Go to logs"
                key="Go to logs"
                onClick={() =>
                  navigate({
                    to: "/$workspaceName/projects/$projectId/logs",
                    params: { projectId: projectIds[0], workspaceName },
                    search: { logsType: LOGS_TYPE_BY_SCOPE[scope] },
                  })
                }
              >
                Go to logs
              </ToastAction>,
            ]
          : undefined;

      toast({
        title: "Evaluation rule set",
        description: getDescription(scope, triggerScope),
        actions,
      });
    },
    [toast, navigate, workspaceName],
  );
};

export default useRuleCreatedToast;
