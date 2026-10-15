import React from "react";
import { FileTerminal } from "lucide-react";

import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { EVALUATORS_RULE_SCOPE } from "@/types/automations";
import { LLM_JUDGE, LLMPromptTemplate } from "@/types/llm";
import MenuOptionItems from "@/v2/pages-shared/CreateMenuButton/MenuOptionItems";
import { getJudgeTemplateOptions } from "@/v2/pages-shared/automations/RuleEditorPanel/judgeTemplates";

type JudgeTemplateMenuProps = {
  scope: EVALUATORS_RULE_SCOPE;
  onSelect: (template: LLMPromptTemplate, label: string) => void;
};

const JudgeTemplateMenu: React.FC<JudgeTemplateMenuProps> = ({
  scope,
  onSelect,
}) => {
  const options = getJudgeTemplateOptions(scope);

  if (!options.length) return null;

  const handleSelect = (value: LLM_JUDGE) => {
    const option = options.find((o) => o.value === value);
    if (option) onSelect(option.template, option.label);
  };

  return (
    <DropdownMenu>
      <TooltipWrapper content="Load a template">
        <DropdownMenuTrigger asChild>
          <Button variant="ghost" size="icon-2xs" type="button">
            <FileTerminal />
          </Button>
        </DropdownMenuTrigger>
      </TooltipWrapper>
      <DropdownMenuContent align="end" className="w-[280px] p-1.5">
        <MenuOptionItems options={options} onSelect={handleSelect} />
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default JudgeTemplateMenu;
