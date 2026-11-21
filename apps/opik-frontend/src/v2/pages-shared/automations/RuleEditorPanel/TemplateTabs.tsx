import React from "react";
import { UseFormReturn } from "react-hook-form";
import { PenLine } from "lucide-react";

import { cn } from "@/lib/utils";
import { Button } from "@/ui/button";
import IconTag from "@/shared/IconTag/IconTag";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { LLMPromptTemplate } from "@/types/llm";
import { UI_EVALUATORS_RULE_TYPE } from "@/types/automations";
import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/RuleEditorPanel/schema";
import {
  findSelectedTemplate,
  getRuleTemplateOptions,
} from "@/v2/pages-shared/automations/RuleEditorPanel/ruleTemplates";

type TemplateTabProps = {
  label: string;
  tooltip: string;
  icon: React.ComponentType<{ className?: string }>;
  iconClassName: string;
  isSelected: boolean;
  onClick: () => void;
};

const TemplateTab: React.FC<TemplateTabProps> = ({
  label,
  tooltip,
  icon,
  iconClassName,
  isSelected,
  onClick,
}) => (
  <TooltipWrapper content={tooltip}>
    <Button
      type="button"
      variant="outline"
      size="2xs"
      aria-pressed={isSelected}
      className={cn(
        "gap-1 pl-1",
        isSelected && "border-primary bg-primary-foreground",
      )}
      onClick={onClick}
    >
      <IconTag icon={icon} className={iconClassName} />
      {label}
    </Button>
  </TooltipWrapper>
);

type TemplateTabsProps = {
  form: UseFormReturn<EvaluationRuleFormType>;
  onSelectTemplate: (template: LLMPromptTemplate, label: string) => void;
  onSelectCustom: () => void;
};

const TemplateTabs: React.FC<TemplateTabsProps> = ({
  form,
  onSelectTemplate,
  onSelectCustom,
}) => {
  const messages = form.watch("llmJudgeDetails.messages");
  const schema = form.watch("llmJudgeDetails.schema");
  const uiType = form.watch("uiType");
  const options = getRuleTemplateOptions(uiType, form.watch("scope"));

  if (!messages.length || !options.length) return null;

  const selected = findSelectedTemplate({ messages, schema }, options, uiType);

  return (
    <div className="flex flex-col gap-1.5">
      <span className="comet-body-xs-accented px-0.5 text-muted-slate">
        {uiType === UI_EVALUATORS_RULE_TYPE.decision_model
          ? "Questions"
          : "Prompt"}
      </span>
      <div className="flex flex-wrap gap-1.5">
        <TemplateTab
          label="Custom"
          tooltip="Your own evaluator. Your changes stay here when you try a template."
          icon={PenLine}
          iconClassName="bg-slate-300"
          isSelected={!selected}
          onClick={onSelectCustom}
        />
        {options.map((option) => (
          <TemplateTab
            key={option.label}
            label={option.label}
            tooltip={option.description}
            icon={option.icon}
            iconClassName={option.iconClassName}
            isSelected={option === selected}
            onClick={() => onSelectTemplate(option.template, option.label)}
          />
        ))}
      </div>
    </div>
  );
};

export default TemplateTabs;
