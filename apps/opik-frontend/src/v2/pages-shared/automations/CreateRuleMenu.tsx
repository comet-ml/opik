import React from "react";
import { BrainCircuit, CircleHelp, SquareFunction } from "lucide-react";

import CreateMenuButton, {
  CreateMenuOption,
} from "@/v2/pages-shared/CreateMenuButton/CreateMenuButton";
import { UI_EVALUATORS_RULE_TYPE } from "@/types/automations";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";

const LLM_JUDGE_OPTION: CreateMenuOption<UI_EVALUATORS_RULE_TYPE> = {
  value: UI_EVALUATORS_RULE_TYPE.llm_judge,
  label: "LLM-as-a-Judge",
  description:
    "Use an LLM to assess qualities like clarity, relevance, and accuracy.",
  icon: BrainCircuit,
  iconClassName: "bg-[var(--rule-icon-llm-judge)]",
};

const CODE_EVALUATOR_OPTION: CreateMenuOption<UI_EVALUATORS_RULE_TYPE> = {
  value: UI_EVALUATORS_RULE_TYPE.python_code,
  label: "Code evaluator",
  description: "Use Python to check exact matches, keywords, and custom rules.",
  icon: SquareFunction,
  iconClassName: "bg-[var(--rule-icon-code)]",
};

const DECISION_MODEL_OPTION: CreateMenuOption<UI_EVALUATORS_RULE_TYPE> = {
  value: UI_EVALUATORS_RULE_TYPE.decision_model,
  label: "Decision model",
  description:
    "Answer yes/no questions about your traces, with a probability for each answer.",
  icon: CircleHelp,
  iconClassName: "bg-[var(--rule-icon-decision-model)]",
};

type CreateRuleMenuProps = {
  onSelect: (type: UI_EVALUATORS_RULE_TYPE) => void;
};

const CreateRuleMenu: React.FC<CreateRuleMenuProps> = ({ onSelect }) => {
  const isPythonEvaluatorEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.PYTHON_EVALUATOR_ENABLED,
  );

  return (
    <CreateMenuButton
      label="Evaluator"
      options={
        isPythonEvaluatorEnabled
          ? [LLM_JUDGE_OPTION, DECISION_MODEL_OPTION, CODE_EVALUATOR_OPTION]
          : [LLM_JUDGE_OPTION, DECISION_MODEL_OPTION]
      }
      onSelect={onSelect}
      data-testid="online-evaluation-create-rule-button"
    />
  );
};

export default CreateRuleMenu;
