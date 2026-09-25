import React from "react";
import { Bot, Code, Plus } from "lucide-react";

import { Button, ButtonProps } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { UI_EVALUATORS_RULE_TYPE } from "@/types/automations";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";

export const RULE_TYPE_OPTIONS: {
  value: UI_EVALUATORS_RULE_TYPE;
  label: string;
  description: string;
  Icon: React.ComponentType<{ className?: string }>;
}[] = [
  {
    value: UI_EVALUATORS_RULE_TYPE.llm_judge,
    label: "LLM-as-judge",
    description:
      "Use an LLM to assess qualities like clarity, relevance, and accuracy.",
    Icon: Bot,
  },
  {
    value: UI_EVALUATORS_RULE_TYPE.python_code,
    label: "Code metric",
    description:
      "Use Python to check exact matches, keywords, and custom rules.",
    Icon: Code,
  },
];

type CreateRuleMenuProps = {
  onSelect: (uiType: UI_EVALUATORS_RULE_TYPE) => void;
  label?: string;
  variant?: ButtonProps["variant"];
  size?: ButtonProps["size"];
  className?: string;
  align?: "start" | "end";
  testId?: string;
};

/**
 * "Create rule" entry point: the rule type is picked here, before the panel
 * opens, so the panel itself is a plain LLM-as-judge or code-metric form.
 * Collapses to a single button when code metrics are disabled for the workspace.
 */
const CreateRuleMenu: React.FC<CreateRuleMenuProps> = ({
  onSelect,
  label = "Create rule",
  variant = "default",
  size = "xs",
  className,
  align = "end",
  testId,
}) => {
  const isCodeMetricEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.PYTHON_EVALUATOR_ENABLED,
  );

  const trigger = (
    <Button
      variant={variant}
      size={size}
      className={className}
      data-testid={testId}
      onClick={
        isCodeMetricEnabled
          ? undefined
          : () => onSelect(UI_EVALUATORS_RULE_TYPE.llm_judge)
      }
    >
      <Plus className="mr-1 size-4" />
      {label}
    </Button>
  );

  if (!isCodeMetricEnabled) {
    return trigger;
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>{trigger}</DropdownMenuTrigger>
      <DropdownMenuContent align={align} className="w-72 p-1">
        {RULE_TYPE_OPTIONS.map(({ value, label, description, Icon }) => (
          <DropdownMenuItem
            key={value}
            onClick={() => onSelect(value)}
            className="h-auto items-start gap-2 py-2"
          >
            <Icon className="mt-0.5 size-4 shrink-0 text-muted-slate" />
            <div className="flex min-w-0 flex-col gap-0.5">
              <span className="comet-body-s-accented">{label}</span>
              <span className="comet-body-xs whitespace-normal text-muted-slate">
                {description}
              </span>
            </div>
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default CreateRuleMenu;
