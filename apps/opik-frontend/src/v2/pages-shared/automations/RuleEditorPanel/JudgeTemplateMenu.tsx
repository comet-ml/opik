import React, { useRef } from "react";
import { FileTerminal } from "lucide-react";

import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { LLMPromptTemplate } from "@/types/llm";
import MenuOptionItems from "@/shared/MenuOptionItems/MenuOptionItems";
import { JudgeTemplateOption } from "@/v2/pages-shared/automations/RuleEditorPanel/judgeTemplates";

type JudgeTemplateMenuProps = {
  options: JudgeTemplateOption[];
  onSelect: (template: LLMPromptTemplate, label: string) => void;
  onAfterSelect?: () => void;
};

const JudgeTemplateMenu: React.FC<JudgeTemplateMenuProps> = ({
  options,
  onSelect,
  onAfterSelect,
}) => {
  const isSelectedRef = useRef(false);

  if (!options.length) return null;

  const handleSelect = (label: string) => {
    const option = options.find((o) => o.label === label);
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
        <MenuOptionItems
          options={options.map((option) => ({
            ...option,
            value: option.label,
          }))}
          onSelect={handleSelect}
        />
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default JudgeTemplateMenu;
