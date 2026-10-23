import React, { useRef } from "react";
import { FileTerminal } from "lucide-react";

import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { EVALUATORS_RULE_SCOPE } from "@/types/automations";
import { LLM_JUDGE, LLMPromptTemplate } from "@/types/llm";
import MenuOptionItems from "@/shared/MenuOptionItems/MenuOptionItems";
import { getJudgeTemplateOptions } from "@/v2/pages-shared/automations/RuleEditorPanel/judgeTemplates";

type JudgeTemplateMenuProps = {
  scope: EVALUATORS_RULE_SCOPE;
  onSelect: (template: LLMPromptTemplate, label: string) => void;
  onAfterSelect?: () => void;
};

const JudgeTemplateMenu: React.FC<JudgeTemplateMenuProps> = ({
  scope,
  onSelect,
  onAfterSelect,
}) => {
  const isSelectedRef = useRef(false);
  const options = getJudgeTemplateOptions(scope);

  if (!options.length) return null;

  const handleSelect = (value: LLM_JUDGE) => {
    const option = options.find((o) => o.value === value);
    if (!option) return;
    isSelectedRef.current = true;
    onSelect(option.template, option.label);
  };

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="2xs" type="button" className="gap-1 px-1">
          <FileTerminal className="size-3 shrink-0" />
          Templates
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent
        align="end"
        className="w-[280px] p-1.5"
        onCloseAutoFocus={(event) => {
          event.preventDefault();
          if (isSelectedRef.current) onAfterSelect?.();
          isSelectedRef.current = false;
        }}
      >
        <MenuOptionItems options={options} onSelect={handleSelect} />
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default JudgeTemplateMenu;
