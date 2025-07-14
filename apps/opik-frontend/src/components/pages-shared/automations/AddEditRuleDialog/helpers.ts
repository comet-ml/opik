import {
  EVALUATORS_RULE_SCOPE,
  EVALUATORS_RULE_TYPE,
  UI_EVALUATORS_RULE_TYPE,
} from "@/types/automations";

export const getUIRuleType = (ruleType: EVALUATORS_RULE_TYPE) =>
  ({
    [EVALUATORS_RULE_TYPE.llm_judge]: UI_EVALUATORS_RULE_TYPE.llm_judge,
    [EVALUATORS_RULE_TYPE.python_code]: UI_EVALUATORS_RULE_TYPE.python_code,
    [EVALUATORS_RULE_TYPE.thread_llm_judge]: UI_EVALUATORS_RULE_TYPE.llm_judge,
    [EVALUATORS_RULE_TYPE.thread_python_code]:
      UI_EVALUATORS_RULE_TYPE.python_code,
  })[ruleType];

export const getUIRuleScope = (ruleType: EVALUATORS_RULE_TYPE) =>
  ({
    [EVALUATORS_RULE_TYPE.llm_judge]: EVALUATORS_RULE_SCOPE.trace,
    [EVALUATORS_RULE_TYPE.python_code]: EVALUATORS_RULE_SCOPE.trace,
    [EVALUATORS_RULE_TYPE.thread_llm_judge]: EVALUATORS_RULE_SCOPE.thread,
    [EVALUATORS_RULE_TYPE.thread_python_code]: EVALUATORS_RULE_SCOPE.thread,
  })[ruleType];

export const getBackendRuleType = (
  scope: EVALUATORS_RULE_SCOPE,
  uiType: UI_EVALUATORS_RULE_TYPE,
) =>
  ({
    [EVALUATORS_RULE_SCOPE.trace]: {
      [UI_EVALUATORS_RULE_TYPE.llm_judge]: EVALUATORS_RULE_TYPE.llm_judge,
      [UI_EVALUATORS_RULE_TYPE.python_code]: EVALUATORS_RULE_TYPE.python_code,
    },
    [EVALUATORS_RULE_SCOPE.thread]: {
      [UI_EVALUATORS_RULE_TYPE.llm_judge]:
        EVALUATORS_RULE_TYPE.thread_llm_judge,
      [UI_EVALUATORS_RULE_TYPE.python_code]:
        EVALUATORS_RULE_TYPE.thread_python_code,
    },
  })[scope][uiType];
