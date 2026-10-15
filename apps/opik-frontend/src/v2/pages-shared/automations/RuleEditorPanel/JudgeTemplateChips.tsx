import React from "react";

import { EVALUATORS_RULE_SCOPE } from "@/types/automations";
import { LLMPromptTemplate } from "@/types/llm";
import { getJudgeTemplateOptions } from "@/v2/pages-shared/automations/RuleEditorPanel/judgeTemplates";
import IconTag from "@/v2/pages-shared/IconTag/IconTag";

type JudgeTemplateChipsProps = {
  scope: EVALUATORS_RULE_SCOPE;
  onSelect: (template: LLMPromptTemplate, label: string) => void;
};

const JudgeTemplateChips: React.FC<JudgeTemplateChipsProps> = ({
  scope,
  onSelect,
}) => {
  const options = getJudgeTemplateOptions(scope);

  if (!options.length) return null;

  return (
    <div className="flex flex-col px-3 pb-3">
      <span className="comet-body-xs-accented pb-1.5 pt-0.5 text-muted-slate">
        Or get started with a template
      </span>
      <div className="flex flex-wrap gap-2">
        {options.map(({ value, label, icon, iconClassName, template }) => (
          <button
            key={value}
            type="button"
            onClick={() => onSelect(template, label)}
            className="comet-body-xs-accented flex h-7 items-center gap-1 rounded-md border border-border bg-soft-background px-1.5 text-foreground hover:bg-muted"
          >
            <IconTag icon={icon} className={iconClassName} />
            <span>{label}</span>
          </button>
        ))}
      </div>
    </div>
  );
};

export default JudgeTemplateChips;
